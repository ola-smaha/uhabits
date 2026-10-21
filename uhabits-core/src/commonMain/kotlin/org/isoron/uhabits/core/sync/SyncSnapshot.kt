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
 * The synced fields of a habit, plus the time of its last modification.
 *
 * Values are normalized the same way the app normalizes them when loading a habit, so that two
 * copies of the same habit compare equal regardless of which device wrote them.
 */
data class SyncHabit(
    val uuid: String,
    val name: String,
    val description: String,
    val question: String,
    val freqNum: Int,
    val freqDen: Int,
    val color: Int,
    val position: Int,
    val reminderHour: Int?,
    val reminderMin: Int?,
    val reminderDays: Int,
    val archived: Int,
    val type: Int,
    val targetValue: Double,
    val targetType: Int,
    val unit: String,
    val lastModified: Long
) {
    /** True if both habits have the same content (ignoring modification time, and optionally position). */
    fun sameContentAs(other: SyncHabit, ignorePosition: Boolean = false): Boolean {
        return compareContent(this, other, ignorePosition) == 0
    }

    companion object {
        /**
         * Total order over the content of two habits. Used to break ties between versions that
         * were modified at exactly the same time, so that every device picks the same winner.
         */
        fun compareContent(a: SyncHabit, b: SyncHabit, ignorePosition: Boolean = false): Int {
            var c = a.uuid.compareTo(b.uuid)
            if (c != 0) return c
            c = a.name.compareTo(b.name)
            if (c != 0) return c
            c = a.description.compareTo(b.description)
            if (c != 0) return c
            c = a.question.compareTo(b.question)
            if (c != 0) return c
            c = a.freqNum.compareTo(b.freqNum)
            if (c != 0) return c
            c = a.freqDen.compareTo(b.freqDen)
            if (c != 0) return c
            c = a.color.compareTo(b.color)
            if (c != 0) return c
            if (!ignorePosition) {
                c = a.position.compareTo(b.position)
                if (c != 0) return c
            }
            c = (a.reminderHour ?: -1).compareTo(b.reminderHour ?: -1)
            if (c != 0) return c
            c = (a.reminderMin ?: -1).compareTo(b.reminderMin ?: -1)
            if (c != 0) return c
            c = a.reminderDays.compareTo(b.reminderDays)
            if (c != 0) return c
            c = a.archived.compareTo(b.archived)
            if (c != 0) return c
            c = a.type.compareTo(b.type)
            if (c != 0) return c
            c = a.targetValue.toBits().compareTo(b.targetValue.toBits())
            if (c != 0) return c
            c = a.targetType.compareTo(b.targetType)
            if (c != 0) return c
            return a.unit.compareTo(b.unit)
        }
    }
}

/** Identifies an entry across devices: habit uuid plus the day (as stored by the app). */
data class SyncEntryKey(val habitUuid: String, val timestamp: Long)

/** A single entry (check-in, number or note) of a habit, plus the time of its last modification. */
data class SyncEntry(
    val habitUuid: String,
    val timestamp: Long,
    val value: Int,
    val notes: String,
    val lastModified: Long
) {
    val key: SyncEntryKey
        get() = SyncEntryKey(habitUuid, timestamp)

    fun sameContentAs(other: SyncEntry): Boolean = value == other.value && notes == other.notes

    companion object {
        /** Total order over the content of two entries; see [SyncHabit.compareContent]. */
        fun compareContent(a: SyncEntry, b: SyncEntry): Int {
            val c = a.value.compareTo(b.value)
            if (c != 0) return c
            return a.notes.compareTo(b.notes)
        }
    }
}

/**
 * Everything that is synced between devices: habits, their entries and the deleted habits.
 */
data class SyncSnapshot(
    val habits: Map<String, SyncHabit>,
    val entries: Map<SyncEntryKey, SyncEntry>,
    val tombstones: Map<String, Long>
) {
    /**
     * A 64-bit hash of the whole snapshot (content and modification times). Two snapshots with
     * the same fingerprint are, for all practical purposes, identical.
     */
    fun fingerprint(): Long {
        val h = Fnv64()
        for (uuid in habits.keys.sorted()) {
            val x = habits.getValue(uuid)
            h.add("H").add(x.uuid).add(x.name).add(x.description).add(x.question)
            h.add(x.freqNum).add(x.freqDen).add(x.color).add(x.position)
            h.add(x.reminderHour ?: -1).add(x.reminderMin ?: -1).add(x.reminderDays)
            h.add(x.archived).add(x.type).add(x.targetValue.toBits()).add(x.targetType)
            h.add(x.unit).add(x.lastModified)
        }
        val keys = entries.keys.sortedWith(compareBy<SyncEntryKey>({ it.habitUuid }, { it.timestamp }))
        for (k in keys) {
            val e = entries.getValue(k)
            h.add("E").add(e.habitUuid).add(e.timestamp).add(e.value).add(e.notes).add(e.lastModified)
        }
        for (uuid in tombstones.keys.sorted()) {
            h.add("T").add(uuid).add(tombstones.getValue(uuid))
        }
        return h.value
    }

    val isEmpty: Boolean
        get() = habits.isEmpty() && entries.isEmpty() && tombstones.isEmpty()

    companion object {
        val EMPTY = SyncSnapshot(emptyMap(), emptyMap(), emptyMap())
    }
}

/** FNV-1a, 64 bits. Not cryptographic; only used to detect changes. */
private class Fnv64 {
    var value: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325

    private fun byte(b: Int) {
        value = (value xor (b.toLong() and 0xff)) * 0x100000001b3L
    }

    fun add(x: Long): Fnv64 {
        var v = x
        repeat(8) {
            byte((v and 0xff).toInt())
            v = v ushr 8
        }
        return this
    }

    fun add(x: Int): Fnv64 = add(x.toLong())

    fun add(s: String): Fnv64 {
        add(s.length)
        for (ch in s) {
            val code = ch.code
            byte(code and 0xff)
            byte(code ushr 8)
        }
        return this
    }
}
