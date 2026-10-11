package com.example.marblemaze

import android.util.Base64
import java.nio.ByteBuffer

/** MRF2 replay payload codec. */
object ReplayCodec {
    private val MAGIC = "MRF2".toByteArray(Charsets.US_ASCII)
    const val PREFIX = "MMREPLAY2:"
    data class Replay(val seed: Long, val difficulty: Int, val level: Int,
                      val marbles: Int, val floats: FloatArray)

    fun encode(r: Replay): String {
        val bb = ByteBuffer.allocate(4 + 8 + 12 + 4 + r.floats.size * 4)
        bb.put(MAGIC); bb.putLong(r.seed)
        bb.putInt(r.difficulty); bb.putInt(r.level); bb.putInt(r.marbles); bb.putInt(r.floats.size)
        for (f in r.floats) bb.putFloat(f)
        return PREFIX + Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    fun decode(payload: String, validDifficulty: IntArray, maxLevel: Int): Replay? = try {
        val bytes = Base64.decode(payload.trim().removePrefix(PREFIX), Base64.NO_WRAP)
        val bb = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4); bb.get(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val seed = bb.long; val diff = bb.int; val lvl = bb.int; val mar = bb.int; val n = bb.int
        val stride = 1 + 2 * mar
        if (diff !in validDifficulty || lvl !in 1..maxLevel || mar !in 1..10 ||
            n <= 0 || n % stride != 0 || bb.remaining() < n * 4) return null
        val floats = FloatArray(n)
        for (i in 0 until n) floats[i] = bb.float
        Replay(seed, diff, lvl, mar, floats)
    } catch (_: Exception) { null }
}
