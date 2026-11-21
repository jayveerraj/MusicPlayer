/*
 * Copyright (c) 2026 Auxio Project
 * FolderFragment.kt is part of Auxio.
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
import org.oxycblt.auxio.databinding.FragmentFolderBinding
import org.oxycblt.auxio.databinding.ItemFolderBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.musikr.Song

/** Browses the music library by the folders the files are stored in. */
@AndroidEntryPoint
class FolderFragment : ViewBindingFragment<FragmentFolderBinding>() {
    private val folderModel: FolderViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val args: FolderFragmentArgs by navArgs()
    private val folderAdapter =
        FolderAdapter(
            onFolder = {
                findNavController().navigateSafe(FolderFragmentDirections.showFolder(it.path))
            },
            onSong = { song, songs -> playbackModel.playFromList(song, songs) },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        folderModel.setPath(args.path)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentFolderBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentFolderBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.folderToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.folderRecycler.adapter = folderAdapter
        collectImmediately(folderModel.content, ::updateContent)
    }

    override fun onDestroyBinding(binding: FragmentFolderBinding) {
        binding.folderRecycler.adapter = null
    }

    private fun updateContent(content: FolderContent?) {
        val toolbar = requireBinding().folderToolbar
        toolbar.title = content?.name?.ifEmpty { null } ?: getString(R.string.lbl_folders)
        toolbar.subtitle = content?.path
        folderAdapter.submit(content)
    }
}

private class FolderAdapter(
    private val onFolder: (Folder) -> Unit,
    private val onSong: (Song, List<Song>) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var folders: List<Folder> = emptyList()
    private var songs: List<Song> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(content: FolderContent?) {
        folders = content?.folders.orEmpty()
        songs = content?.songs.orEmpty()
        notifyDataSetChanged()
    }

    override fun getItemCount() = folders.size + songs.size

    override fun getItemViewType(position: Int) =
        if (position < folders.size) TYPE_FOLDER else TYPE_SONG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderHolder(ItemFolderBinding.inflate(inflater, parent, false))
        } else {
            SongHolder(ItemSongBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FolderHolder -> {
                val folder = folders[position]
                val context = holder.binding.root.context
                holder.binding.folderName.text = folder.name
                holder.binding.folderInfo.text =
                    context.resources.getQuantityString(
                        R.plurals.fmt_song_count,
                        folder.songCount,
                        folder.songCount,
                    )
                holder.binding.root.setOnClickListener { onFolder(folder) }
            }
            is SongHolder -> {
                val song = songs[position - folders.size]
                val context = holder.binding.root.context
                holder.binding.songAlbumCover.bind(song)
                holder.binding.songName.text = song.name.resolve(context)
                holder.binding.songInfo.text = song.artists.resolveNames(context)
                holder.binding.songMenu.isVisible = false
                holder.binding.root.setOnClickListener { onSong(song, songs) }
            }
        }
    }

    class FolderHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    class SongHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_SONG = 1
    }
}
