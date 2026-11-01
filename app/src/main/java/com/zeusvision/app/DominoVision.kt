package com.zeusvision.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Detector experimental para fichas verticales situadas en la franja inferior.
 * Devuelve solo lecturas plausibles; no inventa valores cuando la imagen no es clara.
 */
object DominoVision {
    data class DetectedTile(val a: Int, val b: Int, val confidence: Int) {
        fun label() = "$a-$b"
    }
    data class Result(val tiles: List<DetectedTile>, val message: String)

    fun detectHand(bitmap: Bitmap): Result {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 200) return Result(emptyList(), "Pantalla demasiado pequeña")
        val top = (h * 0.785f).toInt().coerceIn(0, h - 1)
        val bottom = (h * 0.955f).toInt().coerceIn(top + 1, h)
        val occupancy = BooleanArray(w)
        for (x in 0 until w) {
            var light = 0
            var samples = 0
            for (y in top until bottom step 3) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val gray = (r + g + b) / 3
                val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
                if (gray > 105 && maxC - minC < 95) light++
                samples++
            }
            occupancy[x] = samples > 0 && light.toFloat() / samples > 0.32f
        }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in 0 until w) {
            if (occupancy[x] && start < 0) start = x
            if ((!occupancy[x] || x == w - 1) && start >= 0) {
                val end = if (occupancy[x]) x else x - 1
                if (end - start + 1 >= max(18, w / 45)) runs.add(start..end)
                start = -1
            }
        }
        // Join small gaps between pixels belonging to one tile.
        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last <= max(5, w / 100)) merged[merged.lastIndex] = last.first..r.last
            else merged.add(r)
        }
        val found = mutableListOf<DetectedTile>()
        for (run in merged) {
            val tileW = run.last - run.first + 1
            if (tileW < w * 0.035f || tileW > w * 0.19f) continue
            val x1 = run.first.coerceAtLeast(0); val x2 = (run.last + 1).coerceAtMost(w)
            val y1 = top; val y2 = bottom
            val mid = (y1 + y2) / 2
            val a = countPips(bitmap, x1, x2, y1, mid)
            val b = countPips(bitmap, x1, x2, mid, y2)
            if (a in 0..6 && b in 0..6) {
                val confidence = if (tileW > w * 0.045f && tileW < w * 0.13f) 70 else 45
                found.add(DetectedTile(a, b, confidence))
            }
        }
        val unique = found.take(10)
        return if (unique.isEmpty()) Result(emptyList(), "No distingo las fichas: ajusta la vista o despeja la franja inferior")
        else Result(unique, "Mano detectada: ${unique.joinToString { it.label() }}")
    }

    private fun countPips(bm: Bitmap, x1: Int, x2: Int, y1: Int, y2: Int): Int {
        val w = x2 - x1; val h = y2 - y1
        if (w < 8 || h < 8) return -1
        val seen = BooleanArray(w * h)
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        var count = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val idx = yy * w + xx
            if (seen[idx]) continue
            val c = bm.getPixel(x1 + xx, y1 + yy)
            if (dark(c)) {
                var head = 0; var tail = 0; qx[tail] = xx; qy[tail++] = yy; seen[idx] = true
                var area = 0; var minX = xx; var maxX = xx; var minY = yy; var maxY = yy
                while (head < tail) {
                    val px = qx[head]; val py = qy[head++]; area++
                    minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = px + dx; val ny = py + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val ni = ny * w + nx
                        if (!seen[ni] && dark(bm.getPixel(x1 + nx, y1 + ny))) {
                            seen[ni] = true; qx[tail] = nx; qy[tail++] = ny
                        }
                    }
                }
                val cw = maxX - minX + 1; val ch = maxY - minY + 1
                // Ignore the long horizontal separator and tiny noise.
                if (area >= 5 && area <= (w * h) / 8 && cw < w * 0.48f && ch < h * 0.48f && cw > 1 && ch > 1) count++
            }
        }
        return count.coerceIn(0, 7)
    }

    private fun dark(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return (r + g + b) / 3 < 78
    }
}
