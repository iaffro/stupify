package com.eddyizm.tempus.stupify

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stupify Connect: this device's line to the hub. Keeps one WebSocket open (while the app is alive), tells
 * the hub who and what this device is, learns which other devices of the same user are online, and carries
 * small JSON requests between them (a phone telling the TV "play", the TV streaming its state back).
 */
object ConnectHub {
    class Device(val id: String, val name: String, val kind: String, val active: Boolean, val self: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val deviceList = MutableLiveData<List<Device>>(emptyList())
    private val pending = ConcurrentHashMap<Long, (JSONObject) -> Unit>()   // rid -> reply / stream handler
    private var socket: WebSocket? = null
    private var appContext: Context? = null
    @Volatile private var wanted = false
    @Volatile private var open = false
    private var retry = 1000L
    private var rid = 0L
    @Volatile var activeId: String? = null; private set
    /** Bumped on every (re)connection: stream subscriptions made before it are gone. */
    @Volatile var epoch = 0; private set

    /** An incoming request from another device: (fromDeviceId, rid, body). Set by [ConnectReceiver]. */
    @Volatile var onRequest: ((String, Long, JSONObject) -> Unit)? = null
    /** Another of the user's devices started playing: this one should stop. */
    @Volatile var onYield: (() -> Unit)? = null
    /** The device list changed (devices came or went, or the playing device moved). */
    @Volatile var onDevices: (() -> Unit)? = null

    @JvmStatic fun devices(): LiveData<List<Device>> = deviceList
    @JvmStatic fun isOnline(id: String) = deviceList.value.orEmpty().any { it.id == id }
    @JvmStatic fun isConnected() = open

    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("stupify_connect", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    /** What other devices call this one: the name set in the system (the TV's own name for the TV), else the model. */
    fun deviceName(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("stupify_connect", Context.MODE_PRIVATE)
        prefs.getString("name", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val system = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return (system?.takeIf { it.isNotBlank() } ?: Build.MODEL).take(40)
    }

    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    @JvmStatic fun start(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.STUPIFY_SERVER.isEmpty()) return
        wanted = true
        if (socket == null) connect()
    }

    @JvmStatic fun stop() {
        wanted = false
        socket?.close(1000, null); socket = null; open = false
        main.post { deviceList.value = emptyList() }
    }

    @Synchronized private fun connect() {
        val ctx = appContext ?: return
        val client = runCatching { App.getSubsonicClientInstance(false) }.getOrNull()
        val params = client?.params
        if (params.isNullOrEmpty() || params["u"].isNullOrEmpty()) { scheduleRetry(); return }  // not signed in yet
        val query = (params + mapOf("id" to deviceId(ctx), "name" to deviceName(ctx), "kind" to if (isTv(ctx)) "tv" else "phone"))
            .entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val url = BuildConfig.STUPIFY_SERVER.replaceFirst("http", "ws") + "/app/v1/connect?" + query
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open = true; retry = 1000L; epoch++ }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(JSONObject(text))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null; open = false
        main.post { deviceList.value = emptyList(); onDevices?.invoke() }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!wanted) return
        main.postDelayed({ if (wanted && socket == null) connect() }, retry)
        retry = (retry * 2).coerceAtMost(30000L)
    }

    private fun handle(f: JSONObject) {
        when (f.optString("op")) {
            "devices" -> {
                val arr: JSONArray = f.getJSONArray("devices")
                val list = (0 until arr.length()).map { arr.getJSONObject(it).let { o ->
                    Device(o.getString("id"), o.optString("name"), o.optString("kind"), o.optBoolean("active"), o.optBoolean("self")) } }
                activeId = f.optString("active").takeIf { it.isNotEmpty() && it != "null" }
                main.post { deviceList.value = list; onDevices?.invoke() }
            }
            "msg" -> onRequest?.invoke(f.getString("from"), f.getLong("rid"), f.optJSONObject("body") ?: JSONObject())
            "reply" -> pending[f.getLong("rid")]?.let { cb ->
                if (!f.optBoolean("stream")) pending.remove(f.getLong("rid"))
                cb(f.optJSONObject("body") ?: JSONObject())
            }
            "yield" -> main.post { onYield?.invoke() }
        }
    }

    private fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) == true

    /** Ask another device something. [reply] runs on a background thread, once (or for every frame, if the device streams). */
    fun request(to: String, body: JSONObject, timeoutMs: Long = 20000, reply: (JSONObject) -> Unit): Long {
        val id = synchronized(this) { ++rid }
        pending[id] = reply
        if (!send(JSONObject().put("op", "msg").put("to", to).put("rid", id).put("body", body))) {
            pending.remove(id); reply(JSONObject().put("error", "offline")); return id
        }
        if (timeoutMs > 0) main.postDelayed({ pending.remove(id)?.invoke(JSONObject().put("error", "timeout")) }, timeoutMs)
        return id
    }

    /** Stops listening to a stream started with [request]. */
    fun forget(id: Long) { pending.remove(id) }

    /** Answer a request from [to]; [stream] keeps the request open for more frames. */
    fun reply(to: String, rid: Long, body: JSONObject, stream: Boolean = false) {
        val f = JSONObject().put("op", "reply").put("to", to).put("rid", rid).put("body", body)
        if (stream) f.put("stream", true)
        send(f)
    }

    /** This device started playing: it becomes the playing device and the previous one is told to stop. */
    @JvmStatic fun claim() { send(JSONObject().put("op", "claim")) }
    @JvmStatic fun idle() { send(JSONObject().put("op", "idle")) }
    fun rename(context: Context, name: String) {
        context.applicationContext.getSharedPreferences("stupify_connect", Context.MODE_PRIVATE).edit().putString("name", name.take(40)).apply()
        send(JSONObject().put("op", "name").put("name", name.take(40)))
    }
}
