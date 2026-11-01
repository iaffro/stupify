package com.eddyizm.tempus.stupify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.eddyizm.tempus.App
import com.eddyizm.tempus.lan.LanQueuePolicy
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.util.MappingUtil
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * The playing side of Stupify Connect: runs what other devices ask of this one (play, pause, queue edits...)
 * on the local player, and streams its state to the devices watching it. Same command set the LAN remote
 * used; the transport is the hub instead of a TLS socket, so there is nothing to pair.
 */
@UnstableApi
object ConnectReceiver {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(4)
    private val watchers = ConcurrentHashMap<String, Long>()   // watching device -> its request id
    private var appContext: Context? = null
    private var browser: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
    private var listening = false
    @Volatile private var lastPush = 0L

    /** Called after another device started something on this one (the TV uses it to bring Now Playing up). */
    @Volatile var onRemotePlay: ((Context) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ConnectHub.onRequest = { from, rid, body -> workers.execute { serve(from, rid, body) } }
        ConnectHub.onYield = { yieldToOther() }
        ConnectHub.onDevices = {
            val online = ConnectHub.devices().value.orEmpty().map { it.id }.toSet()
            watchers.keys.removeAll { it !in online }
            maybeAttach()
        }
        main.post { player() }
    }

    // ---- auto-attach: opening the app while another of your devices is playing makes this one its remote ----
    private var letGoOf: String? = null      // a device the user stepped away from on purpose: don't snap back to it
    private var probing = false
    fun letGo(deviceId: String) { letGoOf = deviceId }

    private fun maybeAttach() {
        val ctx = appContext ?: return
        if (probing || ConnectHub.isTv(ctx) || com.eddyizm.tempus.lan.LanRemoteSession.isActive()) return
        if (ConnectHub.activeId != letGoOf) letGoOf = null   // the playing device changed: the old "let go" no longer applies
        val active = ConnectHub.devices().value.orEmpty().firstOrNull { it.active && !it.self } ?: return
        if (active.id == letGoOf) return
        if (player()?.isPlaying == true) return    // playing here: this device stays the player
        probing = true
        ConnectHub.request(active.id, JSONObject().put("command", "status"), 6000) { json ->
            main.post {
                probing = false
                val still = ConnectHub.devices().value.orEmpty().firstOrNull { it.id == active.id }
                if (json.optBoolean("ok") && json.optBoolean("playing") && still != null &&
                    !com.eddyizm.tempus.lan.LanRemoteSession.isActive() && player()?.isPlaying != true)
                    com.eddyizm.tempus.lan.LanRemoteSession.connectHub(ctx, active.id, active.name, transfer = false)
            }
        }
    }

