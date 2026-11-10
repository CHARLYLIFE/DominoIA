package com.zeusvision.app

import java.util.Random

/**
 * Evaluacion comun de todas las jugadas legales de la raiz.
 * Trabaja sobre muestras ponderadas del posterior rival y nunca necesita
 * conocer una mano rival real durante la partida.
 */
object DominoMoveEvaluator {
    private const val TILE_COUNT = 28

    data class Metrics(
        val opponentResponseProbability: Double,
        val ownMobilityAfter: Int,
        val opponentMobilityExpected: Double,
        val numberControl: Double,
        val immediateBlockProbability: Double,
        val expectedBlockOutcome: Double,
        val pipsRetained: Int,
        val pipsReleased: Int,
        /** Frecuencia de resultados ganadores en rollouts, no probabilidad calibrada. */
        val rolloutWinRate: Double?,
        /** No disponible hasta contar con marcador fiable y un modelo entrenado para el objetivo real. */
        val matchWinProbability: Double?,
        val tacticalScore: Double,
        val samples: Int
    )

    private val tileA = IntArray(TILE_COUNT)
    private val tileB = IntArray(TILE_COUNT)
    private val tilePips = IntArray(TILE_COUNT)

    private data class WeightedHand(val mask: Int, val weight: Double)

    init {
        var id = 0
        for (a in 0..6) for (b in a..6) {
            tileA[id] = a
            tileB[id] = b
            tilePips[id] = a + b
            id++
        }
    }

