package app.hyperlpa.provisioning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hyperlpa.HyperLpaApplication
import app.hyperlpa.MainActivity
import app.hyperlpa.R

class ProvisioningForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStartSingle -> showForeground(
                buildNotification(
                    content = getString(R.string.provisioning_single_progress),
                    completed = null,
                    total = null,
                ),
            )
            ActionStartBatch -> showForeground(
                buildNotification(
                    content = getString(R.string.provisioning_batch_progress),
                    completed = intent.getIntExtra(ExtraCompleted, 0),
                    total = intent.getIntExtra(ExtraTotal, 0),
                ),
            )
            ActionCancel -> {
                val wasActive = (application as? HyperLpaApplication)
                    ?.provisioningCoordinator
                    ?.cancelActiveProvisioning()
                    ?: false
                if (wasActive) {
                    showForeground(
                        buildNotification(
                            content = getString(R.string.provisioning_cancelling),
                            completed = null,
                            total = null,
                        ),
                    )
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            else -> stopSelf(startId)
        }
        // Never ask Android to replay an activation code after process death. A recovered batch is
        // shown as interrupted and requires an explicit Resume action from the user.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        (application as? HyperLpaApplication)
            ?.provisioningCoordinator
            ?.onForegroundServiceDestroyed()
        super.onDestroy()
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as? HyperLpaApplication)
            ?.provisioningCoordinator
            ?.cancelActiveProvisioning()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun showForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    private fun buildNotification(
        content: String,
        completed: Int?,
        total: Int?,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            OpenRequestCode,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            CancelRequestCode,
            Intent(this, ProvisioningForegroundService::class.java).setAction(ActionCancel),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_notification_white)
            .setColor(Color.WHITE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(total ?: 0, completed ?: 0, total == null || total <= 0)
            .addAction(R.drawable.ic_notification_white, getString(R.string.provisioning_cancel), cancelIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.provisioning_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.provisioning_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val ChannelId = "esim_provisioning"
        private const val NotificationId = 41_200
        private const val OpenRequestCode = 41_201
        private const val CancelRequestCode = 41_202
        private const val ActionStartSingle = "app.hyperlpa.provisioning.START_SINGLE"
        private const val ActionStartBatch = "app.hyperlpa.provisioning.START_BATCH"
        private const val ActionCancel = "app.hyperlpa.provisioning.CANCEL"
        private const val ExtraCompleted = "completed"
        private const val ExtraTotal = "total"

        fun startSingle(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProvisioningForegroundService::class.java)
                    .setAction(ActionStartSingle),
            )
        }

        fun startBatch(context: Context, completed: Int, total: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProvisioningForegroundService::class.java)
                    .setAction(ActionStartBatch)
                    .putExtra(ExtraCompleted, completed)
                    .putExtra(ExtraTotal, total),
            )
        }

        fun updateBatch(context: Context, completed: Int, total: Int) {
            // Progress notification updates are advisory. Revoked notification permission or a
            // temporarily unavailable system service must never abort a provisioning session.
            try {
                val serviceContext = context.applicationContext
                val manager = serviceContext.getSystemService(NotificationManager::class.java)
                val openIntent = PendingIntent.getActivity(
                    serviceContext,
                    OpenRequestCode,
                    Intent(serviceContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val cancelIntent = PendingIntent.getService(
                    serviceContext,
                    CancelRequestCode,
                    Intent(serviceContext, ProvisioningForegroundService::class.java).setAction(ActionCancel),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.notify(
                    NotificationId,
                    NotificationCompat.Builder(serviceContext, ChannelId)
                        .setSmallIcon(R.drawable.ic_notification_white)
                        .setColor(Color.WHITE)
                        .setContentTitle(serviceContext.getString(R.string.app_name))
                        .setContentText(serviceContext.getString(R.string.provisioning_batch_progress))
                        .setContentIntent(openIntent)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setProgress(total, completed, total <= 0)
                        .addAction(
                            R.drawable.ic_notification_white,
                            serviceContext.getString(R.string.provisioning_cancel),
                            cancelIntent,
                        )
                        .build(),
                )
            } catch (_: RuntimeException) {
                // The service remains foreground from its initial startForeground() call.
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, ProvisioningForegroundService::class.java),
            )
        }
    }
}
