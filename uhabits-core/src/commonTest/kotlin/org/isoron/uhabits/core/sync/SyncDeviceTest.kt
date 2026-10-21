/*
 * Copyright (C) 2026 Loop Habit Tracker contributors
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.core.sync

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.Database
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Simulates several devices that sync through a shared copy of the data (the "cloud").
 */
class SyncDeviceTest {
    private val today = LocalDate(2015, 1, 25)
    private var clock = 1_000_000L
    private var cloud: SyncSnapshot? = null
    private var uploads = 0
    private lateinit var originalClock: () -> Long

    private class Device(val db: Database) {
        val factory = SQLModelFactory(db)
        val habits = SQLiteHabitList(factory)
        val applier = SyncApplier(db, habits, factory)

        fun create(name: String): Habit {
            val habit = factory.buildHabit()
            habit.name = name
            habits.add(habit)
            return habit
        }

        fun find(name: String): Habit? = habits.firstOrNull { it.name == name }

        fun get(name: String): Habit = find(name) ?: throw AssertionError("habit $name not found")

        fun check(name: String, date: LocalDate, value: Int = Entry.YES_MANUAL) {
            val habit = get(name)
            habit.originalEntries.add(Entry(date, value))
            habit.recompute()
        }

        fun value(name: String, date: LocalDate): Int = get(name).originalEntries.get(date).value

        fun names(): List<String> = habits.map { it.name }

        fun snapshot(): SyncSnapshot = SyncSnapshotReader.read(db)
    }

    private suspend fun newDevice(): Device = Device(TestDatabaseHelper.createEmptyDatabase())

    private fun tick(millis: Long = 1000) {
        clock += millis
    }

    /** Syncs a device with the cloud, the same way the app does. */
    private fun sync(device: Device): SyncApplier.Result {
        tick()
        val remote = cloud ?: SyncSnapshot.EMPTY
        val result = device.applier.apply(remote)
        if (cloud == null || result.localState != remote) {
            cloud = device.snapshot()
            uploads++
        }
        tick()
        return result
    }

    @BeforeTest
    fun setUp() {
        setToday(today)
        originalClock = SyncClock.now
        SyncClock.now = { clock }
    }

    @AfterTest
    fun tearDown() {
        SyncClock.now = originalClock
    }

    @Test
    fun testStampsLocalChanges() = runTest {
        val a = newDevice()
        val run = a.create("Run")
        val stamp1 = a.snapshot().habits.getValue(run.uuid!!).lastModified
        assertEquals(clock, stamp1)

        tick()
        run.name = "Jog"
        a.habits.update(run)
        val stamp2 = a.snapshot().habits.getValue(run.uuid!!).lastModified
        assertTrue(stamp2 > stamp1)

        // A clock that goes backwards still produces newer stamps.
        clock -= 100_000
        run.name = "Sprint"
        a.habits.update(run)
        assertTrue(a.snapshot().habits.getValue(run.uuid!!).lastModified > stamp2)

        a.check("Sprint", today)
        val entry = a.snapshot().entries.getValue(SyncEntryKey(run.uuid!!, today.unixTime))
        assertEquals(Entry.YES_MANUAL, entry.value)

        a.habits.remove(run)
        val snapshot = a.snapshot()
        assertTrue(snapshot.habits.isEmpty())
        assertTrue(snapshot.entries.isEmpty())
        assertTrue(snapshot.tombstones.getValue(run.uuid!!) > stamp2)
    }

