package com.handsoff.app

import com.handsoff.app.gesture.BumpDetector
import com.handsoff.app.gesture.CountHold
import com.handsoff.app.gesture.GestureTracker
import com.handsoff.app.gesture.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerAndBumpTest {
    /**
     * Upright hand with full joint positions. [up] says which of index, middle, ring,
     * pinky are extended; [thumbOut] whether the thumb sticks out sideways.
     */
    private fun hand(up: List<Boolean>, thumbOut: Boolean): List<Point> {
        val lm = MutableList(21) { Point(0.5f, 0.7f) }
        lm[0] = Point(0.5f, 0.8f) // wrist
        val xs = floatArrayOf(0.44f, 0.48f, 0.52f, 0.56f)
        val joints = listOf(intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
        joints.forEachIndexed { k, (mcp, pip, dip, tip) ->
            val x = xs[k]
            lm[mcp] = Point(x, 0.6f)
            if (up[k]) {
                lm[pip] = Point(x, 0.52f); lm[dip] = Point(x, 0.47f); lm[tip] = Point(x, 0.43f)
            } else {
                // Curled: the tip folds back down toward the palm.
                lm[pip] = Point(x, 0.54f); lm[dip] = Point(x, 0.58f); lm[tip] = Point(x, 0.63f)
            }
        }
        lm[1] = Point(0.46f, 0.76f); lm[2] = Point(0.42f, 0.71f)
        if (thumbOut) {
            lm[3] = Point(0.37f, 0.67f); lm[4] = Point(0.31f, 0.64f)
        } else {
            lm[3] = Point(0.44f, 0.66f); lm[4] = Point(0.47f, 0.62f) // across the palm
        }
        return lm
    }

    private fun count(up: List<Boolean>, thumb: Boolean) = GestureTracker.countFingers(hand(up, thumb))

    @Test
    fun countsCommonHandShapes() {
        assertEquals(0, count(listOf(false, false, false, false), false)) // fist
        assertEquals(1, count(listOf(true, false, false, false), false)) // index
        assertEquals(2, count(listOf(true, true, false, false), false)) // V
        assertEquals(3, count(listOf(true, true, true, false), false)) // 3, thumb holding the pinky
        assertEquals(4, count(listOf(true, true, true, true), false)) // 4, thumb tucked
        assertEquals(5, count(listOf(true, true, true, true), true)) // open hand
    }

    @Test
    fun countHoldFiresOnceAfterAStableHold() {
        val h = CountHold(holdMs = 700)
        var t = 0L
        val fired = mutableListOf<Int>()
        repeat(30) { h.update(3, t)?.let(fired::add); t += 33 } // ~1 s of "3"
        assertEquals(listOf(3), fired)
    }

    @Test
    fun countHoldIgnoresBriefJitterAndPassingCounts() {
        val h = CountHold(holdMs = 700)
        var t = 0L
        // Fingers passing 3→2→1 on the way to a fist must not pick anything.
        for (c in listOf(3, 3, 2, 2, 1, 1, 0, 0, 0, 0, 0, 0)) {
            assertNull(h.update(c, t)); t += 33
        }
        // One odd frame in a steady "2" doesn't restart the hold.
        val fired = mutableListOf<Int>()
        repeat(30) { i -> h.update(if (i == 12) 3 else 2, t)?.let(fired::add); t += 33 }
        assertEquals(listOf(2), fired)
    }

    @Test
    fun bumpNeedsAClosedHandMovingQuicklyTowardTheCamera() {
        val b = BumpDetector()
        // Open hand approaching: not a bump.
        assertFalse(b.update(false, 0.15f, 0)); assertFalse(b.update(false, 0.22f, 200))
        // Fist slowly drifting closer over 2 s: not a bump.
        val slow = BumpDetector()
        var t = 0L
        var size = 0.15f
        repeat(20) { assertFalse(slow.update(true, size, t)); t += 100; size += 0.002f }
        // Fist punched toward the camera: bump, then a cooldown.
        val fast = BumpDetector()
        assertFalse(fast.update(true, 0.15f, 0))
        assertFalse(fast.update(true, 0.17f, 100))
        assertTrue(fast.update(true, 0.21f, 250))
        assertFalse(fast.update(true, 0.15f, 400)); assertFalse(fast.update(true, 0.25f, 600))
    }
}
