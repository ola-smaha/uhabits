package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.io.run
import org.isoron.uhabits.core.sync.SyncClock
import org.isoron.uhabits.core.sync.SyncSchema

data class HabitData(
    var id: Long? = null,
    var name: String = "",
    var description: String = "",
    var question: String = "",
    var freqNum: Int = 1,
    var freqDen: Int = 1,
    var color: Int = 0,
    var position: Int = 0,
    var reminderHour: Int? = null,
    var reminderMin: Int? = null,
    var reminderDays: Int = 0,
    var highlight: Int = 0,
    var archived: Int = 0,
    var type: Int = 0,
    var targetValue: Double = 0.0,
    var targetType: Int = 0,
    var unit: String = "",
    var uuid: String? = null
)

class HabitRepository(private val db: Database) {
    init {
        SyncSchema.ensure(db)
    }

    private val findAllStmt by lazy {
        db.prepareStatement(
            """SELECT id, name, description, question, freq_num, freq_den, color,
               position, reminder_hour, reminder_min, reminder_days, highlight,
               archived, type, target_value, target_type, unit, uuid
               FROM Habits ORDER BY position"""
        )
    }

    private val insertStmt by lazy {
        db.prepareStatement(
            """INSERT INTO Habits(name, description, question, freq_num, freq_den,
               color, position, reminder_hour, reminder_min, reminder_days,
               highlight, archived, type, target_value, target_type, unit, uuid)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val insertWithIdStmt by lazy {
        db.prepareStatement(
            """INSERT INTO Habits(id, name, description, question, freq_num, freq_den,
               color, position, reminder_hour, reminder_min, reminder_days,
               highlight, archived, type, target_value, target_type, unit, uuid)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        )
    }

    private val updateStmt by lazy {
        db.prepareStatement(
            """UPDATE Habits SET name=?, description=?, question=?, freq_num=?,
               freq_den=?, color=?, position=?, reminder_hour=?, reminder_min=?,
               reminder_days=?, highlight=?, archived=?, type=?, target_value=?,
               target_type=?, unit=?, uuid=? WHERE id=?"""
        )
    }

    private val deleteStmt by lazy {
        db.prepareStatement("DELETE FROM Habits WHERE id = ?")
    }

    private val findUuidStmt by lazy {
        db.prepareStatement("SELECT uuid FROM Habits WHERE id = ?")
    }

    private val stampStmt by lazy {
        db.prepareStatement(SyncSchema.STAMP_HABIT_SQL)
    }

    private val tombstoneStmt by lazy {
        db.prepareStatement(SyncSchema.TOMBSTONE_SQL)
    }

    private val deleteMetaStmt by lazy {
        db.prepareStatement("DELETE FROM SyncHabitMeta WHERE uuid = ?")
    }

    fun findAll(): List<HabitData> {
        findAllStmt.reset()
        val results = mutableListOf<HabitData>()
        while (findAllStmt.step() == StepResult.ROW) {
            results.add(readRow(findAllStmt))
        }
        return results
    }

    fun insert(data: HabitData): Long {
        if (data.id != null) {
            insertWithIdStmt.reset()
            insertWithIdStmt.bindLong(1, data.id!!)
            bindForInsert(insertWithIdStmt, data, offset = 1)
            insertWithIdStmt.step()
            markModified(data.uuid)
            return data.id!!
        }
        insertStmt.reset()
        bindForInsert(insertStmt, data)
        insertStmt.step()
        val id = db.queryLong("SELECT last_insert_rowid()")
        markModified(data.uuid)
        return id
    }

    fun update(data: HabitData) {
        updateStmt.reset()
        bindForInsert(updateStmt, data)
        updateStmt.bindLong(18, data.id!!)
        updateStmt.step()
        markModified(data.uuid)
    }

    fun delete(id: Long) {
        val uuid = findUuid(id)
        deleteStmt.reset()
        deleteStmt.bindLong(1, id)
        deleteStmt.step()
        if (uuid != null) markDeleted(uuid)
    }

