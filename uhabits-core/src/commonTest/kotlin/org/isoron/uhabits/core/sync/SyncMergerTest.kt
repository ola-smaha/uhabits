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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMergerTest {
    private fun habit(uuid: String, name: String = uuid, position: Int = 0, modified: Long = 0) =
        SyncHabit(
            uuid = uuid,
            name = name,
            description = "",
            question = "",
            freqNum = 1,
            freqDen = 1,
            color = 8,
            position = position,
            reminderHour = null,
            reminderMin = null,
            reminderDays = 0,
            archived = 0,
            type = 0,
            targetValue = 0.0,
            targetType = 0,
            unit = "",
            lastModified = modified
        )

    private fun entry(uuid: String, day: Long, value: Int, modified: Long, notes: String = "") =
        SyncEntry(uuid, day, value, notes, modified)

    private fun snapshot(
        habits: List<SyncHabit> = emptyList(),
        entries: List<SyncEntry> = emptyList(),
        tombstones: Map<String, Long> = emptyMap()
    ) = SyncSnapshot(habits.associateBy { it.uuid }, entries.associateBy { it.key }, tombstones)

    @Test
    fun testNewerHabitWins() {
        val a = snapshot(habits = listOf(habit("h1", name = "Run", modified = 10)))
        val b = snapshot(habits = listOf(habit("h1", name = "Jog", modified = 20)))
        assertEquals("Jog", SyncMerger.merge(a, b).habits.getValue("h1").name)
        assertEquals("Jog", SyncMerger.merge(b, a).habits.getValue("h1").name)
    }

    @Test
    fun testTiesAreResolvedTheSameWayOnEveryDevice() {
        val a = snapshot(
            habits = listOf(habit("h1", name = "Run", modified = 10)),
            entries = listOf(entry("h1", 100, 2, modified = 10))
        )
        val b = snapshot(
            habits = listOf(habit("h1", name = "Jog", modified = 10)),
            entries = listOf(entry("h1", 100, 0, modified = 10))
        )
        assertEquals(SyncMerger.merge(a, b), SyncMerger.merge(b, a))
    }

    @Test
    fun testUnionOfHabitsAndEntries() {
        val a = snapshot(
            habits = listOf(habit("h1", modified = 1)),
            entries = listOf(entry("h1", 100, 2, modified = 1))
        )
        val b = snapshot(
            habits = listOf(habit("h1", modified = 1), habit("h2", modified = 1)),
            entries = listOf(entry("h1", 200, 2, modified = 1), entry("h2", 100, 2, modified = 1))
        )
        val merged = SyncMerger.merge(a, b)
        assertEquals(setOf("h1", "h2"), merged.habits.keys)
        assertEquals(3, merged.entries.size)
    }

    @Test
    fun testNewerEntryWins() {
        val a = snapshot(listOf(habit("h1")), listOf(entry("h1", 100, 2, modified = 30)))
        val b = snapshot(listOf(habit("h1")), listOf(entry("h1", 100, 0, modified = 20)))
        val merged = SyncMerger.merge(a, b)
        assertEquals(2, merged.entries.getValue(SyncEntryKey("h1", 100)).value)
    }

    @Test
    fun testDeletionWinsOverOlderVersions() {
        val a = snapshot(
            habits = listOf(habit("h1", modified = 10)),
            entries = listOf(entry("h1", 100, 2, modified = 50))
        )
        val b = snapshot(tombstones = mapOf("h1" to 20L))
        val merged = SyncMerger.merge(a, b)
        assertNull(merged.habits["h1"])
        assertTrue(merged.entries.isEmpty())
        assertEquals(20L, merged.tombstones["h1"])
    }

    @Test
    fun testEditAfterDeletionRestoresHabit() {
        val a = snapshot(
            habits = listOf(habit("h1", name = "Renamed", modified = 30)),
            entries = listOf(entry("h1", 100, 2, modified = 5))
        )
        val b = snapshot(tombstones = mapOf("h1" to 20L))
        val merged = SyncMerger.merge(a, b)
        assertEquals("Renamed", merged.habits.getValue("h1").name)
        assertEquals(1, merged.entries.size)
    }

    @Test
    fun testMergeIsIdempotent() {
        val a = snapshot(
            habits = listOf(habit("h1", modified = 10), habit("h2", modified = 3)),
            entries = listOf(entry("h1", 100, 2, modified = 10)),
            tombstones = mapOf("h3" to 7L)
        )
        val b = snapshot(
            habits = listOf(habit("h1", name = "x", modified = 12)),
            entries = listOf(entry("h1", 100, 0, modified = 11), entry("h1", 200, 2, modified = 11))
        )
        val merged = SyncMerger.merge(a, b)
        assertEquals(merged, SyncMerger.merge(merged, merged))
        assertEquals(merged, SyncMerger.merge(merged, a))
        assertEquals(merged, SyncMerger.merge(merged, b))
    }

    @Test
    fun testFingerprint() {
        val a = snapshot(listOf(habit("h1", modified = 1)), listOf(entry("h1", 100, 2, modified = 1)))
        val b = snapshot(listOf(habit("h1", modified = 1)), listOf(entry("h1", 100, 2, modified = 1)))
        val c = snapshot(listOf(habit("h1", modified = 1)), listOf(entry("h1", 100, 0, modified = 1)))
        val d = snapshot(listOf(habit("h1", modified = 2)), listOf(entry("h1", 100, 2, modified = 1)))
        assertEquals(a.fingerprint(), b.fingerprint())
        assertNotEquals(a.fingerprint(), c.fingerprint())
        assertNotEquals(a.fingerprint(), d.fingerprint())
        assertNotEquals(SyncSnapshot.EMPTY.fingerprint(), a.fingerprint())
        assertFalse(a.isEmpty)
    }
}
