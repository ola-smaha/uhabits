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

/**
 * Merges the data of two devices.
 *
 * Every habit and every entry is resolved independently: the most recently modified version
 * wins ("last writer wins"). A deleted habit stays deleted unless it was modified after the
 * deletion. Ties are broken by comparing contents, so the result does not depend on the order
 * of the arguments: merge(a, b) == merge(b, a). Merging is also idempotent and associative,
 * which means devices converge to the same data no matter how often or in which order they sync.
 */
object SyncMerger {
    fun merge(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
        val tombstones = HashMap<String, Long>(a.tombstones)
        for ((uuid, deletedAt) in b.tombstones) {
            val existing = tombstones[uuid]
            if (existing == null || deletedAt > existing) tombstones[uuid] = deletedAt
        }

        val habits = HashMap<String, SyncHabit>()
        for (uuid in a.habits.keys + b.habits.keys) {
            val winner = newer(a.habits[uuid], b.habits[uuid]) ?: continue
            val deletedAt = tombstones[uuid]
            if (deletedAt != null && deletedAt >= winner.lastModified) continue
            habits[uuid] = winner
        }

        val entries = HashMap<SyncEntryKey, SyncEntry>()
        for (key in a.entries.keys + b.entries.keys) {
            if (key.habitUuid !in habits) continue
            val winner = newer(a.entries[key], b.entries[key]) ?: continue
            entries[key] = winner
        }

        return SyncSnapshot(habits, entries, tombstones)
    }

    /** Merges any number of snapshots. */
    fun mergeAll(snapshots: List<SyncSnapshot>): SyncSnapshot {
        var result = SyncSnapshot.EMPTY
        for (s in snapshots) result = merge(result, s)
        return result
    }

    private fun newer(x: SyncHabit?, y: SyncHabit?): SyncHabit? {
        if (x == null) return y
        if (y == null) return x
        if (x.lastModified != y.lastModified) return if (x.lastModified > y.lastModified) x else y
        return if (SyncHabit.compareContent(x, y) >= 0) x else y
    }

    private fun newer(x: SyncEntry?, y: SyncEntry?): SyncEntry? {
        if (x == null) return y
        if (y == null) return x
        if (x.lastModified != y.lastModified) return if (x.lastModified > y.lastModified) x else y
        return if (SyncEntry.compareContent(x, y) >= 0) x else y
    }
}
