package app.hyperlpa.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hyperlpa.R
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class ProfileReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val iccid = inputData.getString(KeyIccid) ?: return Result.failure()
        val label = inputData.getString(KeyLabel).orEmpty().ifEmpty { iccid }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Profile reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("eSIM profile reminder")
            .setContentText(label)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(iccid.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KeyIccid = "iccid"
        const val KeyLabel = "label"
        const val ChannelId = "profile-reminders"
    }
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
