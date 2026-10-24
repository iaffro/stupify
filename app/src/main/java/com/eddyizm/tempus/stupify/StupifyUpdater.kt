package com.eddyizm.tempus.stupify

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.eddyizm.tempus.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the Stupify hub for a newer APK and installs it (replaces Tempus' GitHub release check). */
object StupifyUpdater {
    private const val PREFS = "stupify_updater"
    private const val SNOOZE_MS = 24L * 60 * 60 * 1000

    data class Release(val versionCode: Int, val versionName: String, val changelog: String, val url: String)

    @JvmStatic
    fun isStupifyBuild(): Boolean = BuildConfig.FLAVOR == "stupify"

    @JvmStatic
    @JvmOverloads
    fun check(activity: FragmentActivity, manual: Boolean = false) {
        if (!isStupifyBuild()) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!manual && System.currentTimeMillis() < prefs.getLong("snoozed_until", 0)) return
        activity.lifecycleScope.launch {
            val rel = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            if (rel == null || rel.versionCode <= BuildConfig.VERSION_CODE) {
                if (manual) Toast.makeText(activity, "Stupify is up to date", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (activity.isFinishing) return@launch
            MaterialAlertDialogBuilder(activity)
                .setTitle("Stupify ${rel.versionName} is available")
                .setMessage(rel.changelog.ifBlank { "A new version of Stupify is ready." })
                .setPositiveButton("Update") { _, _ -> download(activity, rel) }
                .setNegativeButton("Later") { _, _ ->
                    prefs.edit().putLong("snoozed_until", System.currentTimeMillis() + SNOOZE_MS).apply()
                }
                .show()
        }
    }

    private fun fetch(): Release {
        val conn = URL(BuildConfig.STUPIFY_SERVER + "/app/v1/version").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            return Release(o.getInt("versionCode"), o.getString("versionName"),
                o.optString("changelog"), BuildConfig.STUPIFY_SERVER + o.getString("url"))
        } finally {
            conn.disconnect()
        }
    }

    private fun download(activity: FragmentActivity, rel: Release) {
        val ctx = activity.applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val name = "Stupify-${rel.versionName}.apk"
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { it.delete() }
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(rel.url))
            .setTitle("Stupify ${rel.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name))
        Toast.makeText(activity, "Downloading Stupify ${rel.versionName}…", Toast.LENGTH_SHORT).show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                ctx.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(ctx, "Stupify update download failed", Toast.LENGTH_LONG).show()
                    return
                }
                ctx.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }
}
