package com.eddyizm.tempus.stupify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R

/**
 * Keeps this device reachable for Stupify Connect while the app isn't open (the TV, mostly: it is always plugged in).
 * Holds the hub connection, and when someone plays something on this device from another one, brings Now Playing up.
 */
@UnstableApi
class ConnectService : Service() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("stupify_connect", "Stupify Connect", NotificationManager.IMPORTANCE_MIN))
        val note: Notification = NotificationCompat.Builder(this, "stupify_connect")
            .setSmallIcon(R.drawable.ic_queue).setContentTitle("Ready to play from your other devices")
            .setContentText(ConnectHub.deviceName(this)).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7302, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(7302, note)
        ConnectReceiver.init(this)
        ConnectHub.start(this)
        if (ConnectHub.isTv(this)) ConnectReceiver.onRemotePlay = { ctx ->
            // someone pressed "play on <this TV>": show what is playing
            runCatching {
                ctx.startActivity(Intent().setClassName(ctx, "com.eddyizm.tempus.stupify.tv.TvPlayerActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @JvmStatic fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ConnectService::class.java)) }
        }
    }

    /** Starts the service when the TV boots (or the app is updated), so the TV is on the device list without opening the app. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ConnectHub.isTv(context)) start(context)
        }
    }
}
