
package com.zeusvision.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ZEUS VISION 0.7
 * Detector experimental de fichas de dominó.
 *
 * Detecta la mano y busca los extremos geométricos de la mesa.
 * Si no puede confirmar los extremos, devuelve valores desconocidos.
 */
object DominoVision {

    data class Tile(
        val a: Int,
        val b: Int,
        val confidence: Int
    )

    data class Analysis(
        val hand: List<Tile>,
        val leftEnd: Int?,
        val rightEnd: Int?,
        val handConfidence: Int,
        val boardConfidence: Int,
        val message: String
    ) {
        val boardKnown: Boolean
            get() = leftEnd != null &&
                    rightEnd != null &&
                    boardConfidence >= 65
    }

    private data class Component(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int
    ) {
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

        val handText =
            if (handResult.first.isEmpty()) {
                "No distingo tu mano"
            } else {
                "Mano: " + handResult.first.joinToString {
                    "${it.a}-${it.b}"
                }
            }

        val boardText =
            if (board.first != null && board.second != null) {
                "Mesa: extremos ${board.first} y ${board.second}"
            } else {
                "NO PUEDO CONFIRMAR LA MESA"
            }

        return Analysis(
            hand = handResult.first,
            leftEnd = board.first,
            rightEnd = board.second,
            handConfidence = handResult.second,
            boardConfidence = board.third,
            message = "$handText · $boardText"
        )
    }

    /**
     * Busca fichas en la franja inferior de la pantalla.
     * Incluye fichas claras y grises.
     */
    private fun detectHand(
        bm: Bitmap
    ): Pair<List<Tile>, Int> {

        val w = bm.width
        val h = bm.height

        val top = (h * 0.785f)
            .toInt()
            .coerceIn(0, h - 1)

        val bottom = (h * 0.955f)
            .toInt()
            .coerceIn(top + 1, h)

        val scores = IntArray(w)
        val sampleStep = 3

        val samples =
            ((bottom - top) / sampleStep).coerceAtLeast(1)

        for (x in 0 until w) {

            var neutral = 0

            for (y in top until bottom step sampleStep) {
                if (isTilePixel(bm.getPixel(x, y))) {
                    neutral++
                }
            }

            scores[x] = neutral
        }

        val threshold =
            (samples * 0.20f).toInt().coerceAtLeast(2)

        val mask = BooleanArray(w) {
            scores[it] >= threshold
        }

        val raw = mutableListOf<IntRange>()
        var start = -1

        for (x in 0 until w) {

            if (mask[x] && start < 0) {
                start = x
            }

            if ((!mask[x] || x == w - 1) && start >= 0) {

                val last =
                    if (mask[x]) x else x - 1

                if (last - start + 1 >= max(12, w / 65)) {
                    raw.add(start..last)
                }

                start = -1
            }
        }

        // Une pequeñas interrupciones provocadas por puntos y líneas.
        val runs = mutableListOf<IntRange>()

        for (r in raw) {

            val prev = runs.lastOrNull()

            if (prev != null &&
                r.first - prev.last <= max(7, w / 70)
            ) {
                runs[runs.lastIndex] = prev.first..r.last
            } else {
                runs.add(r)
            }
        }

        val found = mutableListOf<Tile>()

        for (r in runs) {

            val tileWidth = r.last - r.first + 1

            if (tileWidth < w * 0.045f ||
                tileWidth > w * 0.20f
            ) continue

            val x1 = r.first.coerceAtLeast(0)
            val x2 = (r.last + 1).coerceAtMost(w)

            val mid = (top + bottom) / 2

            val a = countPips(
                bm, x1, x2, top, mid
            )

            val b = countPips(
                bm, x1, x2, mid, bottom
            )

            if (a in 0..6 && b in 0..6) {

                if (a + b > 0) {
                    found.add(Tile(a, b, 72))
                }
            }
        }

        val unique = found.distinctBy {
            if (it.a <= it.b) {
                "${it.a}-${it.b}"
            } else {
                "${it.b}-${it.a}"
            }
        }

        val confidence = when {
            unique.size in 5..7 -> 78
            unique.size in 1..10 -> 55
            else -> 0
        }

        return Pair(unique.take(10), confidence)
    }

    /**
     * Busca componentes de fichas en la zona central.
     * Intenta identificar las dos mitades exteriores.
     */
    private fun detectBoardEnds(
        bm: Bitmap
    ): Triple<Int?, Int?, Int> {

        val w = bm.width
        val h = bm.height

        val scale = 2
        val sw = w / scale

        val yTop = (h * 0.16f).toInt() / scale
        val yBottom = (h * 0.78f).toInt() / scale

        val sh = yBottom - yTop

        if (sw < 50 || sh < 50) {
            return Triple(null, null, 0)
        }

        val seen = BooleanArray(sw * sh)
        val components = mutableListOf<Component>()
        val q = IntArray(sw * sh)

        for (sy in 0 until sh) {
            for (sx in 0 until sw) {

                val idx = sy * sw + sx

                if (seen[idx] ||
                    !isTilePixel(
                        bm.getPixel(
                            sx * scale,
                            (sy + yTop) * scale
                        )
                    )
                ) continue

                var head = 0
                var tail = 0

                q[tail++] = idx
                seen[idx] = true

                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var area = 0

                while (head < tail) {

                    val p = q[head++]
                    val x = p % sw
                    val y = p / sw

                    area++

                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)

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

                            if (
                                isTilePixel(
                                    bm.getPixel(
                                        nx * scale,
                                        (ny + yTop) * scale
                                    )
                                )
                            ) {
                                seen[n] = true
                                q[tail++] = n
                            }
                        }
                    }
                }

