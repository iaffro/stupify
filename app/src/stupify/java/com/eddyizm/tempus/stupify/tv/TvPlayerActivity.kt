package com.eddyizm.tempus.stupify.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import com.bumptech.glide.Glide
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyTvPlayerBinding
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** TV now playing: cover, song, progress and prev / play-pause / next, all for the remote. */
class TvPlayerActivity : FragmentActivity() {
    private lateinit var bind: StupifyTvPlayerBinding
    private lateinit var future: ListenableFuture<MediaBrowser>
    private var player: MediaBrowser? = null
    private val ticker = Handler(Looper.getMainLooper())
    private var shownId: String? = null
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = StupifyTvPlayerBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.tvPlayerPrev.setOnClickListener { player?.seekToPrevious() }
        bind.tvPlayerNext.setOnClickListener { player?.seekToNext() }
        bind.tvPlayerPlay.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        bind.tvPlayerPlay.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        future = StupifyTv.browser(this)
        future.addListener({
            player = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                showSong()
                showPlayState()
                ticker.post(tick)
            }
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
            return
        }
        val m = p.mediaMetadata
        bind.tvPlayerTitle.text = m.title ?: getString(R.string.stupify_tv_nothing_playing)
        bind.tvPlayerArtist.text = m.artist ?: ""
        bind.tvPlayerAlbum.text = m.albumTitle ?: ""
        val art = m.artworkUri
        if (art != null) Glide.with(this).load(art).into(bind.tvPlayerCover)
        else bind.tvPlayerCover.setImageResource(R.drawable.stupify_logo)
    }

    private fun showPlayState() {
        bind.tvPlayerPlay.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun showProgress() {
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
