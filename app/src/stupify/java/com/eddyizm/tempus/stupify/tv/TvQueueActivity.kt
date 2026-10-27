package com.eddyizm.tempus.stupify.tv

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.StupifyTvQueueBinding
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * The play queue: what is playing and what is coming. OK jumps to a song; a long press of OK (or Menu) moves
 * it up or down or removes it; "Clear upcoming" empties what is after the song that is playing. The edits go
 * through the same MediaManager calls the phone's queue uses, so the saved queue stays in step.
 */
class TvQueueActivity : FragmentActivity() {
    private lateinit var bind: StupifyTvQueueBinding
    private lateinit var future: ListenableFuture<MediaBrowser>
    private lateinit var adapter: TvTrackAdapter
    private var player: MediaBrowser? = null
    private var queue: List<Child> = emptyList()
    private var focusAfter = -1
    private var first = true

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = reload()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = StupifyTv.keepAwake(this@TvQueueActivity, player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            adapter.current = player?.currentMediaItemIndex ?: -1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = StupifyTvQueueBinding.inflate(layoutInflater)
        setContentView(bind.root)
        adapter = TvTrackAdapter(onPlay = { jump(it) }, onMore = { more(it) })
        bind.tvQueueList.adapter = adapter
        bind.tvQueueClear.setOnClickListener { confirmClear() }
        future = StupifyTv.browser(this)
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            player = connected  // before the first load: reload() reads it
            connected.addListener(listener)
            StupifyTv.keepAwake(this, connected)
            reload()
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        player?.removeListener(listener)
        player = null
        MediaBrowser.releaseFuture(future)
        super.onDestroy()
    }

    private fun reload() {
        val p = player ?: return
        val keep = if (focusAfter >= 0) focusAfter else bind.tvQueueList.selectedPosition
        queue = (0 until p.mediaItemCount).map { MappingUtil.mapToChild(p.getMediaItemAt(it)) }
        adapter.items = queue
        adapter.current = p.currentMediaItemIndex
        val minutes = queue.sumOf { it.duration ?: 0 } / 60
        bind.tvQueueCount.text = if (queue.isEmpty()) getString(R.string.stupify_tv_queue_empty)
        else "${queue.size} songs · $minutes min"
        // keep the selection where it was (or on the moved song); the first time, on the song that is playing
        val target = if (first) p.currentMediaItemIndex else keep
        val firstLook = first && queue.isNotEmpty()
        if (queue.isNotEmpty()) first = false  // an empty first look (the service still syncing) must not use it up
        if (queue.isNotEmpty()) bind.tvQueueList.post {
            bind.tvQueueList.selectedPosition = target.coerceIn(0, queue.size - 1)
            if (firstLook) bind.tvQueueList.requestFocus()  // not the "Clear upcoming" button: that is a deliberate UP away
            focusAfter = -1
        }
    }

    private fun jump(index: Int) {
        val p = player ?: return
        if (index !in queue.indices) return
        p.seekToDefaultPosition(index)
        p.play()
    }

    private fun more(index: Int) {
        val p = player ?: return
        if (index !in queue.indices) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.stupify_tv_play_now) to { jump(index) }
        if (index > 0) actions += getString(R.string.stupify_tv_move_up) to { move(index, index - 1) }
        if (index < queue.size - 1) actions += getString(R.string.stupify_tv_move_down) to { move(index, index + 1) }
        actions += getString(R.string.stupify_tv_remove) to { remove(index) }
        TvSongActions.pick(this, queue[index].title.orEmpty(), actions)
    }

    private fun move(from: Int, to: Int) {
        val reordered = queue.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        focusAfter = to
        MediaManager.swap(future, ArrayList(reordered), from, to)
    }

    private fun remove(index: Int) {
        val p = player ?: return
        if (index == p.currentMediaItemIndex || p.mediaItemCount <= 1) {
            TvSongActions.toast(this, R.string.stupify_tv_cant_remove_playing)
            return
        }
        focusAfter = index.coerceAtMost(queue.size - 2)
        MediaManager.remove(future, ArrayList(queue), index)
    }

    private fun confirmClear() {
        val p = player ?: return
        val from = p.currentMediaItemIndex + 1
        if (from >= queue.size) {
            TvSongActions.toast(this, R.string.stupify_tv_queue_empty)
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.stupify_tv_clear_confirm)
            .setPositiveButton(R.string.stupify_tv_yes) { _, _ ->
                MediaManager.removeRange(future, ArrayList(queue), from, queue.size)
            }
            .setNegativeButton(R.string.stupify_tv_cancel, null)
            .create()
        // nothing has focus when a dialog opens, so the first D-pad press lands on whatever is first: start on
        // the safe button, so a stray OK cancels instead of removing the queue
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        dialog.show()
    }
}
