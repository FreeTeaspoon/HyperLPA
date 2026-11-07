package app.hyperlpa.remote

import kotlinx.coroutines.flow.MutableStateFlow

/** Correlates snapshots per phone, independently of the currently open detail page. */
internal class PhoneNotificationRequests {
    val state = MutableStateFlow(PhoneNotificationView())
    val views = MutableStateFlow<Map<String, PhoneNotificationView>>(emptyMap())
    private val requestIds = mutableMapOf<String, String>()
    private var selected = ""

    private fun put(deviceId: String, view: PhoneNotificationView) {
        views.value = views.value + (deviceId to view)
        if (selected == deviceId) state.value = view
    }

    @Synchronized
    fun seed(deviceId: String, history: PhoneInboxDevice) {
        if (deviceId !in views.value) put(deviceId, PhoneNotificationView(deviceId,
            history.entries, history.available, loaded = history.loaded))
    }

    @Synchronized
    fun select(deviceId: String) {
        selected = deviceId
        state.value = views.value[deviceId] ?: PhoneNotificationView(deviceId)
    }

    @Synchronized
    fun begin(deviceId: String, clear: Boolean, userInitiated: Boolean, select: Boolean = true): String? {
        if (select) this.select(deviceId)
        val current = views.value[deviceId] ?: PhoneNotificationView(deviceId)
        if (current.loading) {
            // A pull can join a background fetch without starting a second request.
            if (userInitiated && !clear && !current.clearing) put(deviceId, current.copy(refreshing = true))
            return null
        }
        return newDeviceId().also {
            requestIds[deviceId] = it
            put(deviceId, current.copy(loading = true, refreshing = userInitiated, clearing = clear))
        }
    }

    @Synchronized
    fun complete(deviceId: String, token: String, entries: List<PhoneNotificationEntry>, available: Boolean,
        unavailableMessage: String): Boolean {
        val current = views.value[deviceId] ?: return false
        if (token != requestIds[deviceId] && !(token.isBlank() && !available)) return false
        requestIds.remove(deviceId)
        put(deviceId, PhoneNotificationView(deviceId, if (available) entries.distinctBy { it.id } else emptyList(),
            available, loaded = true, error = unavailableMessage.takeIf { current.clearing && !available }))
        return true
    }

    @Synchronized
    fun fail(token: String, error: String) {
        val deviceId = requestIds.entries.firstOrNull { it.value == token }?.key ?: return
        requestIds.remove(deviceId)
        val current = views.value.getValue(deviceId)
        put(deviceId, failed(deviceId, current, error))
    }

    @Synchronized
    fun disconnected(error: String) {
        val pending = requestIds.keys.toSet()
        requestIds.clear()
        views.value.forEach { (deviceId, current) ->
            if (deviceId in pending) put(deviceId, failed(deviceId, current, error))
        }
    }

    private fun failed(deviceId: String, current: PhoneNotificationView, error: String): PhoneNotificationView = current.copy(
        loading = false, refreshing = false, clearing = false,
        error = if (current.refreshing || current.clearing || (selected == deviceId && !current.loaded)) error else current.error,
        refreshError = if (current.refreshing) error else current.refreshError,
    )

    @Synchronized
    fun close(deviceId: String): Boolean {
        if (selected != deviceId) return false
        selected = ""
        state.value = PhoneNotificationView()
        return true
    }

    @Synchronized
    fun remove(deviceId: String) {
        close(deviceId)
        requestIds.remove(deviceId)
        views.value = views.value - deviceId
    }
}
