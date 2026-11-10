package com.zeusvision.app

import kotlin.math.exp

/** Heuristicas expertas suaves: orientan POLICY/PUCT e IS-MCTS, nunca legalizan jugadas. */
object DominoExpertHeuristics {
    data class MatchContext(
        val userScore: Int?,
        val opponentScore: Int?,
        val target: Int = 50
    ) {
        val valid: Boolean get() = userScore != null && opponentScore != null &&
            userScore >= 0 && opponentScore >= 0 && target > 0
    }

    @Volatile private var matchContext = MatchContext(null, null)
    private val a = IntArray(28)
    private val b = IntArray(28)
    private val pips = IntArray(28)

    init {
        var id = 0
        for (x in 0..6) for (y in x..6) {
            a[id] = x; b[id] = y; pips[id] = x + y
            id++
        }
    }

    fun setMatchScore(userScore: Int?, opponentScore: Int?, target: Int = 50) {
        matchContext = MatchContext(userScore, opponentScore, target)
    }

    fun currentMatchContext(): MatchContext = matchContext

    /** Retorna una evaluacion acotada [-1,1] desde el punto de vista del actor. */
    fun scoreMove(
        actorHand: Int,
        moveId: Int,
        side: Int,
        actor: Int,
        left: Int,
        right: Int,
        hasBoard: Boolean,
        passedValuesByUser: Int,
        passedValuesByRival: Int,
        profile: DominoOpponentProfile.Snapshot = DominoOpponentProfile.snapshot()
    ): Double {
        if (moveId !in 0 until 28 || (actorHand and (1 shl moveId)) == 0) return -1.0
        val after = actorHand and (1 shl moveId).inv()
        val resolvedLeft = when {
            !hasBoard || side == 0 -> a[moveId]
            side == 1 -> if (a[moveId] == left) b[moveId] else a[moveId]
            else -> left
        }
        val resolvedRight = when {
            !hasBoard || side == 0 -> b[moveId]
            side == 2 -> if (a[moveId] == right) b[moveId] else a[moveId]
            else -> right
        }
        val mobility = countLegal(after, resolvedLeft, resolvedRight)
        val supportLeft = countValue(after, resolvedLeft)
        val supportRight = if (resolvedRight == resolvedLeft) supportLeft else countValue(after, resolvedRight)
        val support = supportLeft + supportRight
        var orphanDoubles = 0
        for (id in 0 until 28) if ((after and (1 shl id)) != 0 && a[id] == b[id]) {
            if (a[id] != resolvedLeft && a[id] != resolvedRight) orphanDoubles++
        }
        val released = pips[moveId] / 12.0
        val mobilityNorm = (mobility / 7.0).coerceIn(0.0, 1.0)
        val supportNorm = (support / 8.0).coerceIn(0.0, 1.0)
        val control = ((supportLeft - supportRight).toDouble() / 7.0).coerceIn(-1.0, 1.0)
        val ownPassMask = if (actor == 0) passedValuesByUser else passedValuesByRival
        val opponentPassMask = if (actor == 0) passedValuesByRival else passedValuesByUser
        val newlyPassed = Integer.bitCount(opponentPassMask) / 7.0
        val ownWeakness = Integer.bitCount(ownPassMask) / 7.0

        var score = released * 0.24 + mobilityNorm * 0.30 + supportNorm * 0.20 + control * 0.07 -
            orphanDoubles * 0.035 + (newlyPassed - ownWeakness) * 0.04

        // El perfil tiene peso moderado y solo cuando existe una muestra util.
        if (actor == 1 && !profile.lowSample) {
            // El perfil se aprende solo con eventos confirmados de esta mano.
            // Influye en la simulacion del rival, no impone una respuesta fija.
            score += (profile.pointShedding - 0.5) * 0.045 * (released - 0.5)
            val isDouble = a[moveId] == b[moveId]
            score += (profile.earlyDoubleRelease - 0.5) * 0.035 *
                (if (isDouble) 1.0 else -0.20)
        }

        val context = matchContext
        if (context.valid) {
            val myScore = if (actor == 0) context.userScore!! else context.opponentScore!!
            val theirScore = if (actor == 0) context.opponentScore!! else context.userScore!!
            val lead = myScore - theirScore
            val nearTarget = (context.target - myScore).coerceAtLeast(0) <= 15
            if (lead > 0 && nearTarget) {
                // Con ventaja cerca de la meta: priorizar movilidad y evitar dobles aislados.
                score += mobilityNorm * 0.045 - orphanDoubles * 0.012
            } else if (lead < 0) {
                // En desventaja: descargar puntos gana un poco de importancia.
                score += released * 0.06
            }
        }
        return score.coerceIn(-1.0, 1.0)
    }

    fun softmax(scores: DoubleArray): DoubleArray {
        if (scores.isEmpty()) return scores
        val maxScore = scores.maxOrNull() ?: 0.0
        val weights = DoubleArray(scores.size) { exp(((scores[it] - maxScore) * 3.0).coerceIn(-20.0, 0.0)) }
        val total = weights.sum().coerceAtLeast(1e-12)
        for (i in weights.indices) weights[i] /= total
        return weights
    }

    private fun countLegal(hand: Int, left: Int, right: Int): Int {
        var count = 0
        for (id in 0 until 28) if ((hand and (1 shl id)) != 0 &&
            (a[id] == left || b[id] == left || a[id] == right || b[id] == right)) count++
        return count
    }

    private fun countValue(hand: Int, value: Int): Int {
        var count = 0
        for (id in 0 until 28) if ((hand and (1 shl id)) != 0 &&
            (a[id] == value || b[id] == value)) count++
        return count
    }
}
