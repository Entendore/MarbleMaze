package com.example.marblemaze

import kotlin.random.Random

class Maze(val cols: Int, val rows: Int) {
    class Cell { var top = true; var right = true; var bottom = true; var left = true; internal var visited = false }
    private val cells: Array<Array<Cell>> = Array(cols) { Array(rows) { Cell() } }
    operator fun get(x: Int, y: Int): Cell = cells[x][y]

    fun generate(genMode: Int, random: Random, braid: Float = 0.35f) {
        when (genMode) {
            GEN_CAVES -> genCaves(random); GEN_SPIRAL -> genSpiral(random)
            GEN_CHAMBERS -> genChambers(random); GEN_OPEN -> genOpen(random)
            GEN_RIVERS -> genRivers(random); else -> genClassic(random, braid)
        }
        ensureConnected()
    }

    fun isFullyConnected(): Boolean {
        val seen = BooleanArray(cols * rows); val q = ArrayDeque<Int>()
        seen[0] = true; q.addLast(0)
        while (q.isNotEmpty()) {
            val i = q.removeFirst(); val x = i % cols; val y = i / cols; val c = cells[x][y]
            if (!c.top && y > 0 && !seen[i - cols]) { seen[i - cols] = true; q.addLast(i - cols) }
            if (!c.bottom && y < rows - 1 && !seen[i + cols]) { seen[i + cols] = true; q.addLast(i + cols) }
            if (!c.left && x > 0 && !seen[i - 1]) { seen[i - 1] = true; q.addLast(i - 1) }
            if (!c.right && x < cols - 1 && !seen[i + 1]) { seen[i + 1] = true; q.addLast(i + 1) }
        }
        return seen.all { it }
    }

