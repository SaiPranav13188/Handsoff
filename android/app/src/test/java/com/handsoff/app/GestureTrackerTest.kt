package com.handsoff.app

import com.handsoff.app.gesture.GestureEvent
import com.handsoff.app.gesture.GestureTracker
import com.handsoff.app.gesture.HandState
import com.handsoff.app.gesture.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTrackerTest {
    // Synthetic upright hand with realistic proportions (same as the web tests).
    private fun hand(tipY: Float): List<Point> {
        val lm = MutableList(21) { Point(0.5f, 0.7f) }
        lm[0] = Point(0.5f, 0.8f)
        val xs = floatArrayOf(0.44f, 0.48f, 0.52f, 0.56f)
        intArrayOf(5, 9, 13, 17).forEachIndexed { k, i -> lm[i] = Point(xs[k], 0.6f) }
        intArrayOf(8, 12, 16, 20).forEachIndexed { k, i -> lm[i] = Point(xs[k], tipY) }
        return lm
    }

    private val open = hand(0.43f)
    private val relaxed = hand(0.47f)
    private val fist = hand(0.6f)

    private fun run(t: GestureTracker, frames: List<List<Point>?>) = frames.mapNotNull { t.update(it).event }

    @Test
    fun opennessSeparatesOpenHandFromFist() {
        assertTrue(GestureTracker.opennessRatio(open) > 0.95f)
        assertTrue(GestureTracker.opennessRatio(relaxed) > 0.82f)
        assertTrue(GestureTracker.opennessRatio(fist) < 0.5f)
    }

    @Test
    fun openFistOpenGivesOneGrabThenOneRelease() {
        val events = run(GestureTracker(), List(10) { open } + List(10) { fist } + List(10) { relaxed })
        assertEquals(listOf(GestureEvent.GRAB, GestureEvent.RELEASE), events)
    }

    @Test
    fun shortFlickerIsIgnored() {
        val t = GestureTracker()
        run(t, List(10) { open })
        val events = run(t, listOf(fist, fist, open, open, open, fist, open, open, open))
        assertEquals(emptyList<GestureEvent>(), events)
        assertEquals(HandState.OPEN, t.current)
    }

    @Test
    fun handAppearingAsFistIsNotAGrab() {
        val t = GestureTracker()
        assertEquals(emptyList<GestureEvent>(), run(t, List(10) { fist }))
        assertEquals(HandState.CLOSED, t.current)
    }

    @Test
    fun lostHandDropsToNoneAfterGrace() {
        val t = GestureTracker(lostAfter = 3)
        run(t, List(10) { open })
        run(t, listOf(null, null))
        assertEquals(HandState.OPEN, t.current)
        run(t, listOf(null))
        assertEquals(HandState.NONE, t.current)
    }
}
