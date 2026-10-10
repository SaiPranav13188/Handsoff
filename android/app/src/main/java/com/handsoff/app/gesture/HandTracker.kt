package com.handsoff.app.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable

/**
 * Runs MediaPipe's on-device hand model on front-camera frames and turns the
 * landmarks into grab / release events. Results are delivered on the main thread.
 */
class HandTracker(context: Context, private val onFrame: (GestureFrame) -> Unit) : ImageAnalysis.Analyzer, Closeable {
    private val tracker = GestureTracker()
    private val main = Handler(Looper.getMainLooper())
    private var lastTimestamp = 0L
    @Volatile private var closed = false

    // Diagnostics, logged about once a second (adb logcat -s HandTracker).
    @Volatile private var frames = 0
    @Volatile private var results = 0
    @Volatile private var hands = 0
    @Volatile private var lastError: String? = null
    @Volatile private var lastInfo = ""
    private var lastLog = 0L

    private val landmarker: HandLandmarker = HandLandmarker.createFromOptions(
        context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, image ->
                results++
                if (result.landmarks().isNotEmpty()) hands++
                val hand = result.landmarks().firstOrNull()?.map { Point(it.x(), it.y()) }
                val frame = tracker.update(hand, image.width.toFloat() / image.height)
                if (!closed) main.post { onFrame(frame) }
            }
            .setErrorListener { e -> lastError = e.message } // a dropped frame; the next one carries on
            .build(),
    )

    override fun analyze(image: ImageProxy) {
        if (closed) {
            image.close()
            return
        }
        val frame: Bitmap
        frames++
        try {
            val raw = image.toBitmap()
            lastInfo = "rot=${image.imageInfo.rotationDegrees} in=${raw.width}x${raw.height}"
            // Upright and mirrored, matching what the selfie preview shows.
            val m = Matrix().apply {
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            frame = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } finally {
            image.close()
        }
        // MediaPipe requires strictly increasing timestamps.
        val ts = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
        lastTimestamp = ts
        try {
            landmarker.detectAsync(BitmapImageBuilder(frame).build(), ts)
        } catch (e: Exception) {
            lastError = e.message
        }
        if (ts - lastLog > 1000) {
            lastLog = ts
            Log.d(TAG, "frames=$frames results=$results withHand=$hands $lastInfo out=${frame.width}x${frame.height} err=$lastError")
        }
    }

    companion object {
        private const val TAG = "HandTracker"
    }

    override fun close() {
        closed = true
        landmarker.close()
    }
}
