/*
 * Copyright (c) 2026 Auxio Project
 * HomeFeed.kt is part of Auxio.
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
import kotlin.random.Random
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.FilePlaylist
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.stats.PlayStat
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/** Where a [SongCollection] came from. */
enum class CollectionKind {
    /** The user's most played (or most recently added) songs. */
    TOP_SONGS,
    /** An M3U playlist file found in the music locations. */
    FILE,
    /** A folder of songs, such as "Music/Roadtrip". */
    FOLDER,
    /** An automatic mix of songs sharing a genre. */
    GENRE_MIX,
}

/**
 * A read-only, automatically generated list of songs that can be played on it's own.
 *
 * @param id A stable identifier, used to find this collection again after navigating.
 */
data class SongCollection(
    val id: String,
    val kind: CollectionKind,
    val name: String,
    val songs: List<Song>,
)

/** A playlist-like card shown in the home feed. */
sealed interface HomeCard {
    data class User(val playlist: Playlist) : HomeCard

    data class Smart(val collection: SongCollection) : HomeCard
}

/**
 * The contents of the Spotify-style home page.
 *
 * @param topSongs The user's top songs, or recently added songs if [hasPlays] is false.
 * @param hasPlays Whether play history exists. If false, [topSongs] and [artists] are based on the
 *   library itself instead of listening habits.
 */
data class HomeFeed(
    val topSongs: SongCollection,
    val playlists: List<HomeCard>,
    val mixes: List<SongCollection>,
    val artists: List<Artist>,
    val hasPlays: Boolean,
)

/** Builds the [HomeFeed] and it's [SongCollection]s from the library and play history. */
class HomeFeedBuilder(
    private val context: Context,
    private val library: Library,
    private val filePlaylists: List<FilePlaylist>,
    private val stats: Map<Music.UID, PlayStat>,
) {
    private val playedSongs: List<Pair<Song, PlayStat>> by lazy {
        stats.values
            .mapNotNull { stat -> library.findSong(stat.uid)?.let { it to stat } }
            .sortedWith(
                compareByDescending<Pair<Song, PlayStat>> { it.second.count }
                    .thenByDescending { it.second.lastPlayedMs },
            )
    }

    fun build(): HomeFeed {
        val playlists: List<HomeCard> =
            library.playlists
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.resolve(context) })
                .map { HomeCard.User(it) } +
                filePlaylists().map { HomeCard.Smart(it) } +
                folders().map { HomeCard.Smart(it) }
        return HomeFeed(
            topSongs = topSongs(),
            playlists = playlists,
            mixes = genreMixes(),
            artists = favoriteArtists(),
            hasPlays = playedSongs.isNotEmpty(),
        )
    }

    /** Find any [SongCollection] by it's [SongCollection.id]. */
    fun find(id: String): SongCollection? =
        when {
            id == TOP_SONGS_ID -> topSongs()
            id.startsWith(FILE_PREFIX) -> filePlaylists().find { it.id == id }
            id.startsWith(FOLDER_PREFIX) -> folders().find { it.id == id }
            id.startsWith(GENRE_PREFIX) -> genreMixes(limit = Int.MAX_VALUE).find { it.id == id }
            else -> null
        }

    private fun topSongs(): SongCollection {
        val songs =
            if (playedSongs.isNotEmpty()) {
                playedSongs.take(MAX_TOP_SONGS).map { it.first }
            } else {
                library.songs.sortedByDescending { it.addedMs }.take(MAX_TOP_SONGS)
            }
        val name =
            context.getString(
                if (playedSongs.isNotEmpty()) R.string.lbl_top_songs
                else R.string.lbl_recently_added,
            )
        return SongCollection(TOP_SONGS_ID, CollectionKind.TOP_SONGS, name, songs)
    }

    private fun filePlaylists() = filePlaylists.map {
        SongCollection(FILE_PREFIX + it.path, CollectionKind.FILE, it.name, it.songs)
    }

    /**
     * Treat folders of songs as playlists, e.g "Music/Roadtrip". Folders that are just a single
     * album or artist (as is typical with "Artist/Album" layouts) and the top-most folder holding
     * the entire library are skipped since they are already covered elsewhere.
     */
    private fun folders(): List<SongCollection> {
        val nodes = mutableMapOf<String, FolderNode>()
        for (song in library.songs) {
            val parts = song.path.directory.resolve(context).split('/').filter { it.isNotEmpty() }
            for (depth in 1..parts.size) {
                val key = parts.subList(0, depth).joinToString("/")
                val node = nodes.getOrPut(key) { FolderNode(parts[depth - 1]) }
                node.songs.add(song)
                if (depth == parts.size) {
                    node.hasDirectSongs = true
                } else {
                    node.children.add(parts[depth])
                }
            }
        }
        val total = library.songs.size
        return nodes
            .filter { (_, node) ->
                node.songs.size in MIN_FOLDER_SONGS until total &&
                    (node.hasDirectSongs || node.children.size > 1) &&
                    node.songs.distinctBy { it.album.uid }.size > 1 &&
                    node.songs.distinctBy { song -> song.artists.map { it.uid } }.size > 1
            }
            .map { (path, node) ->
                SongCollection(
                    FOLDER_PREFIX + path,
                    CollectionKind.FOLDER,
                    node.name,
                    node.songs.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.path.resolve(context) },
                    ),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private class FolderNode(val name: String) {
        val songs = mutableListOf<Song>()
        val children = mutableSetOf<String>()
        var hasDirectSongs = false
    }

    /**
     * One mix per genre, ordered by how much the user listens to it. Each mix leads with the user's
     * most played songs of that genre, followed by the rest in a stable shuffled order.
     */
    private fun genreMixes(limit: Int = MAX_MIXES): List<SongCollection> {
        val plays = stats.mapValues { it.value.count }
        return library.genres
            .filter { it.songs.size >= MIN_MIX_SONGS }
            .sortedWith(
                compareByDescending<Genre> { genre ->
                        genre.songs.sumOf { plays[it.uid] ?: 0 }
                    }
                    .thenByDescending { it.songs.size },
            )
            .take(limit)
            .map { genre ->
                val random = Random(genre.uid.hashCode())
                val songs =
                    genre.songs
                        .shuffled(random)
                        .sortedByDescending { plays[it.uid] ?: 0 }
                        .take(MAX_MIX_SONGS)
                SongCollection(
                    GENRE_PREFIX + genre.uid,
                    CollectionKind.GENRE_MIX,
                    context.getString(R.string.fmt_genre_mix, genre.name.resolve(context)),
                    songs,
                )
            }
    }

    private fun favoriteArtists(): List<Artist> {
        if (playedSongs.isEmpty()) {
            return library.artists
                .filter { it.songs.isNotEmpty() }
                .sortedByDescending { it.songs.size }
                .take(MAX_ARTISTS)
        }
        val scores = mutableMapOf<Artist, Int>()
        for ((song, stat) in playedSongs) {
            for (artist in song.artists) {
                scores[artist] = (scores[artist] ?: 0) + stat.count
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(MAX_ARTISTS).map { it.key }
    }

    companion object {
        const val TOP_SONGS_ID = "top"
        private const val FILE_PREFIX = "file:"
        private const val FOLDER_PREFIX = "folder:"
        private const val GENRE_PREFIX = "genre:"
        private const val MAX_TOP_SONGS = 50
        private const val MAX_ARTISTS = 12
        private const val MAX_MIXES = 10
        private const val MAX_MIX_SONGS = 100
        private const val MIN_FOLDER_SONGS = 2
        private const val MIN_MIX_SONGS = 3
    }
}
