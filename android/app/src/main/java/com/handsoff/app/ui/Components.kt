package com.handsoff.app.ui

import android.net.Uri
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.handsoff.app.gesture.GestureFrame
import com.handsoff.app.gesture.HandTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

val Accent = Color(0xFF5EEAD4)
val Warn = Color(0xFFFBBF24)
val Muted = Color(0xFF8B93A7)
val Panel = Color(0xFF161A24)

@Composable
fun HandsoffTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF062925),
            background = Color(0xFF0B0D12),
            surface = Color(0xFF0B0D12),
            surfaceVariant = Panel,
            onSurface = Color(0xFFEEF1F7),
            onSurfaceVariant = Muted,
        ),
        content = content,
    )
}

/** Thumbnail for a photo/video URI, or a file-type tile for anything else. */
@Composable
fun Thumb(uri: Uri, name: String, mime: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = if (mime.startsWith("image/") || mime.startsWith("video/")) {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri, Size(360, 360), null).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        } else null
    }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A2230)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val ext = Regex("\\.([A-Za-z0-9]{1,5})$").find(name)?.groupValues?.get(1)?.uppercase() ?: "FILE"
            Text(ext, color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

/** Selfie camera preview that also feeds the hand tracker. */
@Composable
fun GestureCamera(onFrame: (GestureFrame) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFrame by rememberUpdatedState(onFrame)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val tracker = try {
            HandTracker(context) { latestOnFrame(it) }
        } catch (e: Exception) {
            null // model failed to load: the tap fallback still works
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            if (tracker != null) analysis.setAnalyzer(executor, tracker)
            try {
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                /* no front camera */
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.execute { tracker?.close() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.clip(RoundedCornerShape(14.dp)))
}

/**
 * Calls [onKnock] when the phone gets a sharp knock, e.g. two phones tapped together
 * (the alternative to a camera fist bump). Ordinary handling stays well below this.
 */
@Composable
fun KnockListener(onKnock: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onKnock)
    DisposableEffect(Unit) {
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java)
        val linear = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var last = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val (x, y, z) = e.values
                var magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
                if (linear == null) magnitude = kotlin.math.abs(magnitude - 9.81f) // remove gravity
                val now = android.os.SystemClock.uptimeMillis()
                if (magnitude > 14f && now - last > 1500) {
                    last = now
                    latest()
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sensors?.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(listener) }
    }
}
