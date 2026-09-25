package app.hyperlpa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A failure shown in page content, with the same layout as [TipCard]. */
@Composable
fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    TipCard(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.error,
        )
    }
}
