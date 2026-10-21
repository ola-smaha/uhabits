package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.sync.SyncClock
import org.isoron.uhabits.core.sync.SyncSchema

data class EntryData(
    var id: Long? = null,
    var habitId: Long? = null,
    var timestamp: Long = 0,
    var value: Int = 0,
    var notes: String = ""
)

class EntryRepository(private val db: Database) {
    init {
        SyncSchema.ensure(db)
    }

    private val findAllByHabitStmt by lazy {
        db.prepareStatement(
            "SELECT id, habit, timestamp, value, notes FROM Repetitions WHERE habit = ? ORDER BY timestamp DESC"
        )
    }

    private val insertStmt by lazy {
        db.prepareStatement(
            "INSERT INTO Repetitions(habit, timestamp, value, notes) VALUES (?, ?, ?, ?)"
        )
    }

    private val deleteByHabitAndTimestampStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ? AND timestamp = ?")
    }

    private val deleteByHabitStmt by lazy {
        db.prepareStatement("DELETE FROM Repetitions WHERE habit = ?")
    }

    private val stampStmt by lazy {
        db.prepareStatement(SyncSchema.STAMP_ENTRY_SQL)
    }

    private val deleteMetaByHabitStmt by lazy {
        db.prepareStatement("DELETE FROM SyncEntryMeta WHERE habit = ?")
    }

    fun findAllByHabitId(habitId: Long): List<EntryData> {
        findAllByHabitStmt.reset()
        findAllByHabitStmt.bindLong(1, habitId)
        val results = mutableListOf<EntryData>()
        while (findAllByHabitStmt.step() == StepResult.ROW) {
            results.add(
                EntryData(
                    id = findAllByHabitStmt.getLong(0),
                    habitId = findAllByHabitStmt.getLong(1),
                    timestamp = findAllByHabitStmt.getLong(2),
                    value = findAllByHabitStmt.getInt(3),
                    notes = findAllByHabitStmt.getTextOrNull(4) ?: ""
                )
            )
        }
        return results
    }

    fun insert(data: EntryData): Long {
        insertStmt.reset()
        insertStmt.bindLong(1, data.habitId!!)
        insertStmt.bindLong(2, data.timestamp)
        insertStmt.bindInt(3, data.value)
        insertStmt.bindText(4, data.notes)
        insertStmt.step()
        val id = db.queryLong("SELECT last_insert_rowid()")
        stampStmt.reset()
        stampStmt.bindLong(1, data.habitId!!)
        stampStmt.bindLong(2, data.timestamp)
        stampStmt.bindLong(3, SyncClock.now())
        stampStmt.bindLong(4, data.habitId!!)
        stampStmt.bindLong(5, data.timestamp)
        stampStmt.step()
        return id
    }

    fun deleteByHabitIdAndTimestamp(habitId: Long, timestamp: Long) {
        deleteByHabitAndTimestampStmt.reset()
        deleteByHabitAndTimestampStmt.bindLong(1, habitId)
        deleteByHabitAndTimestampStmt.bindLong(2, timestamp)
        deleteByHabitAndTimestampStmt.step()
    }

    fun deleteByHabitId(habitId: Long) {
        deleteByHabitStmt.reset()
        deleteByHabitStmt.bindLong(1, habitId)
        deleteByHabitStmt.step()
        deleteMetaByHabitStmt.reset()
        deleteMetaByHabitStmt.bindLong(1, habitId)
        deleteMetaByHabitStmt.step()
    }

    fun execSQL(sql: String) = db.run(sql)
}
