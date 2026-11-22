/*
 * Copyright (c) 2026 Auxio Project
 * PlayStatsRepository.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.stats

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.Progression
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Counts song plays so the home view can show the user's top songs and favorite artists.
 *
 * A song counts as played once it actually starts playing after becoming the current song, so
 * restoring a paused session or skipping past songs in a paused queue doesn't inflate counts.
 */
@Singleton
class PlayStatsRepository
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val dao: PlayStatsDao,
) : PlaybackStateManager.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var attached = false
    private var lastSongUid: Music.UID? = null
    private var pendingSong: Song? = null

    private val _stats = MutableStateFlow<Map<Music.UID, PlayStat>?>(null)

    /** Play statistics for every played song, or null if they have not been loaded yet. */
    val stats: StateFlow<Map<Music.UID, PlayStat>?> = _stats

    @Synchronized
    fun attach() {
        if (attached) return
        attached = true
        scope.launch { mutex.withLock { _stats.value = dao.getAll().associateBy { it.uid } } }
        playbackManager.addListener(this)
    }

    override fun onIndexMoved(index: Int) = onSongMaybeChanged()

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) =
        onSongMaybeChanged()

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) =
        onSongMaybeChanged()

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) = onSongMaybeChanged()

    override fun onProgressionChanged(progression: Progression) {
        if (progression.isPlaying) countPending()
    }

    @Synchronized
    private fun onSongMaybeChanged() {
        val song = playbackManager.currentSong
        if (song?.uid == lastSongUid) return
        lastSongUid = song?.uid
        pendingSong = song
        if (playbackManager.progression.isPlaying) countPending()
    }

    @Synchronized
    private fun countPending() {
        val song = pendingSong ?: return
        pendingSong = null
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                val stat = PlayStat(song.uid, (dao.get(song.uid)?.count ?: 0) + 1, now)
                dao.put(stat)
                // If the initial load hasn't happened yet, it will pick this play up.
                _stats.update { it?.plus(song.uid to stat) }
                L.d("Recorded play of $song [count=${stat.count}]")
            }
        }
    }
}
