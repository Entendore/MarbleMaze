package com.example.marblemaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModeCodeTest {
    @Test fun `round trip preserves every field`() {
        val m = RunMode(name = "Spir\u00E1l — no portals \u263A", genMode = Maze.GEN_SPIRAL,
            difficulty = 21, marbles = 7, fixedSeed = Long.MIN_VALUE + 5,
            traps = false, portals = false, bumpers = true, coins = false)
        val b = ModeCode.decode(ModeCode.encode(m))!!
        assertEquals(m.name, b.name); assertEquals(m.genMode, b.genMode)
        assertEquals(m.difficulty, b.difficulty); assertEquals(m.marbles, b.marbles)
        assertEquals(m.fixedSeed, b.fixedSeed); assertEquals(m.traps, b.traps)
        assertEquals(m.portals, b.portals); assertEquals(m.bumpers, b.bumpers); assertEquals(m.coins, b.coins) }
    @Test fun `long names truncated not fatal`() {
        val b = ModeCode.decode(ModeCode.encode(RunMode(name = "N".repeat(200))))
        assertNotNull(b); assertTrue(b!!.name.length <= 60) }
    @Test fun `garbage fails safe`() {
        assertNull(ModeCode.decode("MM8-not-base64!!")); assertNull(ModeCode.decode("")) }
    private fun assertTrue(b: Boolean) = org.junit.Assert.assertTrue(b)
}
