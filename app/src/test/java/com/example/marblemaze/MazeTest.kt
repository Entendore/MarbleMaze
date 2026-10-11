package com.example.marblemaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MazeTest {
    @Test fun `all generators fully connected across seeds and sizes`() {
        for (gen in Maze.GEN_CLASSIC..Maze.GEN_RIVERS)
            for (seed in 0L until 50L)
                for (size in intArrayOf(4, 13, 25)) {
                    val m = Maze(size, size); m.generate(gen, Random(seed))
                    assertTrue("gen=$gen seed=$seed size=$size disconnected", m.isFullyConnected()) } }
    @Test fun `same seed identical board`() {
        fun snap(gen: Int, seed: Long): String { val m = Maze(16, 16); m.generate(gen, Random(seed))
            return buildString { for (y in 0 until 16) for (x in 0 until 16) { val c = m[x, y]
                append(if (c.top) '1' else '0').append(if (c.right) '1' else '0')
                append(if (c.bottom) '1' else '0').append(if (c.left) '1' else '0') } } }
        for (gen in Maze.GEN_CLASSIC..Maze.GEN_RIVERS) assertEquals(snap(gen, 42L), snap(gen, 42L)) }
    @Test fun `walls symmetric between neighbours`() {
        val m = Maze(12, 12); m.generate(Maze.GEN_CAVES, Random(7))
        for (y in 0 until 12) for (x in 0 until 12) {
            if (x > 0) assertEquals(m[x, y].left, m[x - 1, y].right)
            if (y > 0) assertEquals(m[x, y].top, m[x, y - 1].bottom) } }
}
