package app.hyperlpa.ui

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.basic.SnackbarDuration

val LocalMiuixSnackbar = staticCompositionLocalOf<(String, SnackbarDuration) -> Unit> {
    { _, _ -> }
}