    /** Deletes all habits and entries, recording the deletions so that they reach other devices. */
    fun deleteAll() {
        db.run(
            "INSERT OR REPLACE INTO SyncTombstones(uuid, deleted_at) " +
                "SELECT h.uuid, MAX(?, COALESCE(m.last_modified, 0) + 1) " +
                "FROM Habits h LEFT JOIN SyncHabitMeta m ON m.uuid = h.uuid " +
                "WHERE h.uuid IS NOT NULL"
        ) { bindLong(1, SyncClock.now()) }
        db.run("DELETE FROM Habits")
        db.run("DELETE FROM Repetitions")
        db.run("DELETE FROM SyncHabitMeta")
        db.run("DELETE FROM SyncEntryMeta")
    }

    /** Records that the habits whose positions lie in the given range were modified. */
    fun markPositionsModified(fromPosition: Int, toPosition: Int) {
        db.run(SyncSchema.STAMP_HABIT_POSITION_RANGE_SQL) {
            bindLong(1, SyncClock.now())
            bindInt(2, fromPosition)
            bindInt(3, toPosition)
        }
    }

    private fun findUuid(id: Long): String? {
        findUuidStmt.reset()
        findUuidStmt.bindLong(1, id)
        val uuid = if (findUuidStmt.step() == StepResult.ROW) findUuidStmt.getTextOrNull(0) else null
        findUuidStmt.reset()
        return uuid
    }

    private fun markModified(uuid: String?) {
        if (uuid == null) return
        stampStmt.reset()
        stampStmt.bindText(1, uuid)
        stampStmt.bindLong(2, SyncClock.now())
        stampStmt.bindText(3, uuid)
        stampStmt.step()
    }

    private fun markDeleted(uuid: String) {
        tombstoneStmt.reset()
        tombstoneStmt.bindText(1, uuid)
        tombstoneStmt.bindLong(2, SyncClock.now())
        tombstoneStmt.bindText(3, uuid)
        tombstoneStmt.bindText(4, uuid)
        tombstoneStmt.step()
        deleteMetaStmt.reset()
        deleteMetaStmt.bindText(1, uuid)
        deleteMetaStmt.step()
    }

    fun execSQL(sql: String) = db.run(sql)

    fun execSQL(sql: String, bind: PreparedStatement.() -> Unit) = db.run(sql, bind)

    private fun bindForInsert(stmt: PreparedStatement, data: HabitData, offset: Int = 0) {
        val o = offset
        stmt.bindText(1 + o, data.name)
        stmt.bindText(2 + o, data.description)
        stmt.bindText(3 + o, data.question)
        stmt.bindInt(4 + o, data.freqNum)
        stmt.bindInt(5 + o, data.freqDen)
        stmt.bindInt(6 + o, data.color)
        stmt.bindInt(7 + o, data.position)
        if (data.reminderHour != null) stmt.bindInt(8 + o, data.reminderHour!!) else stmt.bindNull(8 + o)
        if (data.reminderMin != null) stmt.bindInt(9 + o, data.reminderMin!!) else stmt.bindNull(9 + o)
        stmt.bindInt(10 + o, data.reminderDays)
        stmt.bindInt(11 + o, data.highlight)
        stmt.bindInt(12 + o, data.archived)
        stmt.bindInt(13 + o, data.type)
        stmt.bindReal(14 + o, data.targetValue)
        stmt.bindInt(15 + o, data.targetType)
        stmt.bindText(16 + o, data.unit)
        if (data.uuid != null) stmt.bindText(17 + o, data.uuid!!) else stmt.bindNull(17 + o)
    }

    private fun readRow(stmt: PreparedStatement): HabitData {
        return HabitData(
            id = stmt.getLong(0),
            name = stmt.getText(1),
            description = stmt.getText(2),
            question = stmt.getText(3),
            freqNum = stmt.getInt(4),
            freqDen = stmt.getInt(5),
            color = stmt.getInt(6),
            position = stmt.getInt(7),
            reminderHour = stmt.getIntOrNull(8),
            reminderMin = stmt.getIntOrNull(9),
            reminderDays = stmt.getInt(10),
            highlight = stmt.getInt(11),
            archived = stmt.getInt(12),
            type = stmt.getInt(13),
            targetValue = stmt.getReal(14),
            targetType = stmt.getInt(15),
            unit = stmt.getText(16),
            uuid = stmt.getTextOrNull(17)
        )
    }
}
