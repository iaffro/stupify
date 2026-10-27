package com.eddyizm.tempus.stupify.tv

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaBrowser
import com.bumptech.glide.Glide
import com.eddyizm.tempus.R
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * The bar along the bottom of a browse screen (layout stupify_tv_mini_bar): while something is playing it shows
 * what, how far along, and prev / play-pause / next; OK on the song opens Now Playing. It connects to the
 * playback service while its screen is on show, so every screen that includes the layout and creates one of
 * these gets the bar. `onSong` hears the id of the playing song whenever it changes (null: nothing playing).
 */
class TvMiniBar(
    private val activity: FragmentActivity,
    private val onSong: ((String?) -> Unit)? = null,
) : DefaultLifecycleObserver {
    private val bar: View = activity.findViewById(R.id.tv_mini_bar)
    private val cover: ImageView = activity.findViewById(R.id.tv_mini_cover)
    private val title: TextView = activity.findViewById(R.id.tv_mini_title)
    private val artist: TextView = activity.findViewById(R.id.tv_mini_artist)
    private val progress: ProgressBar = activity.findViewById(R.id.tv_mini_progress)
    private val play: ImageButton = activity.findViewById(R.id.tv_mini_play)

    /** The id of the song the bar is showing (null: nothing playing), for a screen that loads its songs later. */
    val playingId: String? get() = shownId

    private var future: ListenableFuture<MediaBrowser>? = null
    private var player: MediaBrowser? = null
    private var shownId: String? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // The player's state reaches a freshly connected screen without any event to hear (nothing changed
            // in the service), so look again whenever the song on show differs from the one playing.
            val id = player?.currentMediaItem?.mediaId
            if (id != shownId || (id != null && bar.visibility != View.VISIBLE)) refresh()
            showProgress()
            ticker.postDelayed(this, 500)
        }
    }
    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) = showPlayState()
    }

    init {
        activity.lifecycle.addObserver(this)
        activity.findViewById<View>(R.id.tv_mini_open).setOnClickListener { StupifyTv.openPlayer(activity, reuse = true) }
        activity.findViewById<View>(R.id.tv_mini_prev).setOnClickListener { player?.seekToPrevious() }
        activity.findViewById<View>(R.id.tv_mini_next).setOnClickListener { player?.seekToNext() }
        play.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    }

    override fun onStart(owner: LifecycleOwner) {
        val f = StupifyTv.browser(activity)
        future = f
        f.addListener({
            val connected = runCatching { f.get() }.getOrNull() ?: return@addListener
            player = connected  // before the first refresh: it reads it
            connected.addListener(listener)
            refresh()
            showPlayState()
            ticker.post(tick)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop(owner: LifecycleOwner) {
        ticker.removeCallbacks(tick)
        player?.removeListener(listener)
        player = null
        future?.let { MediaBrowser.releaseFuture(it) }
        future = null
    }

    private fun refresh() {
        val p = player ?: return
        val item = p.currentMediaItem
        if (item == null) {
            bar.visibility = View.GONE
            if (shownId != null) onSong?.invoke(null)
            shownId = null
            return
        }
        bar.visibility = View.VISIBLE
        if (item.mediaId != shownId) {
            shownId = item.mediaId
            onSong?.invoke(item.mediaId)
        }
        if (item.mediaId.startsWith("dj-")) {  // the DJ talking between sets
            title.setText(R.string.stupify_dj_title)
            artist.text = p.mediaMetadata.title ?: ""
            cover.setImageResource(R.drawable.stupify_logo)
            return
        }
        val m = p.mediaMetadata
        title.text = m.title ?: activity.getString(R.string.stupify_tv_nothing_playing)
        artist.text = m.artist ?: ""
        val art = m.artworkUri
        if (art != null) Glide.with(activity).load(art).into(cover) else cover.setImageResource(R.drawable.stupify_logo)
    }

    private fun showPlayState() {
        play.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        StupifyTv.keepAwake(activity, player)
    }

    private fun showProgress() {
        StupifyTv.keepAwake(activity, player)
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        progress.progress = if (dur > 0) (p.currentPosition.coerceAtLeast(0) * 1000 / dur).toInt() else 0
    }
}