    private fun genClassic(r: Random, braid: Float) {
        val stack = ArrayDeque<Pair<Int, Int>>(); var x = 0; var y = 0
        cells[0][0].visited = true; var v = 1
        while (v < cols * rows) {
            val n = ArrayList<Pair<Int, Int>>(4)
            if (y > 0 && !cells[x][y - 1].visited) n.add(x to y - 1)
            if (x < cols - 1 && !cells[x + 1][y].visited) n.add(x + 1 to y)
            if (y < rows - 1 && !cells[x][y + 1].visited) n.add(x to y + 1)
            if (x > 0 && !cells[x - 1][y].visited) n.add(x - 1 to y)
            if (n.isNotEmpty()) { val (nx, ny) = n.random(r); carve(x, y, nx, ny); stack.addLast(x to y); x = nx; y = ny; cells[x][y].visited = true; v++ }
            else { val (px, py) = stack.removeLast(); x = px; y = py }
        }
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val c = cells[cx][cy]
            if (listOf(c.top, c.right, c.bottom, c.left).count { it } >= 3 && r.nextFloat() < braid) {
                val o = ArrayList<Pair<Int, Int>>(4)
                if (c.top && cy > 0) o.add(cx to cy - 1); if (c.right && cx < cols - 1) o.add(cx + 1 to cy)
                if (c.bottom && cy < rows - 1) o.add(cx to cy + 1); if (c.left && cx > 0) o.add(cx - 1 to cy)
                if (o.isNotEmpty()) { val (nx, ny) = o.random(r); carve(cx, cy, nx, ny) }
            }
        }
    }

    private class Room(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        fun overlaps(o: Room) = x0 <= o.x1 + 1 && o.x0 <= x1 + 1 && y0 <= o.y1 + 1 && o.y0 <= y1 + 1
        val cx get() = (x0 + x1) / 2; val cy get() = (y0 + y1) / 2
    }

    private fun genCaves(r: Random) {
        val target = (cols * rows / 16).coerceIn(3, 10); val rooms = ArrayList<Room>()
        var a = 0
        while (rooms.size < target && a++ < 300) {
            val w = 2 + r.nextInt(3); val h = 2 + r.nextInt(3)
            val x0 = r.nextInt(cols - w + 1); val y0 = r.nextInt(rows - h + 1)
            val rm = Room(x0, y0, x0 + w - 1, y0 + h - 1)
            if (rooms.none { it.overlaps(rm) }) rooms.add(rm)
        }
        for (rm in rooms) for (y in rm.y0..rm.y1) for (x in rm.x0..rm.x1) {
            if (x < rm.x1) carve(x, y, x + 1, y); if (y < rm.y1) carve(x, y, x, y + 1)
        }
        for (i in 1 until rooms.size) corridor(rooms[i - 1], rooms[i], r)
        repeat(rooms.size / 3) {
            val p = rooms.random(r); val q = rooms.random(r); if (p !== q) corridor(p, q, r)
        }
    }

    private fun corridor(a: Room, b: Room, r: Random) {
        var x = a.cx; var y = a.cy
        if (r.nextBoolean()) { while (x != b.cx) { carve(x, y, x + (if (b.cx > x) 1 else -1), y); x += if (b.cx > x) 1 else -1 }; while (y != b.cy) { carve(x, y, x, y + (if (b.cy > y) 1 else -1)); y += if (b.cy > y) 1 else -1 } }
        else { while (y != b.cy) { carve(x, y, x, y + (if (b.cy > y) 1 else -1)); y += if (b.cy > y) 1 else -1 }; while (x != b.cx) { carve(x, y, x + (if (b.cx > x) 1 else -1), y); x += if (b.cx > x) 1 else -1 } }
    }

    private fun genSpiral(r: Random) {
        var x0 = 0; var y0 = 0; var x1 = cols - 1; var y1 = rows - 1
        while (x0 <= x1 && y0 <= y1) {
            for (x in x0 until x1) carve(x, y0, x + 1, y0)
            for (y in y0 until y1) carve(x1, y, x1, y + 1)
            for (x in x1 downTo x0 + 1) carve(x, y1, x - 1, y1)
            for (y in y1 downTo y0 + 1) carve(x0, y, x0, y - 1)
            if (x1 - x0 >= 2 && y1 - y0 >= 2) when (r.nextInt(4)) {
                0 -> { val g = x0 + 1 + r.nextInt(x1 - x0 - 1); carve(g, y0, g, y0 + 1) }
                1 -> { val g = x0 + 1 + r.nextInt(x1 - x0 - 1); carve(g, y1, g, y1 - 1) }
                2 -> { val g = y0 + 1 + r.nextInt(y1 - y0 - 1); carve(x0, g, x0 + 1, g) }
                else -> { val g = y0 + 1 + r.nextInt(y1 - y0 - 1); carve(x1, g, x1 - 1, g) }
            }
            x0++; y0++; x1--; y1--
        }
    }

    private fun genChambers(r: Random) {
        val cw = 3; val ch = 3
        val nCx = (cols + cw - 1) / cw; val nCy = (rows + ch - 1) / ch
        for (cy in 0 until nCy) for (cx in 0 until nCx) {
            val x0 = cx * cw; val y0 = cy * ch
            val x1 = minOf(x0 + cw - 1, cols - 1); val y1 = minOf(y0 + ch - 1, rows - 1)
            for (y in y0..y1) for (x in x0..x1) { if (x < x1) carve(x, y, x + 1, y); if (y < y1) carve(x, y, x, y + 1) }
        }
        for (cy in 0 until nCy) for (cx in 0 until nCx - 1) repeat(1 + if (r.nextFloat() < 0.35f) 1 else 0) {
            val y = cy * ch + r.nextInt(minOf(ch, rows - cy * ch)); val x = cx * cw + cw - 1
            if (x < cols - 1) carve(x, y, x + 1, y)
        }
        for (cy in 0 until nCy - 1) for (cx in 0 until nCx) repeat(1 + if (r.nextFloat() < 0.35f) 1 else 0) {
            val x = cx * cw + r.nextInt(minOf(cw, cols - cx * cw)); val y = cy * ch + ch - 1
            if (y < rows - 1) carve(x, y, x, y + 1)
        }
    }

    private fun genOpen(r: Random) {
        genClassic(r, 0.85f)
        for (y in 0 until rows) for (x in 0 until cols) {
            if (x < cols - 1 && cells[x][y].right && r.nextFloat() < 0.55f) carve(x, y, x + 1, y)
            if (y < rows - 1 && cells[x][y].bottom && r.nextFloat() < 0.55f) carve(x, y, x, y + 1)
        }
    }

    private fun genRivers(r: Random) {
        for (y in 0 until rows) for (x in 0 until cols - 1) carve(x, y, x + 1, y)
        for (y in 0 until rows - 1) {
            val endX = if (y % 2 == 0) cols - 1 else 0
            carve(endX, y, endX, y + 1)
            if (r.nextFloat() < 0.30f) carve(r.nextInt(cols), y, r.nextInt(cols), y + 1).let {}
            if (r.nextFloat() < 0.30f) { val x = r.nextInt(cols); carve(x, y, x, y + 1) }
        }
    }

    private fun ensureConnected() {
        repeat(80) {
            val seen = BooleanArray(cols * rows); val q = ArrayDeque<Int>()
            seen[0] = true; q.addLast(0)
            while (q.isNotEmpty()) {
                val i = q.removeFirst(); val x = i % cols; val y = i / cols; val c = cells[x][y]
                if (!c.top && y > 0 && !seen[i - cols]) { seen[i - cols] = true; q.addLast(i - cols) }
                if (!c.bottom && y < rows - 1 && !seen[i + cols]) { seen[i + cols] = true; q.addLast(i + cols) }
                if (!c.left && x > 0 && !seen[i - 1]) { seen[i - 1] = true; q.addLast(i - 1) }
                if (!c.right && x < cols - 1 && !seen[i + 1]) { seen[i + 1] = true; q.addLast(i + 1) }
            }
            var fixed = 0
            for (y in 0 until rows) for (x in 0 until cols) {
                val i = y * cols + x; if (seen[i]) continue
                val c = cells[x][y]
                if (y > 0 && seen[i - cols]) { c.top = false; cells[x][y - 1].bottom = false; fixed++ }
                else if (y < rows - 1 && seen[i + cols]) { c.bottom = false; cells[x][y + 1].top = false; fixed++ }
                else if (x > 0 && seen[i - 1]) { c.left = false; cells[x - 1][y].right = false; fixed++ }
                else if (x < cols - 1 && seen[i + 1]) { c.right = false; cells[x + 1][y].left = false; fixed++ }
            }
            if (fixed == 0) return
        }
    }

    private fun carve(x: Int, y: Int, nx: Int, ny: Int) {
        if (nx == x && ny == y - 1) { cells[x][y].top = false; cells[nx][ny].bottom = false }
        else if (nx == x + 1 && ny == y) { cells[x][y].right = false; cells[nx][ny].left = false }
        else if (nx == x && ny == y + 1) { cells[x][y].bottom = false; cells[nx][ny].top = false }
        else if (nx == x - 1 && ny == y) { cells[x][y].left = false; cells[nx][ny].right = false }
    }

    companion object {
        const val GEN_CLASSIC = 0; const val GEN_CAVES = 1; const val GEN_SPIRAL = 2
        const val GEN_CHAMBERS = 3; const val GEN_OPEN = 4; const val GEN_RIVERS = 5
        val GEN_NAMES = arrayOf("Classic", "Caves", "Spiral", "Chambers", "Open field", "Rivers")
    }
}
