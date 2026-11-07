package com.zeusvision.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ZEUS VISION - motor de percepcion visual doble-seis.
 *
 * Solo realiza identificacion visual. No contiene reglas de juego ni modifica
 * el motor de sugerencias. Mantiene la API usada por CaptureService.
 *
 * Rutas de deteccion que se contrastan:
 *  1) componentes/contornos de superficies neutras (fichas separadas, sobre todo mano),
 *  2) busqueda de lineas divisorias largas (fichas de la mesa que se tocan),
 *  3) conteo de componentes circulares oscuros en cada mitad,
 *  4) comparacion con los nueve puntos de una plantilla 3x3,
 *  5) reconstruccion espacial de las conexiones y estabilizacion temporal.
 *
 * No se asume una posicion fija para cada ficha ni una cadena en linea recta.
 */
object DominoVision {
    data class Tile(val a: Int, val b: Int, val confidence: Int)

    data class BoardTile(
        val a: Int,
        val b: Int,
        val confidence: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val orientation: String,
        val method: String
    )

    data class Analysis(
        val hand: List<Tile>,
        val leftEnd: Int?,
        val rightEnd: Int?,
        val handConfidence: Int,
        val boardConfidence: Int,
        val message: String,
        val boardTiles: List<BoardTile> = emptyList(),
        val diagnostics: String = ""
    ) {
        val boardKnown: Boolean
            get() = leftEnd != null && rightEnd != null && boardConfidence >= 65
    }

    private enum class Split { LEFT_RIGHT, TOP_BOTTOM }
    private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

    private data class RectF(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) {
        val width: Float get() = x2 - x1
        val height: Float get() = y2 - y1
        val cx: Float get() = (x1 + x2) * 0.5f
        val cy: Float get() = (y1 + y2) * 0.5f
        val shortSide: Float get() = min(width, height)
        val longSide: Float get() = max(width, height)
        val vertical: Boolean get() = height > width
    }

    private data class PipRead(val value: Int, val confidence: Int, val blobCount: Int, val gridValue: Int)

    private data class Observation(
        val rect: RectF,
        val a: Int,
        val b: Int,
        val confidence: Int,
        val split: Split,
        val method: String
    ) {
        val vertical: Boolean get() = split == Split.TOP_BOTTOM
        val width: Int get() = rect.width.toInt().coerceAtLeast(1)
        val height: Int get() = rect.height.toInt().coerceAtLeast(1)
        fun publicBoardTile() = BoardTile(
            a, b, confidence, rect.x1.toInt(), rect.y1.toInt(), width, height,
            if (vertical) "VERTICAL" else "HORIZONTAL", method
        )
    }

    private data class LineHit(val horizontal: Boolean, val coordinate: Float, val start: Float, val end: Float) {
        val length: Float get() = end - start + 1f
    }

    private data class ComponentBox(val rect: RectF, val area: Int, val coverage: Float)

    private var previousHandSignature: String = ""
    private var previousBoardSignature: String = ""
    private var handRepeatCount: Int = 0
    private var boardRepeatCount: Int = 0

