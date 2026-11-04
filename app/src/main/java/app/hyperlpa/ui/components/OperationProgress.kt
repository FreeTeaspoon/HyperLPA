package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Retains display data and input ownership through the native overlay's exit. */
@Composable
fun CompactProgressDialog(message: String?, onLockChange: (Boolean) -> Unit = {}) {
    val lockChange by rememberUpdatedState(onLockChange)
    DisposableEffect(Unit) { onDispose { lockChange(false) } }
    var retained by remember { mutableStateOf(message) }
    if (message != null) retained = message
    SideEffect { if (message != null) lockChange(true) }
    OverlayDialog(
        show = message != null,
        cornerRadius = 36.dp,
        onDismissRequest = null,
        onDismissFinished = {
            if (message == null) { retained = null; lockChange(false) }
        },
    ) {
        NavigationBackHandler(
            state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = {},
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            InfiniteProgressIndicator(size = 24.dp, color = MiuixTheme.colorScheme.onSurface)
            Text(retained.orEmpty(), style = MiuixTheme.textStyles.body1)
        }
    }
}
