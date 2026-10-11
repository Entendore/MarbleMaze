package com.example.marblemaze

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayCodecTest {
    private val diffs = intArrayOf(9, 14, 22, 28)
    @Test fun `round trip`() {
        val f = FloatArray(3 * 40) { it / 10f }
        val p = ReplayCodec.encode(ReplayCodec.Replay(99L, 14, 5, 2, f))
        val b = ReplayCodec.decode(p, diffs, 40)!!
        assertEquals(99L, b.seed); assertEquals(2, b.marbles)
        assertArrayEquals(f, b.floats, 0f) }
    @Test fun `garbage null`() {
        assertNull(ReplayCodec.decode("MMREPLAY2:!!!", diffs, 40)) }
}
