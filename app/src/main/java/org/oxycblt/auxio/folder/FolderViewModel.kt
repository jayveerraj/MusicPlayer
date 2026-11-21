/*
 * Copyright (c) 2026 Auxio Project
 * FolderViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.folder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TreeMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Song

data class Folder(val name: String, val path: String, val songCount: Int)

data class FolderContent(
    val path: String,
    val name: String,
    val folders: List<Folder>,
    val songs: List<Song>,
)

/** Builds a folder tree from the directories of the songs in the library. */
@HiltViewModel
class FolderViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    @ApplicationContext private val context: Context,
) : ViewModel(), MusicRepository.UpdateListener {
    private val _content = MutableStateFlow<FolderContent?>(null)
    val content: StateFlow<FolderContent?> = _content

    private var path: String? = null
    private var initialized = false
    private var job: Job? = null

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    /** @param path The folder to show, or null to show the top-most folder of the library. */
    fun setPath(path: String?) {
        if (initialized && path == this.path) return
        initialized = true
        this.path = path
        refresh()
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary && initialized) refresh()
    }

    private fun refresh() {
        val library = musicRepository.library
        if (library == null) {
            _content.value = null
            return
        }
        val requested = path
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.Default) {
                _content.value = build(library.songs, requested)
            }
    }

    private fun build(songs: Collection<Song>, requested: String?): FolderContent {
        val dirs = songs.associateWith { split(it.path.directory.resolve(context)) }
        val root = requested?.let(::split) ?: commonPrefix(dirs.values)
        val folderCounts = TreeMap<String, Int>(String.CASE_INSENSITIVE_ORDER)
        val direct = mutableListOf<Song>()
        for ((song, dir) in dirs) {
            if (dir.size < root.size || dir.subList(0, root.size) != root) continue
            if (dir.size == root.size) {
                direct += song
            } else {
                val child = dir[root.size]
                folderCounts[child] = (folderCounts[child] ?: 0) + 1
            }
        }
        return FolderContent(
            path = root.joinToString("/"),
            name = root.lastOrNull().orEmpty(),
            folders =
                folderCounts.map { (name, count) ->
                    Folder(name, (root + name).joinToString("/"), count)
                },
            songs =
                direct.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.path.name.orEmpty() },
                ),
        )
    }

    private fun split(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun commonPrefix(dirs: Collection<List<String>>): List<String> {
        val first = dirs.firstOrNull() ?: return emptyList()
        var length = first.size
        for (dir in dirs) {
            var i = 0
            while (i < length && i < dir.size && dir[i] == first[i]) i++
            length = i
        }
        return first.subList(0, length)
    }
}
