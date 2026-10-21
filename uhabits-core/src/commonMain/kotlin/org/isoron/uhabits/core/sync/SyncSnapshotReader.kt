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
import org.isoron.platform.io.query
import org.isoron.uhabits.core.models.Frequency
import org.isoron.uhabits.core.models.WeekdayList

/**
 * Reads a [SyncSnapshot] from a Loop database (either the app's own database or a copy that
 * was downloaded from another device). The sync tables must exist; see [SyncSchema.ensure].
 */
object SyncSnapshotReader {
    private const val HABITS_SQL =
        "SELECT h.uuid, h.name, h.description, h.question, h.freq_num, h.freq_den, h.color, " +
            "h.position, h.reminder_hour, h.reminder_min, h.reminder_days, h.archived, h.type, " +
            "h.target_value, h.target_type, h.unit, COALESCE(m.last_modified, 0) " +
            "FROM Habits h LEFT JOIN SyncHabitMeta m ON m.uuid = h.uuid " +
            "WHERE h.uuid IS NOT NULL"

    private const val ENTRIES_SQL =
        "SELECT h.uuid, r.timestamp, r.value, r.notes, COALESCE(m.last_modified, 0) " +
            "FROM Repetitions r JOIN Habits h ON h.id = r.habit " +
            "LEFT JOIN SyncEntryMeta m ON m.habit = r.habit AND m.timestamp = r.timestamp " +
            "WHERE h.uuid IS NOT NULL AND r.timestamp IS NOT NULL AND r.value IS NOT NULL"

    private const val TOMBSTONES_SQL = "SELECT uuid, deleted_at FROM SyncTombstones"

    fun read(db: Database): SyncSnapshot {
        return SyncSnapshot(readHabits(db), readEntries(db), readTombstones(db))
    }

    fun readHabits(db: Database): Map<String, SyncHabit> {
        val habits = HashMap<String, SyncHabit>()
        db.query(HABITS_SQL) { s ->
            val uuid = s.getTextOrNull(0) ?: return@query
            val freq = Frequency(s.getIntOrNull(4) ?: 1, s.getIntOrNull(5) ?: 1)
            val reminderHour = s.getIntOrNull(8)
            val reminderMin = s.getIntOrNull(9)
            val hasReminder = reminderHour != null && reminderMin != null
            habits[uuid] = SyncHabit(
                uuid = uuid,
                name = s.getTextOrNull(1) ?: "",
                description = s.getTextOrNull(2) ?: "",
                question = s.getTextOrNull(3) ?: "",
                freqNum = freq.numerator,
                freqDen = freq.denominator,
                color = s.getIntOrNull(6) ?: 0,
                position = s.getIntOrNull(7) ?: 0,
                reminderHour = if (hasReminder) reminderHour else null,
                reminderMin = if (hasReminder) reminderMin else null,
                reminderDays = if (hasReminder) WeekdayList(s.getIntOrNull(10) ?: 0).toInteger() else 0,
                archived = if ((s.getIntOrNull(11) ?: 0) != 0) 1 else 0,
                type = s.getIntOrNull(12) ?: 0,
                targetValue = s.getRealOrNull(13) ?: 0.0,
                targetType = s.getIntOrNull(14) ?: 0,
                unit = s.getTextOrNull(15) ?: "",
                lastModified = s.getLongOrNull(16) ?: 0L
            )
        }
        return habits
    }

    fun readEntries(db: Database): Map<SyncEntryKey, SyncEntry> {
        val entries = HashMap<SyncEntryKey, SyncEntry>()
        db.query(ENTRIES_SQL) { s ->
            val uuid = s.getTextOrNull(0) ?: return@query
            val entry = SyncEntry(
                habitUuid = uuid,
                timestamp = s.getLong(1),
                value = s.getInt(2),
                notes = s.getTextOrNull(3) ?: "",
                lastModified = s.getLongOrNull(4) ?: 0L
            )
            entries[entry.key] = entry
        }
        return entries
    }

    fun readTombstones(db: Database): Map<String, Long> {
        val tombstones = HashMap<String, Long>()
        db.query(TOMBSTONES_SQL) { s ->
            val uuid = s.getTextOrNull(0) ?: return@query
            tombstones[uuid] = s.getLong(1)
        }
        return tombstones
    }
}
