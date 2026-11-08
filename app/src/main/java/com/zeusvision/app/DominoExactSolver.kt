package com.zeusvision.app

import kotlin.math.abs

/**
 * Solucionador minimax exacto para una mano rival conocida dentro de cada
 * determinacion compatible. Agrega resultados sobre TODAS las manos posibles.
 */
object DominoExactSolver {
    data class Result(
        val actionKey: Int,
        val distributions: Int,
        val nodes: Long,
        val winProbability: Double,
        val expectedPipMargin: Double,
        val expectedUtility: Double
    )

    private data class State(
        val mine: Int,
        val rival: Int,
        val left: Int,
        val right: Int,
        val hasBoard: Boolean,
        val turn: Int,
        val passStreak: Int
    )
    private data class Score(val utility: Double, val winner: Int, val margin: Int)
    private enum class Bound { EXACT, LOWER, UPPER }
    private data class Entry(val score: Score, val bound: Bound)
    private data class Key(
        val mine: Int, val rival: Int, val left: Int, val right: Int,
        val hasBoard: Boolean, val turn: Int, val passStreak: Int
    )
    private data class Move(val id: Int, val side: Int) { val key: Int get() = id * 3 + side }

    private const val TILE_COUNT = 28
    private const val DEFAULT_MAX_DISTRIBUTIONS = 1
    private const val DEFAULT_MAX_ACTIVE_TILES = 8
    private const val DEFAULT_TIME_NANOS = 180_000_000L
    private val a = IntArray(TILE_COUNT)
    private val b = IntArray(TILE_COUNT)
    private val pips = IntArray(TILE_COUNT)

    init {
        var id = 0
        for (x in 0..6) for (y in x..6) {
            a[id] = x; b[id] = y; pips[id] = x + y
            id++
        }
        check(id == TILE_COUNT)
    }

