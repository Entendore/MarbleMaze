package com.example.marblemaze

import android.graphics.RectF
import kotlin.math.sqrt

/** Pure, allocation-light collision math — unit-testable without a View. */
object Physics {
    data class RectHit(val nx: Float, val ny: Float, val pushX: Float, val pushY: Float, val impact: Float)

    fun circleVsRect(x: Float, y: Float, vx: Float, vy: Float, r: Float, rect: RectF, e: Float): RectHit? {
        val px = x.coerceIn(rect.left, rect.right); val py = y.coerceIn(rect.top, rect.bottom)
        val dx = x - px; val dy = y - py; val d2 = dx * dx + dy * dy
        if (d2 > r * r) return null
        if (d2 > 1e-12f) {
            val d = sqrt(d2); val nx = dx / d; val ny = dy / d; val vn = vx * nx + vy * ny
            return RectHit(nx, ny, nx * (r - d), ny * (r - d), if (vn < 0f) -vn else 0f)
        }
        val penL = (x - rect.left) + r; val penR = (rect.right - x) + r
        val penT = (y - rect.top) + r; val penB = (rect.bottom - y) + r
        return when {
            penL <= penR && penL <= penT && penL <= penB -> RectHit(-1f, 0f, -penL, 0f, if (vx > 0f) vx else 0f)
            penR <= penT && penR <= penB -> RectHit(1f, 0f, penR, 0f, if (vx < 0f) -vx else 0f)
            penT <= penB -> RectHit(0f, -1f, 0f, -penT, if (vy > 0f) vy else 0f)
            else -> RectHit(0f, 1f, 0f, penB, if (vy < 0f) -vy else 0f)
        }
    }

    fun reflect(vx: Float, vy: Float, nx: Float, ny: Float, vn: Float, e: Float): Pair<Float, Float> {
        val j = -(1f + e) * vn
        return (vx + j * nx) to (vy + j * ny)
    }

    data class PairResult(
        val ax: Float, val ay: Float, val avx: Float, val avy: Float,
        val bx: Float, val by: Float, val bvx: Float, val bvy: Float, val impact: Float)

    fun resolveCircles(
        ax: Float, ay: Float, avx: Float, avy: Float,
        bx: Float, by: Float, bvx: Float, bvy: Float, minD: Float, e: Float
    ): PairResult? {
        val dx = bx - ax; val dy = by - ay; val d2 = dx * dx + dy * dy
        if (d2 >= minD * minD || d2 == 0f) return null
        val d = sqrt(d2); val nx = dx / d; val ny = dy / d; val ov = (minD - d) * 0.5f
        val vn = (bvx - avx) * nx + (bvy - avy) * ny
        var navx = avx; var navy = avy; var nbvx = bvx; var nbvy = bvy; var impact = 0f
        if (vn < 0f) {
            impact = -vn; val imp = -(1f + e) * vn * 0.5f
            navx -= imp * nx; navy -= imp * ny; nbvx += imp * nx; nbvy += imp * ny
        }
        return PairResult(ax - nx * ov, ay - ny * ov, navx, navy, bx + nx * ov, by + ny * ov, nbvx, nbvy, impact)
    }
}
