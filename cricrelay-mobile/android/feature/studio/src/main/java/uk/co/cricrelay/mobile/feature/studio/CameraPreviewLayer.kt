package uk.co.cricrelay.mobile.feature.studio

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import uk.co.cricrelay.stream.CameraPreviewHost

/**
 * Embeds the native camera preview inside Compose.
 * Fixes black preview caused by stacking SurfaceView behind an opaque Compose host.
 */
@Composable
fun CameraPreviewLayer(
    modifier: Modifier = Modifier,
    onPreviewSurfaceBound: () -> Unit = {},
) {
    val activity = LocalContext.current as? Activity ?: return

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            CameraPreviewHost.createAndBindPreviewSurface(activity).also {
                onPreviewSurfaceBound()
            }
        },
        onRelease = { view ->
            CameraPreviewHost.unbindPreviewSurface(view, activity)
        },
    )
}
