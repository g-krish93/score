package uk.co.cricrelay.mobile.feature.studio

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.cricrelay.mobile.ui.AppColors
import uk.co.cricrelay.mobile.ui.AppSpacing
import uk.co.cricrelay.mobile.ui.AppTypography
import uk.co.cricrelay.mobile.ui.CameraCircleButton
import uk.co.cricrelay.mobile.ui.ErrorBanner
import uk.co.cricrelay.mobile.ui.LoadingState
import uk.co.cricrelay.mobile.ui.StudioBackdrop

@Composable
fun PairRemoteScreen(
    onBack: () -> Unit,
    viewModel: StudioViewModel,
    modifier: Modifier = Modifier,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var expiresAt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching {
            val (payload, expiry) = viewModel.createPairingCode()
            expiresAt = expiry
            qrBitmap = withContext(Dispatchers.Default) { encodeQrBitmap(payload, 512) }
        }.onFailure { e ->
            error = e.message ?: "Failed to create pairing code"
        }
        loading = false
    }

    StudioBackdrop(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.lg),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraCircleButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = AppSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Pair Remote",
                        style = AppTypography.headlineMedium,
                        color = AppColors.OnBackground,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(
                        "Scan this code on a second phone to control start/stop, mic mute, and focus lock.",
                        style = AppTypography.bodyMedium,
                        color = AppColors.OnBackgroundMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(AppSpacing.lg))
                    when {
                        loading -> LoadingState("Generating code…")
                        error != null -> ErrorBanner(error!!)
                        qrBitmap != null -> {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Remote pairing QR code",
                                modifier = Modifier
                                    .size(260.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            )
                            if (expiresAt.isNotBlank()) {
                                Spacer(Modifier.height(AppSpacing.md))
                                Text(
                                    "Code expires soon — keep this screen open while pairing.",
                                    style = AppTypography.bodySmall,
                                    color = AppColors.OnBackgroundDim,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun encodeQrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}
