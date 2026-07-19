package app.hyperlpa.reminders

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hyperlpa.MainActivity
import app.hyperlpa.R
import app.hyperlpa.data.metadata.ProfileMetadataStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class ProfileReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val iccid = inputData.getString(KeyIccid) ?: return Result.failure()
        val label = inputData.getString(KeyLabel).orEmpty().ifEmpty { iccid }
        if (showProfileReminderNotification(applicationContext, iccid.hashCode(), label)) {
            ProfileMetadataStore(applicationContext).markReminderDelivered(iccid)
        }
        return Result.success()
    }

    companion object {
        const val KeyIccid = "iccid"
        const val KeyLabel = "label"
        const val ChannelId = "profile-reminders"
    }
}

fun hasProfileReminderPermission(context: Context): Boolean {
    val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!runtimePermissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

    val channel = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(ProfileReminderWorker.ChannelId)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

fun showTestProfileReminder(context: Context): Boolean = showProfileReminderNotification(
    context = context,
    notificationId = TestNotificationId,
    label = "Notifications are working",
)

private fun showProfileReminderNotification(
    context: Context,
    notificationId: Int,
    label: String,
): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            ProfileReminderWorker.ChannelId,
            "Profile reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Reminders for eSIM profile dates and events"
        },
    )
    if (!hasProfileReminderPermission(context)) return false

    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, ProfileReminderWorker.ChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("eSIM profile reminder")
        .setContentText(label)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(notificationId, notification)
    return true
}

fun scheduleProfileReminder(
    context: Context,
    iccid: String,
    label: String,
    reminderAt: Instant?,
) {
    val workName = "profile-reminder-$iccid"
    val manager = WorkManager.getInstance(context)
    if (reminderAt == null) {
        manager.cancelUniqueWork(workName)
        return
    }
    val delay = Duration.between(Instant.now(), reminderAt).toMillis().coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<ProfileReminderWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(ProfileReminderWorker.KeyIccid to iccid, ProfileReminderWorker.KeyLabel to label))
        .build()
    manager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
}

private const val TestNotificationId = 0x48595045
