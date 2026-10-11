package com.example.marblemaze

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils

data class RunOptions(
    val genMode: Int = Maze.GEN_CLASSIC,
    val traps: Boolean = true, val pads: Boolean = true, val belts: Boolean = true,
    val winds: Boolean = true, val movers: Boolean = true, val flappers: Boolean = true,
    val spinners: Boolean = true, val portals: Boolean = true, val gates: Boolean = true,
    val crumble: Boolean = true, val terrain: Boolean = true, val coins: Boolean = true,
    val powerUps: Boolean = true, val bumpers: Boolean = true
) {
    fun toJson() = org.json.JSONObject()
        .put("g", genMode).put("tr", traps).put("pa", pads).put("be", belts).put("wi", winds)
        .put("mo", movers).put("fl", flappers).put("sp", spinners).put("po", portals)
        .put("ga", gates).put("cr", crumble).put("te", terrain).put("co", coins)
        .put("pw", powerUps).put("bu", bumpers)
    companion object {
        fun fromJson(o: org.json.JSONObject) = RunOptions(
            genMode = o.optInt("g", Maze.GEN_CLASSIC),
            traps = o.optBoolean("tr", true), pads = o.optBoolean("pa", true),
            belts = o.optBoolean("be", true), winds = o.optBoolean("wi", true),
            movers = o.optBoolean("mo", true), flappers = o.optBoolean("fl", true),
            spinners = o.optBoolean("sp", true), portals = o.optBoolean("po", true),
            gates = o.optBoolean("ga", true), crumble = o.optBoolean("cr", true),
            terrain = o.optBoolean("te", true), coins = o.optBoolean("co", true),
            powerUps = o.optBoolean("pw", true), bumpers = o.optBoolean("bu", true))
    }
}

data class GameResult(
    val timeMs: Long, val parMs: Long, val coins: Int, val coinsTotal: Int,
    val difficulty: Int, val level: Int, val stars: Int, val daily: Boolean,
    val score: Int, val trapsHit: Int, val maxCombo: Int, val marbleCount: Int,
    val beatGhost: Boolean, val ghost: ByteArray?, val replay: ByteArray?,
    val usedRewind: Boolean, val fog: Boolean, val touchUsed: Boolean,
    val zen: Boolean, val seed: Long, val distCells: Float,
    val boss: Boolean, val custom: Boolean, val bumperHits: Int
)

data class BoardTheme(val bg: Int, val board: Int, val wall: Int, val bouncy: Int, val border: Int)

object GameThemes {
    val THEMES = listOf(
        BoardTheme(Color.parseColor("#12141C"), Color.parseColor("#1D2233"),
            Color.parseColor("#D9A05B"), Color.parseColor("#22D3EE"), Color.parseColor("#66D9A05B")),
        BoardTheme(Color.parseColor("#07070F"), Color.parseColor("#101024"),
            Color.parseColor("#7C3AED"), Color.parseColor("#F472B6"), Color.parseColor("#887C3AED")),
        BoardTheme(Color.parseColor("#0B0B0B"), Color.parseColor("#151515"),
            Color.parseColor("#B8B8B8"), Color.parseColor("#FFFFFF"), Color.parseColor("#66B8B8B8"))
    )
    /** Theme 3 = Material You dynamic slots (API 31+); graceful Classic fallback. */
    fun resolveTheme(id: Int, context: Context): BoardTheme {
        if (id == 3 && android.os.Build.VERSION.SDK_INT >= 31) return BoardTheme(
            bg = context.getColor(android.R.color.system_neutral1_900),
            board = context.getColor(android.R.color.system_neutral2_800),
            wall = context.getColor(android.R.color.system_accent1_300),
            bouncy = context.getColor(android.R.color.system_accent2_200),
            border = ColorUtils.setAlphaComponent(context.getColor(android.R.color.system_accent1_400), 0x88))
        return THEMES[id.coerceIn(0, THEMES.size - 1)]
    }

    val MARBLE_COLORS = intArrayOf(
        Color.parseColor("#EF4444"), Color.parseColor("#3B82F6"), Color.parseColor("#22C55E"),
        Color.parseColor("#EAB308"), Color.parseColor("#A855F7"), Color.parseColor("#F97316"),
        Color.parseColor("#06B6D4"), Color.parseColor("#EC4899"), Color.parseColor("#84CC16"),
        Color.parseColor("#F43F5E"))
    val LETTERS = arrayOf("R", "B", "G", "Y", "P", "O", "C", "M", "L", "S")
    val COLOR_NAMES = arrayOf("Ruby", "Cobalt", "Moss", "Sunny", "Violet",
        "Tango", "Splash", "Blossom", "Limey", "Rose")

    fun teamMembers(marbles: Int) = COLOR_NAMES.take(marbles.coerceIn(1, COLOR_NAMES.size))
    fun teamCaptain(difficulty: Int, level: Int, marbles: Int): String {
        val m = teamMembers(marbles)
        return m[((difficulty * 31 + level * 7) % m.size + m.size) % m.size]
    }
}