                val cw = maxX - minX + 1
                val ch = maxY - minY + 1

                val ratio =
                    max(cw, ch).toFloat() /
                    max(1, min(cw, ch))

                if (
                    area > 120 &&
                    ratio in 1.35f..2.35f &&
                    cw in 22..150 &&
                    ch in 22..150
                ) {
                    components.add(
                        Component(
                            minX * scale,
                            (minY + yTop) * scale,
                            (maxX + 1) * scale,
                            (maxY + yTop + 1) * scale
                        )
                    )
                }
            }
        }

        if (components.size < 2 ||
            components.size > 35
        ) {
            return Triple(null, null, 0)
        }

        data class OpenEnd(
            val tile: Component,
            val firstHalf: Boolean,
            val x: Float,
            val y: Float
        )

        val open = mutableListOf<OpenEnd>()

        for (tile in components) {

            val ends =
                if (tile.vertical) {
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

                    if (other === tile) {
                        false
                    } else {

                        val threshold =
                            max(
                                tile.thickness,
                                other.thickness
                            ) * 0.65f

                        val near = distance(
                            ex,
                            ey,
                            nearestEndX(other, ex, ey),
                            nearestEndY(other, ex, ey)
                        )

                        val aligned =
                            if (tile.vertical) {
                                abs(other.cx - tile.cx) < threshold
                            } else {
                                abs(other.cy - tile.cy) < threshold
                            }

                        aligned && near < threshold * 1.8f
                    }
                }

                if (!connected) {
                    open.add(
                        OpenEnd(tile, first, ex, ey)
                    )
                }
            }
        }

        if (open.size != 2) {
            return Triple(null, null, 0)
        }

        val values = open.map { end ->

            val t = end.tile

            if (t.vertical) {

                if (end.firstHalf) {
                    countPips(
                        bm,
                        t.x1,
                        t.x2,
                        t.y1,
                        (t.y1 + t.y2) / 2
                    )
                } else {
                    countPips(
                        bm,
                        t.x1,
                        t.x2,
                        (t.y1 + t.y2) / 2,
                        t.y2
                    )
                }

            } else {

                if (end.firstHalf) {
                    countPips(
                        bm,
                        t.x1,
                        (t.x1 + t.x2) / 2,
                        t.y1,
                        t.y2
                    )
                } else {
                    countPips(
                        bm,
                        (t.x1 + t.x2) / 2,
                        t.x2,
                        t.y1,
                        t.y2
                    )
                }
            }
        }

        if (values.any { it !in 0..6 }) {
            return Triple(null, null, 0)
        }

        return Triple(values[0], values[1], 68)
    }

    private fun nearestEndX(
        c: Component,
        x: Float,
        y: Float
    ): Float {
        return if (c.vertical) {
            c.cx
        } else if (x < c.cx) {
            c.x1.toFloat()
        } else {
            c.x2.toFloat()
        }
    }

    private fun nearestEndY(
        c: Component,
        x: Float,
        y: Float
    ): Float {
        return if (!c.vertical) {
            c.cy
        } else if (y < c.cy) {
            c.y1.toFloat()
        } else {
            c.y2.toFloat()
        }
    }

    private fun distance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): Float {

        val dx = x1 - x2
        val dy = y1 - y2

        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Distingue las fichas del fondo azul.
     * Tolera diferentes tonos de gris.
     */
    private fun isTilePixel(c: Int): Boolean {

        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255

        val gray = (r + g + b) / 3

        val chroma =
            max(r, max(g, b)) -
            min(r, min(g, b))

        return gray > 78 && chroma < 105
    }

    /**
     * Cuenta grupos de puntos negros en una mitad de ficha.
     */
    private fun countPips(
        bm: Bitmap,
        x1: Int,
        x2: Int,
        y1: Int,
        y2: Int
    ): Int {

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

        for (y in 0 until sh) {
            for (x in 0 until sw) {

                val idx = y * sw + x

                if (
                    seen[idx] ||
                    !isDark(
                        bm.getPixel(
                            left + x * step,
                            top + y * step
                        )
                    )
                ) continue

                var head = 0
                var tail = 0

                q[tail++] = idx
                seen[idx] = true

                var area = 0
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y

                while (head < tail) {

                    val p = q[head++]

                    val px = p % sw
                    val py = p / sw

                    area++

                    minX = min(minX, px)
                    maxX = max(maxX, px)
                    minY = min(minY, py)
                    maxY = max(maxY, py)

                    val ns = intArrayOf(
                        if (px > 0) p - 1 else -1,
                        if (px + 1 < sw) p + 1 else -1,
                        if (py > 0) p - sw else -1,
                        if (py + 1 < sh) p + sw else -1
                    )

                    for (n in ns) {

                        if (n >= 0 && !seen[n]) {

                            val nx = n % sw
                            val ny = n / sw

                            if (
                                isDark(
                                    bm.getPixel(
                                        left + nx * step,
                                        top + ny * step
                                    )
                                )
                            ) {
                                seen[n] = true
                                q[tail++] = n
                            }
                        }
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1

                if (
                    area >= 2 &&
                    area <= 90 &&
                    bw <= sw * 0.35f &&
                    bh <= sh * 0.35f
                ) {
                    count++
                }
            }
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
