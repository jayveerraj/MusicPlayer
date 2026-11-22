/*
 * Copyright (c) 2026 Auxio Project
 * HomeFeedViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.feed

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.playback.stats.PlayStatsRepository

/** Provides the [HomeFeed] shown in the home tab. */
@HiltViewModel
class HomeFeedViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val playStats: PlayStatsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel(), MusicRepository.UpdateListener {
    private val _feed = MutableStateFlow<HomeFeed?>(null)
    /** The current [HomeFeed], or null if the library has not loaded yet. */
    val feed: StateFlow<HomeFeed?> = _feed

    private var job: Job? = null

    init {
        musicRepository.addUpdateListener(this)
        viewModelScope.launch { playStats.stats.drop(1).collect { refresh() } }
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        refresh()
    }

    private fun refresh() {
        val library = musicRepository.library
        if (library == null) {
            _feed.value = null
            return
        }
        val filePlaylists = musicRepository.filePlaylists
        val stats = playStats.stats.value.orEmpty()
        job?.cancel()
        job = viewModelScope.launch {
            // withContext discards the result if this job was cancelled in the meantime.
            val feed =
                withContext(Dispatchers.Default) {
                    HomeFeedBuilder(context, library, filePlaylists, stats).build()
                }
            _feed.value = feed
        }
    }
}

/** Provides a single [SongCollection] for [CollectionFragment]. */
@HiltViewModel
class CollectionViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val playStats: PlayStatsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel(), MusicRepository.UpdateListener {
    private val _collection = MutableStateFlow<SongCollection?>(null)
    val collection: StateFlow<SongCollection?> = _collection

    private var id: String? = null
    private var job: Job? = null

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    fun setId(id: String) {
        if (id == this.id) return
        this.id = id
        refresh()
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        refresh()
    }

    private fun refresh() {
        val id = id ?: return
        val library = musicRepository.library ?: return
        val filePlaylists = musicRepository.filePlaylists
        // Intentionally not refreshed on every play, so the list doesn't reorder while in use.
        val stats = playStats.stats.value.orEmpty()
        job?.cancel()
        job = viewModelScope.launch {
            val collection =
                withContext(Dispatchers.Default) {
                    HomeFeedBuilder(context, library, filePlaylists, stats).find(id)
                }
            _collection.value = collection
        }
    }
}
