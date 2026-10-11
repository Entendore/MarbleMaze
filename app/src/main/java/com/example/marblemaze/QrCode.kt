package com.example.marblemaze

import kotlin.math.abs

/** Minimal QR generator: byte mode, ECC L, versions 1-4, fixed mask 0. */
object QrCode {
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.ISO_8859_1)
        val dataCw = intArrayOf(19, 34, 55, 80); val eccCw = intArrayOf(7, 10, 15, 20)
        val needed = 12 + data.size * 8
        var ver = -1
        for (v in dataCw.indices) if (needed <= dataCw[v] * 8) { ver = v; break }
        check(ver >= 0) { "QR payload too long" }
        val version = ver + 1; val size = version * 4 + 17

        val bits = ArrayList<Boolean>(dataCw[ver] * 8)
        fun append(v: Int, c: Int) { for (i in c - 1 downTo 0) bits.add((v ushr i) and 1 == 1) }
        append(0b0100, 4); append(data.size, 8)
        for (b in data) append(b.toInt() and 0xFF, 8)
        val capacity = dataCw[ver] * 8
        append(0, minOf(4, capacity - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0
        while (bits.size < capacity) { append(if (pad % 2 == 0) 0xEC else 0x11, 8); pad++ }
        val dc = ByteArray(dataCw[ver])
        for (i in dc.indices) { var b = 0; for (j in 0..7) if (bits[i * 8 + j]) b = b or (1 shl (7 - j)); dc[i] = b.toByte() }
        val all = dc + rsRemainder(dc, eccCw[ver])
        val totalBits = all.size * 8 + if (version == 1) 0 else 7

        val dark = Array(size) { BooleanArray(size) }
        val func = Array(size) { BooleanArray(size) }
        fun set(x: Int, y: Int, d: Boolean) { if (x in 0 until size && y in 0 until size) { dark[y][x] = d; func[y][x] = true } }

        for (i in 0 until size) { set(i, 6, i % 2 == 0); set(6, i, i % 2 == 0) }
        fun finder(cx: Int, cy: Int) { for (dy in -4..4) for (dx in -4..4) set(cx + dx, cy + dy, maxOf(abs(dx), abs(dy)) != 2 && maxOf(abs(dx), abs(dy)) != 4) }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        val ap = when (version) { 2 -> intArrayOf(6, 18); 3 -> intArrayOf(6, 22); 4 -> intArrayOf(6, 26); else -> IntArray(0) }
        for (i in ap.indices) for (j in ap.indices) {
            val corner = (i == 0 && j == 0) || (i == 0 && j == ap.size - 1) || (i == ap.size - 1 && j == 0)
            if (!corner) for (dy in -2..2) for (dx in -2..2) set(ap[i] + dx, ap[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
        val fd = (1 shl 3) or 0
        var rem = fd; repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val fb = ((fd shl 10) or rem) xor 0x5412
        fun fbit(i: Int) = (fb ushr i) and 1 == 1
        for (i in 0..5) set(8, i, fbit(i)); set(8, 7, fbit(6)); set(8, 8, fbit(7)); set(7, 8, fbit(8))
        for (i in 9..14) set(14 - i, 8, fbit(i))
        for (i in 0..7) set(size - 1 - i, 8, fbit(i))
        for (i in 8..14) set(8, size - 15 + i, fbit(i))
        set(size - 8, 8, true)

        fun cwBit(i: Int) = ((all[i / 8].toInt() and 0xFF) ushr (7 - (i % 8))) and 1 == 1
        var bi = 0; var right = size - 1
        while (right >= 1) {
            val r = if (right == 6) 5 else right
            for (vert in 0 until size) for (j in 0..1) {
                val x = r - j
                val y = if (((r + 1) and 2) == 0) size - 1 - vert else vert
                if (!func[y][x]) {
                    var bit = bi < totalBits && cwBit(bi)
                    if ((y + x) % 2 == 0) bit = !bit
                    dark[y][x] = bit; bi++
                }
            }
            right -= 2
        }
        return dark
    }

    private fun rsRemainder(data: ByteArray, degree: Int): ByteArray {
        val divisor = ByteArray(degree); divisor[degree - 1] = 1; var root = 1
        for (i in 0 until degree) {
            for (j in divisor.indices) {
                divisor[j] = gfMul(divisor[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < divisor.size) divisor[j] = (divisor[j].toInt() xor divisor[j + 1].toInt()).toByte()
            }
            root = (root shl 1) xor ((root ushr 7) * 0x11D)
        }
        val res = ByteArray(degree)
        for (b in data) {
            val factor = (b.toInt() xor res[0].toInt()) and 0xFF
            System.arraycopy(res, 1, res, 0, res.size - 1); res[res.size - 1] = 0
            for (i in divisor.indices) res[i] = (res[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
        }
        return res
    }
    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
        return z and 0xFF
    }
}
