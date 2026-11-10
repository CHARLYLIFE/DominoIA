package com.zeusvision.app

import java.util.LinkedHashMap
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/** Inferencia exacta del conjunto de manos rivales compatibles con los datos publicos. */
object DominoOpponentInference {
    data class EnemyPlay(val tileId: Int, val leftBefore: Int, val rightBefore: Int)
    data class PassConstraint(val left: Int, val right: Int, val enemyPlaysBefore: Int)

    data class Request(
        val knownHandMask: Int,
        val playedMask: Int,
        val unknownMask: Int,
        val opponentCount: Int,
        val enemyPlays: List<EnemyPlay> = emptyList(),
        val passConstraints: List<PassConstraint> = emptyList(),
        val useActionLikelihood: Boolean = true
    )

    class Posterior internal constructor(
        val hands: IntArray,
        private val cumulativeWeights: DoubleArray,
        private val normalizedWeights: DoubleArray,
        val tileProbabilities: DoubleArray,
        val unconstrainedHandCount: Long
    ) {
        val compatibleHandCount: Int get() = hands.size
        val totalWeight: Double get() = cumulativeWeights.lastOrNull() ?: 0.0
        val coverage: Double get() = if (unconstrainedHandCount == 0L) 0.0
            else compatibleHandCount.toDouble() / unconstrainedHandCount.toDouble()
        /** Entropia del posterior en bits: alta cuando aun hay mucha incertidumbre. */
        val entropyBits: Double get() = normalizedWeights.sumOf { w ->
            if (w <= 0.0) 0.0 else -w * (ln(w) / ln(2.0))
        }
        /** Tamano efectivo de la muestra ponderada; detecta pesos excesivamente concentrados. */
        val effectiveSampleSize: Double get() = 1.0 / normalizedWeights.sumOf { it * it }.coerceAtLeast(1e-15)
        val maxTileProbability: Double get() = tileProbabilities.maxOrNull() ?: 0.0

        fun sample(random: Random): Int {
            require(hands.isNotEmpty()) { "posterior vacio" }
            val target = random.nextDouble() * totalWeight
            var low = 0
            var high = cumulativeWeights.lastIndex
            while (low < high) {
                val mid = (low + high) ushr 1
                if (cumulativeWeights[mid] < target) low = mid + 1 else high = mid
            }
            return hands[low]
        }

        fun weightAt(index: Int): Double = normalizedWeights[index]

        fun topTileIds(limit: Int = 5): List<Pair<Int, Double>> =
            tileProbabilities.indices.map { it to tileProbabilities[it] }
                .sortedByDescending { it.second }.take(limit)
    }

