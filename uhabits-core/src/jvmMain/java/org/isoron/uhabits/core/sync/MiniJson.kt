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
 * Minimal JSON reader and writer, enough for the Google Drive REST API.
 *
 * Objects become [Map]s, arrays become [List]s, numbers become [Double]s.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("Unexpected trailing characters")
        return value
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else {
                    throw IllegalArgumentException("Unexpected character '$c' at $i")
                }
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Expected $word at $i")
            i += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDouble()
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("Unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue())
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("Unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Expected ',' or ']' at ${i - 1}")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw IllegalArgumentException("Expected key at $i")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("Unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Expected ',' or '}' at ${i - 1}")
                }
            }
        }
    }
}
