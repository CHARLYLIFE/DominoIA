package com.zeusvision.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Detector heurístico para el diseño de Dominó Online mostrado por el usuario.
 * No utiliza una cadena fija: localiza componentes de fichas y busca sus dos
 * extremos geométricos libres. Si no obtiene dos extremos fiables, los marca
 * como desconocidos y el motor no recomienda una jugada.
 */
object DominoVision {
    data class Tile(val a: Int, val b: Int, val confidence: Int)
    data class Analysis(
        val hand: List<Tile>,
        val leftEnd: Int?,
        val rightEnd: Int?,
        val handConfidence: Int,
        val boardConfidence: Int,
        val message: String
    ) {
        val boardKnown: Boolean get() = leftEnd != null && rightEnd != null && boardConfidence >= 65
    }

    private data class Component(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        val w get() = x2 - x1 + 1
        val h get() = y2 - y1 + 1
        val cx get() = (x1 + x2) / 2f
        val cy get() = (y1 + y2) / 2f
        val vertical get() = h >= w
        val thickness get() = min(w, h).toFloat()
        val length get() = max(w, h).toFloat()
    }

    fun analyze(bitmap: Bitmap): Analysis {
        val handResult = detectHand(bitmap)
        val board = detectBoardEnds(bitmap)
        val handText = if (handResult.first.isEmpty()) "No distingo tu mano" else
            "Mano: " + handResult.first.joinToString { "${it.a}-${it.b}" }
        val boardText = if (board.first != null && board.second != null)
            "Mesa: extremos ${board.first} y ${board.second}"
        else "NO PUEDO CONFIRMAR LA MESA"
        return Analysis(
            handResult.first, board.first, board.second,
            handResult.second, board.third,
            "$handText · $boardText"
        )
    }