    private const val TILE_COUNT = 28
    private const val MAX_ENUMERATED_HANDS = 200_000L
    private val a = IntArray(TILE_COUNT)
    private val b = IntArray(TILE_COUNT)
    private val idByPair = Array(7) { IntArray(7) { -1 } }
    // Dos posteriors evitan repetir una enumeracion cara, sin retener varias
    // manos iniciales gigantes en memoria en telefonos con RAM limitada.
    private val posteriorCache = object : LinkedHashMap<Request, Posterior>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Request, Posterior>?): Boolean = size > 2
    }

    init {
        var id = 0
        for (x in 0..6) for (y in x..6) {
            a[id] = x; b[id] = y
            idByPair[x][y] = id; idByPair[y][x] = id
            id++
        }
        check(id == TILE_COUNT)
    }

    /**
     * Posterior bayesiano sobre combinaciones completas: prior uniforme sobre
     * repartos legales, restriccion por pases/jugadas observadas y likelihood
     * suave de seleccion del rival mezclada con una politica uniforme. Se
     * reconstruye desde el historial publico completo para evitar deriva de
     * actualizaciones incrementales duplicadas.
     */
    @Synchronized
    fun infer(request: Request): Posterior? {
        posteriorCache[request]?.let { return it }
        val result = inferUncached(request) ?: return null
        posteriorCache[request] = result
        return result
    }

    @Synchronized
    fun clearCache() { posteriorCache.clear() }

    private fun inferUncached(request: Request): Posterior? {
        if (request.opponentCount !in 0..7) return null
        // Solo se admiten los 28 bits del doble-seis y una particion completa,
        // disjunta: mano propia + mesa + fichas ocultas = universo total.
        val allMask = (1 shl TILE_COUNT) - 1
        val combinedMask = request.knownHandMask or request.playedMask or request.unknownMask
        if (combinedMask and allMask.inv() != 0) return null
        if (request.knownHandMask and request.playedMask != 0) return null
        if (request.knownHandMask and request.unknownMask != 0) return null
        if (request.playedMask and request.unknownMask != 0) return null
        if (combinedMask != allMask) return null
        if (Integer.bitCount(request.unknownMask) != 14 + request.opponentCount) return null
        if (request.passConstraints.any {
                it.left !in 0..6 || it.right !in 0..6 || it.enemyPlaysBefore !in 0..request.enemyPlays.size
            }) return null
        if (request.enemyPlays.any {
                it.tileId !in 0 until TILE_COUNT ||
                    (request.playedMask and (1 shl it.tileId)) == 0 ||
                    !validHistoricalEnds(it.leftBefore, it.rightBefore)
            }) return null
        if (request.enemyPlays.map { it.tileId }.distinct().size != request.enemyPlays.size) return null

        val ids = IntArray(Integer.bitCount(request.unknownMask))
        var p = 0
        for (id in 0 until TILE_COUNT) if ((request.unknownMask and (1 shl id)) != 0) ids[p++] = id
        val theoretical = choose(ids.size, request.opponentCount)
        if (theoretical <= 0L || theoretical > MAX_ENUMERATED_HANDS) return null

        val maxSize = theoretical.toInt()
        val hands = IntArray(maxSize)
        val logWeights = DoubleArray(maxSize)
        var used = 0

        fun visit(start: Int, remaining: Int, mask: Int) {
            if (remaining == 0) {
                if (!satisfiesPasses(mask, request)) return
                // La legalidad de una ficha observada es evidencia dura, aunque
                // se desactive el peso conductual suave para pruebas/ablation.
                if (request.enemyPlays.isNotEmpty() && !satisfiesObservedPlays(mask, request)) return
                hands[used] = mask
                logWeights[used] = if (request.useActionLikelihood && request.enemyPlays.isNotEmpty())
                    actionLogLikelihood(mask, request) else 0.0
                used++
                return
            }
            val lastStart = ids.size - remaining
            for (i in start..lastStart) visit(i + 1, remaining - 1, mask or (1 shl ids[i]))
        }
        visit(0, request.opponentCount, 0)
        if (used == 0) return null

        val maxLog = (0 until used).maxOf { logWeights[it] }
        val rawWeights = DoubleArray(used) {
            exp((logWeights[it] - maxLog).coerceIn(-24.0, 0.0)).coerceAtLeast(1e-12)
        }
        val total = rawWeights.sum()
        if (!total.isFinite() || total <= 0.0) return null
        val cumulative = DoubleArray(used)
        val normalized = DoubleArray(used)
        val tileMass = DoubleArray(TILE_COUNT)
        var running = 0.0
        for (i in 0 until used) {
            normalized[i] = rawWeights[i] / total
            running += rawWeights[i]
            cumulative[i] = running
            val mask = hands[i]
            for (id in 0 until TILE_COUNT) if ((mask and (1 shl id)) != 0) tileMass[id] += normalized[i]
        }
        return Posterior(hands.copyOf(used), cumulative, normalized, tileMass, theoretical)
    }

    private fun satisfiesPasses(candidate: Int, r: Request): Boolean {
        for (constraint in r.passConstraints) {
            var handAtPass = candidate
            for (i in constraint.enemyPlaysBefore until r.enemyPlays.size) {
                val bit = 1 shl r.enemyPlays[i].tileId
                if (handAtPass and bit != 0) return false
                handAtPass = handAtPass or bit
            }
            if (hasLegalMove(handAtPass, constraint.left, constraint.right)) return false
        }
        return true
    }

    private fun satisfiesObservedPlays(candidate: Int, r: Request): Boolean {
        for (index in r.enemyPlays.indices) {
            val event = r.enemyPlays[index]
            val historicalHand = handAtEnemyEvent(candidate, r.enemyPlays, index) ?: return false
            if (event.tileId !in 0 until TILE_COUNT ||
                (historicalHand and (1 shl event.tileId)) == 0) return false
            if (event.leftBefore == -1 || event.rightBefore == -1) continue
            if (!connects(event.tileId, event.leftBefore, event.rightBefore)) return false
        }
        return true
    }

    private fun actionLogLikelihood(candidate: Int, r: Request): Double {
        var logLikelihood = 0.0
        for (index in r.enemyPlays.indices) {
            val event = r.enemyPlays[index]
            val hand = handAtEnemyEvent(candidate, r.enemyPlays, index) ?: return -1e6
            val actions = legalActions(hand, event.leftBefore, event.rightBefore)
            if (actions.isEmpty()) return -1e6
            val chosen = actions.filter { it.first == event.tileId }
            if (chosen.isEmpty()) return -1e6
            val scores = DoubleArray(actions.size) { i -> actionScore(hand, actions[i].first, actions[i].second,
                event.leftBefore, event.rightBefore) }
            val maxScore = scores.maxOrNull() ?: 0.0
            val probabilities = DoubleArray(actions.size) { i -> exp(((scores[i] - maxScore) / 2.4).coerceIn(-12.0, 0.0)) }
            val denominator = probabilities.sum().coerceAtLeast(1e-12)
            val policyProbability = actions.indices.filter { actions[it].first == event.tileId }
                .sumOf { probabilities[it] } / denominator
            val uniformProbability = chosen.size.toDouble() / actions.size
            // La decision humana aporta evidencia blanda, nunca una prohibicion:
            // mantenemos un prior uniforme dominante para no sobreajustar una jugada.
            val blended = (0.30 * policyProbability + 0.70 * uniformProbability).coerceIn(1e-7, 1.0)
            logLikelihood += ln(blended)
        }
        return logLikelihood
    }

    private fun handAtEnemyEvent(candidate: Int, events: List<EnemyPlay>, index: Int): Int? {
        var hand = candidate
        for (i in index until events.size) {
            val bit = 1 shl events[i].tileId
            if (hand and bit != 0) return null
            hand = hand or bit
        }
        return hand
    }

    private fun hasLegalMove(hand: Int, left: Int, right: Int): Boolean {
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0 &&
            (a[id] == left || b[id] == left || a[id] == right || b[id] == right)) return true
        return false
    }

    private fun validHistoricalEnds(left: Int, right: Int): Boolean =
        (left == -1 && right == -1) || (left in 0..6 && right in 0..6)

    private fun connects(id: Int, left: Int, right: Int): Boolean =
        if (left == -1 && right == -1) true
        else (a[id] == left || b[id] == left || a[id] == right || b[id] == right)

    private fun legalActions(hand: Int, left: Int, right: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        if (!validHistoricalEnds(left, right)) return out
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0) {
            if (left == -1 && right == -1) out.add(id to 0)
            else {
                if (a[id] == left || b[id] == left) out.add(id to 1)
                if (a[id] == right || b[id] == right) out.add(id to 2)
            }
        }
        return out
    }

    private fun actionScore(hand: Int, tile: Int, side: Int, left: Int, right: Int): Double {
        val after = hand and (1 shl tile).inv()
        val aValue = a[tile]; val bValue = b[tile]
        val newLeft: Int
        val newRight: Int
        when (side) {
            0 -> { newLeft = aValue; newRight = bValue }
            1 -> { newLeft = if (aValue == left) bValue else aValue; newRight = right }
            else -> { newLeft = left; newRight = if (aValue == right) bValue else aValue }
        }
        var mobility = 0
        var support = 0
        for (id in 0 until TILE_COUNT) if ((after and (1 shl id)) != 0) {
            if (a[id] == newLeft || b[id] == newLeft || a[id] == newRight || b[id] == newRight) mobility++
            if (a[id] == newLeft || b[id] == newLeft) support++
            if (newRight != newLeft && (a[id] == newRight || b[id] == newRight)) support++
        }
        return (aValue + bValue) * 0.09 + mobility * 0.48 + support * 0.25 +
            if (aValue == bValue) 0.28 else 0.0
    }

    private fun choose(n: Int, kRaw: Int): Long {
        val k = minOf(kRaw, n - kRaw)
        if (k < 0) return 0
        var value = 1L
        for (i in 1..k) {
            val numerator = (n - k + i).toLong()
            if (value > Long.MAX_VALUE / numerator) return Long.MAX_VALUE
            value = value * numerator / i
        }
        return value
    }
}
