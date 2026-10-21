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
import org.isoron.uhabits.core.models.sqlite.SQLModelFactory
import org.isoron.uhabits.core.models.sqlite.SQLiteHabitList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Random edits on several devices, synced in random order, must always converge to the same data.
 */
class SyncFuzzTest {
    private val today = LocalDate(2015, 1, 25)
    private var clock = 1_000_000L
    private var cloud: SyncSnapshot? = null
    private var uploads = 0
    private lateinit var originalClock: () -> Long

    private class Device(val db: Database) {
        val factory = SQLModelFactory(db)
        val habits = SQLiteHabitList(factory)
        val applier = SyncApplier(db, habits, factory)
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

    private fun sync(device: Device): SyncApplier.Result {
        clock += 7
        val remote = cloud ?: SyncSnapshot.EMPTY
        val result = device.applier.apply(remote)
        if (cloud == null || result.localState != remote) {
            cloud = SyncSnapshotReader.read(device.db)
            uploads++
        }
        return result
    }

    private fun randomEdit(device: Device, random: Random, counter: Int) {
        val list = device.habits.toList()
        when (random.nextInt(10)) {
            0 -> {
                val habit = device.factory.buildHabit()
                habit.name = "habit $counter"
                device.habits.add(habit)
            }
            1 -> if (list.isNotEmpty()) {
                val habit = list[random.nextInt(list.size)]
                habit.name = "renamed $counter"
                device.habits.update(habit)
            }
            2 -> if (list.isNotEmpty() && random.nextInt(3) == 0) {
                device.habits.remove(list[random.nextInt(list.size)])
            }
            3 -> if (list.size >= 2) {
                val from = list[random.nextInt(list.size)]
                val to = list[random.nextInt(list.size)]
                if (from !== to) device.habits.reorder(from, to)
            }
            4 -> if (list.isNotEmpty()) {
                val habit = list[random.nextInt(list.size)]
                habit.isArchived = !habit.isArchived
                device.habits.update(habit)
            }
            else -> if (list.isNotEmpty()) {
                val habit = list[random.nextInt(list.size)]
                val date = today.minus(random.nextInt(5))
                val values = listOf(Entry.YES_MANUAL, Entry.NO, Entry.SKIP, Entry.UNKNOWN)
                habit.originalEntries.add(Entry(date, values[random.nextInt(values.size)]))
                habit.recompute()
            }
        }
    }

    private fun assertModelMatchesDatabase(device: Device, label: String) {
        val snapshot = SyncSnapshotReader.read(device.db)
        val inMemory = device.habits.toList()
        assertEquals(snapshot.habits.size, inMemory.size, label)
        for (habit in inMemory) {
            val stored = snapshot.habits.getValue(habit.uuid!!)
            assertEquals(stored.name, habit.name, label)
            assertEquals(stored.position, habit.position, label)
            assertEquals(stored.archived != 0, habit.isArchived, label)
            for (offset in 0 until 5) {
                val date = today.minus(offset)
                val entry = snapshot.entries[SyncEntryKey(habit.uuid!!, date.unixTime)]
                val expected = entry?.value ?: Entry.UNKNOWN
                assertEquals(expected, habit.originalEntries.get(date).value, label)
            }
        }
        assertEquals(inMemory.sortedBy { it.position }.map { it.uuid }, inMemory.map { it.uuid }, label)
        assertEquals((0 until inMemory.size).toList(), inMemory.map { it.position }.sorted(), label)
    }

    private suspend fun runScenario(seed: Int, deviceCount: Int, steps: Int) {
        cloud = null
        val random = Random(seed)
        val devices = List(deviceCount) { Device(TestDatabaseHelper.createEmptyDatabase()) }
        for (step in 0 until steps) {
            clock += random.nextInt(1, 5000)
            val device = devices[random.nextInt(deviceCount)]
            if (random.nextInt(4) == 0) {
                sync(device)
                assertModelMatchesDatabase(device, "seed $seed step $step")
            } else {
                randomEdit(device, random, step)
            }
        }

        // Let every device sync a few times; they must end up identical.
        repeat(3) { for (d in devices) sync(d) }
        val reference = SyncSnapshotReader.read(devices[0].db)
        for (d in devices) {
            assertEquals(reference, SyncSnapshotReader.read(d.db), "seed $seed")
            assertEquals(cloud, SyncSnapshotReader.read(d.db), "seed $seed")
            assertModelMatchesDatabase(d, "seed $seed final")
        }

        // Once converged, nothing changes and nothing is uploaded.
        val before = uploads
        for (d in devices) assertFalse(sync(d).changedAnything, "seed $seed")
        assertEquals(before, uploads, "seed $seed")
    }

    @Test
    fun testRandomEditsConverge() = runTest {
        for (seed in 1..12) runScenario(seed, deviceCount = 2 + seed % 2, steps = 80)
    }
}
