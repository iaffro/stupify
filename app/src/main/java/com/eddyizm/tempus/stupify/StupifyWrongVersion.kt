package com.eddyizm.tempus.stupify

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Child
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Wrong version": the song plays another recording; the server finds a different copy and swaps it in. */
object StupifyWrongVersion {
    @JvmStatic
    fun ask(activity: FragmentActivity, song: Child) {
        val note = EditText(activity).apply {
            hint = activity.getString(R.string.stupify_wrong_version_note)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(pad, 0, pad, 0); addView(note) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.stupify_wrong_version_title)
            .setMessage(activity.getString(R.string.stupify_wrong_version_explain, song.title, song.artist))
            .setView(box)
            .setPositiveButton(R.string.stupify_wrong_version_send) { _, _ -> send(activity, song.id, note.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun send(activity: FragmentActivity, songId: String, note: String) {
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { post(songId, note) }
            Toast.makeText(app, error ?: app.getString(R.string.stupify_wrong_version_sent), Toast.LENGTH_LONG).show()
        }
    }

    /** null when the server took the report, else the message to show. */
    private fun post(songId: String, note: String): String? {
        val query = App.getSubsonicClientInstance(false).params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("${BuildConfig.STUPIFY_SERVER}/app/v1/wrong-version?$query").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("songId", songId).put("note", note).toString().toByteArray()) }
            if (conn.responseCode == 200) return null
            conn.errorStream?.bufferedReader()?.readText()
                ?.let { runCatching { JSONObject(it).optString("detail") }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: "Couldn't send the report (${conn.responseCode})"
        } catch (e: IOException) {
            "Can't reach Stupify. Check your connection."
        } finally {
            conn.disconnect()
        }
    }
}
