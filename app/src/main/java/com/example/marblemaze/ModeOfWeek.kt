package com.example.marblemaze

import java.util.Calendar
import kotlin.random.Random

/** Deterministic weekly featured mode — same week, identical mode for everyone. */
object ModeOfWeek {
    fun weekKey(cal: Calendar = Calendar.getInstance()): Long =
        cal.get(Calendar.YEAR) * 100L + cal.get(Calendar.WEEK_OF_YEAR)
    fun idFor(weekKey: Long) = "motw_$weekKey"
    fun build(weekKey: Long): RunMode {
        val h = (weekKey * 2654435761L) xor (weekKey ushr 17)
        val rng = Random(h)
        return RunMode(
            id = idFor(weekKey), name = "Week $weekKey special",
            genMode = rng.nextInt(Maze.GEN_RIVERS + 1),
            difficulty = intArrayOf(12, 15, 18, 21)[rng.nextInt(4)],
            marbles = 2 + rng.nextInt(4), fixedSeed = h,
            portals = rng.nextBoolean(), winds = rng.nextBoolean(), movers = rng.nextBoolean())
    }
}
