package app.hyperlpa.ui

import app.hyperlpa.remote.RemoteDevicesUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Retain the resolved order across page openings; never publish an unsorted placeholder list. */
internal fun phoneNotificationApps(
    states: Flow<RemoteDevicesUiState>,
    scope: CoroutineScope,
    defaultMessagesApp: () -> String?,
    label: suspend (String) -> String,
): StateFlow<List<Pair<String, String>>?> = states
    .filter { it.loaded }
    .map { it.notificationApps }
    .distinctUntilChanged()
    .map { packages ->
        val messagesApp = defaultMessagesApp()
        packages.map { it to label(it) }
            .sortedWith(compareBy({ it.first != messagesApp }, { it.second.lowercase() }, { it.first }))
    }
    .flowOn(Dispatchers.IO)
    .stateIn(scope, SharingStarted.Eagerly, null)
