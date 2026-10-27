package com.eddyizm.tempus.stupify.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyTvDetailBinding
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.subsonic.models.Child
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A playlist, album or artist: its cover and name, Play / Shuffle / Queue, and its songs. Pressing a card on
 * the home screen opens this rather than starting to play, as Spotify does; Back from Now Playing comes
 * back here. OK on a song plays from it; a long press of OK (or Menu) offers more.
 */
class TvDetailActivity : FragmentActivity() {
    private lateinit var bind: StupifyTvDetailBinding
    private lateinit var browser: ListenableFuture<MediaBrowser>
    private lateinit var adapter: TvTrackAdapter
    private lateinit var miniBar: TvMiniBar
    private var songs: List<Child> = emptyList()
    private lateinit var source: StupifyTv.Source

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = StupifyTvDetailBinding.inflate(layoutInflater)
        setContentView(bind.root)
        browser = StupifyTv.browser(this)
        adapter = TvTrackAdapter(onPlay = { playFrom(it, shuffle = false) }, onMore = { more(it) })
        bind.tvDetailList.adapter = adapter
        miniBar = TvMiniBar(this) { id -> adapter.current = songs.indexOfFirst { it.id == id } }

        bind.tvDetailPlay.setOnClickListener { playFrom(0, shuffle = false) }
        bind.tvDetailShuffle.setOnClickListener { playFrom(0, shuffle = true) }
        bind.tvDetailQueue.setOnClickListener {
            if (songs.isEmpty()) return@setOnClickListener
            StupifyTv.enqueue(browser, songs, next = false)
            TvSongActions.toast(this, R.string.stupify_tv_added)
        }
        open(intent)
    }

    /** Coming back to this screen from Now Playing for another playlist: show that one instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    override fun onDestroy() {
        MediaBrowser.releaseFuture(browser)
        super.onDestroy()
    }

    private fun open(intent: Intent) {
        source = StupifyTv.Source(
            type = intent.getStringExtra(TYPE).orEmpty(),
            id = intent.getStringExtra(ID).orEmpty(),
            title = intent.getStringExtra(TITLE).orEmpty(),
            subtitle = intent.getStringExtra(SUBTITLE).orEmpty(),
            coverArtId = intent.getStringExtra(COVER),
        )
        bind.tvDetailTitle.text = source.title
        bind.tvDetailSubtitle.text = source.subtitle
        bind.tvDetailCount.setText(R.string.stupify_tv_loading)
        val type = when (source.type) {
            "playlist" -> CustomGlideRequest.ResourceType.Playlist
            "artist" -> CustomGlideRequest.ResourceType.Artist
            else -> CustomGlideRequest.ResourceType.Album
        }
        if (source.type == "liked") bind.tvDetailCover.setImageResource(R.drawable.stupify_liked_cover)
        else CustomGlideRequest.Builder.from(this, source.coverArtId, type).build().into(bind.tvDetailCover)
        songs = emptyList()
        adapter.items = songs
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { StupifyTv.songsOf(source.type, source.id, source.title) }
            songs = loaded
            adapter.items = loaded
            // the bar may have named the playing song before these loaded: mark it now they are here
            adapter.current = loaded.indexOfFirst { it.id == miniBar.playingId }
            val minutes = loaded.sumOf { it.duration ?: 0 } / 60
            bind.tvDetailCount.text = if (loaded.isEmpty()) "" else "${loaded.size} songs · $minutes min"
            if (loaded.isEmpty()) Toast.makeText(this@TvDetailActivity, R.string.stupify_tv_nothing_to_play, Toast.LENGTH_SHORT).show()
            bind.tvDetailHint.visibility = if (loaded.isEmpty()) View.INVISIBLE else View.VISIBLE
            bind.tvDetailPlay.requestFocus()
        }
    }

    private fun playFrom(index: Int, shuffle: Boolean) {
        if (songs.isEmpty()) {
            Toast.makeText(this, R.string.stupify_tv_nothing_to_play, Toast.LENGTH_SHORT).show()
            return
        }
        val queue = if (shuffle) songs.shuffled() else songs
        StupifyTv.play(browser, queue, if (shuffle) 0 else index, source)
        StupifyTv.openPlayer(this)
    }

    private fun more(index: Int) {
        val song = songs.getOrNull(index) ?: return
        TvSongActions.show(this, browser, song) { adapter.notifyItemChanged(index) }
    }

    companion object {
        private const val TYPE = "type"
        private const val ID = "id"
        private const val TITLE = "title"
        private const val SUBTITLE = "subtitle"
        private const val COVER = "cover"

        /** type: "playlist", "album", "artist" or "liked". */
        fun intent(context: Context, type: String, id: String, title: String, subtitle: String, coverArtId: String?) =
            Intent(context, TvDetailActivity::class.java)
                .putExtra(TYPE, type).putExtra(ID, id).putExtra(TITLE, title)
                .putExtra(SUBTITLE, subtitle).putExtra(COVER, coverArtId)
    }
}
