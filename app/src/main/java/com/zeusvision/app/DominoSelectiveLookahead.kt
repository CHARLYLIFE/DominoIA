package com.zeusvision.app

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Lookahead selectivo de respuesta rival posterior a la accion raiz.
 * Evalua las mismas acciones a traves de varias manos ocultas ponderadas; no
 * entrega una mano muestreada al motor como si fuera informacion conocida.
 * Su resultado es un ajuste pequeno al ranking IS-MCTS, nunca una sustitucion.
 */
object DominoSelectiveLookahead {
    private const val TILE_COUNT = 28
    private val a = IntArray(TILE_COUNT)
    private val b = IntArray(TILE_COUNT)
    private val pips = IntArray(TILE_COUNT)

    private data class State(
        val mine: Int,
        val rival: Int,
        val left: Int,
        val right: Int,
        val hasBoard: Boolean,
        val turn: Int,
        val passStreak: Int
    )
    private data class Move(val id: Int, val side: Int) { val key: Int get() = id * 3 + side }

    init {
        var id = 0
        for (x in 0..6) for (y in x..6) {
            a[id] = x; b[id] = y; pips[id] = x + y
            id++
        }
    }

    /** Devuelve scores por actionKey; null significa que el ajuste se omitio. */
    fun evaluateRootMoves(
        ownHandMask: Int,
        posterior: DominoOpponentInference.Posterior,
        left: Int?,
        right: Int?,
        hasBoard: Boolean,
        actionKeys: List<Int>,
        seed: Long,
        sampleCount: Int = 16,
        depthAfterRoot: Int = 1,
        timeBudgetNanos: Long = 24_000_000L
    ): Map<Int, Double>? {
        if (actionKeys.isEmpty() || posterior.compatibleHandCount == 0 || sampleCount <= 0) return null
        if (hasBoard && (left !in 0..6 || right !in 0..6)) return null
        val rootMoves = actionKeys.distinct().mapNotNull { key ->
            val id = key / 3; val side = key % 3
            if (id !in 0 until TILE_COUNT || (ownHandMask and (1 shl id)) == 0) null
            else Move(id, side)
        }
        if (rootMoves.isEmpty()) return null
        val random = Random(seed xor 0x4F1BBCDCBFA54001L)
        // Muestreo con reemplazo: cada determinacion ya sigue el peso posterior.
        // No volver a ponderarla, porque eso elevaria artificialmente los pesos altos.
        val samples = ArrayList<Pair<Int, Double>>(sampleCount)
        repeat(sampleCount) { samples.add(posterior.sample(random) to 1.0) }
        if (samples.isEmpty()) return null

        val deadline = System.nanoTime() + timeBudgetNanos
        val totals = DoubleArray(rootMoves.size)
        val weights = DoubleArray(rootMoves.size)
        var completedDeals = 0
        try {
            for ((enemyHand, dealWeight) in samples) {
                if (System.nanoTime() >= deadline) return null
                if (enemyHand and ownHandMask != 0) return null
                val initial = State(ownHandMask, enemyHand, left ?: -1, right ?: -1, hasBoard, 0, 0)
                for (index in rootMoves.indices) {
                    if (System.nanoTime() >= deadline) return null
                    val rootMove = rootMoves[index]
                    if (!isLegal(initial, rootMove)) continue
                    val child = applyAny(initial, rootMove)
                    val value = search(child, depthAfterRoot, Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY, deadline)
                    totals[index] += dealWeight * value
                    weights[index] += dealWeight
                }
                completedDeals++
            }
        } catch (_: LookaheadTimeout) {
            return null
        }
        if (completedDeals < min(4, samples.size)) return null
        val result = LinkedHashMap<Int, Double>()
        for (index in rootMoves.indices) {
            if (weights[index] > 0.0) result[rootMoves[index].key] =
                (totals[index] / weights[index]).coerceIn(-1.0, 1.0)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun search(state: State, depth: Int, alphaInput: Double, betaInput: Double, deadline: Long): Double {
        if (System.nanoTime() >= deadline) throw LookaheadTimeout()
        terminal(state)?.let { return it }
        if (depth <= 0) return evaluate(state)
        val hand = if (state.turn == 0) state.mine else state.rival
        val moves = legalMoves(hand, state.left, state.right, !state.hasBoard)
        if (moves.isEmpty()) {
            if (state.passStreak >= 1) return blockedValue(state)
            return search(state.copy(turn = 1 - state.turn, passStreak = state.passStreak + 1),
                depth - 1, alphaInput, betaInput, deadline)
        }
        var alpha = alphaInput
        var beta = betaInput
        var best = if (state.turn == 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        for (move in moves) {
            val candidate = search(applyAny(state, move), depth - 1, alpha, beta, deadline)
            if (state.turn == 0) {
                best = max(best, candidate)
                alpha = max(alpha, best)
            } else {
                best = min(best, candidate)
                beta = min(beta, best)
            }
            if (alpha >= beta) break
        }
        return best
    }

    private fun legalMoves(hand: Int, left: Int, right: Int, opening: Boolean): List<Move> {
        val out = ArrayList<Move>(14)
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0) {
            if (opening) out.add(Move(id, 0)) else {
                if (a[id] == left || b[id] == left) out.add(Move(id, 1))
                if (a[id] == right || b[id] == right) out.add(Move(id, 2))
            }
        }
        return out
    }

    private fun isLegal(state: State, move: Move): Boolean =
        (state.mine and (1 shl move.id)) != 0 &&
            (!state.hasBoard || (move.side == 1 && (a[move.id] == state.left || b[move.id] == state.left)) ||
                (move.side == 2 && (a[move.id] == state.right || b[move.id] == state.right)))

    private fun applyAny(state: State, move: Move): State {
        val bit = 1 shl move.id
        val actorHand = if (state.turn == 0) state.mine else state.rival
        val after = actorHand and bit.inv()
        val mine = if (state.turn == 0) after else state.mine
        val rival = if (state.turn == 1) after else state.rival
        val newLeft: Int
        val newRight: Int
        when {
            !state.hasBoard || move.side == 0 -> { newLeft = a[move.id]; newRight = b[move.id] }
            move.side == 1 -> { newLeft = if (a[move.id] == state.left) b[move.id] else a[move.id]; newRight = state.right }
            else -> { newLeft = state.left; newRight = if (a[move.id] == state.right) b[move.id] else a[move.id] }
        }
        return State(mine, rival, newLeft, newRight, true, 1 - state.turn, 0)
    }

    private fun terminal(state: State): Double? {
        if (state.mine == 0) return 1.0 + pipSum(state.rival) / 840.0
        if (state.rival == 0) return -1.0 - pipSum(state.mine) / 840.0
        if (state.passStreak >= 2) return blockedValue(state)
        return null
    }

    private fun blockedValue(state: State): Double {
        val my = pipSum(state.mine)
        val enemy = pipSum(state.rival)
        return when {
            my < enemy -> 0.90 + (enemy - my) / 840.0
            my > enemy -> -0.90 - (my - enemy) / 840.0
            else -> 0.0
        }
    }

    private fun evaluate(state: State): Double {
        val pipAdvantage = (pipSum(state.rival) - pipSum(state.mine)) / 84.0
        val myMoves = legalMoves(state.mine, state.left, state.right, !state.hasBoard).size
        val enemyMoves = legalMoves(state.rival, state.left, state.right, !state.hasBoard).size
        val mobility = (myMoves - enemyMoves) / 7.0
        return (0.62 * pipAdvantage + 0.24 * mobility).coerceIn(-0.82, 0.82)
    }

    private fun pipSum(mask: Int): Int {
        var sum = 0
        for (id in 0 until TILE_COUNT) if ((mask and (1 shl id)) != 0) sum += pips[id]
        return sum
    }

    private class LookaheadTimeout : RuntimeException()
}
