/*
 * Copyright (c) 2026 Auxio Project
 * LyricsReader.kt is part of Auxio.
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
 
package org.oxycblt.musikr.metadata

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.File

/** Reads embedded lyrics on demand, since they are not stored in the library cache. */
object LyricsReader {
    suspend fun read(context: Context, song: Song): String? =
        withContext(Dispatchers.IO) {
            val file =
                File(
                    uri = song.uri,
                    path = song.path,
                    addedMs = NoAddedMs,
                    modifiedMs = song.modifiedMs,
                    mimeType = song.format.mimeType,
                    size = song.size,
                    parent = null,
                )
            val result =
                try {
                    MetadataExtractor.from(context).extract(file)
                } catch (e: Exception) {
                    null
                }
            val metadata = (result as? MetadataResult.Success)?.metadata ?: return@withContext null
            (metadata.xiph["LYRICS"]
                    ?: metadata.xiph["UNSYNCEDLYRICS"]
                    ?: metadata.xiph["UNSYNCED LYRICS"]
                    ?: metadata.mp4["©lyr"])
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
        }

    private object NoAddedMs : AddedMs {
        override suspend fun resolve(): Long? = null
    }
}
