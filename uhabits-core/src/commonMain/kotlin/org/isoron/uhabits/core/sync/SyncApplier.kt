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

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.begin
import org.isoron.platform.io.commit
import org.isoron.platform.io.query
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitType
import org.isoron.uhabits.core.models.ModelFactory
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.models.sqlite.SQLiteEntryList

/**
 * Merges data received from other devices into the local database, keeping the in-memory
 * models (habit list, entries, scores) consistent with it.
 */
class SyncApplier(
    private val db: Database,
    private val habitList: HabitList,
    private val modelFactory: ModelFactory
) {
    class Result(
        /** Local data after the merge, as seen by sync. */
        val localState: SyncSnapshot,
        val createdHabits: List<Habit>,
        val updatedHabits: List<Habit>,
        val deletedHabits: List<Habit>,
        /** Habits that gained, lost or changed entries. */
        val habitsWithChangedEntries: List<Habit>,
        /** True if the order of the habits changed. */
        val reordered: Boolean,
        /** True if habit positions had to be renumbered to resolve concurrent reorderings. */
        val renumberedPositions: Boolean
    ) {
        val changedAnything: Boolean
            get() = createdHabits.isNotEmpty() ||
                updatedHabits.isNotEmpty() ||
                deletedHabits.isNotEmpty() ||
                habitsWithChangedEntries.isNotEmpty() ||
                reordered ||
                renumberedPositions
    }

    /**
     * Merges [remote] into the local database. Returns what changed locally. The caller should
     * upload the local database if [Result.localState] differs from [remote].
     */
    fun apply(remote: SyncSnapshot): Result {
        // Step 1: habits are created, updated and deleted through the regular model (holding the
        // list's lock), so that the in-memory list stays consistent with the database.
        val habits = habitList.runLocked { applyHabits(remote) }

        // Step 2: entries, positions and bookkeeping, written directly in one transaction.
        val changes = applyEntries(remote, habits)

        // Step 3: bring the habits in memory up to date.
        var reordered = changes.reordered
        habitList.runLocked {
            for ((uuid, position) in changes.positions) {
                val habit = habitList.getByUUID(uuid) ?: continue
                if (habit.position != position) {
                    habit.position = position
                    reordered = true
                }
            }
        }
        val changedEntries = changes.changedEntryHabits.mapNotNull { habitList.getByUUID(it) }
        val refreshed = LinkedHashSet<Habit>()
        refreshed.addAll(habits.created)
        refreshed.addAll(habits.updated)
        refreshed.addAll(changedEntries)
        for (habit in refreshed) {
            (habit.originalEntries as? SQLiteEntryList)?.invalidate()
            habit.recompute()
        }

        val result = Result(
            localState = changes.localState,
            createdHabits = habits.created,
            updatedHabits = habits.updated,
            deletedHabits = habits.deleted,
            habitsWithChangedEntries = changedEntries,
            reordered = reordered,
            renumberedPositions = changes.renumbered
        )
        if (result.changedAnything) habitList.resort()
        return result
    }

    private class HabitChanges(
        val merged: SyncSnapshot,
        val created: List<Habit>,
        val updated: List<Habit>,
        val deleted: List<Habit>
    )

    private fun applyHabits(remote: SyncSnapshot): HabitChanges {
        val local = SyncSnapshotReader.read(db)
        val merged = SyncMerger.merge(local, remote)

        val deleted = ArrayList<Habit>()
        for (uuid in local.habits.keys) {
            if (uuid in merged.habits) continue
            val habit = habitList.getByUUID(uuid) ?: continue
            deleted.add(habit)
            habitList.remove(habit)
        }

        val created = ArrayList<Habit>()
        val updated = ArrayList<Habit>()
        for ((uuid, m) in merged.habits) {
            val l = local.habits[uuid]
            if (l != null && l.sameContentAs(m, ignorePosition = true)) continue
            val existing = habitList.getByUUID(uuid)
            if (existing == null) {
                val habit = modelFactory.buildHabit()
                writeFields(m, habit)
                habitList.add(habit)
                created.add(habit)
            } else {
                writeFields(m, existing)
                habitList.update(existing)
                updated.add(existing)
            }
        }
        return HabitChanges(merged, created, updated, deleted)
    }

    private class EntryChanges(
        val localState: SyncSnapshot,
        val changedEntryHabits: Set<String>,
        /** Position of each habit, by uuid. */
        val positions: Map<String, Int>,
        val reordered: Boolean,
        val renumbered: Boolean
    )

    /**
     * Writes entries, positions and sync bookkeeping in a single transaction. Entries are merged
     * again against a fresh read, so that check-ins made while the merge was running are never
     * overwritten. Nothing here touches the in-memory models: other threads may be holding
     * their locks while waiting for the database.
     */
    private fun applyEntries(remote: SyncSnapshot, habits: HabitChanges): EntryChanges {
        val merged = habits.merged
        val deletedUuids = habits.deleted.mapNotNull { it.uuid }.toSet()
        val changedEntryHabits = LinkedHashSet<String>()
        val positions = HashMap<String, Int>()
        var renumbered = false
        var reordered = false
        val localState: SyncSnapshot
        db.begin()
        try {
            val current = SyncSnapshotReader.read(db)
            val mergedEntries = SyncMerger.merge(current, remote)
            val ids = HashMap<String, Long>()
            db.query("SELECT uuid, id FROM Habits WHERE uuid IS NOT NULL") { s ->
                ids[s.getText(0)] = s.getLong(1)
            }

            Statements(db).use { st ->
                for ((key, m) in mergedEntries.entries) {
                    val habitId = ids[key.habitUuid] ?: continue
                    val l = current.entries[key]
                    if (l == null || !l.sameContentAs(m)) {
                        if (l != null) st.deleteEntry(habitId, key.timestamp)
                        st.insertEntry(habitId, key.timestamp, m.value, m.notes)
                        st.stampEntry(habitId, key.timestamp, m.lastModified)
                        changedEntryHabits.add(key.habitUuid)
                    } else if (l.lastModified != m.lastModified) {
                        st.stampEntry(habitId, key.timestamp, m.lastModified)
                    }
                }

                // Positions are resolved per habit, which may leave gaps or duplicates after
                // concurrent reorderings. Renumber them deterministically, so every device that
                // merges the same data ends up with the same order.
                val ordered = merged.habits.values
                    .filter { it.uuid in ids }
                    .sortedWith(compareBy<SyncHabit>({ it.position }, { it.uuid }))
                for ((index, m) in ordered.withIndex()) {
                    positions[m.uuid] = index
                    val c = current.habits[m.uuid]
                    if (c == null || c.position != index) {
                        st.setPosition(m.uuid, index)
                        reordered = true
                    }
                    val stamp = if (m.position == index) {
                        m.lastModified
                    } else {
                        renumbered = true
                        maxOf(SyncClock.now(), m.lastModified + 1)
                    }
                    if (c == null || c.lastModified != stamp) st.stampHabit(m.uuid, stamp)
                }

                for ((uuid, deletedAt) in mergedEntries.tombstones) {
                    // For habits deleted by this merge, keep the original deletion time.
                    val target = if (uuid in deletedUuids) merged.tombstones[uuid] ?: deletedAt else deletedAt
                    if (current.tombstones[uuid] != target) st.setTombstone(uuid, target)
                }
            }
            localState = SyncSnapshotReader.read(db)
        } finally {
            db.commit()
        }
        return EntryChanges(localState, changedEntryHabits, positions, reordered, renumbered)
    }

    private fun writeFields(m: SyncHabit, habit: Habit) {
        habit.uuid = m.uuid
        habit.name = m.name
        habit.description = m.description
        habit.question = m.question
        habit.frequency = Frequency(m.freqNum, m.freqDen)
        habit.color = PaletteColor(m.color)
        habit.position = m.position
        habit.isArchived = m.archived != 0
        habit.type = HabitType.fromInt(m.type)
        habit.targetType = NumericalHabitType.fromInt(m.targetType)
        habit.targetValue = m.targetValue
        habit.unit = m.unit
        val hour = m.reminderHour
        val minute = m.reminderMin
        habit.reminder = if (hour != null && minute != null) {
            Reminder(hour, minute, WeekdayList(m.reminderDays))
        } else {
            null
        }
    }

    private class Statements(private val db: Database) {
        private val all = mutableListOf<PreparedStatement>()

        private fun prepare(sql: String) = db.prepareStatement(sql).also { all.add(it) }

        private val deleteEntryStmt by lazy {
            prepare("DELETE FROM Repetitions WHERE habit = ? AND timestamp = ?")
        }
        private val insertEntryStmt by lazy {
            prepare("INSERT INTO Repetitions(habit, timestamp, value, notes) VALUES (?, ?, ?, ?)")
        }
        private val stampEntryStmt by lazy {
            prepare("INSERT OR REPLACE INTO SyncEntryMeta(habit, timestamp, last_modified) VALUES (?, ?, ?)")
        }
        private val setPositionStmt by lazy {
            prepare("UPDATE Habits SET position = ? WHERE uuid = ?")
        }
        private val stampHabitStmt by lazy {
            prepare("INSERT OR REPLACE INTO SyncHabitMeta(uuid, last_modified) VALUES (?, ?)")
        }
        private val tombstoneStmt by lazy {
            prepare("INSERT OR REPLACE INTO SyncTombstones(uuid, deleted_at) VALUES (?, ?)")
        }

        fun deleteEntry(habitId: Long, timestamp: Long) = with(deleteEntryStmt) {
            reset()
            bindLong(1, habitId)
            bindLong(2, timestamp)
            step()
        }

        fun insertEntry(habitId: Long, timestamp: Long, value: Int, notes: String) = with(insertEntryStmt) {
            reset()
            bindLong(1, habitId)
            bindLong(2, timestamp)
            bindInt(3, value)
            bindText(4, notes)
            step()
        }

        fun stampEntry(habitId: Long, timestamp: Long, lastModified: Long) = with(stampEntryStmt) {
            reset()
            bindLong(1, habitId)
            bindLong(2, timestamp)
            bindLong(3, lastModified)
            step()
        }

        fun setPosition(uuid: String, position: Int) = with(setPositionStmt) {
            reset()
            bindInt(1, position)
            bindText(2, uuid)
            step()
        }

        fun stampHabit(uuid: String, lastModified: Long) = with(stampHabitStmt) {
            reset()
            bindText(1, uuid)
            bindLong(2, lastModified)
            step()
        }

        fun setTombstone(uuid: String, deletedAt: Long) = with(tombstoneStmt) {
            reset()
            bindText(1, uuid)
            bindLong(2, deletedAt)
            step()
        }

        fun <T> use(block: (Statements) -> T): T {
            try {
                return block(this)
            } finally {
                for (s in all) s.finalize()
            }
        }
    }
}
