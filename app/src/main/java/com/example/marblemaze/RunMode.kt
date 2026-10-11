package com.example.marblemaze

import org.json.JSONObject
import java.util.UUID

data class RunMode(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val genMode: Int = Maze.GEN_CLASSIC,
    val difficulty: Int = 14, val marbles: Int = 3, val fixedSeed: Long = 0L,
    val traps: Boolean = true, val pads: Boolean = true, val belts: Boolean = true,
    val winds: Boolean = true, val movers: Boolean = true, val flappers: Boolean = true,
    val spinners: Boolean = true, val portals: Boolean = true, val gates: Boolean = true,
    val crumble: Boolean = true, val terrain: Boolean = true, val coins: Boolean = true,
    val powerUps: Boolean = true, val bumpers: Boolean = true
) {
    fun toOptions() = RunOptions(genMode, traps, pads, belts, winds, movers, flappers,
        spinners, portals, gates, crumble, terrain, coins, powerUps, bumpers)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("gen", genMode).put("diff", difficulty)
        .put("mar", marbles).put("seed", fixedSeed)
        .put("traps", traps).put("pads", pads).put("belts", belts).put("winds", winds)
        .put("movers", movers).put("flappers", flappers).put("spinners", spinners)
        .put("portals", portals).put("gates", gates).put("crumble", crumble)
        .put("terrain", terrain).put("coins", coins).put("power", powerUps).put("bump", bumpers)

    companion object {
        fun fromJson(o: JSONObject): RunMode = RunMode(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Custom run"),
            genMode = o.optInt("gen", Maze.GEN_CLASSIC).coerceIn(Maze.GEN_CLASSIC, Maze.GEN_RIVERS),
            difficulty = o.optInt("diff", 14).coerceIn(6, 34),
            marbles = o.optInt("mar", 3).coerceIn(1, 10),
            fixedSeed = o.optLong("seed", 0L),
            traps = o.optBoolean("traps", true), pads = o.optBoolean("pads", true),
            belts = o.optBoolean("belts", true), winds = o.optBoolean("winds", true),
            movers = o.optBoolean("movers", true), flappers = o.optBoolean("flappers", true),
            spinners = o.optBoolean("spinners", true), portals = o.optBoolean("portals", true),
            gates = o.optBoolean("gates", true), crumble = o.optBoolean("crumble", true),
            terrain = o.optBoolean("terrain", true), coins = o.optBoolean("coins", true),
            powerUps = o.optBoolean("power", true), bumpers = o.optBoolean("bump", true))
    }
}
