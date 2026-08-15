package app.hyperlpa.reminders

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.hyperlpa.MainActivity
import app.hyperlpa.R
import app.hyperlpa.data.backup.HyperLpaBackupManager
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.metadata.normalizeReminderLabel
import app.hyperlpa.data.settings.AppSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ProfileReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withProfileReminderIsolation {
        // A worker may be created by WorkManager during process startup. Never inspect or mutate
        // the provisional generation while the application is recovering an interrupted restore.
        if (HyperLpaBackupManager.hasInterruptedRestore(applicationContext)) {
            return@withProfileReminderIsolation Result.retry()
        }
        val iccid = inputData.getString(KeyIccid)
            ?: return@withProfileReminderIsolation Result.failure()
        val expectedEpochMillis = inputData
            .getLong(KeyReminderEpochMillis, MissingReminderEpochMillis)
            .takeUnless { it == MissingReminderEpochMillis }
            ?: return@withProfileReminderIsolation Result.success()
        val label = normalizeReminderLabel(inputData.getString(KeyLabel))
            ?: applicationContext.getString(R.string.profile_reminder_profile_fallback)
        val metadataStore = ProfileMetadataStore(applicationContext)
        val settingsStore = AppSettingsStore(applicationContext)
        val currentState = try {
            val storedReminder = metadataStore.reminderDeliveryRecord(iccid)
            ReminderDeliveryState(
                scheduledRemindersEnabled = settingsStore.settings.first().scheduledReminders,
                storedReminderEpochMillis = storedReminder?.epochMillis,
                expectedReminderEpochMillis = expectedEpochMillis,
                storedReminderLabel = storedReminder?.label,
                expectedReminderLabel = label,
                notificationsAvailable = hasProfileReminderPermission(applicationContext),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A transient store failure must not permit an unverified notification. Completing
            // leaves metadata intact so an explicit future reschedule can create fresh work.
            return@withProfileReminderIsolation Result.success()
        }
        if (!shouldDeliverProfileReminder(currentState)) {
            return@withProfileReminderIsolation Result.success()
        }
        val claimToken = id.toString()
        if (!metadataStore.claimReminderDelivery(iccid, expectedEpochMillis, label, claimToken)) {
            return@withProfileReminderIsolation Result.success()
        }

        val delivered = try {
            // Re-check the global setting after claiming so disabling reminders while the worker
            // starts does not intentionally post. Permission is checked again by the notifier.
            settingsStore.settings.first().scheduledReminders &&
                metadataStore.isReminderDeliveryClaimCurrent(
                    iccid,
                    expectedEpochMillis,
                    label,
                    claimToken,
                ) &&
                showProfileReminderNotification(applicationContext, iccid.hashCode(), label)
        } catch (cancelled: CancellationException) {
            metadataStore.releaseReminderDeliveryClaim(iccid, expectedEpochMillis, claimToken)
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        if (delivered) {
            metadataStore.completeReminderDelivery(iccid, expectedEpochMillis, claimToken)
        } else {
            metadataStore.releaseReminderDeliveryClaim(iccid, expectedEpochMillis, claimToken)
        }
        // Permission/channel failures deliberately complete instead of retrying forever. The
        // persisted timestamp remains available for a future explicit reschedule or re-enable.
        Result.success()
    }

    companion object {
        const val KeyIccid = "iccid"
        const val KeyLabel = "label"
        const val KeyReminderEpochMillis = "reminder_epoch_millis"
        const val ChannelId = "profile-reminders"
        const val WorkTag = "profile-reminders"
        private const val MissingReminderEpochMillis = Long.MIN_VALUE
    }
}

internal data class ReminderDeliveryState(
    val scheduledRemindersEnabled: Boolean,
    val storedReminderEpochMillis: Long?,
    val expectedReminderEpochMillis: Long?,
    val storedReminderLabel: String?,
    val expectedReminderLabel: String?,
    val notificationsAvailable: Boolean,
)

internal fun shouldDeliverProfileReminder(state: ReminderDeliveryState): Boolean =
    state.scheduledRemindersEnabled &&
        state.notificationsAvailable &&
        state.expectedReminderEpochMillis != null &&
        state.storedReminderEpochMillis == state.expectedReminderEpochMillis &&
        state.expectedReminderLabel != null &&
        state.storedReminderLabel == state.expectedReminderLabel

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
    label = context.getString(R.string.profile_reminder_test_label),
)

@SuppressLint("MissingPermission")
private fun showProfileReminderNotification(
    context: Context,
    notificationId: Int,
    label: String,
): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            ProfileReminderWorker.ChannelId,
            context.getString(R.string.profile_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.profile_reminder_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
    val publicNotification = NotificationCompat.Builder(context, ProfileReminderWorker.ChannelId)
        .setSmallIcon(R.drawable.ic_notification_white)
        .setColor(Color.WHITE)
        .setContentTitle(context.getString(R.string.profile_reminder_title))
        .setContentText(context.getString(R.string.profile_reminder_private_text))
        .build()
    val notification = NotificationCompat.Builder(context, ProfileReminderWorker.ChannelId)
        .setSmallIcon(R.drawable.ic_notification_white)
        .setColor(Color.WHITE)
        .setContentTitle(context.getString(R.string.profile_reminder_title))
        .setContentText(label)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicNotification)
        .build()
    return try {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        true
    } catch (_: SecurityException) {
        // The runtime permission can be revoked between the check and notify().
        false
    }
}

fun scheduleProfileReminder(
    context: Context,
    iccid: String,
    label: String,
    reminderAt: Instant?,
): Operation {
    val workName = "profile-reminder-$iccid"
    val manager = WorkManager.getInstance(context)
    val dateOnlyReminderAt = reminderAt?.normalizeReminderInstant()
    if (dateOnlyReminderAt == null) {
        return manager.cancelUniqueWork(workName)
    }
    val delay = Duration.between(Instant.now(), dateOnlyReminderAt).toMillis().coerceAtLeast(0L)
    val request = OneTimeWorkRequestBuilder<ProfileReminderWorker>()
        .addTag(ProfileReminderWorker.WorkTag)
        .addTag(reminderWorkIdentityTag(iccid))
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(
            workDataOf(
                ProfileReminderWorker.KeyIccid to iccid,
                ProfileReminderWorker.KeyLabel to label,
                ProfileReminderWorker.KeyReminderEpochMillis to dateOnlyReminderAt.toEpochMilli(),
            ),
        )
        .build()
    return manager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
}

internal fun reminderWorkIdentityTag(iccid: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(iccid.toByteArray(StandardCharsets.UTF_8))
    return buildString(ReminderIdentityTagPrefix.length + ReminderIdentityDigestBytes * 2) {
        append(ReminderIdentityTagPrefix)
        repeat(ReminderIdentityDigestBytes) { index ->
            append(HexDigits[(digest[index].toInt() ushr 4) and 0x0f])
            append(HexDigits[digest[index].toInt() and 0x0f])
        }
    }
}

private const val TestNotificationId = 0x48595045
private const val ReminderIdentityTagPrefix = "profile-reminder-id-"
private const val ReminderIdentityDigestBytes = 16
private const val HexDigits = "0123456789abcdef"
