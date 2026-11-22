/*
 * Copyright (c) 2026 Auxio Project
 * CollectionFragment.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentCollectionBinding
import org.oxycblt.auxio.databinding.ItemCollectionHeaderBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

/**
 * Shows the songs of a [SongCollection] (a folder, playlist file, mix, or top songs). Anything
 * played from here only plays the songs in this collection.
 */
@AndroidEntryPoint
class CollectionFragment : ViewBindingFragment<FragmentCollectionBinding>() {
    private val collectionModel: CollectionViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: CollectionFragmentArgs by navArgs()
    private val collectionAdapter =
        CollectionAdapter(
            onPlay = { playbackModel.play(it) },
            onShuffle = { playbackModel.shuffle(it) },
            onSong = { song, songs -> playbackModel.playFromList(song, songs) },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        collectionModel.setId(args.collectionId)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentCollectionBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentCollectionBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.collectionToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.collectionRecycler.adapter = collectionAdapter
        collectImmediately(collectionModel.collection, ::updateCollection)
    }

    override fun onDestroyBinding(binding: FragmentCollectionBinding) {
        binding.collectionRecycler.adapter = null
    }

    private fun updateCollection(collection: SongCollection?) {
        val toolbar = requireBinding().collectionToolbar
        toolbar.title = collection?.name
        toolbar.subtitle =
            collection?.let {
                resources.getQuantityString(R.plurals.fmt_song_count, it.songs.size, it.songs.size)
            }
        collectionAdapter.submit(collection?.songs.orEmpty())
    }
}

private class CollectionAdapter(
    private val onPlay: (List<Song>) -> Unit,
    private val onShuffle: (List<Song>) -> Unit,
    private val onSong: (Song, List<Song>) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var songs: List<Song> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    override fun getItemCount() = if (songs.isEmpty()) 0 else songs.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemCollectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            SongHolder(ItemSongBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> {
                holder.binding.collectionPlay.setOnClickListener { onPlay(songs) }
                holder.binding.collectionShuffle.setOnClickListener { onShuffle(songs) }
            }
            is SongHolder -> {
                val song = songs[position - 1]
                val context = holder.binding.root.context
                holder.binding.songAlbumCover.bind(song)
                holder.binding.songName.text = song.name.resolve(context)
                holder.binding.songInfo.text = song.artists.resolveNames(context)
                holder.binding.songMenu.isVisible = false
                holder.binding.root.setOnClickListener { onSong(song, songs) }
            }
        }
    }

    class HeaderHolder(val binding: ItemCollectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    class SongHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SONG = 1
    }
}
