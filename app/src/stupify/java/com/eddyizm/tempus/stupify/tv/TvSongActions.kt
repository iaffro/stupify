package com.eddyizm.tempus.stupify.tv

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Child
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** What you can do with one song, as a list to pick from (opened by a long press of OK, or Menu, on a song). */
object TvSongActions {
    fun show(
        activity: FragmentActivity,
        browser: ListenableFuture<MediaBrowser>,
        song: Child,
        onChanged: () -> Unit,
    ) {
        val liked = song.starred != null
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += activity.getString(R.string.stupify_tv_play_next) to {
            StupifyTv.enqueue(browser, listOf(song), next = true)
            toast(activity, R.string.stupify_tv_playing_next)
        }
        actions += activity.getString(R.string.stupify_tv_add_to_queue) to {
            StupifyTv.enqueue(browser, listOf(song), next = false)
            toast(activity, R.string.stupify_tv_added)
        }
        actions += activity.getString(if (liked) R.string.stupify_tv_unlike else R.string.stupify_tv_like) to {
            setLiked(activity, song, !liked, onChanged)
        }
        song.albumId?.takeIf { it.isNotBlank() }?.let { id ->
            actions += activity.getString(R.string.stupify_tv_go_album) to {
                activity.startActivity(TvDetailActivity.intent(activity, "album", id, song.album.orEmpty(), song.artist.orEmpty(), song.coverArtId))
            }
        }
        song.artistId?.takeIf { it.isNotBlank() }?.let { id ->
            actions += activity.getString(R.string.stupify_tv_go_artist) to {
                activity.startActivity(TvDetailActivity.intent(activity, "artist", id, song.artist.orEmpty(), "Artist", null))
            }
        }
        pick(activity, song.title.orEmpty(), actions)
    }

    /** A plain list of actions in a dialog: the D-pad moves, OK chooses, Back dismisses. */
    fun pick(activity: FragmentActivity, title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    fun toast(activity: FragmentActivity, res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    fun setLiked(activity: FragmentActivity, song: Child, liked: Boolean, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { StupifyTv.setStarred(song.id, liked) }
            if (ok) {
                song.starred = if (liked) Date() else null
                onChanged()
                toast(activity, if (liked) R.string.stupify_tv_liked else R.string.stupify_tv_unliked)
            } else {
                toast(activity, R.string.stupify_tv_like_failed)
            }
        }
    }
}