    @Synchronized
    fun analyze(bitmap: Bitmap): Analysis {
        if (bitmap.isRecycled || bitmap.width < 80 || bitmap.height < 120) {
            return Analysis(emptyList(), null, null, 0, 0,
                "CAPTURA NO VALIDA · ESPERANDO IMAGEN", diagnostics = "bitmap invalido")
        }

        val frame = Frame(bitmap)
        val componentBoxes = findNeutralComponents(frame)
        val componentObservations = componentBoxes.mapNotNull { box ->
            recognizeRect(frame, box.rect, if (box.rect.vertical) Split.TOP_BOTTOM else Split.LEFT_RIGHT,
                "CONTORNO", box.coverage)
        }

        val lineObservations = findDividerTiles(frame)
        val splitY = frame.h * HAND_ZONE_START
        val handFromContours = componentObservations.filter { it.rect.cy >= splitY }
        // Los contornos son mas fiables para las cartas separadas de la mano.
        // El detector de divisores queda como respaldo cuando la mano esta solapada.
        val handCandidates = if (handFromContours.isNotEmpty()) handFromContours
            else lineObservations.filter { it.rect.cy >= splitY }
        val boardCandidates = mergeSpatialDuplicates((componentObservations + lineObservations).filter {
            it.rect.cy < splitY && it.rect.cy >= frame.h * BOARD_ZONE_TOP && it.rect.cy <= frame.h * BOARD_ZONE_BOTTOM
        })

        val handObservations = selectHand(handCandidates, frame)
        val boardObservations = selectBoard(boardCandidates)
        val chain = reconstructChain(boardObservations)

        val rawHand = handObservations.map { Tile(it.a, it.b, it.confidence) }
        val handSig = rawHand.joinToString("|") { orderedKey(it.a, it.b) }
        val boardSig = boardObservations.sortedBy { it.rect.cy * frame.w + it.rect.cx }
            .joinToString("|") { orderedKey(it.a, it.b) + "@" + it.rect.cx.toInt() + "," + it.rect.cy.toInt() }
        handRepeatCount = if (handSig.isNotEmpty() && handSig == previousHandSignature) (handRepeatCount + 1).coerceAtMost(4) else 0
        boardRepeatCount = if (boardSig.isNotEmpty() && boardSig == previousBoardSignature) (boardRepeatCount + 1).coerceAtMost(4) else 0
        previousHandSignature = handSig
        previousBoardSignature = boardSig

        val handBoost = min(6, handRepeatCount * 2)
        val boardBoost = min(6, boardRepeatCount * 2)
        val hand = handObservations.map {
            it.copy(confidence = (it.confidence + handBoost).coerceAtMost(98))
        }.map { Tile(it.a, it.b, it.confidence) }
        val handConfidence = when {
            handObservations.isEmpty() -> 0
            handObservations.size <= 7 -> (handObservations.map { it.confidence }.average().toInt() + handBoost).coerceAtMost(96)
            else -> 45
        }

        val left = chain.endValues?.getOrNull(0)
        val right = chain.endValues?.getOrNull(1)
        val boardConfidence = if (chain.endValues != null) (chain.confidence + boardBoost).coerceAtMost(97) else 0
        val boardTiles = boardObservations.map { it.publicBoardTile() }
        val message = buildString {
            append(if (hand.isEmpty()) "NO DISTINGO TU MANO" else "MANO ${hand.size}: " + hand.joinToString { "${it.a}-${it.b}" })
            append(" · ")
            if (left != null && right != null) append("MESA ${boardTiles.size} FICHAS · EXTREMOS $left/$right")
            else append("NO PUEDO CONFIRMAR LA MESA")
        }
        val diagnostics = buildString {
            append("contornos=").append(componentObservations.size)
            append("; separadores=").append(lineObservations.size)
            append("; mano=").append(handObservations.size)
            append("; mesa=").append(boardObservations.size)
            append("; enlaces=").append(chain.links)
            append("; extremos=").append(chain.endValues?.joinToString("-") ?: "?")
        }

        return Analysis(
            hand = hand,
            leftEnd = left,
            rightEnd = right,
            handConfidence = handConfidence,
            boardConfidence = boardConfidence,
            message = message,
            boardTiles = boardTiles,
            diagnostics = diagnostics
        )
    }

    // ---------------------------------------------------------------------
    // 1) Superficies neutrales y componentes: recupera fichas que no se tocan.
    // ---------------------------------------------------------------------

