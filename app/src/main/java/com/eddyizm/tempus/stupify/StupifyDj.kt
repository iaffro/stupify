package com.eddyizm.tempus.stupify

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.utils.EmptyDateTypeAdapter
import com.eddyizm.tempus.util.MappingUtil
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Date

/**
 * Stupify DJ: an endless queue from the server (GET /app/v1/dj/next), in sets of a few songs, each
 * opened by a spoken intro clip (a `dj-` song the server streams). The media service calls
 * [onTransition] on every track change, and the next set is appended while the last one plays.
 */
object StupifyDj {
    private const val TAG = "StupifyDj"
    private const val TOP_UP_AT = 2  // songs left in the queue when the next set is fetched

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, EmptyDateTypeAdapter())
        .setLenient()
        .create()

    /** Ids of what the DJ put in the queue: playing anything else turns the DJ off. */
    private val djIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    @Volatile private var active = false
    @Volatile private var fetching = false

    @Keep  // read by Gson: R8 must leave its fields alone
    private class DjSet {
        var label: String? = null
        var intro: Child? = null
        var songs: List<Child> = emptyList()
        fun queue(): List<Child> = listOfNotNull(intro) + songs
    }

    @JvmStatic
    fun start(fragment: Fragment, browser: ListenableFuture<MediaBrowser>, onBusy: (Boolean) -> Unit) =
        start(fragment.viewLifecycleOwner, fragment.requireContext().applicationContext, browser, onBusy)

    /** From any screen (the TV app has no Home fragment); [onStarted] runs once the queue is set. */
    fun start(owner: LifecycleOwner, context: Context, browser: ListenableFuture<MediaBrowser>,
              onBusy: (Boolean) -> Unit, onStarted: () -> Unit = {}) {
        onBusy(true)
        owner.lifecycleScope.launch {
            val set = withContext(Dispatchers.IO) { runCatching { fetch(start = true) } }
            onBusy(false)
            set.onSuccess { songs ->
                djIds.clear()
                djIds += songs.map { it.id }
                active = true
                MediaManager.startQueue(browser, songs, 0)
                onStarted()
            }.onFailure {
                Log.w(TAG, "DJ start failed", it)
                Toast.makeText(context, R.string.stupify_dj_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** From the media service on every track change: tops the queue up while the DJ is playing. */
    @JvmStatic
    fun onTransition(player: Player) {
        if (!active || fetching) return
        val current = player.currentMediaItem?.mediaId ?: return
        if (current !in djIds) {  // the user started something else
            active = false
            djIds.clear()
            return
        }
        if (player.currentMediaItemIndex < player.mediaItemCount - TOP_UP_AT) return
        fetching = true
        scope.launch {
            try {
                val songs = fetch(start = false)
                val items = MappingUtil.mapMediaItems(songs)  // does DB lookups: not on the main thread
                withContext(Dispatchers.Main) {
                    if (active) {
                        djIds += songs.map { it.id }
                        player.addMediaItems(items)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "next DJ set failed", e)
            } finally {
                fetching = false
            }
        }
    }

    private fun fetch(start: Boolean): List<Child> {
        val params = App.getSubsonicClientInstance(false).params + mapOf("start" to if (start) "1" else "0")
        val query = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/dj/next?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 60000  // the very first set is written and voiced on the spot
        try {
            if (conn.responseCode != 200) throw IllegalStateException("DJ answered ${conn.responseCode}")
            val set = gson.fromJson(conn.inputStream.bufferedReader().readText(), DjSet::class.java)
            return set.queue().ifEmpty { throw IllegalStateException("DJ sent an empty set") }
        } finally {
            conn.disconnect()
        }
    }
}
