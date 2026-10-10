package com.handsoff.app.gesture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** Static hand signs used beyond finger counting. */
enum class HandSign {
    /** Fist with the thumb pointing up: a 👍 reaction. */
    THUMBS_UP,
    /** Index and middle finger up: a ✌️ reaction. */
    PEACE,
    /** Thumb and index tips touching, other fingers up (the "OK" pinch). */
    PINCH,
}

enum class Direction { LEFT, RIGHT, UP, DOWN }

object Signs {
    private val FINGER_JOINTS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)

    fun detect(lm: List<Point>, aspect: Float = 1f): HandSign? {
        if (lm.size < 21) return null
        fun px(i: Int) = Point(lm[i].x * aspect, lm[i].y)
        fun dist(a: Int, b: Int): Float {
            val p = px(a); val q = px(b); return hypot(p.x - q.x, p.y - q.y)
        }
        val size = dist(0, 9)
        if (size < 1e-6f) return null
        val up = FINGER_JOINTS.map { (tip, pip) -> dist(tip, 0) > dist(pip, 0) * 1.12f }
        val thumbOut = dist(4, 5) > 0.5f * size && dist(4, 0) > dist(3, 0)

        if (dist(4, 8) < 0.28f * size && up[1] && up[2] && up[3]) return HandSign.PINCH
        if (up.none { it } && lm[4].y < lm[3].y && lm[4].y < lm[5].y - 0.3f * size) return HandSign.THUMBS_UP
        if (up[0] && up[1] && !up[2] && !up[3] && !thumbOut) return HandSign.PEACE
        return null
    }
}

/** A sign must be held steadily for [holdMs] to count, and counts once per hold. */
class SignHold(private val holdMs: Long = 500, private val settleFrames: Int = 3) {
    private var current: HandSign? = null
    private var candidate: HandSign? = null
    private var streak = 0
    private var since = 0L
    private var fired = false

    fun update(sign: HandSign?, nowMs: Long): HandSign? {
        if (sign == candidate) streak++ else {
            candidate = sign
            streak = 1
        }
        if (candidate != current && streak >= settleFrames) {
            current = candidate
            since = nowMs
            fired = false
        }
        val held = current ?: return null
        if (!fired && nowMs - since >= holdMs) {
            fired = true
            return held
        }
        return null
    }
}

/** Keeps the palm's recent path (normalised, mirrored like the preview). */
private class Path(private val windowMs: Long) {
    val points = ArrayDeque<Triple<Long, Float, Float>>()

    fun add(x: Float, y: Float, nowMs: Long) {
        points.addLast(Triple(nowMs, x, y))
        while (points.isNotEmpty() && nowMs - points.first().first > windowMs) points.removeFirst()
    }

    fun clear() = points.clear()
}

/** A quick horizontal sweep of the hand: -1 = left, +1 = right, 0 = none. */
class SwipeDetector(windowMs: Long = 350, private val minDx: Float = 0.18f, private val cooldownMs: Long = 600) {
    private val path = Path(windowMs)
    private var last = -cooldownMs

    fun update(palm: Point?, nowMs: Long): Int {
        if (palm == null) {
            path.clear()
            return 0
        }
        path.add(palm.x, palm.y, nowMs)
        val first = path.points.first()
        val dx = palm.x - first.second
        val dy = palm.y - first.third
        if (abs(dx) >= minDx && abs(dy) < abs(dx) * 0.6f && nowMs - last >= cooldownMs) {
            last = nowMs
            path.clear()
            return if (dx > 0) 1 else -1
        }
        return 0
    }
}

/** A wave: the hand goes side to side, changing direction at least [reversals] times. */
class WaveDetector(
    windowMs: Long = 1500,
    private val minAmplitude: Float = 0.05f,
    private val reversals: Int = 3,
) {
    private val path = Path(windowMs)

    fun update(palm: Point?, nowMs: Long): Boolean {
        if (palm == null) return false
        path.add(palm.x, palm.y, nowMs)
        var extreme = path.points.first().second
        var dir = 0f
        var turns = 0
        for ((_, x, _) in path.points) {
            when {
                dir == 0f -> if (abs(x - extreme) >= minAmplitude) {
                    dir = sign(x - extreme); extreme = x
                }
                dir > 0 -> if (x > extreme) extreme = x else if (extreme - x >= minAmplitude) {
                    turns++; dir = -1f; extreme = x
                }
                else -> if (x < extreme) extreme = x else if (x - extreme >= minAmplitude) {
                    turns++; dir = 1f; extreme = x
                }
            }
        }
        if (turns >= reversals) {
            path.clear()
            return true
        }
        return false
    }
}

/** Remembers the palm's last movement so a release can be read as a flick in some direction. */
class FlickTracker(windowMs: Long = 400, private val minDistance: Float = 0.12f) {
    private val path = Path(windowMs)

    fun add(palm: Point?, nowMs: Long) {
        if (palm != null) path.add(palm.x, palm.y, nowMs)
    }

    fun direction(): Direction? {
        if (path.points.size < 2) return null
        val a = path.points.first()
        val b = path.points.last()
        val dx = b.second - a.second
        val dy = b.third - a.third
        if (hypot(dx, dy) < minDistance) return null
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) Direction.RIGHT else Direction.LEFT
        } else {
            if (dy < 0) Direction.UP else Direction.DOWN
        }
    }

    fun clear() = path.clear()
}
