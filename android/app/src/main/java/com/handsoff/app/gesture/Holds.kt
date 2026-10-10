package com.handsoff.app.gesture

/**
 * Turns a noisy per-frame finger count into one deliberate choice: the count must
 * stay the same (ignoring 1–2 odd frames) for [holdMs] before it fires, and fires
 * only once per hold. Counts outside 1–5 never fire.
 */
class CountHold(private val holdMs: Long = 700, private val settleFrames: Int = 3) {
    private var current = -1
    private var since = 0L
    private var fired = false
    private var candidate = -1
    private var streak = 0

    /** Fraction (0–1) of the hold completed for the current count, for a progress hint. */
    var progress = 0f
        private set

    val count get() = current

    fun update(count: Int, nowMs: Long): Int? {
        if (count == candidate) streak++ else {
            candidate = count
            streak = 1
        }
        if (candidate != current && streak >= settleFrames) {
            current = candidate
            since = nowMs
            fired = false
        }
        if (current !in 1..5) {
            progress = 0f
            return null
        }
        progress = ((nowMs - since).toFloat() / holdMs).coerceIn(0f, 1f)
        if (!fired && nowMs - since >= holdMs) {
            fired = true
            return current
        }
        return null
    }

    fun reset() {
        current = -1; candidate = -1; streak = 0; fired = false; progress = 0f
    }
}

/**
 * Detects a fist "bump" toward the camera: the hand is closed and its apparent size
 * jumps by [growth]× within [windowMs] (it moved quickly toward the lens).
 */
class BumpDetector(
    private val windowMs: Long = 600,
    private val growth: Float = 1.3f,
    private val cooldownMs: Long = 1500,
) {
    private val history = ArrayDeque<Pair<Long, Float>>()
    private var lastBump = -cooldownMs

    fun update(closed: Boolean, size: Float, nowMs: Long): Boolean {
        if (size <= 0f) {
            history.clear()
            return false
        }
        while (history.isNotEmpty() && nowMs - history.first().first > windowMs) history.removeFirst()
        history.addLast(nowMs to size)
        if (!closed) return false
        val smallest = history.minOf { it.second }
        if (size >= smallest * growth && nowMs - lastBump >= cooldownMs) {
            lastBump = nowMs
            history.clear()
            return true
        }
        return false
    }
}
