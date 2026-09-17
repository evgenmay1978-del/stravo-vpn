package com.stravo.vpn.ui.mobile

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.stravo.vpn.R
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

private val qrScanner by lazy {
    BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
}

/**
 * РЎРєР°РЅРµСЂ QR РЅР° С‚РµР»РµС„РѕРЅРµ: РєР°РјРµСЂР° + ML Kit.
 * Р Р°Р·СЂРµС€РµРЅРёРµ СЃРїСЂР°С€РёРІР°РµС‚СЃСЏ С‚РѕР»СЊРєРѕ Р·РґРµСЃСЊ Рё С‚РѕР»СЊРєРѕ РЅР° РІСЂРµРјСЏ СЃРєР°РЅРёСЂРѕРІР°РЅРёСЏ.
 */
@Composable
fun QrScannerScreen(
    onCode: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.StringRes subtitleRes: Int = R.string.scan_sub,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var handled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted -> granted = isGranted }

    LaunchedEffect(granted) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.scan_title),
            subtitle = stringResource(id = subtitleRes),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(StravoTokens.CardRadiusMobile))
                .background(palette.medallion)
                .border(1.dp, palette.outline.copy(alpha = 0.3f), RoundedCornerShape(StravoTokens.CardRadiusMobile)),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    update = { view ->
                        val providerFuture = ProcessCameraProvider.getInstance(view.context)
                        providerFuture.addListener(
                            {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(ContextCompat.getMainExecutor(view.context)) { proxy ->
                                    scanQr(proxy) { value ->
                                        if (!handled) {
                                            handled = true
                                            onCode(value)
                                        }
                                    }
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                            },
                            ContextCompat.getMainExecutor(view.context),
                        )
                    },
                )
            } else {
                Text(
                    text = stringResource(id = R.string.scan_permission),
                    style = StravoType.Caption,
                    color = palette.onAccent,
                    modifier = Modifier.padding(StravoTokens.SpaceXl),
                )
            }
        }

        Text(
            text = stringResource(id = R.string.scan_hint),
            style = StravoType.Caption,
            color = palette.textSecondary,
            modifier = Modifier.padding(vertical = StravoTokens.SpaceLg),
        )
    }
}

@OptIn(ExperimentalGetImage::class)
private fun scanQr(proxy: ImageProxy, onValue: (String) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    qrScanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.rawValue?.let(onValue)
        }
        .addOnCompleteListener { proxy.close() }
}
