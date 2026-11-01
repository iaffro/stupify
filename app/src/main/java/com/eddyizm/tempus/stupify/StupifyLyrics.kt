package com.eddyizm.tempus.stupify

import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Time-synced lyrics from the hub (`/app/v1/lyrics`: LRCLIB, cached on the server). */
object StupifyLyrics {
    class Line(val startMs: Long, val text: String)

    /** Blocking. Empty list when the hub has no synced lyrics for the song (or is unreachable). */
    @JvmStatic
    fun fetch(artist: String, title: String, album: String, durationSec: Int): List<Line> = try {
        val params = App.getSubsonicClientInstance(false).params.toMutableMap()
        params["artist"] = artist; params["title"] = title; params["album"] = album
        params["duration"] = durationSec.toString()
        val query = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/lyrics?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        try {
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("synced")
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Line(o.getLong("start"), o.getString("value")) } }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