    @Test
    fun testNewDeviceReceivesEverything() = runTest {
        val a = newDevice()
        val run = a.create("Run")
        run.frequency = Frequency(3, 7)
        run.reminder = Reminder(8, 30, WeekdayList(127))
        a.habits.update(run)
        a.create("Read")
        a.check("Run", today)
        a.check("Run", today.minus(1))
        a.check("Read", today, 0)
        sync(a)

        val b = newDevice()
        val result = sync(b)
        assertEquals(2, result.createdHabits.size)
        assertEquals(listOf("Run", "Read"), b.names())
        val bRun = b.get("Run")
        assertEquals(run.uuid, bRun.uuid)
        assertEquals(Frequency(3, 7), bRun.frequency)
        assertEquals(Reminder(8, 30, WeekdayList(127)), bRun.reminder)
        assertEquals(Entry.YES_MANUAL, b.value("Run", today))
        assertEquals(Entry.YES_MANUAL, b.value("Run", today.minus(1)))
        assertEquals(0, b.value("Read", today))
        assertTrue(bRun.isCompletedToday())
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun testCheckInsFlowBothWays() = runTest {
        val a = newDevice()
        a.create("Run")
        sync(a)
        val b = newDevice()
        sync(b)

        a.check("Run", today)
        tick()
        b.check("Run", today.minus(1))
        sync(a)
        sync(b)
        sync(a)

        for (device in listOf(a, b)) {
            assertEquals(Entry.YES_MANUAL, device.value("Run", today))
            assertEquals(Entry.YES_MANUAL, device.value("Run", today.minus(1)))
        }
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun testLatestChangeToTheSameDayWins() = runTest {
        val a = newDevice()
        a.create("Run")
        sync(a)
        val b = newDevice()
        sync(b)

        a.check("Run", today, Entry.YES_MANUAL)
        tick()
        b.check("Run", today, Entry.NO)
        sync(b)
        sync(a)
        sync(b)

        assertEquals(Entry.NO, a.value("Run", today))
        assertEquals(Entry.NO, b.value("Run", today))
    }

    @Test
    fun testHabitEditsAreSynced() = runTest {
        val a = newDevice()
        val run = a.create("Run")
        sync(a)
        val b = newDevice()
        sync(b)

        run.name = "Morning run"
        run.isArchived = true
        run.reminder = null
        a.habits.update(run)
        sync(a)
        val result = sync(b)

        assertEquals(1, result.updatedHabits.size)
        val bRun = b.get("Morning run")
        assertTrue(bRun.isArchived)
        assertNull(bRun.reminder)
    }

    @Test
    fun testRemovedReminderIsSynced() = runTest {
        val a = newDevice()
        val run = a.create("Run")
        run.reminder = Reminder(7, 0, WeekdayList(127))
        a.habits.update(run)
        sync(a)
        val b = newDevice()
        sync(b)
        assertNotNull(b.get("Run").reminder)

        run.reminder = null
        a.habits.update(run)
        sync(a)
        sync(b)
        assertNull(b.get("Run").reminder)
    }

    @Test
    fun testDeletionsAreSynced() = runTest {
        val a = newDevice()
        a.create("Run")
        a.create("Read")
        a.check("Run", today)
        sync(a)
        val b = newDevice()
        sync(b)

        a.habits.remove(a.get("Run"))
        sync(a)
        val result = sync(b)

        assertEquals(1, result.deletedHabits.size)
        assertEquals(listOf("Read"), b.names())
        assertEquals(0, b.get("Read").position)
        assertTrue(b.snapshot().entries.isEmpty())

        // The deletion sticks: syncing again does not bring the habit back.
        sync(a)
        sync(b)
        assertEquals(listOf("Read"), a.names())
        assertEquals(listOf("Read"), b.names())
    }

    @Test
    fun testEditAfterDeletionRestoresHabit() = runTest {
        val a = newDevice()
        a.create("Run")
        a.check("Run", today)
        sync(a)
        val b = newDevice()
        sync(b)

        a.habits.remove(a.get("Run"))
        tick()
        val bRun = b.get("Run")
        bRun.name = "Run again"
        b.habits.update(bRun)

        sync(a)
        sync(b)
        sync(a)

        assertEquals(listOf("Run again"), a.names())
        assertEquals(listOf("Run again"), b.names())
        assertEquals(Entry.YES_MANUAL, a.value("Run again", today))
    }

    @Test
    fun testReorderingIsSynced() = runTest {
        val a = newDevice()
        a.create("A")
        a.create("B")
        a.create("C")
        sync(a)
        val b = newDevice()
        sync(b)

        a.habits.reorder(a.get("C"), a.get("A"))
        sync(a)
        val result = sync(b)

        assertTrue(result.reordered)
        assertEquals(listOf("C", "A", "B"), b.names())
        assertEquals(listOf(0, 1, 2), b.habits.map { it.position })
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun testHabitsCreatedOnBothDevicesConverge() = runTest {
        val a = newDevice()
        a.create("Shared")
        sync(a)
        val b = newDevice()
        sync(b)

        a.create("Only on A")
        tick()
        b.create("Only on B")
        sync(a)
        sync(b)
        sync(a)
        sync(b)

        assertEquals(a.names(), b.names())
        assertEquals(3, a.names().size)
        assertEquals(listOf(0, 1, 2), a.habits.map { it.position })
        assertEquals(listOf(0, 1, 2), b.habits.map { it.position })
        assertEquals(a.snapshot(), b.snapshot())

        // Once converged, syncing again changes nothing and uploads nothing.
        val before = uploads
        assertFalse(sync(a).changedAnything)
        assertFalse(sync(b).changedAnything)
        assertEquals(before, uploads)
    }

    @Test
    fun testThreeDevicesConverge() = runTest {
        val devices = listOf(newDevice(), newDevice(), newDevice())
        devices[0].create("Run")
        sync(devices[0])
        for (d in devices) sync(d)

        devices[0].check("Run", today)
        tick()
        devices[1].create("Read")
        tick()
        devices[2].check("Run", today.minus(2))
        tick()
        devices[2].habits.remove(devices[2].get("Run"))

        repeat(2) { for (d in devices) sync(d) }

        for (d in devices) {
            assertEquals(listOf("Read"), d.names())
            assertEquals(devices[0].snapshot(), d.snapshot())
        }
    }

    @Test
    fun testNothingToDoWhenAlreadyInSync() = runTest {
        val a = newDevice()
        a.create("Run")
        a.check("Run", today)
        sync(a)
        val b = newDevice()
        sync(b)

        val before = uploads
        val result = sync(b)
        assertFalse(result.changedAnything)
        assertEquals(before, uploads)
        assertEquals(cloud, b.snapshot())
    }
}
