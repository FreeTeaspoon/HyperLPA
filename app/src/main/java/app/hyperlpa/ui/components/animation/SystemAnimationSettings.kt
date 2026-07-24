package app.hyperlpa.ui.components.animation

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Tracks Android's animator-duration setting for custom frame-driven decorative effects. */
@Composable
internal fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }.isSuccess
        onDispose {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return enabled
}