    fun solve(
        ownHandMask: Int,
        posterior: DominoOpponentInference.Posterior,
        left: Int?,
        right: Int?,
        hasBoard: Boolean,
        maxDistributions: Int = DEFAULT_MAX_DISTRIBUTIONS,
        maxActiveTiles: Int = DEFAULT_MAX_ACTIVE_TILES,
        timeBudgetNanos: Long = DEFAULT_TIME_NANOS
    ): Result? {
        val ownCount = Integer.bitCount(ownHandMask)
        if (ownCount == 0) return null
        if (ownCount + (posterior.hands.firstOrNull()?.let { Integer.bitCount(it) } ?: 0) > maxActiveTiles) return null
        // La busqueda minimax clasica necesita informacion perfecta. No convertir
        // varias manos ocultas en varios mundos de informacion perfecta: eso filtra
        // informacion futura. Con incertidumbre, el llamador debe usar IS-MCTS.
        if (posterior.compatibleHandCount != 1 || posterior.compatibleHandCount > maxDistributions) return null
        if (hasBoard && (left !in 0..6 || right !in 0..6)) return null
        val rootMoves = legalMoves(ownHandMask, left ?: -1, right ?: -1, hasBoard)
        if (rootMoves.isEmpty()) return null

        val deadline = System.nanoTime() + timeBudgetNanos
        val sumUtility = DoubleArray(TILE_COUNT * 3)
        val sumWin = DoubleArray(TILE_COUNT * 3)
        val sumMargin = DoubleArray(TILE_COUNT * 3)
        var totalWeight = 0.0
        var totalNodes = 0L

        try {
            for (dealIndex in posterior.hands.indices) {
                if (System.nanoTime() >= deadline) return null
                val enemyHand = posterior.hands[dealIndex]
                if (enemyHand and ownHandMask != 0) return null
                val weight = posterior.weightAt(dealIndex)
                if (!weight.isFinite() || weight <= 0.0) continue
                val table = HashMap<Key, Entry>(2048)
                val solver = Search(deadline, table)
                val initial = State(ownHandMask, enemyHand, left ?: -1, right ?: -1, hasBoard, 0, 0)
                for (move in rootMoves) {
                    if (System.nanoTime() >= deadline) return null
                    val child = apply(initial, move)
                    val outcome = solver.value(child, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                    val idx = move.key
                    sumUtility[idx] += weight * outcome.utility
                    sumWin[idx] += weight * when (outcome.winner) { 0 -> 1.0; 1 -> 0.0; else -> 0.5 }
                    sumMargin[idx] += weight * outcome.margin.toDouble()
                }
                totalNodes += solver.nodes
                totalWeight += weight
            }
        } catch (_: SearchTimeout) {
            return null
        }
        if (totalWeight <= 0.0 || System.nanoTime() > deadline) return null

        val selected = rootMoves.maxWithOrNull(compareBy<Move> { sumUtility[it.key] / totalWeight }
            .thenBy { sumWin[it.key] / totalWeight }
            .thenBy { sumMargin[it.key] / totalWeight }) ?: return null
        return Result(
            actionKey = selected.key,
            distributions = posterior.compatibleHandCount,
            nodes = totalNodes,
            winProbability = (sumWin[selected.key] / totalWeight).coerceIn(0.0, 1.0),
            expectedPipMargin = sumMargin[selected.key] / totalWeight,
            expectedUtility = sumUtility[selected.key] / totalWeight
        )
    }

    private class Search(private val deadline: Long, private val table: MutableMap<Key, Entry>) {
        var nodes: Long = 0
            private set

        fun value(state: State, alphaInput: Double, betaInput: Double): Score {
            nodes++
            if ((nodes and 127L) == 0L && System.nanoTime() >= deadline) throw SearchTimeout()
            terminal(state)?.let { return it }

            val key = Key(state.mine, state.rival, state.left, state.right,
                state.hasBoard, state.turn, state.passStreak)
            var alpha = alphaInput
            var beta = betaInput
            val originalAlpha = alpha
            val originalBeta = beta
            val cached = table[key]
            if (cached != null) {
                when (cached.bound) {
                    Bound.EXACT -> return cached.score
                    Bound.LOWER -> alpha = maxOf(alpha, cached.score.utility)
                    Bound.UPPER -> beta = minOf(beta, cached.score.utility)
                }
                if (alpha >= beta) return cached.score
            }

            val hand = if (state.turn == 0) state.mine else state.rival
            val moves = legalMoves(hand, if (state.hasBoard) state.left else -1,
                if (state.hasBoard) state.right else -1, !state.hasBoard)
            if (moves.isEmpty()) {
                val passed = state.copy(turn = 1 - state.turn, passStreak = state.passStreak + 1)
                val result = if (passed.passStreak >= 2) terminalBlocked(passed) else value(passed, alpha, beta)
                val bound = when {
                    result.utility <= originalAlpha -> Bound.UPPER
                    result.utility >= originalBeta -> Bound.LOWER
                    else -> Bound.EXACT
                }
                table[key] = Entry(result, bound)
                return result
            }

            var best = if (state.turn == 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            var bestScore: Score? = null
            for (move in moves) {
                val child = apply(state, move)
                val candidate = value(child, alpha, beta)
                val improves = if (state.turn == 0) candidate.utility > best else candidate.utility < best
                if (bestScore == null || improves) {
                    best = candidate.utility
                    bestScore = candidate
                }
                if (state.turn == 0) alpha = maxOf(alpha, best) else beta = minOf(beta, best)
                if (alpha >= beta) break
            }
            val finalScore = bestScore ?: Score(0.0, -1, 0)
            val bound = when {
                finalScore.utility <= originalAlpha -> Bound.UPPER
                finalScore.utility >= originalBeta -> Bound.LOWER
                else -> Bound.EXACT
            }
            table[key] = Entry(finalScore, bound)
            return finalScore
        }
    }

    private class SearchTimeout : RuntimeException()

    private fun terminal(state: State): Score? {
        if (state.mine == 0) {
            val points = pipSum(state.rival)
            return Score(1.0 + points / 840.0, 0, points)
        }
        if (state.rival == 0) {
            val points = pipSum(state.mine)
            return Score(-1.0 - points / 840.0, 1, -points)
        }
        if (state.passStreak >= 2) return terminalBlocked(state)
        return null
    }

    private fun terminalBlocked(state: State): Score {
        val minePoints = pipSum(state.mine)
        val rivalPoints = pipSum(state.rival)
        val winner = when {
            minePoints < rivalPoints -> 0
            rivalPoints < minePoints -> 1
            else -> -1
        }
        val margin = rivalPoints - minePoints
        val awarded = maxOf(minePoints, rivalPoints)
        val utility = when (winner) {
            0 -> 1.0 + awarded / 840.0
            1 -> -1.0 - awarded / 840.0
            else -> 0.0
        }
        return Score(utility, winner, margin)
    }

    private fun legalMoves(hand: Int, left: Int, right: Int, opening: Boolean): List<Move> {
        val moves = ArrayList<Move>(14)
        for (id in 0 until TILE_COUNT) if ((hand and (1 shl id)) != 0) {
            if (opening) moves.add(Move(id, 0))
            else {
                if (a[id] == left || b[id] == left) moves.add(Move(id, 1))
                if (a[id] == right || b[id] == right) moves.add(Move(id, 2))
            }
        }
        return moves
    }

    private fun apply(state: State, move: Move): State {
        val bit = 1 shl move.id
        val mine = if (state.turn == 0) state.mine and bit.inv() else state.mine
        val rival = if (state.turn == 1) state.rival and bit.inv() else state.rival
        val x = a[move.id]; val y = b[move.id]
        val newLeft: Int
        val newRight: Int
        when (move.side) {
            0 -> { newLeft = x; newRight = y }
            1 -> { newLeft = if (x == state.left) y else x; newRight = state.right }
            else -> { newLeft = state.left; newRight = if (x == state.right) y else x }
        }
        return State(mine, rival, newLeft, newRight, true, 1 - state.turn, 0)
    }

    private fun pipSum(mask: Int): Int {
        var sum = 0
        for (id in 0 until TILE_COUNT) if ((mask and (1 shl id)) != 0) sum += pips[id]
        return sum
    }
}
