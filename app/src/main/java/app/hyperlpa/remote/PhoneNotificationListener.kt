package app.hyperlpa.remote

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PhoneNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName == packageName) return
        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        (application as app.hyperlpa.HyperLpaApplication).remoteDevices.recordPhoneNotification(
            notification.packageName, notification.key, title, text, notification.postTime,
        )
    }
}
