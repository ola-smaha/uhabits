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
import org.isoron.platform.io.run
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Bookkeeping tables used by device sync.
 *
 * They live next to Loop's own tables and never alter them, so the database keeps its regular
 * schema version and remains importable by the stock app.
 *
 * - SyncHabitMeta: when each habit (by uuid) was last modified.
 * - SyncEntryMeta: when each entry (local habit id + day) was last modified.
 * - SyncTombstones: habits that were deleted, so that deletions reach other devices.
 *
 * All times are milliseconds since the Unix epoch. Rows written before sync existed have no
 * metadata and are treated as having been modified at time zero.
 */
object SyncSchema {
    private val createStatements = listOf(
        "CREATE TABLE IF NOT EXISTS SyncHabitMeta (" +
            "uuid TEXT PRIMARY KEY NOT NULL, " +
            "last_modified INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS SyncEntryMeta (" +
            "habit INTEGER NOT NULL, " +
            "timestamp INTEGER NOT NULL, " +
            "last_modified INTEGER NOT NULL, " +
            "PRIMARY KEY (habit, timestamp))",
        "CREATE TABLE IF NOT EXISTS SyncTombstones (" +
            "uuid TEXT PRIMARY KEY NOT NULL, " +
            "deleted_at INTEGER NOT NULL)"
    )

    /** Creates the sync tables, if they do not exist yet. Safe to call many times. */
    fun ensure(db: Database) {
        for (sql in createStatements) db.run(sql)
    }

    /**
     * Marks a habit as modified. The new time is always strictly greater than the previous one,
     * so a local edit wins over the version it replaced even if the device clock went backwards.
     *
     * Bindings: 1 = uuid, 2 = now, 3 = uuid.
     */
    const val STAMP_HABIT_SQL =
        "INSERT OR REPLACE INTO SyncHabitMeta(uuid, last_modified) VALUES (?, MAX(?, " +
            "COALESCE((SELECT last_modified FROM SyncHabitMeta WHERE uuid = ?), 0) + 1))"

    /**
     * Marks all habits whose position lies in the given range as modified.
     *
     * Bindings: 1 = now, 2 = first position, 3 = last position.
     */
    const val STAMP_HABIT_POSITION_RANGE_SQL =
        "INSERT OR REPLACE INTO SyncHabitMeta(uuid, last_modified) " +
            "SELECT h.uuid, MAX(?, COALESCE(m.last_modified, 0) + 1) " +
            "FROM Habits h LEFT JOIN SyncHabitMeta m ON m.uuid = h.uuid " +
            "WHERE h.uuid IS NOT NULL AND h.position >= ? AND h.position <= ?"

    /**
     * Records the deletion of a habit. The deletion time is always strictly greater than the
     * last modification of the habit, so that the deletion wins over the version it removed.
     *
     * Bindings: 1 = uuid, 2 = now, 3 = uuid, 4 = uuid.
     */
    const val TOMBSTONE_SQL =
        "INSERT OR REPLACE INTO SyncTombstones(uuid, deleted_at) VALUES (?, MAX(?, " +
            "COALESCE((SELECT last_modified FROM SyncHabitMeta WHERE uuid = ?), 0) + 1, " +
            "COALESCE((SELECT deleted_at FROM SyncTombstones WHERE uuid = ?), 0)))"

    /**
     * Marks an entry as modified, with the same guarantees as [STAMP_HABIT_SQL].
     *
     * Bindings: 1 = habit id, 2 = timestamp, 3 = now, 4 = habit id, 5 = timestamp.
     */
    const val STAMP_ENTRY_SQL =
        "INSERT OR REPLACE INTO SyncEntryMeta(habit, timestamp, last_modified) VALUES (?, ?, MAX(?, " +
            "COALESCE((SELECT last_modified FROM SyncEntryMeta WHERE habit = ? AND timestamp = ?), 0) + 1))"
}

/**
 * Source of the current time for sync bookkeeping. Tests may replace it.
 */
object SyncClock {
    @OptIn(ExperimentalTime::class)
    var now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
}
