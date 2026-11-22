/*
 * Copyright (c) 2026 Auxio Project
 * HomeFeedFragment.kt is part of Auxio.
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
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeFeedBinding
import org.oxycblt.auxio.databinding.ItemHomeArtistBinding
import org.oxycblt.auxio.databinding.ItemHomeCardBinding
import org.oxycblt.auxio.databinding.ItemHomeCarouselBinding
import org.oxycblt.auxio.databinding.ItemHomeHintBinding
import org.oxycblt.auxio.databinding.ItemHomeSectionBinding
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.home.HomeFragmentDirections
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Song

/**
 * A Spotify-style home page showing the user's top songs, playlists, mixes and favorite artists.
 */
class HomeFeedFragment : ViewBindingFragment<FragmentHomeFeedBinding>() {
    private val feedModel: HomeFeedViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val feedAdapter = HomeFeedAdapter()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeFeedBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeFeedBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeFeedRecycler.adapter = feedAdapter
        binding.homeFeedEmptyAction.setOnClickListener { homeModel.startChooseMusicLocations() }
        collectImmediately(feedModel.feed, ::updateFeed)
    }

    override fun onDestroyBinding(binding: FragmentHomeFeedBinding) {
        binding.homeFeedRecycler.adapter = null
    }

    private fun updateFeed(feed: HomeFeed?) {
        val binding = requireBinding()
        val empty = feed != null && feed.topSongs.songs.isEmpty()
        binding.homeFeedEmpty.isInvisible = !empty
        binding.homeFeedRecycler.isInvisible = empty
        feedAdapter.submit(if (feed == null || empty) emptyList() else buildItems(feed))
    }

    private fun buildItems(feed: HomeFeed): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        val top = feed.topSongs
        items += FeedItem.Section(top.name) { showCollection(top) }
        top.songs.take(TOP_SONGS_SHOWN).forEach { items += FeedItem.SongRow(it, top.songs) }

        items += FeedItem.Section(getString(R.string.lbl_your_playlists), null)
        if (feed.playlists.isEmpty()) {
            items += FeedItem.Hint(getString(R.string.lng_home_no_playlists))
        } else {
            items += FeedItem.Cards(feed.playlists)
        }

        if (feed.mixes.isNotEmpty()) {
            items += FeedItem.Section(getString(R.string.lbl_home_mixes), null)
            items += FeedItem.Cards(feed.mixes.map { HomeCard.Smart(it) })
        }

        if (feed.artists.isNotEmpty()) {
            items +=
                FeedItem.Section(
                    getString(
                        if (feed.hasPlays) R.string.lbl_favorite_artists else R.string.lbl_artists,
                    ),
                    null,
                )
            items += FeedItem.Artists(feed.artists)
        }
        return items
    }

    private fun showCollection(collection: SongCollection) {
        findNavController().navigateSafe(HomeFragmentDirections.showCollection(collection.id))
    }

    private fun openCard(card: HomeCard) {
        when (card) {
            is HomeCard.User -> detailModel.showPlaylist(card.playlist)
            is HomeCard.Smart -> showCollection(card.collection)
        }
    }

    private fun playCard(card: HomeCard) {
        when (card) {
            is HomeCard.User ->
                if (card.playlist.songs.isNotEmpty()) playbackModel.play(card.playlist)
            is HomeCard.Smart ->
                if (card.collection.songs.isNotEmpty()) playbackModel.play(card.collection.songs)
        }
    }

    private fun openCardMenu(card: HomeCard): Boolean {
        if (card !is HomeCard.User) return false
        listModel.openMenu(R.menu.playlist, card.playlist)
        return true
    }

    private sealed interface FeedItem {
        data class Section(val title: String, val onShowAll: (() -> Unit)?) : FeedItem

        data class SongRow(val song: Song, val queue: List<Song>) : FeedItem

        data class Cards(val cards: List<HomeCard>) : FeedItem

        data class Artists(val artists: List<Artist>) : FeedItem

        data class Hint(val text: String) : FeedItem
    }

    private inner class HomeFeedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = listOf<FeedItem>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<FeedItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) =
            when (items[position]) {
                is FeedItem.Section -> TYPE_SECTION
                is FeedItem.SongRow -> TYPE_SONG
                is FeedItem.Cards -> TYPE_CARDS
                is FeedItem.Artists -> TYPE_ARTISTS
                is FeedItem.Hint -> TYPE_HINT
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION ->
                    SectionHolder(ItemHomeSectionBinding.inflate(inflater, parent, false))
                TYPE_SONG -> SongHolder(ItemSongBinding.inflate(inflater, parent, false))
                TYPE_CARDS ->
                    CardsHolder(ItemHomeCarouselBinding.inflate(inflater, parent, false))
                TYPE_ARTISTS ->
                    ArtistsHolder(ItemHomeCarouselBinding.inflate(inflater, parent, false))
                else -> HintHolder(ItemHomeHintBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is FeedItem.Section -> {
                    val binding = (holder as SectionHolder).binding
                    binding.sectionTitle.text = item.title
                    binding.sectionAction.isVisible = item.onShowAll != null
                    binding.sectionAction.setOnClickListener { item.onShowAll?.invoke() }
                }
                is FeedItem.SongRow -> {
                    val binding = (holder as SongHolder).binding
                    val context = binding.root.context
                    binding.songAlbumCover.bind(item.song)
                    binding.songName.text = item.song.name.resolve(context)
                    binding.songInfo.text = item.song.artists.resolveNames(context)
                    binding.songMenu.setOnClickListener {
                        listModel.openMenu(R.menu.song, item.song, homeModel.playWith)
                    }
                    binding.root.setOnClickListener {
                        playbackModel.playFromList(item.song, item.queue)
                    }
                }
                is FeedItem.Cards -> (holder as CardsHolder).adapter.submit(item.cards)
                is FeedItem.Artists -> (holder as ArtistsHolder).adapter.submit(item.artists)
                is FeedItem.Hint -> (holder as HintHolder).binding.hintText.text = item.text
            }
        }
    }

    private class SectionHolder(val binding: ItemHomeSectionBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class SongHolder(val binding: ItemSongBinding) : RecyclerView.ViewHolder(binding.root)

    private class HintHolder(val binding: ItemHomeHintBinding) :
        RecyclerView.ViewHolder(binding.root)

    private inner class CardsHolder(binding: ItemHomeCarouselBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val adapter = CardAdapter()

        init {
            binding.carouselRecycler.adapter = adapter
        }
    }

    private inner class ArtistsHolder(binding: ItemHomeCarouselBinding) :
        RecyclerView.ViewHolder(binding.root) {
        val adapter = ArtistAdapter()

        init {
            binding.carouselRecycler.adapter = adapter
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        private var cards = listOf<HomeCard>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newCards: List<HomeCard>) {
            if (newCards == cards) return
            cards = newCards
            notifyDataSetChanged()
        }

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(
                ItemHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = cards[position]
            val binding = holder.binding
            val context = binding.root.context
            when (card) {
                is HomeCard.User -> {
                    binding.cardCover.bind(card.playlist)
                    binding.cardName.text = card.playlist.name.resolve(context)
                    binding.cardInfo.text =
                        cardInfo(context, R.string.lbl_playlist, card.playlist.songs.size)
                }
                is HomeCard.Smart -> {
                    val collection = card.collection
                    val (kindRes, iconRes) =
                        when (collection.kind) {
                            CollectionKind.FILE ->
                                R.string.lbl_playlist_file to R.drawable.ic_playlist_24
                            CollectionKind.FOLDER -> R.string.lbl_folder to R.drawable.ic_folder_24
                            CollectionKind.GENRE_MIX ->
                                R.string.lbl_genre_mix to R.drawable.ic_genre_24
                            CollectionKind.TOP_SONGS ->
                                R.string.lbl_top_songs to R.drawable.ic_song_24
                        }
                    binding.cardCover.bind(
                        collection.songs,
                        collection.name,
                        iconRes,
                        collection.id.hashCode(),
                    )
                    binding.cardName.text = collection.name
                    binding.cardInfo.text =
                        if (collection.kind == CollectionKind.GENRE_MIX) {
                            collection.songs
                                .flatMap { it.artists }
                                .distinct()
                                .take(MIX_ARTISTS_SHOWN)
                                .resolveNames(context)
                        } else {
                            cardInfo(context, kindRes, collection.songs.size)
                        }
                }
            }
            binding.root.setOnClickListener { openCard(card) }
            binding.root.setOnLongClickListener { openCardMenu(card) }
            binding.cardPlay.isVisible =
                when (card) {
                    is HomeCard.User -> card.playlist.songs.isNotEmpty()
                    is HomeCard.Smart -> card.collection.songs.isNotEmpty()
                }
            binding.cardPlay.setOnClickListener { playCard(card) }
        }

        private fun cardInfo(context: Context, kindRes: Int, count: Int) =
            context.getString(
                R.string.fmt_home_card_info,
                context.getString(kindRes),
                context.resources.getQuantityString(R.plurals.fmt_song_count, count, count),
            )
    }

    private inner class ArtistAdapter : RecyclerView.Adapter<ArtistHolder>() {
        private var artists = listOf<Artist>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newArtists: List<Artist>) {
            if (newArtists == artists) return
            artists = newArtists
            notifyDataSetChanged()
        }

        override fun getItemCount() = artists.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ArtistHolder(
                ItemHomeArtistBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: ArtistHolder, position: Int) {
            val artist = artists[position]
            val binding = holder.binding
            binding.artistCover.bind(artist)
            binding.artistName.text = artist.name.resolve(binding.root.context)
            binding.root.setOnClickListener { detailModel.showArtist(artist) }
            binding.root.setOnLongClickListener {
                listModel.openMenu(R.menu.parent, artist)
                true
            }
        }
    }

    private class CardHolder(val binding: ItemHomeCardBinding) :
        RecyclerView.ViewHolder(binding.root)

    private class ArtistHolder(val binding: ItemHomeArtistBinding) :
        RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_SONG = 1
        const val TYPE_CARDS = 2
        const val TYPE_ARTISTS = 3
        const val TYPE_HINT = 4
        const val TOP_SONGS_SHOWN = 5
        const val MIX_ARTISTS_SHOWN = 3
    }
}
