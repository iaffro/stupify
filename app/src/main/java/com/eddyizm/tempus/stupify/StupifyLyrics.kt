package com.eddyizm.tempus.stupify

import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.subsonic.models.LyricsList
import com.eddyizm.tempus.subsonic.models.StructuredLyrics
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

    /** The same lines in the shape the phone's lyrics screen already shows (synced, line by line). */
    @JvmStatic
    fun asLyricsList(lines: List<Line>, artist: String?, title: String?): LyricsList = LyricsList().apply {
        structuredLyrics = listOf(StructuredLyrics().apply {
            displayArtist = artist; displayTitle = title; synced = true
            line = lines.map { l -> com.eddyizm.tempus.subsonic.models.Line().also { it.start = l.startMs.toInt(); it.value = l.text } }
        })
    }

    /** Colour for the player's background glow: the most vivid pixel of the cover, softened toward the theme's surface so text keeps its contrast. */
    @JvmStatic
    fun glow(cover: android.graphics.Bitmap, surface: Int): Int {
        var best = surface; var bestScore = -1f
        val hsv = FloatArray(3)
        for (x in 0 until cover.width step 2) for (y in 0 until cover.height step 2) {
            val px = cover.getPixel(x, y)
            android.graphics.Color.colorToHSV(px, hsv)
            val score = hsv[1] * hsv[2] * (if (hsv[2] < 0.2f) 0.2f else 1f)
            if (score > bestScore) { bestScore = score; best = px }
        }
        return androidx.core.graphics.ColorUtils.blendARGB(surface, best or (0xFF shl 24), 0.38f)
    }
}
