package app.hyperlpa.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.MainActivity
import app.hyperlpa.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** User-enabled interaction with paired external devices over the network. */
class RemoteAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            ChannelId, getString(R.string.remote_title), NotificationManager.IMPORTANCE_LOW,
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val devices = (application as HyperLpaApplication).remoteDevices
        val open = PendingIntent.getActivity(this, 45, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification_white)
            .setContentTitle(getString(R.string.remote_notification_title))
            .setContentText(getString(R.string.remote_notification_summary))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NotificationId, notification)
        scope.launch {
            if (devices.shouldResume()) devices.startRuntime() else stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as HyperLpaApplication).remoteDevices.stopRuntime()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ChannelId = "remote-devices"
        private const val NotificationId = 7041
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, RemoteAccessService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, RemoteAccessService::class.java)) }
    }
}

class RemoteAccessBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val devices = (context.applicationContext as HyperLpaApplication).remoteDevices
                if (devices.shouldResume()) runCatching { RemoteAccessService.start(context) }
            } finally { result.finish() }
        }
    }
}