    private fun findNeutralComponents(frame: Frame): List<ComponentBox> {
        val scale = 2
        val sw = (frame.w + scale - 1) / scale
        val sh = (frame.h + scale - 1) / scale
        val size = sw * sh
        val seen = BooleanArray(size)
        val queue = IntArray(size)
        val result = mutableListOf<ComponentBox>()
        val minShort = max(18f, min(frame.w, frame.h) * 0.035f)
        val maxShort = min(frame.w, frame.h) * 0.25f

        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                val start = sy * sw + sx
                if (seen[start]) continue
                val px = (sx * scale).coerceAtMost(frame.w - 1)
                val py = (sy * scale).coerceAtMost(frame.h - 1)
                if (!frame.isTileSurface(px, py)) {
                    seen[start] = true
                    continue
                }

                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var area = 0

                while (head < tail) {
                    val p = queue[head++]
                    val x = p % sw
                    val y = p / sw
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y

                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until sw || ny !in 0 until sh) continue
                        val ni = ny * sw + nx
                        if (seen[ni]) continue
                        val npx = (nx * scale).coerceAtMost(frame.w - 1)
                        val npy = (ny * scale).coerceAtMost(frame.h - 1)
                        if (frame.isTileSurface(npx, npy)) {
                            seen[ni] = true
                            queue[tail++] = ni
                        } else {
                            seen[ni] = true
                        }
                    }
                }

                if (area < 180) continue
                val x1 = minX * scale.toFloat()
                val y1 = minY * scale.toFloat()
                val x2 = min(frame.w, (maxX + 1) * scale).toFloat()
                val y2 = min(frame.h, (maxY + 1) * scale).toFloat()
                val rect = RectF(x1, y1, x2, y2)
                val short = rect.shortSide
                val ratio = rect.longSide / max(1f, short)
                if (short < minShort || short > maxShort || ratio !in 1.60f..2.70f) continue
                if (rect.cy < frame.h * 0.70f) continue
                val coverage = area.toFloat() / max(1f, (rect.width / scale) * (rect.height / scale))
                if (coverage < 0.40f) continue
                result.add(ComponentBox(rect, area, coverage.coerceIn(0f, 1f)))
            }
        }
        return result
    }

    // ---------------------------------------------------------------------
    // 2) Busqueda global de separadores: divide fichas contiguas sin necesitar
    //    que el color de una ficha este desconectado del de su vecina.
    // ---------------------------------------------------------------------

    private fun findDividerTiles(frame: Frame): List<Observation> {
        val minDimension = min(frame.w, frame.h).toFloat()
        // Los puntos individuales miden mucho menos que una division central real.
        // El 6% filtra rachas de puntos/texto sin perder las divisiones de las fichas del juego.
        val minLine = max(28f, minDimension * 0.06f)
        val maxLine = min(frame.w * 0.22f, minDimension * 0.24f)
        val raw = ArrayList<LineHit>(96)
        raw.addAll(scanRuns(frame, horizontal = true, minLine = minLine, maxLine = maxLine))
        raw.addAll(scanRuns(frame, horizontal = false, minLine = minLine, maxLine = maxLine))
        val merged = mergeLineHits(raw)
        val result = ArrayList<Observation>(merged.size)

        for (line in merged) {
            val length = line.length
            if (length < minLine || length > maxLine) continue
            // El trazo interior suele ocupar aproximadamente el 88-92% del lado corto.
            val short = length * 1.13f
            val rect: RectF
            val split: Split
            if (line.horizontal) {
                val cx = (line.start + line.end) * 0.5f
                val cy = line.coordinate
                rect = RectF(cx - short * 0.5f, cy - short, cx + short * 0.5f, cy + short)
                split = Split.TOP_BOTTOM
            } else {
                val cx = line.coordinate
                val cy = (line.start + line.end) * 0.5f
                rect = RectF(cx - short, cy - short * 0.5f, cx + short, cy + short * 0.5f)
                split = Split.LEFT_RIGHT
            }
            if (rect.x1 < 0f || rect.y1 < 0f || rect.x2 > frame.w || rect.y2 > frame.h) continue
            val surface = tileSurfaceScore(frame, rect)
            if (surface < 0.68f) continue
            val observation = recognizeRect(frame, rect, split, "SEPARADOR", surface) ?: continue
            // Descarta lineas de botones, texto o adornos que no formen dos mitades de ficha.
            if (observation.confidence >= 58) result.add(observation)
        }
        return result
    }

    private fun scanRuns(frame: Frame, horizontal: Boolean, minLine: Float, maxLine: Float): List<LineHit> {
        val hits = ArrayList<LineHit>()
        val outer = if (horizontal) frame.h else frame.w
        val inner = if (horizontal) frame.w else frame.h
        val low = 145
        for (coord in 1 until outer - 1) {
            var p = 0
            while (p < inner) {
                val g = if (horizontal) frame.grayAt(p, coord) else frame.grayAt(coord, p)
                if (g >= low) {
                    p++
                    continue
                }
                val start = p
                while (p < inner) {
                    val value = if (horizontal) frame.grayAt(p, coord) else frame.grayAt(coord, p)
                    if (value >= low) break
                    p++
                }
                val len = p - start
                if (len >= minLine && len <= maxLine) {
                    hits.add(LineHit(horizontal, coord.toFloat(), start.toFloat(), (p - 1).toFloat()))
                }
            }
        }
        return hits
    }

    private fun mergeLineHits(source: List<LineHit>): List<LineHit> {
        if (source.isEmpty()) return emptyList()
        val sorted = source.sortedWith(compareBy<LineHit> { if (it.horizontal) 0 else 1 }
            .thenBy { it.coordinate }.thenBy { it.start })
        data class Group(var horizontal: Boolean, var coordinateSum: Float, var coordinateCount: Int,
                         var start: Float, var end: Float)
        val groups = mutableListOf<Group>()
        for (hit in sorted) {
            val group = groups.lastOrNull { g ->
                g.horizontal == hit.horizontal && abs(g.coordinateSum / g.coordinateCount - hit.coordinate) <= 5f &&
                    min(g.end, hit.end) - max(g.start, hit.start) >= min(g.end - g.start + 1f, hit.length) * 0.55f
            }
            if (group == null) {
                groups.add(Group(hit.horizontal, hit.coordinate, 1, hit.start, hit.end))
            } else {
                group.coordinateSum += hit.coordinate
                group.coordinateCount++
                group.start = min(group.start, hit.start)
                group.end = max(group.end, hit.end)
            }
        }
        return groups.map {
            LineHit(it.horizontal, it.coordinateSum / it.coordinateCount, it.start, it.end)
        }
    }

    // ---------------------------------------------------------------------
    // 3) Lectura de las dos mitades. Consenso entre puntos y plantilla 3x3.
    // ---------------------------------------------------------------------

    private fun recognizeRect(
        frame: Frame,
        rect: RectF,
        split: Split,
        method: String,
        surfaceScore: Float
    ): Observation? {
        val short = rect.shortSide
        if (short < 18f || rect.longSide / short !in 1.35f..2.75f) return null
        val inset = max(3f, short * 0.075f)
        val gap = max(3f, short * 0.075f)
        val halves = if (split == Split.TOP_BOTTOM) {
            listOf(
                RectF(rect.x1 + inset, rect.y1 + inset, rect.x2 - inset, rect.cy - gap),
                RectF(rect.x1 + inset, rect.cy + gap, rect.x2 - inset, rect.y2 - inset)
            )
        } else {
            listOf(
                RectF(rect.x1 + inset, rect.y1 + inset, rect.cx - gap, rect.y2 - inset),
                RectF(rect.cx + gap, rect.y1 + inset, rect.x2 - inset, rect.y2 - inset)
            )
        }
        if (halves.any { it.width < 8f || it.height < 8f }) return null
        val first = readPips(frame, halves[0], short)
        val second = readPips(frame, halves[1], short)
        if (first.value !in 0..6 || second.value !in 0..6) return null
        val pipConfidence = min(first.confidence, second.confidence)
        val confidence = (surfaceScore * 36f + pipConfidence * 0.64f).toInt().coerceIn(0, 97)
        if (confidence < 54) return null
        return Observation(rect, first.value, second.value, confidence, split, method)
    }

    private fun readPips(frame: Frame, rect: RectF, tileShort: Float): PipRead {
        val left = rect.x1.toInt().coerceIn(0, frame.w - 1)
        val top = rect.y1.toInt().coerceIn(0, frame.h - 1)
        val right = rect.x2.toInt().coerceIn(left + 1, frame.w)
        val bottom = rect.y2.toInt().coerceIn(top + 1, frame.h)
        val step = 2
        val sw = ((right - left + step - 1) / step).coerceAtLeast(1)
        val sh = ((bottom - top + step - 1) / step).coerceAtLeast(1)
        val visited = BooleanArray(sw * sh)
        val queue = IntArray(sw * sh)
        val centersX = ArrayList<Float>(6)
        val centersY = ArrayList<Float>(6)
        val minBlob = max(2, (tileShort * 0.055f / step).toInt())
        val maxBlob = max(minBlob + 2, (tileShort * 0.36f / step).toInt())
        val maxArea = max(12, (tileShort * tileShort * 0.075f / (step * step)).toInt())

        for (sy in 0 until sh) for (sx in 0 until sw) {
            val start = sy * sw + sx
            if (visited[start]) continue
            val px = (left + sx * step).coerceAtMost(frame.w - 1)
            val py = (top + sy * step).coerceAtMost(frame.h - 1)
            if (frame.grayAt(px, py) >= 105) {
                visited[start] = true
                continue
            }
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var area = 0
            var minX = sx
            var maxX = sx
            var minY = sy
            var maxY = sy
            var sumX = 0f
            var sumY = 0f
            while (head < tail) {
                val p = queue[head++]
                val x = p % sw
                val y = p / sw
                area++
                sumX += x
                sumY += y
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until sw || ny !in 0 until sh) continue
                    val ni = ny * sw + nx
                    if (visited[ni]) continue
                    val npx = (left + nx * step).coerceAtMost(frame.w - 1)
                    val npy = (top + ny * step).coerceAtMost(frame.h - 1)
                    if (frame.grayAt(npx, npy) < 105) {
                        visited[ni] = true
                        queue[tail++] = ni
                    } else {
                        visited[ni] = true
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / max(1, bh)
            if (area in 3..maxArea && bw in minBlob..maxBlob && bh in minBlob..maxBlob && aspect in 0.48f..2.05f) {
                centersX.add(sumX / area / max(1, sw - 1))
                centersY.add(sumY / area / max(1, sh - 1))
            }
        }

        val blobCount = centersX.size
        val grid = readGridPattern(frame, rect)
        val blobConfidence = when {
            blobCount == 0 -> 84
            blobCount in 1..6 -> 90
            else -> 35
        }

        val chosen: Int
        val confidence: Int
        when {
            blobCount in 0..6 && grid.value == blobCount && grid.confidence >= 60 -> {
                chosen = blobCount
                confidence = min(97, (blobConfidence + grid.confidence) / 2 + 5)
            }
            // Si los circulos estan bien separados, su conteo prevalece sobre una
            // plantilla debil: los seis puntos pueden producir una plantilla ambigua.
            blobCount in 1..6 -> {
                chosen = blobCount
                confidence = if (grid.value == blobCount) 91 else 76
            }
            blobCount == 0 && grid.value > 0 && grid.confidence >= 82 -> {
                chosen = grid.value
                confidence = grid.confidence - 4
            }
            blobCount == 0 -> {
                chosen = 0
                confidence = 84
            }
            grid.confidence >= 62 -> {
                chosen = grid.value
                confidence = grid.confidence
            }
            else -> {
                chosen = -1
                confidence = 0
            }
        }
        return PipRead(chosen, confidence, blobCount, grid.value)
    }

    private data class GridRead(val value: Int, val confidence: Int)

    private fun readGridPattern(frame: Frame, rect: RectF): GridRead {
        val l = rect.x1.toInt().coerceIn(0, frame.w - 1)
        val t = rect.y1.toInt().coerceIn(0, frame.h - 1)
        val r = rect.x2.toInt().coerceIn(l + 1, frame.w)
        val b = rect.y2.toInt().coerceIn(t + 1, frame.h)
        val cellW = (r - l) / 4f
        val cellH = (b - t) / 4f
        val radiusX = max(2, (cellW * 0.36f).toInt())
        val radiusY = max(2, (cellH * 0.36f).toInt())
        val darkness = FloatArray(9)
        var idx = 0
        for (row in 0..2) for (col in 0..2) {
            val cx = l + (col + 1f) * cellW
            val cy = t + (row + 1f) * cellH
            var dark = 0
            var total = 0
            for (yy in (cy.toInt() - radiusY)..(cy.toInt() + radiusY)) {
                if (yy !in t until b) continue
                for (xx in (cx.toInt() - radiusX)..(cx.toInt() + radiusX)) {
                    if (xx !in l until r) continue
                    if (frame.grayAt(xx, yy) < 105) dark++
                    total++
                }
            }
            darkness[idx++] = if (total == 0) 0f else dark.toFloat() / total
        }

        val patterns = listOf(
            0 to listOf(emptySet<Int>()),
            1 to listOf(setOf(4)),
            2 to listOf(setOf(0, 8), setOf(2, 6)),
            3 to listOf(setOf(0, 4, 8), setOf(2, 4, 6)),
            4 to listOf(setOf(0, 2, 6, 8)),
            5 to listOf(setOf(0, 2, 4, 6, 8)),
            6 to listOf(setOf(0, 3, 6, 2, 5, 8))
        )
        var bestValue = 0
        var bestScore = -1f
        var secondScore = -1f
        for ((value, alternatives) in patterns) {
            for (pattern in alternatives) {
                val score = if (pattern.isEmpty()) {
                    1f - darkness.maxOrNull()!!
                } else {
                    val on = pattern.map { darkness[it] }.average().toFloat()
                    val off = darkness.indices.filter { it !in pattern }.map { darkness[it] }.average().toFloat()
                    on - off * 0.72f
                }
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestValue = value
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
        }
        val confidence = when {
            bestValue == 0 && darkness.maxOrNull()!! < 0.10f -> 90
            bestScore >= 0.34f && bestScore - secondScore >= 0.035f -> 88
            bestScore >= 0.23f -> 74
            bestScore >= 0.14f -> 60
            else -> 35
        }
        return GridRead(bestValue, confidence)
    }

    private fun tileSurfaceScore(frame: Frame, rect: RectF): Float {
        var neutral = 0
        var total = 0
        val marginX = max(2f, rect.width * 0.07f)
        val marginY = max(2f, rect.height * 0.07f)
        for (iy in 0..6) for (ix in 0..6) {
            val x = (rect.x1 + marginX + (rect.width - 2f * marginX) * ix / 6f).toInt().coerceIn(0, frame.w - 1)
            val y = (rect.y1 + marginY + (rect.height - 2f * marginY) * iy / 6f).toInt().coerceIn(0, frame.h - 1)
            if (frame.isTileSurface(x, y)) neutral++
            total++
        }
        return if (total == 0) 0f else neutral.toFloat() / total
    }

    // ---------------------------------------------------------------------
    // 4) Fusion, eliminacion de duplicados y clasificacion mano/mesa.
    // ---------------------------------------------------------------------

    private fun mergeSpatialDuplicates(source: List<Observation>): List<Observation> {
        val sorted = source.sortedByDescending { it.confidence }
        val kept = mutableListOf<Observation>()
        for (candidate in sorted) {
            val duplicate = kept.any { existing -> intersectionOverUnion(existing.rect, candidate.rect) >= 0.48f }
            if (!duplicate) kept.add(candidate)
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val iw = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val ih = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val intersection = iw * ih
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun selectHand(candidates: List<Observation>, frame: Frame): List<Observation> {
        if (candidates.isEmpty()) return emptyList()
        val likely = candidates.filter { it.rect.cy >= frame.h * HAND_ZONE_START }
            .sortedWith(compareBy<Observation> { it.rect.cx }.thenByDescending { it.confidence })
        val spatial = mutableListOf<Observation>()
        for (item in likely) {
            if (spatial.none { intersectionOverUnion(it.rect, item.rect) >= 0.40f }) spatial.add(item)
        }
        // La mano de una partida avanzada puede tener una sola ficha: eso no es fallo.
        return spatial.sortedBy { it.rect.cx }.take(7)
    }

    private fun selectBoard(candidates: List<Observation>): List<Observation> {
        if (candidates.isEmpty()) return emptyList()
        val spatial = mutableListOf<Observation>()
        for (item in candidates.sortedByDescending { it.confidence }) {
            if (spatial.none { intersectionOverUnion(it.rect, item.rect) >= 0.48f }) spatial.add(item)
        }
        return spatial.sortedWith(compareBy<Observation> { it.rect.cy }.thenBy { it.rect.cx })
    }

    // ---------------------------------------------------------------------
    // 5) Reconstruccion de cadena por contacto geometrico y coincidencia de
    //    valor. Las esquinas y giros se resuelven con los cuatro bordes reales.
    // ---------------------------------------------------------------------

    private data class ChainResult(val endValues: List<Int>?, val confidence: Int, val links: Int)
    private data class Contact(val halfA: Int, val halfB: Int, val score: Float)
    private data class FreeEnd(val value: Int, val x: Float, val y: Float)

    private fun reconstructChain(tiles: List<Observation>): ChainResult {
        if (tiles.isEmpty()) return ChainResult(null, 0, 0)
        if (tiles.size == 1) {
            val t = tiles[0]
            return ChainResult(listOf(t.a, t.b), (t.confidence - 4).coerceAtLeast(0), 0)
        }
        val connected = Array(tiles.size) { BooleanArray(2) }
        val adjacency = Array(tiles.size) { mutableSetOf<Int>() }
        var links = 0
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            val contact = findContact(tiles[i], tiles[j]) ?: continue
            connected[i][contact.halfA] = true
            connected[j][contact.halfB] = true
            adjacency[i].add(j)
            adjacency[j].add(i)
            links++
        }
        if (links == 0) return ChainResult(null, 0, 0)

        val components = mutableListOf<List<Int>>()
        val seen = BooleanArray(tiles.size)
        for (i in tiles.indices) {
            if (seen[i] || adjacency[i].isEmpty()) continue
            val q = IntArray(tiles.size)
            var head = 0
            var tail = 0
            q[tail++] = i
            seen[i] = true
            val part = mutableListOf<Int>()
            while (head < tail) {
                val at = q[head++]
                part.add(at)
                for (n in adjacency[at]) if (!seen[n]) {
                    seen[n] = true
                    q[tail++] = n
                }
            }
            components.add(part)
        }
        val largest = components.maxByOrNull { it.size } ?: return ChainResult(null, 0, links)
        if (largest.size < 2) return ChainResult(null, 0, links)
        if (largest.any { adjacency[it].size > 2 }) return ChainResult(null, 0, links)
        // Si se han detectado fichas en varias islas parecidas, no adivinar cual es la mesa.
        if (largest.size < tiles.size && tiles.size - largest.size > 1) return ChainResult(null, 0, links)

        val freeEnds = mutableListOf<FreeEnd>()
        for (index in largest) {
            val t = tiles[index]
            if (!connected[index][0]) {
                val point = endpointPoint(t, 0)
                freeEnds.add(FreeEnd(t.a, point.first, point.second))
            }
            if (!connected[index][1]) {
                val point = endpointPoint(t, 1)
                freeEnds.add(FreeEnd(t.b, point.first, point.second))
            }
        }
        if (freeEnds.size != 2) return ChainResult(null, 0, links)

        val confidence = largest.map { tiles[it].confidence }.average().toInt()
        // Se conserva una orientacion espacial estable para IZQUIERDA/DERECHA.
        val values = freeEnds.sortedWith(compareBy<FreeEnd> { it.x }.thenBy { it.y }).map { it.value }
        return ChainResult(values, confidence, links)
    }

    private fun findContact(a: Observation, b: Observation): Contact? {
        val minShort = min(a.rect.shortSide, b.rect.shortSide)
        val tolerance = max(4f, minShort * 0.24f)
        var best: Contact? = null

        fun tryPair(edgeA: Edge, edgeB: Edge, gap: Float, overlap: Float, overlapStart: Float, overlapEnd: Float) {
            if (abs(gap) > tolerance || overlap < minShort * 0.54f) return
            val coordinate = (overlapStart + overlapEnd) * 0.5f
            val halfA = halfAtEdge(a, edgeA, coordinate)
            val halfB = halfAtEdge(b, edgeB, coordinate)
            val valueA = if (halfA == 0) a.a else a.b
            val valueB = if (halfB == 0) b.a else b.b
            if (valueA != valueB) return
            val score = overlap - abs(gap) * 1.4f
            if (best == null || score > best!!.score) best = Contact(halfA, halfB, score)
        }

        val overlapY = min(a.rect.y2, b.rect.y2) - max(a.rect.y1, b.rect.y1)
        val startY = max(a.rect.y1, b.rect.y1)
        val endY = min(a.rect.y2, b.rect.y2)
        tryPair(Edge.RIGHT, Edge.LEFT, b.rect.x1 - a.rect.x2, overlapY, startY, endY)
        tryPair(Edge.LEFT, Edge.RIGHT, a.rect.x1 - b.rect.x2, overlapY, startY, endY)

        val overlapX = min(a.rect.x2, b.rect.x2) - max(a.rect.x1, b.rect.x1)
        val startX = max(a.rect.x1, b.rect.x1)
        val endX = min(a.rect.x2, b.rect.x2)
        tryPair(Edge.BOTTOM, Edge.TOP, b.rect.y1 - a.rect.y2, overlapX, startX, endX)
        tryPair(Edge.TOP, Edge.BOTTOM, a.rect.y1 - b.rect.y2, overlapX, startX, endX)
        return best
    }

    private fun endpointPoint(tile: Observation, half: Int): Pair<Float, Float> {
        return if (tile.split == Split.LEFT_RIGHT) {
            Pair(if (half == 0) tile.rect.x1 else tile.rect.x2, tile.rect.cy)
        } else {
            Pair(tile.rect.cx, if (half == 0) tile.rect.y1 else tile.rect.y2)
        }
    }

    private fun halfAtEdge(tile: Observation, edge: Edge, coordinate: Float): Int {
        return when (tile.split) {
            Split.LEFT_RIGHT -> when (edge) {
                Edge.LEFT -> 0
                Edge.RIGHT -> 1
                Edge.TOP, Edge.BOTTOM -> if (coordinate < tile.rect.cx) 0 else 1
            }
            Split.TOP_BOTTOM -> when (edge) {
                Edge.TOP -> 0
                Edge.BOTTOM -> 1
                Edge.LEFT, Edge.RIGHT -> if (coordinate < tile.rect.cy) 0 else 1
            }
        }
    }

    private fun orderedKey(a: Int, b: Int): String = if (a <= b) "$a-$b" else "$b-$a"

    private class Frame(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        private val pixels = IntArray(w * h)
        private val gray = ByteArray(w * h)

        init {
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c ushr 16) and 255
                val g = (c ushr 8) and 255
                val b = c and 255
                gray[i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
            }
        }

        fun grayAt(x: Int, y: Int): Int = gray[y * w + x].toInt() and 255

        fun isTileSurface(x: Int, y: Int): Boolean {
            val i = y * w + x
            val c = pixels[i]
            val r = (c ushr 16) and 255
            val g = (c ushr 8) and 255
            val b = c and 255
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            return grayAt(x, y) > 78 && chroma < 108
        }
    }

    private const val HAND_ZONE_START = 0.80f
    private const val BOARD_ZONE_TOP = 0.10f
    private const val BOARD_ZONE_BOTTOM = 0.80f
}