    /**
     * Calcula los indicadores para cada actionKey (tileId * 3 + lado).
     * El muestreo es reproducible y sigue el posterior bayesiano; para un
     * posterior pequeño se evalúan todas las manos compatibles exactamente.
     */
    fun evaluateRootMoves(
        ownHandMask: Int,
        posterior: DominoOpponentInference.Posterior,
        left: Int?,
        right: Int?,
        hasBoard: Boolean,
        actionKeys: List<Int>,
        mctsWinRates: Map<Int, Double>,
        seed: Long,
        sampleCount: Int = 192
    ): Map<Int, Metrics> {
        if (actionKeys.isEmpty() || posterior.compatibleHandCount == 0 || sampleCount <= 0) return emptyMap()
        if (hasBoard && (left !in 0..6 || right !in 0..6)) return emptyMap()

        val samples = sampleHands(posterior, sampleCount, seed)
        if (samples.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, Metrics>()

        for (key in actionKeys.distinct()) {
            val id = key / 3
            val side = key % 3
            if (id !in 0 until TILE_COUNT || side !in 0..2 || ownHandMask and (1 shl id) == 0) continue
            if (!isLegal(id, side, hasBoard, left, right)) continue

            val nextEnds = resolveEnds(id, side, hasBoard, left, right) ?: continue
            val nextLeft = nextEnds.first
            val nextRight = nextEnds.second
            val ownAfter = ownHandMask and (1 shl id).inv()
            val ownMobility = countLegal(ownAfter, nextLeft, nextRight)
            val ownSupport = countSupport(ownAfter, nextLeft, nextRight)
            val ownPips = pipSum(ownAfter)

            var responseWeight = 0.0
            var opponentMobility = 0.0
            var opponentSupport = 0.0
            var blockWeight = 0.0
            var blockOutcome = 0.0
            var totalWeight = 0.0
            for (sample in samples) {
                val enemyHand = sample.mask
                val weight = sample.weight
                if (enemyHand and ownHandMask != 0) continue
                totalWeight += weight
                val enemyMoves = countLegal(enemyHand, nextLeft, nextRight)
                if (enemyMoves > 0) responseWeight += weight
                opponentMobility += enemyMoves.toDouble() * weight
                opponentSupport += countSupport(enemyHand, nextLeft, nextRight).toDouble() * weight
                val enemyPipCount = pipSum(enemyHand)
                if (ownMobility == 0 && enemyMoves == 0) {
                    blockWeight += weight
                    blockOutcome += when {
                        ownPips < enemyPipCount -> weight
                        ownPips > enemyPipCount -> -weight
                        else -> 0.0
                    }
                }
            }

            if (totalWeight <= 0.0) continue
            val n = totalWeight
            val responseProbability = (responseWeight / n).coerceIn(0.0, 1.0)
            val expectedEnemyMobility = opponentMobility / n
            val expectedEnemySupport = opponentSupport / n
            val control = ((ownSupport - expectedEnemySupport) / 14.0).coerceIn(-1.0, 1.0)
            val blockProbability = (blockWeight / n).coerceIn(0.0, 1.0)
            val blockOutcomeEstimate = if (blockWeight == 0.0) 0.0
                else (blockOutcome / n).coerceIn(-1.0, 1.0)
            val releasedPips = tilePips[id]
            val mobilityBalance = (ownMobility / 7.0 - expectedEnemyMobility / 7.0).coerceIn(-1.0, 1.0)
            val releaseScore = ((releasedPips / 12.0) * 2.0 - 1.0).coerceIn(-1.0, 1.0)

            // Sesgo pequeño: IS-MCTS/PUCT sigue dominando la elección.
            val tactical = (0.25 * mobilityBalance + 0.25 * control +
                0.25 * blockOutcomeEstimate + 0.25 * releaseScore).coerceIn(-1.0, 1.0)
            val winRate = mctsWinRates[key]
            out[key] = Metrics(
                opponentResponseProbability = responseProbability,
                ownMobilityAfter = ownMobility,
                opponentMobilityExpected = expectedEnemyMobility,
                numberControl = control,
                immediateBlockProbability = blockProbability,
                expectedBlockOutcome = blockOutcomeEstimate,
                pipsRetained = ownPips,
                pipsReleased = releasedPips,
                rolloutWinRate = winRate?.coerceIn(0.0, 1.0),
                matchWinProbability = null,
                tacticalScore = tactical,
                samples = samples.size
            )
        }
        return out
    }

    private fun sampleHands(
        posterior: DominoOpponentInference.Posterior,
        requested: Int,
        seed: Long
    ): List<WeightedHand> {
        val count = posterior.compatibleHandCount
        if (count <= 0) return emptyList()
        // En posteriores pequeños se calcula la esperanza exacta usando sus
        // pesos normalizados; nunca se reemplaza el posterior por una media uniforme.
        if (count <= requested) return List(count) { i ->
            WeightedHand(posterior.hands[i], posterior.weightAt(i))
        }
        val random = Random(seed xor 0x5A17C0DEL)
        val weight = 1.0 / requested.toDouble()
        return List(requested) { WeightedHand(posterior.sample(random), weight) }
    }

    private fun isLegal(id: Int, side: Int, hasBoard: Boolean, left: Int?, right: Int?): Boolean {
        if (!hasBoard) return side == 0
        return when (side) {
            1 -> left != null && (tileA[id] == left || tileB[id] == left)
            2 -> right != null && (tileA[id] == right || tileB[id] == right)
            else -> false
        }
    }

    private fun resolveEnds(
        id: Int,
        side: Int,
        hasBoard: Boolean,
        left: Int?,
        right: Int?
    ): Pair<Int, Int>? {
        if (!hasBoard) return tileA[id] to tileB[id]
        val oldLeft = left ?: return null
        val oldRight = right ?: return null
        return when (side) {
            1 -> (if (tileA[id] == oldLeft) tileB[id] else tileA[id]) to oldRight
            2 -> oldLeft to (if (tileA[id] == oldRight) tileB[id] else tileA[id])
            else -> null
        }
    }

    private fun countLegal(hand: Int, left: Int, right: Int): Int {
        var count = 0
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0 &&
            (tileA[id] == left || tileB[id] == left || tileA[id] == right || tileB[id] == right)) count++
        return count
    }

    private fun countSupport(hand: Int, left: Int, right: Int): Int {
        var count = 0
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0) {
            val touchesLeft = tileA[id] == left || tileB[id] == left
            val touchesRight = tileA[id] == right || tileB[id] == right
            if (touchesLeft || touchesRight) count += if (touchesLeft && touchesRight && left != right) 2 else 1
        }
        return count
    }

    private fun pipSum(mask: Int): Int {
        var sum = 0
        for (id in 0 until TILE_COUNT) if ((mask and (1 shl id)) != 0) sum += tilePips[id]
        return sum
    }
}
