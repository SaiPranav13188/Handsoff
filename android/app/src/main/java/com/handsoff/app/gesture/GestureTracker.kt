package com.handsoff.app.gesture

import kotlin.math.hypot

// Grab / release detection from MediaPipe hand landmarks (ported from the web app).
//
// "Curl score": the average distance from each fingertip to the palm centre,
// normalised by hand size (wrist → middle-finger MCP). With real hand
// proportions an open hand gives ~0.9–1.2 and a fist ~0.3–0.5. The raw ratio is
// smoothed with an EMA, classified with hysteresis, and a state is only
// accepted after it holds for a minimum streak of consecutive frames.

data class Point(val x: Float, val y: Float)

enum class HandState { NONE, OPEN, CLOSED }

enum class GestureEvent { GRAB, RELEASE }

data class GestureFrame(
    val state: HandState,
    val event: GestureEvent?,
    /** Smoothed openness ratio (higher = more open). NaN when no hand. */
    val openness: Float,
    /** Palm centre in normalised image coordinates, or null when no hand. */
    val palm: Point?,
    /** Extended fingers (0–5), or -1 when no hand. */
    val fingers: Int = -1,
    /** Wrist → middle-knuckle distance in normalised units (grows as the hand nears the camera). */
    val handSize: Float = 0f,
    /** 👍 / ✌️ / pinch, if the hand is making one. */
    val sign: HandSign? = null,
)

class GestureTracker(
    private val alpha: Float = 0.35f,
    private val closedBelow: Float = 0.6f,
    private val openAbove: Float = 0.82f,
    private val minStreak: Int = 4,
    private val lostAfter: Int = 8,
) {
    private var ema = Float.NaN
    private var candidate = HandState.NONE
    private var streak = 0
    private var missing = 0

    var current = HandState.NONE
        private set

    fun reset() {
        ema = Float.NaN
        current = HandState.NONE
        candidate = HandState.NONE
        streak = 0
        missing = 0
    }

    /** Feed one frame's 21 landmarks (or null when no hand was detected). */
    fun update(landmarks: List<Point>?, aspect: Float = 1f): GestureFrame {
        if (landmarks == null || landmarks.size < 21) {
            missing++
            if (missing >= lostAfter && current != HandState.NONE) {
                current = HandState.NONE
                candidate = HandState.NONE
                streak = 0
                ema = Float.NaN
            }
            return GestureFrame(current, null, Float.NaN, null)
        }
        missing = 0

        val raw = opennessRatio(landmarks, aspect)
        if (!raw.isNaN()) ema = if (ema.isNaN()) raw else alpha * raw + (1 - alpha) * ema

        // Hysteresis: between the two thresholds the previous reading wins.
        var reading = if (candidate == HandState.NONE) current else candidate
        reading = when {
            ema < closedBelow -> HandState.CLOSED
            ema > openAbove -> HandState.OPEN
            reading == HandState.NONE -> if (ema >= (closedBelow + openAbove) / 2) HandState.OPEN else HandState.CLOSED
            else -> reading
        }

        if (reading == candidate) streak++ else {
            candidate = reading
            streak = 1
        }

        var event: GestureEvent? = null
        if (candidate != current && streak >= minStreak) {
            val prev = current
            current = candidate
            if (prev == HandState.OPEN && current == HandState.CLOSED) event = GestureEvent.GRAB
            else if (prev == HandState.CLOSED && current == HandState.OPEN) event = GestureEvent.RELEASE
        }
        return GestureFrame(
            current, event, ema, palmCenter(landmarks),
            countFingers(landmarks, aspect), handSize(landmarks), Signs.detect(landmarks, aspect),
        )
    }

    companion object {
        private val PALM = intArrayOf(0, 5, 9, 13, 17)
        private val TIPS = intArrayOf(8, 12, 16, 20) // thumb left out: it barely moves in a fist

        fun opennessRatio(lm: List<Point>, aspect: Float = 1f): Float {
            fun px(p: Point) = Point(p.x * aspect, p.y)
            var cx = 0f
            var cy = 0f
            for (i in PALM) {
                val p = px(lm[i]); cx += p.x / PALM.size; cy += p.y / PALM.size
            }
            val wrist = px(lm[0])
            val mcp = px(lm[9])
            val size = hypot(wrist.x - mcp.x, wrist.y - mcp.y)
            if (size < 1e-6f) return Float.NaN
            var reach = 0f
            for (i in TIPS) {
                val t = px(lm[i]); reach += hypot(t.x - cx, t.y - cy) / TIPS.size
            }
            return reach / size
        }

        /**
         * Counts extended fingers. A finger is extended when its tip is clearly farther
         * from the wrist than its middle joint; the thumb when its tip is well away from
         * the index knuckle (so a thumb tucked across the palm doesn't count).
         */
        fun countFingers(lm: List<Point>, aspect: Float = 1f): Int {
            fun px(i: Int) = Point(lm[i].x * aspect, lm[i].y)
            fun dist(a: Int, b: Int): Float {
                val p = px(a); val q = px(b); return hypot(p.x - q.x, p.y - q.y)
            }
            val size = dist(0, 9)
            if (size < 1e-6f) return 0
            var n = 0
            for ((tip, pip) in FINGER_JOINTS) if (dist(tip, 0) > dist(pip, 0) * 1.12f) n++
            if (dist(4, 5) > 0.5f * size && dist(4, 0) > dist(3, 0)) n++
            return n
        }

        fun handSize(lm: List<Point>): Float = hypot(lm[0].x - lm[9].x, lm[0].y - lm[9].y)

        private val FINGER_JOINTS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)

        fun palmCenter(lm: List<Point>): Point {
            var x = 0f
            var y = 0f
            for (i in PALM) {
                x += lm[i].x / PALM.size; y += lm[i].y / PALM.size
            }
            return Point(x, y)
        }
    }
}