    private fun player(): MediaBrowser? {
        val ctx = appContext ?: return null
        val f = browser ?: MediaBrowser.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MediaService::class.java))).buildAsync().also { browser = it }
        if (!f.isDone) return null
        val p = runCatching { f.get() }.getOrNull() ?: run { browser = null; return null }
        if (!listening) {
            listening = true
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { push() }
            })
            main.post(object : Runnable {   // a heartbeat, so a watcher's clock never drifts far
                override fun run() { if (watchers.isNotEmpty()) push(true); main.postDelayed(this, 4000) }
            })
        }
        return p
    }

    private fun yieldToOther() {
        // Another of this user's devices took over playback. Remote-controlled sessions have nothing local to stop.
        player()?.let { if (it.isPlaying) it.pause() }
    }

    private fun push(force: Boolean = false) {
        if (watchers.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPush < 250) return
        lastPush = now
        val p = player() ?: return
        val snap = runCatching { snapshot(p) }.getOrNull() ?: return
        watchers.forEach { (to, rid) -> ConnectHub.reply(to, rid, snap, stream = true) }
    }

    private fun serve(from: String, rid: Long, request: JSONObject) {
        val answer = try { handle(from, rid, request) ?: return } catch (e: Exception) {
            JSONObject().put("error", "unavailable")
        }
        ConnectHub.reply(from, rid, answer)
    }

    private fun handle(from: String, rid: Long, request: JSONObject): JSONObject? {
        val ctx = appContext ?: return null
        return when (val command = request.optString("command")) {
            "watch" -> {
                watchers[from] = rid
                onMain { snapshot(requirePlayer()) }.also { ConnectHub.reply(from, rid, it, stream = true) }
                null   // the stream stays open; later frames come from push()
            }
            "unwatch" -> { watchers.remove(from); JSONObject().put("ok", true) }
            "edit" -> editQueue(request)
            "queue_state" -> onMain { queuePage(requirePlayer(), request.optInt("offset", 0)) }
            else -> onMain {
                val player = requirePlayer()
                when (command) {
                    "hello", "status" -> Unit
                    "play" -> { player.play() }
                    "pause" -> player.pause()
                    "next" -> player.seekToNextMediaItem()
                    "previous" -> player.seekToPrevious()
                    "shuffle" -> player.shuffleModeEnabled = request.getBoolean("enabled")
                    "repeat" -> player.repeatMode = request.getInt("mode").also { require(it in 0..2) }
                    "speed" -> {
                        val speed = request.getDouble("speed").toFloat(); val pitch = request.getDouble("pitch").toFloat()
                        require(speed in 0.1f..5f && pitch in 0.1f..5f)
                        player.playbackParameters = PlaybackParameters(speed, pitch)
                    }
                    "timer" -> {
                        val timer = com.eddyizm.tempus.util.SleepTimerManager.getInstance()
                        when (request.getString("mode")) {
                            "cancel" -> timer.cancelTimer()
                            "end" -> timer.startEndOfTrack()
                            "minutes" -> timer.startTimer(request.getInt("minutes").also { require(it in 1..1440) })
                        }
                    }
                    "seek" -> player.seekTo(request.getLong("position").coerceIn(0, player.duration.coerceAtLeast(0)))
                    "volume" -> player.volume = request.getDouble("value").toFloat().coerceIn(0f, 1f)
                    "device_volume" -> {
                        val audio = ctx.getSystemService(AudioManager::class.java)
                        val music = AudioManager.STREAM_MUSIC
                        if (request.has("direction")) audio.adjustStreamVolume(music, request.getInt("direction"), 0)
                        else audio.setStreamVolume(music, request.getInt("level").coerceIn(0, audio.getStreamMaxVolume(music)), 0)
                    }
                    else -> error("Unknown command")
                }
                snapshot(player)
            }
        }
    }

    private fun requirePlayer(): MediaBrowser = requireNotNull(player()) { "player not ready" }

    private fun snapshot(player: Player): JSONObject {
        val ctx = appContext!!
        val media = player.mediaMetadata
        val audio = ctx.getSystemService(AudioManager::class.java)
        val timer = com.eddyizm.tempus.util.SleepTimerManager.getInstance()
        return JSONObject().put("ok", true).put("remoteVersion", 4)
            .put("name", ConnectHub.deviceName(ctx)).put("title", media.title?.toString()?.take(256) ?: "")
            .put("artist", media.artist?.toString()?.take(256) ?: "").put("album", media.albumTitle?.toString()?.take(256) ?: "")
            .put("cover", media.extras?.getString("coverArtId")?.take(512) ?: "")
            .put("position", player.currentPosition.coerceAtLeast(0)).put("duration", player.duration.coerceAtLeast(0))
            .put("playing", player.isPlaying).put("playWhenReady", player.playWhenReady)
            .put("volume", player.volume).put("index", player.currentMediaItemIndex)
            .put("deviceVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("deviceVolumeMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("shuffle", player.shuffleModeEnabled).put("repeat", player.repeatMode)
            .put("id", player.currentMediaItem?.mediaId ?: "")
            .put("speed", player.playbackParameters.speed).put("pitch", player.playbackParameters.pitch)
            .put("timerActive", timer.isActive).put("timerEnd", timer.isEndOfTrack).put("timerRemaining", timer.remainingFormatted)
            .put("next", player.hasNextMediaItem()).put("previous", player.hasPreviousMediaItem())
            .put("count", player.mediaItemCount).put("revision", revision(player))
    }

    private fun revision(player: Player): String = com.eddyizm.tempus.lan.LanWire.fingerprint(
        (0 until player.mediaItemCount).joinToString("\u0000") { player.getMediaItemAt(it).mediaId }.toByteArray())

    private fun queuePage(player: Player, offset: Int): JSONObject {
        require(offset in 0..player.mediaItemCount)
        val items = JSONArray()
        for (i in offset until (offset + minOf(20, player.mediaItemCount - offset))) {
            val md = player.getMediaItemAt(i).mediaMetadata
            items.put(JSONObject().put("id", player.getMediaItemAt(i).mediaId)
                .put("album", md.albumTitle?.toString()?.take(256) ?: "")
                .put("cover", md.extras?.getString("coverArtId")?.take(512) ?: "")
                .put("albumId", md.extras?.getString("albumId")?.take(512) ?: "")
                .put("artistId", md.extras?.getString("artistId")?.take(512) ?: "")
                .put("suffix", md.extras?.getString("suffix")?.take(32) ?: "")
                .put("bitrate", md.extras?.getInt("bitrate") ?: 0).put("duration", md.extras?.getInt("duration") ?: 0)
                .put("title", md.title?.toString()?.take(256) ?: "").put("artist", md.artist?.toString()?.take(256) ?: ""))
        }
        return JSONObject().put("ok", true).put("items", items).put("index", player.currentMediaItemIndex)
            .put("count", player.mediaItemCount).put("revision", revision(player))
    }

    private fun fetchSongs(ids: List<String>): List<com.eddyizm.tempus.subsonic.models.Child> {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = ids.map { id -> pool.submit(Callable {
                val call = App.getSubsonicClientInstance(false).browsingClient.getSong(id)
                call.timeout().timeout(10, TimeUnit.SECONDS)
                val response = call.execute()
                require(response.isSuccessful)
                requireNotNull(response.body()?.subsonicResponse?.song)
            }) }
            return futures.map { it.get(90, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Synchronized private fun editQueue(request: JSONObject): JSONObject {
        val action = request.getString("action")
        val adds = action in setOf("replace", "append", "next")
        val added = if (adds) {
            val json = request.getJSONArray("ids")
            val ids = (0 until json.length()).map { json.getString(it) }
            LanQueuePolicy.validateSongs(ids)
            if (action == "replace") require(request.getInt("index") in ids.indices)
            fetchSongs(ids).map { MappingUtil.mapMediaItem(it) }
        } else emptyList()
        var started = false
        val result = onMain {
            val player = requirePlayer()
            if (!adds && action != "clear" && !LanQueuePolicy.matchesRevision(request.optString("revision"), revision(player)))
                return@onMain JSONObject().put("error", "queue_changed")
            when (action) {
                "replace" -> {
                    player.setMediaItems(added, request.getInt("index"), request.optLong("position", 0).coerceAtLeast(0))
                    player.prepare()
                    if (request.optBoolean("play", true)) { player.play(); started = true }
                }
                "append", "next" -> {
                    val empty = player.mediaItemCount == 0
                    if (action == "next" && !empty) {
                        val index = player.currentMediaItemIndex + 1
                        MediaManager.requestPlayNextFixup(player, index, added.size, player.mediaItemCount + added.size)
                        player.addMediaItems(index, added)
                    } else player.addMediaItems(added)
                    if (empty) { player.prepare(); player.play(); started = true }
                }
                "select" -> {
                    val index = request.getInt("index")
                    require(index in 0 until player.mediaItemCount)
                    player.seekTo(index, 0); player.prepare(); player.play(); started = true
                }
                "remove" -> player.removeMediaItems(request.getInt("from"), request.getInt("to"))
                "move" -> player.moveMediaItem(request.getInt("from"), request.getInt("to"))
                "reorder" -> {
                    val ids = request.getJSONArray("ids")
                    val ordered = (0 until ids.length()).map { ids.getString(it) }
                    ordered.forEachIndexed { destination, id ->
                        val source = (destination until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId == id }
                        if (source != destination) player.moveMediaItem(source, destination)
                    }
                }
                "clear" -> player.clearMediaItems()
                else -> error("Unknown queue edit")
            }
            val songs = (0 until player.mediaItemCount).map { MappingUtil.mapToChild(player.getMediaItemAt(it)) }
            QueueRepository().insertAll(songs, true, 0)
            snapshot(player)
        }
        if (started) main.post { appContext?.let { c -> onRemotePlay?.invoke(c) } }
        return result
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        main.post(task)
        return try { task.get(8, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(false); throw e }
    }
}
