package com.example.marblemaze

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsTest {
    private val rect = RectF(100f, 100f, 200f, 140f)
    @Test fun `miss returns null`() = assertNull(Physics.circleVsRect(300f, 300f, 0f, 0f, 10f, rect, 0.5f))
    @Test fun `hit from right reflects`() {
        val h = Physics.circleVsRect(206f, 120f, -50f, 0f, 10f, rect, 0.5f)!!
        assertEquals(1f, h.nx, 1e-4f); assertEquals(50f, h.impact, 1e-3f)
        val (vx, _) = Physics.reflect(-50f, 0f, h.nx, h.ny, -h.impact, 0.5f)
        assertEquals(25f, vx, 1e-3f) }
    @Test fun `momentum conserved at e one`() {
        val r = Physics.resolveCircles(0f, 0f, 10f, 0f, 20f, 0f, -10f, 0f, 12f, 1f)!!
        assertEquals(0f, r.avx + r.bvx, 1e-4f) }
    @Test fun `resolve non touching null`() =
        assertNull(Physics.resolveCircles(0f, 0f, 0f, 0f, 100f, 0f, 0f, 0f, 12f, 0.5f))
    @Test fun `centre inside escapes shallow axis`() {
        assertNotNull(Physics.circleVsRect(101f, 120f, 0f, 0f, 10f, rect, 0.5f)) }
}
