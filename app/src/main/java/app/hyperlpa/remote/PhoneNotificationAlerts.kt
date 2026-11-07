package app.hyperlpa.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hyperlpa.MainActivity
import app.hyperlpa.R

internal class PhoneNotificationAlerts(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @SuppressLint("MissingPermission")
    fun show(deviceId: String, deviceName: String, arrivals: List<PhoneNotificationEntry>) {
        if (!hasRemoteNotificationPermission(context)) return
        manager.createNotificationChannel(NotificationChannel(ChannelId,
            context.getString(R.string.phone_inbox_alerts_title), NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        // A snapshot may contain several arrivals after reconnect. Only its newest entry makes a sound.
        arrivals.sortedBy { it.timestamp }.forEachIndexed { index, entry ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = OpenInboxAction
                data = Uri.Builder().scheme("hyperlpa-inbox").authority("notification")
                    .appendPath(deviceId).appendPath(entry.id).build()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val contentIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, ChannelId)
                .setSmallIcon(R.drawable.ic_notification_white)
                .setContentTitle(entry.title.ifBlank { entry.appLabel.ifBlank { entry.packageName } })
                .setContentText(entry.text)
                .setSubText(context.getString(R.string.phone_inbox_source,
                    entry.appLabel.ifBlank { entry.packageName }, deviceName))
                .setStyle(NotificationCompat.BigTextStyle().bigText(entry.text))
                .setWhen(entry.timestamp)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setGroup(group(deviceId))
                .setAutoCancel(true)
                .setSilent(index != arrivals.lastIndex)
                .setContentIntent(contentIntent)
                .build()
            manager.notify(tag(deviceId, entry.id), 0, notification)
        }
        val newest = arrivals.maxBy { it.timestamp }
        val summaryIntent = Intent(context, MainActivity::class.java).apply {
            action = OpenInboxAction
            data = Uri.Builder().scheme("hyperlpa-inbox").authority("notification")
                .appendPath(deviceId).appendPath(newest.id).build()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        manager.notify(group(deviceId), 1, NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_notification_white)
            .setContentTitle(deviceName)
            .setContentText(context.getString(R.string.phone_inbox_alerts_summary))
            .setGroup(group(deviceId)).setGroupSummary(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(context, 0, summaryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }

    fun reconcile(inbox: Map<String, PhoneInboxDevice>) {
        val newTags = inbox.flatMap { (id, history) ->
            history.entries.filter(history::isNew).map { tag(id, it.id) }
        }.toSet()
        val groups = inbox.filterValues { history -> history.entries.any(history::isNew) }.keys.map(::group).toSet()
        manager.activeNotifications.filter { notification ->
            val tag = notification.tag.orEmpty()
            tag.startsWith(Prefix) && tag !in newTags && tag !in groups
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun clear() {
        manager.activeNotifications.filter { it.tag.orEmpty().startsWith(Prefix) }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun group(deviceId: String) = "$Prefix$deviceId"
    private fun tag(deviceId: String, entryId: String) = "${group(deviceId)}/$entryId"

    companion object {
        const val OpenInboxAction = "app.hyperlpa.OPEN_PHONE_INBOX"
        const val ChannelId = "remote-phone-notifications"
        private const val Prefix = "remote-phone/"
    }
}

internal fun hasRemoteNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(PhoneNotificationAlerts.ChannelId)
            ?.importance != NotificationManager.IMPORTANCE_NONE
