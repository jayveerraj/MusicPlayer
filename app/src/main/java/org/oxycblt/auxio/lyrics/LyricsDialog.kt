/*
 * Copyright (c) 2026 Auxio Project
 * LyricsDialog.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogLyricsBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.metadata.LyricsReader

/** Shows the embedded lyrics of the playing song, following playback when they are synced. */
@AndroidEntryPoint
class LyricsDialog : ViewBindingBottomSheetDialogFragment<DialogLyricsBinding>() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val lyricsAdapter = LyricsAdapter { playbackModel.seekTo(it.timeMs / 100) }
    private var loadJob: Job? = null
    private var loadedSong: Song? = null

    override fun onCreateBinding(inflater: LayoutInflater) = DialogLyricsBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogLyricsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        (dialog as BackportBottomSheetDialog).behavior.apply {
            skipCollapsed = true
            state = BackportBottomSheetBehavior.STATE_EXPANDED
        }

        val contentHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        binding.lyricsContent.updateLayoutParams<ViewGroup.LayoutParams> {
            height = contentHeight
        }
        binding.lyricsRecycler.apply {
            // Lets the first and last lines scroll to the center.
            updatePadding(top = contentHeight / 3, bottom = contentHeight / 3)
            itemAnimator = null
            adapter = lyricsAdapter
        }

        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
    }

    override fun onDestroyBinding(binding: DialogLyricsBinding) {
        loadJob?.cancel()
        loadedSong = null
        binding.lyricsRecycler.adapter = null
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            dismiss()
            return
        }
        if (song == loadedSong) return
        loadedSong = song

        val context = requireContext()
        requireBinding().apply {
            lyricsTitle.text = song.name.resolve(context)
            lyricsSubtitle.text = song.artists.resolveNames(context)
            lyricsEmpty.isVisible = false
        }
        lyricsAdapter.submit(null)

        loadJob?.cancel()
        loadJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val lyrics = LyricsReader.read(context.applicationContext, song)?.let(Lyrics::parse)
                val current = binding ?: return@launch
                lyricsAdapter.submit(lyrics)
                current.lyricsEmpty.isVisible = lyricsAdapter.itemCount == 0
                current.lyricsRecycler.scrollToPosition(0)
                updatePosition(playbackModel.positionDs.value)
            }
    }

    private fun updatePosition(positionDs: Long) {
        val index = lyricsAdapter.updatePosition(positionDs * 100)
        if (index < 0) return
        val recycler = binding?.lyricsRecycler ?: return
        // Don't fight the user while they are scrolling through the lyrics.
        if (recycler.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return
        recycler.layoutManager?.startSmoothScroll(
            CenterScroller(recycler.context).apply { targetPosition = index },
        )
    }

    companion object {
        const val TAG = "LyricsDialog"
    }
}

private class CenterScroller(context: Context) : LinearSmoothScroller(context) {
    override fun calculateDtToFit(
        viewStart: Int,
        viewEnd: Int,
        boxStart: Int,
        boxEnd: Int,
        snapPreference: Int,
    ) = (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
}

private class LyricsAdapter(private val onSeek: (Lyrics.Line) -> Unit) :
    RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {
    private var synced: List<Lyrics.Line>? = null
    private var lines: List<String> = emptyList()
    private var current = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(lyrics: Lyrics?) {
        synced = (lyrics as? Lyrics.Synced)?.lines
        lines =
            when (lyrics) {
                is Lyrics.Synced -> lyrics.lines.map { it.text.ifEmpty { "♪" } }
                is Lyrics.Plain -> lyrics.lines
                null -> emptyList()
            }
        current = -1
        notifyDataSetChanged()
    }

    /** Returns the newly active line, or -1 if the active line did not change. */
    fun updatePosition(positionMs: Long): Int {
        val synced = synced ?: return -1
        val index = synced.indexOfLast { it.timeMs <= positionMs }
        if (index == current) return -1
        val old = current
        current = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
        return index
    }

    override fun getItemCount() = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
                as TextView,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val view = holder.text
        view.text = lines[position]
        val syncedLines = synced
        if (syncedLines == null) {
            view.alpha = 1f
            view.setTextColor(
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface),
            )
            view.setOnClickListener(null)
            view.isClickable = false
            return
        }
        val active = position == current
        view.alpha = if (active) 1f else 0.5f
        view.setTextColor(
            MaterialColors.getColor(
                view,
                if (active) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface,
            ),
        )
        view.setOnClickListener {
            syncedLines.getOrNull(holder.bindingAdapterPosition)?.let(onSeek)
        }
    }

    class ViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
