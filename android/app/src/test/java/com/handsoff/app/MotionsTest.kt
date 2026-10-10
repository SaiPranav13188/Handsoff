package com.handsoff.app

import com.handsoff.app.gesture.Direction
import com.handsoff.app.gesture.FlickTracker
import com.handsoff.app.gesture.HandSign
import com.handsoff.app.gesture.Point
import com.handsoff.app.gesture.SignHold
import com.handsoff.app.gesture.Signs
import com.handsoff.app.gesture.SwipeDetector
import com.handsoff.app.gesture.WaveDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionsTest {
    /** Upright hand; [up] = index, middle, ring, pinky extended; thumb pose by name. */
    private fun hand(up: List<Boolean>, thumb: String): List<Point> {
        val lm = MutableList(21) { Point(0.5f, 0.7f) }
        lm[0] = Point(0.5f, 0.8f)
        val xs = floatArrayOf(0.44f, 0.48f, 0.52f, 0.56f)
        val joints = listOf(intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
        joints.forEachIndexed { k, (mcp, pip, dip, tip) ->
            val x = xs[k]
            lm[mcp] = Point(x, 0.6f)
            if (up[k]) {
                lm[pip] = Point(x, 0.52f); lm[dip] = Point(x, 0.47f); lm[tip] = Point(x, 0.43f)
            } else {
                lm[pip] = Point(x, 0.54f); lm[dip] = Point(x, 0.58f); lm[tip] = Point(x, 0.63f)
            }
        }
        lm[1] = Point(0.46f, 0.76f); lm[2] = Point(0.42f, 0.71f)
        when (thumb) {
            "out" -> { lm[3] = Point(0.37f, 0.67f); lm[4] = Point(0.31f, 0.64f) }
            "up" -> { lm[2] = Point(0.42f, 0.66f); lm[3] = Point(0.41f, 0.58f); lm[4] = Point(0.41f, 0.48f) }
            else -> { lm[3] = Point(0.44f, 0.66f); lm[4] = Point(0.47f, 0.62f) } // tucked
        }
        return lm
    }

    @Test
    fun recognisesSigns() {
        val none = listOf(false, false, false, false)
        assertEquals(HandSign.THUMBS_UP, Signs.detect(hand(none, "up")))
        assertEquals(HandSign.PEACE, Signs.detect(hand(listOf(true, true, false, false), "tucked")))
        // OK / pinch: index bent so its tip meets the thumb tip.
        val pinch = hand(listOf(false, true, true, true), "out").toMutableList()
        pinch[4] = Point(0.40f, 0.55f); pinch[8] = Point(0.41f, 0.54f)
        assertEquals(HandSign.PINCH, Signs.detect(pinch))
        // Plain fist and open hand are not signs.
        assertNull(Signs.detect(hand(none, "tucked")))
        assertNull(Signs.detect(hand(listOf(true, true, true, true), "out")))
    }

    @Test
    fun signHoldFiresOncePerHold() {
        val h = SignHold(holdMs = 500)
        var t = 0L
        val fired = mutableListOf<HandSign>()
        repeat(30) { h.update(HandSign.THUMBS_UP, t)?.let(fired::add); t += 33 }
        assertEquals(listOf(HandSign.THUMBS_UP), fired)
    }

    @Test
    fun swipeNeedsAFastSidewaysMove() {
        val s = SwipeDetector()
        var t = 0L
        // Slow drift: nothing.
        var x = 0.3f
        repeat(20) { assertEquals(0, s.update(Point(x, 0.5f), t)); t += 100; x += 0.01f }
        // Quick move right.
        val fast = SwipeDetector()
        assertEquals(0, fast.update(Point(0.3f, 0.5f), 0))
        assertEquals(0, fast.update(Point(0.4f, 0.5f), 100))
        assertEquals(1, fast.update(Point(0.52f, 0.51f), 200))
        // Quick move left after the cooldown.
        assertEquals(0, fast.update(Point(0.52f, 0.5f), 900))
        assertEquals(-1, fast.update(Point(0.3f, 0.5f), 1100))
    }

    @Test
    fun waveNeedsSeveralDirectionChanges() {
        val w = WaveDetector()
        var t = 0L
        val xs = listOf(0.5f, 0.6f, 0.5f, 0.4f, 0.5f, 0.6f, 0.5f, 0.4f)
        val fired = xs.map { x -> w.update(Point(x, 0.5f), t).also { t += 120 } }
        assertTrue(fired.last() || fired.any { it })
        // One swipe is not a wave.
        val once = WaveDetector()
        t = 0
        assertFalse(listOf(0.3f, 0.45f, 0.6f).map { x -> once.update(Point(x, 0.5f), t).also { t += 100 } }.any { it })
    }

    @Test
    fun flickDirectionFromRecentMovement() {
        val f = FlickTracker()
        f.add(Point(0.5f, 0.5f), 0); f.add(Point(0.58f, 0.5f), 100); f.add(Point(0.7f, 0.48f), 200)
        assertEquals(Direction.RIGHT, f.direction())
        f.clear()
        f.add(Point(0.5f, 0.6f), 0); f.add(Point(0.5f, 0.42f), 150)
        assertEquals(Direction.UP, f.direction())
        f.clear()
        f.add(Point(0.5f, 0.5f), 0); f.add(Point(0.53f, 0.5f), 150) // too small
        assertNull(f.direction())
    }
}
