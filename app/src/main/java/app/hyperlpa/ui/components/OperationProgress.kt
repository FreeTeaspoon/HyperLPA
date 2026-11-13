package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hyperlpa.ui.theme.LocalDarkTheme
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
        modifier = Modifier.clip(RoundedCornerShape(36.dp)),
        cornerRadius = 36.dp,
        backgroundColor = if (LocalDarkTheme.current) MiuixTheme.colorScheme.background else Color.White,
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
        OperationLoadingRow(retained.orEmpty())
    }
}

/** Xiaomi Account reference: 24 dp wheel, 8 dp gap and regular 16 sp dark-gray text. */
@Composable
internal fun OperationLoadingRow(message: String) {
    val foreground = if (LocalDarkTheme.current) MiuixTheme.colorScheme.onSurface else Color(0xFF333333)
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InfiniteProgressIndicator(size = 24.dp, color = foreground)
        Text(message, style = MiuixTheme.textStyles.body1, fontSize = 16.sp,
            fontWeight = FontWeight.Normal, color = foreground, modifier = Modifier.weight(1f))
    }
}