    private fun detectHand(bm: Bitmap): Pair<List<Tile>, Int> {
        val w = bm.width
        val h = bm.height
        val top = (h * 0.785f).toInt().coerceIn(0, h - 1)
        val bottom = (h * 0.955f).toInt().coerceIn(top + 1, h)
        val mask = BooleanArray(w)
        for (x in 0 until w) {
            var light = 0
            var total = 0
            for (y in top until bottom step 3) {
                if (isTilePixel(bm.getPixel(x, y))) light++
                total++
            }
            mask[x] = total > 0 && light.toFloat() / total > 0.28f
        }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until w) {
            if (mask[x] && start < 0) start = x
            if ((!mask[x] || x == w - 1) && start >= 0) {
                val end = if (mask[x]) x else x - 1
                if (end - start + 1 >= max(16, w / 48)) runs.add(start..end)
                start = -1
            }
        }
        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            val prev = merged.lastOrNull()
            if (prev != null && r.first - prev.last <= max(5, w / 110))
                merged[merged.lastIndex] = prev.first..r.last
            else merged.add(r)
        }
        val found = mutableListOf<Tile>()
        for (r in merged) {
            val tw = r.last - r.first + 1
            if (tw < w * 0.035f || tw > w * 0.19f) continue
            val x1 = r.first.coerceAtLeast(0)
            val x2 = (r.last + 1).coerceAtMost(w)
            val mid = (top + bottom) / 2
            val a = countPips(bm, x1, x2, top, mid)
            val b = countPips(bm, x1, x2, mid, bottom)
            if (a in 0..6 && b in 0..6) found.add(Tile(a, b, 75))
        }
        return Pair(found.take(10), if (found.size in 5..7) 85 else if (found.isNotEmpty()) 60 else 0)
    }

    private fun detectBoardEnds(bm: Bitmap): Triple<Int?, Int?, Int> {
        val w = bm.width
        val h = bm.height
        val scale = 2
        val sw = w / scale
        val yTop = (h * 0.16f).toInt() / scale
        val yBottom = (h * 0.78f).toInt() / scale
        val sh = yBottom - yTop
        if (sw < 50 || sh < 50) return Triple(null, null, 0)

        val seen = BooleanArray(sw * sh)
        val components = mutableListOf<Component>()
        val q = IntArray(sw * sh)
        for (sy in 0 until sh) for (sx in 0 until sw) {
            val idx = sy * sw + sx
            if (seen[idx] || !isTilePixel(bm.getPixel(sx * scale, (sy + yTop) * scale))) continue
            var head = 0
            var tail = 0
            q[tail++] = idx
            seen[idx] = true
            var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
            var area = 0
            while (head < tail) {
                val p = q[head++]
                val x = p % sw
                val y = p / sw
                area++
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                val neighbors = intArrayOf(
                    if (x > 0) p - 1 else -1,
                    if (x + 1 < sw) p + 1 else -1,
                    if (y > 0) p - sw else -1,
                    if (y + 1 < sh) p + sw else -1
                )
                for (n in neighbors) {
                    if (n >= 0 && !seen[n]) {
                        val nx = n % sw
                        val ny = n / sw
                        if (isTilePixel(bm.getPixel(nx * scale, (ny + yTop) * scale))) {
                            seen[n] = true
                            q[tail++] = n
                        }
                    }
                }
            }
            val cw = maxX - minX + 1
            val ch = maxY - minY + 1
            val ratio = max(cw, ch).toFloat() / max(1, min(cw, ch))
            if (area > 120 && ratio in 1.35f..2.35f && cw in 22..150 && ch in 22..150) {
                components.add(Component(minX * scale, (minY + yTop) * scale,
                    (maxX + 1) * scale, (maxY + yTop + 1) * scale))
            }
        }

        if (components.size < 2 || components.size > 35) return Triple(null, null, 0)

        data class OpenEnd(val tile: Component, val firstHalf: Boolean, val x: Float, val y: Float)
        val open = mutableListOf<OpenEnd>()
        for (tile in components) {
            val ends = if (tile.vertical) {
                listOf(
                    Triple(tile.cx, tile.y1.toFloat(), true),
                    Triple(tile.cx, tile.y2.toFloat(), false)
                )
            } else {
                listOf(
                    Triple(tile.x1.toFloat(), tile.cy, true),
                    Triple(tile.x2.toFloat(), tile.cy, false)
                )
            }
            for ((ex, ey, first) in ends) {
                val connected = components.any { other ->
                    if (other === tile) false else {
                        val threshold = max(tile.thickness, other.thickness) * 0.65f
                        val near = distance(ex, ey, nearestEndX(other, ex, ey), nearestEndY(other, ex, ey))
                        val aligned = if (tile.vertical) abs(other.cx - tile.cx) < threshold
                                      else abs(other.cy - tile.cy) < threshold
                        aligned && near < threshold * 1.8f
                    }
                }
                if (!connected) open.add(OpenEnd(tile, first, ex, ey))
            }
        }
        if (open.size != 2) return Triple(null, null, 0)

        val values = open.map { end ->
            val t = end.tile
            if (t.vertical) {
                if (end.firstHalf) countPips(bm, t.x1, t.x2, t.y1, (t.y1 + t.y2) / 2)
                else countPips(bm, t.x1, t.x2, (t.y1 + t.y2) / 2, t.y2)
            } else {
                if (end.firstHalf) countPips(bm, t.x1, (t.x1 + t.x2) / 2, t.y1, t.y2)
                else countPips(bm, (t.x1 + t.x2) / 2, t.x2, t.y1, t.y2)
            }
        }
        if (values.any { it !in 0..6 }) return Triple(null, null, 0)
        return Triple(values[0], values[1], 68)
    }

    private fun nearestEndX(c: Component, x: Float, y: Float): Float =
        if (c.vertical) c.cx else if (x < c.cx) c.x1.toFloat() else c.x2.toFloat()

    private fun nearestEndY(c: Component, x: Float, y: Float): Float =
        if (!c.vertical) c.cy else if (y < c.cy) c.y1.toFloat() else c.y2.toFloat()

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2; val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun isTilePixel(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val gray = (r + g + b) / 3
        return gray > 145 && max(r, max(g, b)) - min(r, min(g, b)) < 75
    }

    private fun countPips(bm: Bitmap, x1: Int, x2: Int, y1: Int, y2: Int): Int {
        val left = x1.coerceIn(0, bm.width)
        val right = x2.coerceIn(left, bm.width)
        val top = y1.coerceIn(0, bm.height)
        val bottom = y2.coerceIn(top, bm.height)
        val rw = right - left
        val rh = bottom - top
        if (rw < 5 || rh < 5) return -1
        val step = 2
        val sw = rw / step
        val sh = rh / step
        if (sw < 2 || sh < 2) return -1
        val seen = BooleanArray(sw * sh)
        val q = IntArray(sw * sh)
        var count = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            val idx = y * sw + x
            if (seen[idx] || !isDark(bm.getPixel(left + x * step, top + y * step))) continue
            var head = 0; var tail = 0
            q[tail++] = idx; seen[idx] = true
            var area = 0; var minX = x; var maxX = x; var minY = y; var maxY = y
            while (head < tail) {
                val p = q[head++]; val px = p % sw; val py = p / sw
                area++
                minX = min(minX, px); maxX = max(maxX, px)
                minY = min(minY, py); maxY = max(maxY, py)
                val ns = intArrayOf(if (px > 0) p - 1 else -1, if (px + 1 < sw) p + 1 else -1,
                    if (py > 0) p - sw else -1, if (py + 1 < sh) p + sw else -1)
                for (n in ns) if (n >= 0 && !seen[n]) {
                    val nx = n % sw; val ny = n / sw
                    if (isDark(bm.getPixel(left + nx * step, top + ny * step))) {
                        seen[n] = true; q[tail++] = n
                    }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (area >= 2 && area <= 90 && bw <= sw * 0.35f && bh <= sh * 0.35f) count++
        }
        return if (count <= 6) count else -1
    }

    private fun isDark(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        return (r + g + b) / 3 < 95
    }
}
