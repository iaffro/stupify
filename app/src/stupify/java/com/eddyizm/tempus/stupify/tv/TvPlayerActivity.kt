package com.eddyizm.tempus.stupify.tv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import com.bumptech.glide.Glide
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyTvPlayerBinding
import com.eddyizm.tempus.util.Preferences
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TV now playing: cover, song, progress (left / right on it seek), prev / play-pause / next, like, the queue, and a way back to where it came from. */
class TvPlayerActivity : FragmentActivity() {
    private lateinit var bind: StupifyTvPlayerBinding
    private lateinit var future: ListenableFuture<MediaBrowser>
    private var player: MediaBrowser? = null
    private val ticker = Handler(Looper.getMainLooper())
    private var shownId: String? = null
    private var likeFor: String? = null  // the song the heart below is showing the state of
    private var liked = false
    private val tick = object : Runnable {
        override fun run() {
            // the queue is often set a moment after this screen connects: catch the song change here too
            if (player?.currentMediaItem?.mediaId != shownId) showSong()
            showProgress()
            ticker.postDelayed(this, 500)
        }
    }
    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = showSong()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = showSong()
        override fun onIsPlayingChanged(isPlaying: Boolean) = showPlayState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = showShuffle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = StupifyTvPlayerBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.tvPlayerPrev.setOnClickListener { player?.seekToPrevious() }
        bind.tvPlayerNext.setOnClickListener { player?.seekToNext() }
        bind.tvPlayerPlay.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        bind.tvPlayerLike.setOnClickListener { toggleLike() }
        bind.tvPlayerShuffle.setOnClickListener { toggleShuffle() }
        bind.tvPlayerQueue.setOnClickListener { startActivity(Intent(this, TvQueueActivity::class.java)) }
        bind.tvPlayerSource.setOnClickListener {
            StupifyTv.playingFrom?.let {
                // back to the playlist / album it is playing from; if that screen is already open behind this one, return to it
                startActivity(TvDetailActivity.intent(this, it.type, it.id, it.title, it.subtitle, it.coverArtId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        StupifyTv.bindSeek(bind.tvPlayerProgress, bind.tvPlayerProgress, { player }) { showProgress() }
        bind.tvPlayerProgress.requestFocus()  // left / right seek right away; down reaches the buttons
    }

    override fun onStart() {
        super.onStart()
        future = StupifyTv.browser(this)
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            player = connected  // before the first look: showSong() reads it
            connected.addListener(listener)
            showSong()
            showPlayState()
            ticker.post(tick)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        player?.removeListener(listener)
        player = null
        MediaBrowser.releaseFuture(future)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {  // remote media keys, wherever focus is
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
            KeyEvent.KEYCODE_MEDIA_NEXT -> p.seekToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> p.seekToPrevious()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekBack()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun showSong() {
        val p = player ?: return
        shownId = p.currentMediaItem?.mediaId
        if (p.currentMediaItem?.mediaId?.startsWith("dj-") == true) {  // the DJ talking between sets
            bind.tvPlayerTitle.setText(R.string.stupify_dj_title)
            bind.tvPlayerArtist.text = p.mediaMetadata.title ?: ""
            bind.tvPlayerAlbum.text = ""
            bind.tvPlayerCover.setImageResource(R.drawable.stupify_logo)
            refreshLike()
            return
        }
        val m = p.mediaMetadata
        bind.tvPlayerTitle.text = m.title ?: getString(R.string.stupify_tv_nothing_playing)
        bind.tvPlayerArtist.text = m.artist ?: ""
        bind.tvPlayerAlbum.text = m.albumTitle ?: ""
        val art = m.artworkUri
        if (art != null) Glide.with(this).load(art).into(bind.tvPlayerCover)
        else bind.tvPlayerCover.setImageResource(R.drawable.stupify_logo)
        refreshLike()
    }

    /** The heart and the "go to playlist" link follow the song that is playing. */
    private fun refreshLike() {
        showShuffle()
        val src = StupifyTv.playingFrom
        bind.tvPlayerSource.visibility = if (src == null) View.GONE else View.VISIBLE
        if (src != null) bind.tvPlayerSource.setText(when (src.type) {
            "playlist" -> R.string.stupify_tv_go_playlist
            "artist" -> R.string.stupify_tv_go_artist
            else -> R.string.stupify_tv_go_album
        })
        val id = player?.currentMediaItem?.mediaId
        if (id == null || id.startsWith("dj-")) {  // the DJ's voice is not a song you can like
            bind.tvPlayerLike.visibility = View.INVISIBLE
            likeFor = null
            return
        }
        bind.tvPlayerLike.visibility = View.VISIBLE
        if (id == likeFor) return
        likeFor = id
        showHeart(false)
        lifecycleScope.launch {
            val starred = withContext(Dispatchers.IO) { StupifyTv.isStarred(id) }
            if (likeFor == id) {
                liked = starred
                showHeart(starred)
            }
        }
    }

    /** The shuffle button: accent while shuffle is on, hidden while the DJ's voice is playing. */
    private fun showShuffle() {
        val dj = player?.currentMediaItem?.mediaId?.startsWith("dj-") == true
        bind.tvPlayerShuffle.visibility = if (dj) View.INVISIBLE else View.VISIBLE
        val on = player?.shuffleModeEnabled == true
        val text = ContextCompat.getColor(this, R.color.stupify_tv_text)
        val rest = ContextCompat.getColor(this, if (on) R.color.stupify_tv_accent else R.color.stupify_tv_text)
        bind.tvPlayerShuffle.imageTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(text, rest))
        bind.tvPlayerShuffle.contentDescription = getString(if (on) R.string.stupify_tv_shuffle_on else R.string.stupify_tv_shuffle_off)
    }

    private fun toggleShuffle() {
        val p = player ?: return
        val on = !p.shuffleModeEnabled
        p.shuffleModeEnabled = on
        Preferences.setShuffleModeEnabled(on)  // the same choice the phone's button remembers
        showShuffle()
        Toast.makeText(this, if (on) R.string.stupify_tv_shuffled else R.string.stupify_tv_unshuffled, Toast.LENGTH_SHORT).show()
    }

    private fun showHeart(on: Boolean) {
        bind.tvPlayerLike.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorites_outlined)
        val text = ContextCompat.getColor(this, R.color.stupify_tv_text)
        val rest = ContextCompat.getColor(this, if (on) R.color.stupify_tv_accent else R.color.stupify_tv_text)
        // focused the button is filled with the accent colour, so a liked (accent) heart would vanish into it
        bind.tvPlayerLike.imageTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(text, rest))
        bind.tvPlayerLike.contentDescription = getString(if (on) R.string.stupify_tv_unlike else R.string.stupify_tv_like)
    }

    private fun toggleLike() {
        val id = likeFor ?: return
        val want = !liked
        showHeart(want)  // at once; put back if the server says no
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { StupifyTv.setStarred(id, want) }
            if (likeFor != id) return@launch
            if (ok) {
                liked = want
                Toast.makeText(this@TvPlayerActivity, if (want) R.string.stupify_tv_liked else R.string.stupify_tv_unliked, Toast.LENGTH_SHORT).show()
            } else {
                showHeart(liked)
                Toast.makeText(this@TvPlayerActivity, R.string.stupify_tv_like_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPlayState() {
        bind.tvPlayerPlay.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        StupifyTv.keepAwake(this, player)
    }

    private fun showProgress() {
        StupifyTv.keepAwake(this, player)
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0)
        bind.tvPlayerProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        bind.tvPlayerTime.text = if (dur > 0) "${clock(pos)} / ${clock(dur)}" else clock(pos)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
