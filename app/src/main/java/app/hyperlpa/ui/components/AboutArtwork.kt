package app.hyperlpa.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.hyperlpa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bounded image, prepared off Main and retained while the route host lives. */
@Composable
fun rememberAboutArtwork(): State<ImageBitmap?> {
    val resources = LocalContext.current.resources
    return produceState<ImageBitmap?>(null, resources) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeResource(resources, R.drawable.about_logo_display).asImageBitmap()
        }
    }
}
