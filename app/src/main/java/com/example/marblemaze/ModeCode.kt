package com.example.marblemaze

import android.util.Base64
import java.nio.ByteBuffer

/** Packs a full RunMode into a shareable "MM8-…" code (URL-safe Base64). */
object ModeCode {
    fun encode(m: RunMode): String {
        val name = m.name.toByteArray(Charsets.UTF_8).take(60).toByteArray()
        val bb = ByteBuffer.allocate(3 + 2 + 8 + 1 + name.size)
        bb.put(m.genMode.toByte()); bb.put(m.difficulty.coerceIn(0, 255).toByte())
        bb.put(m.marbles.coerceIn(0, 255).toByte()); bb.putShort(flags(m)); bb.putLong(m.fixedSeed)
        bb.put(name.size.toByte()); bb.put(name)
        return "MM8-" + Base64.encodeToString(bb.array(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decode(code: String): RunMode? = try {
        val bb = ByteBuffer.wrap(Base64.decode(code.trim().removePrefix("MM8-"), Base64.NO_WRAP or Base64.URL_SAFE))
        val gen = bb.get().toInt().coerceIn(Maze.GEN_CLASSIC, Maze.GEN_RIVERS)
        val diff = (bb.get().toInt() and 0xFF).coerceIn(6, 34)
        val mar = (bb.get().toInt() and 0xFF).coerceIn(1, 10)
        val f = bb.short.toInt() and 0xFFFF
        val seed = bb.long
        val nameBytes = ByteArray(bb.get().toInt()); bb.get(nameBytes)
        RunMode(name = String(nameBytes, Charsets.UTF_8).ifBlank { "Shared mode" },
            genMode = gen, difficulty = diff, marbles = mar, fixedSeed = seed,
            traps = bit(f, 0), pads = bit(f, 1), belts = bit(f, 2), winds = bit(f, 3),
            movers = bit(f, 4), flappers = bit(f, 5), spinners = bit(f, 6), portals = bit(f, 7),
            gates = bit(f, 8), crumble = bit(f, 9), terrain = bit(f, 10), coins = bit(f, 11),
            powerUps = bit(f, 12), bumpers = bit(f, 13))
    } catch (_: Exception) { null }

    private fun flags(m: RunMode): Short {
        var f = 0
        fun b(i: Int, on: Boolean) { if (on) f = f or (1 shl i) }
        b(0, m.traps); b(1, m.pads); b(2, m.belts); b(3, m.winds); b(4, m.movers)
        b(5, m.flappers); b(6, m.spinners); b(7, m.portals); b(8, m.gates)
        b(9, m.crumble); b(10, m.terrain); b(11, m.coins); b(12, m.powerUps); b(13, m.bumpers)
        return f.toShort()
    }
    private fun bit(f: Int, i: Int) = (f shr i) and 1 == 1
}
