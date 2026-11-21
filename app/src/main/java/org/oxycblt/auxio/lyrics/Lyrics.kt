/*
 * Copyright (c) 2026 Auxio Project
 * Lyrics.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.lyrics

sealed interface Lyrics {
    data class Synced(val lines: List<Line>) : Lyrics

    data class Plain(val lines: List<String>) : Lyrics

    data class Line(val timeMs: Long, val text: String)

    companion object {
        private val TIMESTAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val ID_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

        /** Parses LRC-formatted lyrics, falling back to plain text if no timestamps exist. */
        fun parse(raw: String): Lyrics {
            val synced = mutableListOf<Line>()
            val plain = mutableListOf<String>()
            for (rawLine in raw.lineSequence()) {
                var rest = rawLine.trim()
                val times = mutableListOf<Long>()
                while (true) {
                    val match = TIMESTAMP.find(rest) ?: break
                    val (min, sec, frac) = match.destructured
                    val fracMs = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').toLong()
                    times += min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
                    rest = rest.substring(match.range.last + 1).trimStart()
                }
                if (times.isNotEmpty()) {
                    times.forEach { synced += Line(it, rest) }
                } else if (!ID_TAG.matches(rest)) {
                    plain += rest
                }
            }
            return if (synced.isNotEmpty()) {
                Synced(synced.sortedBy { it.timeMs })
            } else {
                Plain(plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() })
            }
        }
    }
}
