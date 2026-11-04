package app.hyperlpa.remote

import kotlinx.coroutines.flow.MutableStateFlow

/** Correlates authenticated snapshots with the request that owns the visible result. */
internal class PhoneNotificationRequests {
    val state = MutableStateFlow(PhoneNotificationView())
    private var requestId: String? = null

    @Synchronized
    fun begin(deviceId: String, clear: Boolean, userInitiated: Boolean): String? {
        val current = state.value
        if (current.clearing || (current.loading && current.deviceId == deviceId)) return null
        val previous = current.takeIf { it.deviceId == deviceId } ?: PhoneNotificationView(deviceId = deviceId)
        return newDeviceId().also {
            requestId = it
            state.value = previous.copy(loading = true, refreshing = userInitiated, clearing = clear, error = null)
        }
    }

    @Synchronized
    fun complete(deviceId: String, token: String, entries: List<PhoneNotificationEntry>, available: Boolean,
        unavailableMessage: String): Boolean {
        val current = state.value
        if (current.deviceId != deviceId ||
            (token != requestId && !(token.isBlank() && !available))) return false
        requestId = null
        state.value = PhoneNotificationView(deviceId, if (available) entries.distinctBy { it.id } else emptyList(),
            available, loaded = true, error = unavailableMessage.takeIf { current.clearing && !available })
        return true
    }

    @Synchronized
    fun fail(token: String, error: String) {
        if (token != requestId) return
        requestId = null
        state.value = state.value.copy(loading = false, refreshing = false, clearing = false, error = error)
    }

    @Synchronized
    fun disconnected(error: String) {
        requestId = null
        state.value = state.value.copy(loading = false, refreshing = false, clearing = false,
            error = error.takeIf { state.value.deviceId.isNotBlank() })
    }

    @Synchronized
    fun close(deviceId: String): Boolean {
        if (state.value.deviceId != deviceId) return false
        requestId = null
        state.value = PhoneNotificationView()
        return true
    }
}
