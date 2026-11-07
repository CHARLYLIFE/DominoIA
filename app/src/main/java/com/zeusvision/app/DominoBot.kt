package com.zeusvision.app

import java.util.Random
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ZEUS VISION - motor estrategico doble-seis, 1 contra 1, sin robo.
 *
 * Arquitectura:
 *  - Muestreo de manos ocultas (7 fichas del rival + 14 fuera de juego).
 *  - IS-MCTS: los nodos se comparten por informacion publica y la mano PRIVADA
 *    del jugador que toma la decision; nunca se incluye la mano contraria en
 *    la clave de informacion de ese jugador.
 *  - UCT, expansion progresiva, simulaciones tacticas y utilidad por victoria
 *    mas margen de puntos.
 *  - Seguimiento de fichas vistas y pases inferibles entre capturas consecutivas.
 *  - Inferencia bayesiana ligera de decisiones anteriores del rival.
 *
 * No usa red neuronal ni afirma estar entrenado. El motor funciona sin modelo
 * externo y sin Internet. El entrenamiento masivo debe ejecutarse aparte.
 */
object DominoBot {
    data class Tile(val a: Int, val b: Int) {
        val pips: Int get() = a + b
        val label: String get() = "$a-$b"
    }

    data class Suggestion(
        val tile: Tile,
        val side: String,
        val oriented: String,
        val reason: String
    )

    data class Result(
        val suggestion: Suggestion?,
        val message: String,
        val diagnostics: String = ""
    )

    private const val PLAYER_TILES = 7
    private const val OUTSIDE_TILES = 14
    private const val MAX_TILE_ID = 28
    private const val TARGET_ITERATIONS = 850
    private const val MIN_ITERATIONS = 260
    private const val MAX_SEARCH_NANOS = 420_000_000L
    private const val MAX_PLY = 32
    private const val C_UCT = 0.78
    private const val ALL_MASK = (1 shl MAX_TILE_ID) - 1

    private val tileA = IntArray(MAX_TILE_ID)
    private val tileB = IntArray(MAX_TILE_ID)
    private val tilePips = IntArray(MAX_TILE_ID)
    private val tileId = Array(7) { IntArray(7) { -1 } }

    private data class PassConstraint(
        val left: Int,
        val right: Int,
        val opponentPlaysAtThatTime: Int
    )

    private data class EnemyPlay(
        val id: Int,
        val leftBefore: Int,
        val rightBefore: Int,
        val opponentPlayIndex: Int
    )

    private data class Snapshot(
        val handMask: Int,
        val boardMask: Int,
        val left: Int?,
        val right: Int?
    )

    private class Tracker {
        var starter: String? = null
        var previous: Snapshot? = null
        var lastActor: Int? = null // 0 = usuario, 1 = rival
        var reliable: Boolean = true
        val enemyPlays = mutableListOf<EnemyPlay>()
        val passConstraints = mutableListOf<PassConstraint>()

        fun reset(newStarter: String) {
            starter = newStarter
            previous = null
            lastActor = null
            reliable = true
            enemyPlays.clear()
            passConstraints.clear()
        }
    }

    private data class Observation(
        val handMask: Int,
        val playedMask: Int,
        val unknownMask: Int,
        val opponentCount: Int,
        val left: Int?,
        val right: Int,
        val hasBoard: Boolean,
        val passConstraints: List<PassConstraint>,
        val enemyPlays: List<EnemyPlay>,
        val publicHistoryHash: Long
    )

    private data class Move(val id: Int, val side: Int) // 0=start, 1=left, 2=right

    private data class State(
        val mine: Int,
        val rival: Int,
        val left: Int,
        val right: Int,
        val hasBoard: Boolean,
        val played: Int,
        val turn: Int,
        val passStreak: Int,
        val history: Long
    )

    /**
     * Clave de informacion: incluye la mano privada del jugador que decide,
     * pero excluye la mano privada del adversario. Es esencial para evitar que
     * la busqueda fusione estrategias usando informacion que ese jugador no ve.
     */
    private data class InfoKey(
        val actor: Int,
        val actorHand: Int,
        val played: Int,
        val left: Int,
        val right: Int,
        val hasBoard: Boolean,
        val passStreak: Int,
        val history: Long
    )

    private class Edge(val move: Move) {
        var visits: Int = 0
        var valueSum: Double = 0.0 // utilidad desde la perspectiva de ZEUS
    }

    private class Node(moves: List<Move>) {
        var visits: Int = 0
        val edges = moves.associateByTo(LinkedHashMap()) { moveKey(it) }
            .mapValuesTo(LinkedHashMap()) { Edge(it.value) }
        val unexpanded = moves.map { moveKey(it) }.toMutableList()
    }

    private data class SearchResult(
        val move: Move,
        val iterations: Int,
        val validDeals: Int,
        val value: Double
    )

    private data class DecisionKey(
        val handMask: Int,
        val boardMask: Int,
        val left: Int,
        val right: Int,
        val leader: String,
        val historyHash: Long
    )

    private data class RolloutStyle(
        val pipWeight: Double,
        val mobilityWeight: Double,
        val blockWeight: Double,
        val supportWeight: Double,
        val doublePenalty: Double
    )

    private val styles = arrayOf(
        RolloutStyle(0.13, 0.55, 0.48, 0.28, 0.28), // equilibrado
        RolloutStyle(0.22, 0.34, 0.24, 0.15, 0.18), // descarga de puntos
        RolloutStyle(0.07, 0.74, 0.48, 0.43, 0.34), // control y movilidad
        RolloutStyle(0.10, 0.43, 1.08, 0.25, 0.27)  // bloqueo
    )

    private var tracker = Tracker()
    private var lastDecisionKey: DecisionKey? = null
    private var lastDecisionResult: Result? = null

    init {
        var next = 0
        for (a in 0..6) for (b in a..6) {
            tileA[next] = a
            tileB[next] = b
            tilePips[next] = a + b
            tileId[a][b] = next
            tileId[b][a] = next
            next++
        }
        check(next == MAX_TILE_ID)
    }

    /** API anterior conservada por compatibilidad con llamadas existentes. */
    @Synchronized
    fun suggest(
        handTiles: List<DominoVision.Tile>,
        left: Int?,
        right: Int?,
        leader: String
    ): Result = suggest(handTiles, left, right, leader, emptyList())

    /** API principal: recibe tambien las fichas publicas reconocidas en la mesa. */
    @Synchronized
    fun suggest(
        handTiles: List<DominoVision.Tile>,
        left: Int?,
        right: Int?,
        leader: String,
        boardTiles: List<DominoVision.BoardTile>
    ): Result {
        val normalizedLeader = if (leader.equals("RIVAL", true)) "RIVAL" else "TÚ"
        val parsedHand = parseHand(handTiles)
            ?: return Result(null, "NO PUEDO CONFIRMAR TU MANO", "mano invalida o duplicada")

        if (parsedHand.count() > PLAYER_TILES || parsedHand == 0) {
            return Result(null, if (parsedHand == 0) "NO QUEDAN FICHAS EN TU MANO" else "MANO INCONSISTENTE")
        }

        val boardMask = parseBoard(boardTiles)
            ?: return Result(null, "NO PUEDO CONFIRMAR TODAS LAS FICHAS DE LA MESA", "ficha duplicada o lectura dudosa")

        if (boardMask.count() > 14) {
            return Result(null, "MESA INCONSISTENTE", "hay mas de 14 fichas observadas")
        }
        if ((parsedHand and boardMask) != 0) {
            return Result(null, "LECTURA INCONSISTENTE", "una ficha aparece en tu mano y en la mesa")
        }

        // El selector del panel indica quien abre la partida. Durante la ronda
        // se analiza siempre la mejor respuesta posible para la mano reconocida.
        updateTracker(parsedHand, boardMask, left, right, normalizedLeader)

        if (boardMask == 0 && left == null && right == null) {
            if (normalizedLeader == "RIVAL") {
                return Result(null, "SALE EL RIVAL · ESPERANDO SU PRIMERA FICHA", trackerDiagnostics())
            }
            if (parsedHand.count() != PLAYER_TILES) {
                return Result(null, "NO PUEDO CONFIRMAR LA SALIDA", "la mano inicial debe contener 7 fichas")
            }
            val obs = buildObservation(parsedHand, boardMask, null, null, normalizedLeader)
                ?: return Result(null, "NO PUEDO VALIDAR EL REPARTO", "estado inicial imposible")
            return calculateOrReuse(obs, normalizedLeader)
        }

        if (left == null || right == null || left !in 0..6 || right !in 0..6) {
            return Result(null, "NO PUEDO CONFIRMAR LOS EXTREMOS", "se necesitan dos extremos validos")
        }
        if (boardMask == 0) {
            return Result(null, "NO PUEDO CONFIRMAR LA MESA", "hay extremos pero no hay fichas reconocidas")
        }

        val obs = buildObservation(parsedHand, boardMask, left, right, normalizedLeader)
            ?: return Result(null, "ESTADO NO CONSISTENTE · NO JUGAR", "revisa mano, fichas de mesa y lectura visual")

        val legal = legalMoves(parsedHand, left, right, false)
        if (legal.isEmpty()) {
            return Result(null, "NO TIENES JUGADA LEGAL · PASAR", trackerDiagnostics())
        }

        return calculateOrReuse(obs, normalizedLeader)
    }

    private fun calculateOrReuse(observation: Observation, leader: String): Result {
        val key = DecisionKey(
            observation.handMask,
            observation.playedMask,
            observation.left ?: -1,
            observation.right,
            leader,
            observation.publicHistoryHash
        )
        if (key == lastDecisionKey) lastDecisionResult?.let { return it }

        val search = runSearch(observation)
        val result = if (search.validDeals == 0) {
            Result(null, "NO PUEDO VALIDAR LAS MANOS OCULTAS", trackerDiagnostics())
        } else {
            resultToPublic(search, observation)
        }
        if (result.suggestion != null) {
            lastDecisionKey = key
            lastDecisionResult = result
        }
        return result
    }

    /** Entrada determinista para simuladores externos y torneos de autojuego. */
    fun suggestForSimulation(
        hand: List<Tile>,
        left: Int?,
        right: Int?,
        board: List<Tile>,
        iterations: Int = 420,
        seed: Long = System.nanoTime()
    ): Suggestion? {
        var handMask = 0
        for (t in hand) {
            val id = idOf(t.a, t.b) ?: return null
            if ((handMask and (1 shl id)) != 0) return null
            handMask = handMask or (1 shl id)
        }
        var playedMask = 0
        for (t in board) {
            val id = idOf(t.a, t.b) ?: return null
            if ((playedMask and (1 shl id)) != 0) return null
            playedMask = playedMask or (1 shl id)
        }
        if ((handMask and playedMask) != 0 || hand.size > 7 || hand.isEmpty()) return null
        if (board.isEmpty()) {
            if (left != null || right != null || hand.size != 7) return null
        } else if (left !in 0..6 || right !in 0..6) return null

        val ownPlayed = PLAYER_TILES - hand.size
        val opponentPlayed = board.size - ownPlayed
        val opponentCount = PLAYER_TILES - opponentPlayed
        if (opponentCount !in 0..7) return null
        val unknown = ALL_MASK and handMask.inv() and playedMask.inv()
        if (unknown.count() != OUTSIDE_TILES + opponentCount) return null

        val obs = Observation(
            handMask, playedMask, unknown, opponentCount, left, right ?: -1,
            board.isNotEmpty(), emptyList(), emptyList(), 0x5EEDL
        )
        val rootMoves = if (board.isEmpty()) legalMoves(handMask, null, null, true)
            else legalMoves(handMask, left, right, false)
        if (rootMoves.isEmpty()) return null
        val result = search(obs, iterations.coerceIn(80, 2500), 700_000_000L, seed)
        if (result.validDeals == 0 || rootMoves.none { it.id == result.move.id && it.side == result.move.side }) return null
        return suggestionFor(result.move, if (board.isEmpty()) "SALIDA" else if (result.move.side == 1) "IZQUIERDA" else "DERECHA", result.iterations, left, right)
    }

    private fun parseHand(tiles: List<DominoVision.Tile>): Int? {
        if (tiles.size > PLAYER_TILES) return null
        var mask = 0
        for (tile in tiles) {
            if (tile.a !in 0..6 || tile.b !in 0..6 || tile.confidence < 45) return null
            val id = idOf(tile.a, tile.b) ?: return null
            val bit = 1 shl id
            if ((mask and bit) != 0) return null
            mask = mask or bit
        }
        return mask
    }

    private fun parseBoard(tiles: List<DominoVision.BoardTile>): Int? {
        if (tiles.size > 14) return null
        var mask = 0
        for (tile in tiles) {
            if (tile.a !in 0..6 || tile.b !in 0..6 || tile.confidence < 50) return null
            val id = idOf(tile.a, tile.b) ?: return null
            val bit = 1 shl id
            if ((mask and bit) != 0) return null
            mask = mask or bit
        }
        return mask
    }

    private fun buildObservation(
        handMask: Int,
        boardMask: Int,
        left: Int?,
        right: Int?,
        leader: String
    ): Observation? {
        val handCount = handMask.count()
        val boardCount = boardMask.count()
        val ownPlayed = PLAYER_TILES - handCount
        val opponentPlayed = boardCount - ownPlayed
        if (opponentPlayed !in 0..PLAYER_TILES) return null
        val opponentCount = PLAYER_TILES - opponentPlayed
        val unknownMask = ALL_MASK and handMask.inv() and boardMask.inv()
        if (unknownMask.count() != OUTSIDE_TILES + opponentCount) return null

        val reliable = tracker.reliable
        val passes = if (reliable) tracker.passConstraints.toList() else emptyList()
        val enemyPlays = if (reliable) tracker.enemyPlays.toList() else emptyList()
        if (passes.any { it.left !in 0..6 || it.right !in 0..6 || it.opponentPlaysAtThatTime !in 0..enemyPlays.size }) return null
        val historyHash = if (reliable) trackerHash() else stateHash(handMask, boardMask, left ?: -1, right ?: -1, leader.hashCode().toLong())
        return Observation(handMask, boardMask, unknownMask, opponentCount, left, right ?: -1,
            left != null && right != null, passes, enemyPlays, historyHash)
    }

    private fun updateTracker(
        handMask: Int,
        boardMask: Int,
        left: Int?,
        right: Int?,
        leader: String
    ) {
        if (tracker.starter != leader) tracker.reset(leader)
        val previous = tracker.previous
        val current = Snapshot(handMask, boardMask, left, right)
        if (previous == null) {
            // Si ZEUS se activa a mitad de una partida no inventa la secuencia
            // anterior; empieza a aprender eventos verificables desde ahora.
            if (boardMask != 0) tracker.reliable = false
            tracker.previous = current
            return
        }

        if (boardMask == 0 && handMask.count() == PLAYER_TILES && previous.boardMask != 0) {
            tracker.reset(leader)
            tracker.previous = current
            return
        }

        val added = boardMask and previous.boardMask.inv()
        val removed = previous.handMask and handMask.inv()
        val addedCount = added.count()
        if (addedCount == 0) {
            if (handMask != previous.handMask || left != previous.left || right != previous.right) {
                tracker.reliable = false
                tracker.enemyPlays.clear()
                tracker.passConstraints.clear()
                tracker.lastActor = null
            }
            tracker.previous = current
            return
        }

        if (addedCount != 1 || removed.count() > 1) {
            tracker.reliable = false
            tracker.enemyPlays.clear()
            tracker.passConstraints.clear()
            tracker.lastActor = null
            tracker.previous = current
            return
        }

        val id = Integer.numberOfTrailingZeros(added)
        val actor = when {
            removed.count() == 1 && removed == added -> 0 // usuario jugo
            removed == 0 -> 1 // rival jugo
            else -> -1
        }
        if (actor < 0) {
            tracker.reliable = false
            tracker.enemyPlays.clear()
            tracker.passConstraints.clear()
            tracker.lastActor = null
            tracker.previous = current
            return
        }

        if (tracker.reliable && tracker.lastActor != null && tracker.lastActor == actor) {
            val passer = 1 - actor
            val oldLeft = previous.left
            val oldRight = previous.right
            if (passer == 1 && oldLeft != null && oldRight != null) {
                // El rival paso: ninguna ficha de su mano en ese momento
                // podia conectar con ninguno de esos dos extremos.
                tracker.passConstraints.add(PassConstraint(oldLeft, oldRight, tracker.enemyPlays.size))
            } else if (passer == 0 && oldLeft != null && oldRight != null) {
                // El usuario solo puede pasar si no tenia ninguna jugada.
                if (legalMoves(previous.handMask, oldLeft, oldRight, false).isNotEmpty()) {
                    tracker.reliable = false
                    tracker.enemyPlays.clear()
                    tracker.passConstraints.clear()
                }
            }
        }

        if (actor == 1 && tracker.reliable) {
            tracker.enemyPlays.add(EnemyPlay(id, previous.left ?: -1, previous.right ?: -1, tracker.enemyPlays.size))
        }
        tracker.lastActor = actor
        tracker.previous = current
    }

    private fun trackerHash(): Long {
        var h = 0x6A09E667F3BCC909L
        for (event in tracker.enemyPlays) {
            h = mix64(h xor event.id.toLong() xor (event.leftBefore.toLong() shl 8) xor (event.rightBefore.toLong() shl 16))
        }
        for (pass in tracker.passConstraints) {
            h = mix64(h xor (pass.left.toLong() shl 8) xor (pass.right.toLong() shl 16) xor (pass.opponentPlaysAtThatTime.toLong() shl 24))
        }
        return h
    }

    private fun trackerDiagnostics(): String =
        "historial=${if (tracker.reliable) "fiable" else "parcial"}; pases=${tracker.passConstraints.size}; jugadasR=${tracker.enemyPlays.size}"

    private fun resultToPublic(result: SearchResult, observation: Observation): Result {
        val side = when (result.move.side) {
            0 -> "SALIDA"
            1 -> "IZQUIERDA"
            else -> "DERECHA"
        }
        val suggestion = suggestionFor(result.move, side, result.iterations, observation.left, observation.right)
        val diagnostics = "IS-MCTS=${result.iterations}; repartos=${result.validDeals}; " +
            "valor=${"%.3f".format(java.util.Locale.US, result.value)}; " +
            "${trackerDiagnostics()}"
        return Result(suggestion, "JUGADA LEGAL", diagnostics)
    }

    private fun suggestionFor(move: Move, side: String, iterations: Int, left: Int?, right: Int?): Suggestion {
        val a = tileA[move.id]
        val b = tileB[move.id]
        val tile = Tile(a, b)
        val oriented = when (move.side) {
            1 -> if (b == left) "$a-$b" else "$b-$a"
            2 -> if (a == right) "$a-$b" else "$b-$a"
            else -> "$a-$b"
        }
        val reason = "IS-MCTS · ${iterations} sim"
        return Suggestion(tile, side, oriented, reason)
    }

    private fun runSearch(observation: Observation): SearchResult {
        val seed = System.nanoTime() xor observation.publicHistoryHash xor observation.handMask.toLong()
        val remainingTiles = observation.handMask.count() + observation.opponentCount
        val adaptiveTarget = when {
            remainingTiles <= 4 -> 2200
            remainingTiles <= 6 -> 1800
            remainingTiles <= 9 -> 1250
            else -> TARGET_ITERATIONS
        }
        return search(observation, adaptiveTarget, MAX_SEARCH_NANOS, seed)
    }

    private fun search(observation: Observation, targetIterations: Int, maxNanos: Long, seed: Long): SearchResult {
        val rootMoves = if (!observation.hasBoard) legalMoves(observation.handMask, null, null, true)
            else legalMoves(observation.handMask, observation.left, observation.right, false)
        if (rootMoves.isEmpty()) return SearchResult(Move(0, 0), 0, 0, -1.0)

        val random = Random(seed)
        val nodes = HashMap<InfoKey, Node>(4096)
        val rootKey = infoKey(
            State(observation.handMask, 0, observation.left ?: -1, observation.right,
                observation.hasBoard, observation.playedMask, 0, 0, observation.publicHistoryHash)
        )
        nodes[rootKey] = Node(rootMoves)
        val start = System.nanoTime()
        var iterations = 0
        var validDeals = 0

        while (iterations < targetIterations) {
            if (iterations >= MIN_ITERATIONS && System.nanoTime() - start >= maxNanos) break
            val sampled = sampleOpponentHand(observation, random) ?: break
            validDeals++
            val root = State(
                observation.handMask, sampled, observation.left ?: -1, observation.right,
                observation.hasBoard, observation.playedMask, 0, 0, observation.publicHistoryHash
            )
            runIteration(root, nodes, random)
            iterations++
        }

        val root = nodes[rootKey] ?: return SearchResult(rootMoves.first(), iterations, validDeals, 0.0)
        val chosen = root.edges.values
            .filter { it.visits > 0 }
            .maxWithOrNull(compareBy<Edge> { it.valueSum / it.visits }.thenBy { it.visits })
            ?: root.edges.values.maxByOrNull { it.visits }
            ?: return SearchResult(rootMoves.first(), iterations, validDeals, 0.0)
        val mean = if (chosen.visits == 0) 0.0 else chosen.valueSum / chosen.visits
        return SearchResult(chosen.move, iterations, validDeals, mean)
    }

    private fun runIteration(root: State, nodes: MutableMap<InfoKey, Node>, random: Random): Double {
        var state = root
        val path = ArrayList<Pair<Node, Edge>>(24)
        var depth = 0
        var reward: Double

        while (depth < MAX_PLY) {
            reward = terminalValue(state)
            if (!reward.isNaN()) break

            val actorHand = if (state.turn == 0) state.mine else state.rival
            val moves = legalMoves(actorHand, if (state.hasBoard) state.left else null,
                if (state.hasBoard) state.right else null, !state.hasBoard)

            if (moves.isEmpty()) {
                state = pass(state)
                depth++
                continue
            }

            val key = infoKey(state)
            val node = nodes.getOrPut(key) { Node(moves) }
            node.visits++

            val edge = if (node.unexpanded.isNotEmpty()) {
                val index = random.nextInt(node.unexpanded.size)
                val moveKey = node.unexpanded.removeAt(index)
                node.edges[moveKey] ?: continue
            } else {
                selectUct(node, state.turn, random)
            }
            path.add(node to edge)
            state = applyMove(state, edge.move)
            depth++

            if (edge.visits == 0) {
                break
            }
        }

        reward = if (!terminalValue(state).isNaN()) terminalValue(state)
            else rollout(state, random)
        for ((_, edge) in path) {
            edge.visits++
            edge.valueSum += reward
        }
        return reward
    }

    private fun selectUct(node: Node, actor: Int, random: Random): Edge {
        val logParent = ln((node.visits + 1).toDouble())
        var best: Edge? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (edge in node.edges.values) {
            val mean = if (edge.visits == 0) 0.0 else edge.valueSum / edge.visits
            val exploitation = if (actor == 0) mean else -mean
            val exploration = C_UCT * sqrt(logParent / (edge.visits + 1.0))
            val jitter = random.nextDouble() * 1e-7
            val score = exploitation + exploration + jitter
            if (score > bestScore) {
                bestScore = score
                best = edge
            }
        }
        return best ?: node.edges.values.first()
    }

    private fun rollout(initial: State, random: Random): Double {
        var state = initial
        val style = styles[random.nextInt(styles.size)]
        var ply = 0
        while (ply < MAX_PLY) {
            val terminal = terminalValue(state)
            if (!terminal.isNaN()) return terminal
            val hand = if (state.turn == 0) state.mine else state.rival
            val moves = legalMoves(hand, if (state.hasBoard) state.left else null,
                if (state.hasBoard) state.right else null, !state.hasBoard)
            if (moves.isEmpty()) {
                state = pass(state)
            } else {
                val move = moves.maxByOrNull { candidate ->
                    rolloutScore(state, candidate, style) + (random.nextDouble() - 0.5) * 0.32
                } ?: moves.first()
                state = applyMove(state, move)
            }
            ply++
        }
        return terminalValue(state).let { if (it.isNaN()) 0.0 else it }
    }

    private fun rolloutScore(state: State, move: Move, style: RolloutStyle): Double {
        val actorHand = if (state.turn == 0) state.mine else state.rival
        val after = actorHand and (1 shl move.id).inv()
        val tA = tileA[move.id]
        val tB = tileB[move.id]
        val newLeft: Int
        val newRight: Int
        when (move.side) {
            0 -> { newLeft = tA; newRight = tB }
            1 -> {
                val old = state.left
                newLeft = if (tA == old) tB else tA
                newRight = state.right
            }
            else -> {
                val old = state.right
                newLeft = state.left
                newRight = if (tA == old) tB else tA
            }
        }
        val mobility = if (after == 0) 0 else legalMoves(after, newLeft, newRight, false).map { it.id }.distinct().size
        val support = countValue(after, newLeft) + countValue(after, newRight)
        val orphanDoubles = (0 until MAX_TILE_ID).count { id ->
            (after and (1 shl id)) != 0 && tileA[id] == tileB[id] &&
                tileA[id] != newLeft && tileA[id] != newRight
        }
        val otherHand = if (state.turn == 0) state.rival else state.mine
        val opponentResponse = probabilityOpponentCanPlay(state, after, otherHand.count(), newLeft, newRight, move.id)
        val blockChance = 1.0 - opponentResponse
        val pipsReleased = tilePips[move.id]
        val distinctValues = countDistinctValues(after)
        return pipsReleased * style.pipWeight +
            mobility * style.mobilityWeight +
            support * style.supportWeight +
            blockChance * style.blockWeight +
            distinctValues * 0.08 - orphanDoubles * style.doublePenalty
    }

    private fun probabilityOpponentCanPlay(state: State, actingHandAfter: Int, opponentCount: Int, left: Int, right: Int, playedNow: Int): Double {
        val unknown = ALL_MASK and state.played.inv() and actingHandAfter.inv() and (1 shl playedNow).inv()
        val n = unknown.count()
        if (n <= 0 || opponentCount <= 0) return 0.0
        var eligibleMask = 0
        for (id in 0 until MAX_TILE_ID) if ((unknown and (1 shl id)) != 0) {
            if (tileA[id] == left || tileB[id] == left || tileA[id] == right || tileB[id] == right) {
                eligibleMask = eligibleMask or (1 shl id)
            }
        }
        val eligible = eligibleMask.count()
        val k = opponentCount.coerceAtMost(n)
        if (eligible == 0) return 0.0
        if (n - eligible < k) return 1.0
        var pNone = 1.0
        for (i in 0 until k) pNone *= (n - eligible - i).toDouble() / (n - i).toDouble()
        return (1.0 - pNone).coerceIn(0.0, 1.0)
    }

    private fun sampleOpponentHand(observation: Observation, random: Random): Int? {
        val unknownIds = IntArray(observation.unknownMask.count())
        var index = 0
        for (id in 0 until MAX_TILE_ID) if ((observation.unknownMask and (1 shl id)) != 0) unknownIds[index++] = id
        if (observation.opponentCount == 0) return 0
        if (observation.opponentCount > unknownIds.size) return null

        val candidateCount = if (observation.enemyPlays.isEmpty()) 1 else 8
        val candidates = ArrayList<Pair<Int, Double>>(candidateCount)
        val maxAttempts = 240
        repeat(candidateCount) {
            var candidate: Int? = null
            var attempts = 0
            while (candidate == null && attempts < maxAttempts) {
                attempts++
                val shuffled = unknownIds.copyOf()
                for (i in shuffled.lastIndex downTo 1) {
                    val j = random.nextInt(i + 1)
                    val tmp = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = tmp
                }
                var mask = 0
                for (i in 0 until observation.opponentCount) mask = mask or (1 shl shuffled[i])
                if (satisfiesPasses(mask, observation)) candidate = mask
            }
            if (candidate != null) {
                val likelihood = if (observation.enemyPlays.isEmpty()) 0.0
                    else opponentHistoryLogLikelihood(candidate, observation)
                candidates.add(candidate to likelihood)
            }
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].first
        val maxLog = candidates.maxOf { it.second }
        val weights = candidates.map { kotlin.math.exp((it.second - maxLog).coerceIn(-18.0, 0.0)) }
        val total = weights.sum()
        var draw = random.nextDouble() * total
        for (i in candidates.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return candidates[i].first
        }
        return candidates.last().first
    }

    private fun satisfiesPasses(candidate: Int, observation: Observation): Boolean {
        for (constraint in observation.passConstraints) {
            var handAtPass = candidate
            for (i in constraint.opponentPlaysAtThatTime until observation.enemyPlays.size) {
                handAtPass = handAtPass or (1 shl observation.enemyPlays[i].id)
            }
            if (legalMoves(handAtPass, constraint.left, constraint.right, false).isNotEmpty()) return false
        }
        return true
    }

    /**
     * Likelihood suave de las fichas que el rival eligio en el pasado. Se mezcla
     * una politica tactica con una parte uniforme para no asumir un estilo unico.
     */
    private fun opponentHistoryLogLikelihood(candidate: Int, observation: Observation): Double {
        var logLikelihood = 0.0
        for (eventIndex in observation.enemyPlays.indices) {
            val event = observation.enemyPlays[eventIndex]
            var historicalHand = candidate
            for (i in event.opponentPlayIndex until observation.enemyPlays.size) {
                historicalHand = historicalHand or (1 shl observation.enemyPlays[i].id)
            }
            val openingEvent = event.leftBefore < 0 || event.rightBefore < 0
            val moves = if (openingEvent) legalMoves(historicalHand, null, null, true)
                else legalMoves(historicalHand, event.leftBefore, event.rightBefore, false)
            if (moves.isEmpty() || moves.none { it.id == event.id }) return -1e6
            val scores = if (openingEvent) moves.map { historyOpeningPolicyScore(historicalHand, it) }
                else moves.map { historyPolicyScore(historicalHand, it, event.leftBefore, event.rightBefore) }
            val maxScore = scores.maxOrNull() ?: 0.0
            val expScores = scores.map { kotlin.math.exp(((it - maxScore) / 2.4).coerceIn(-12.0, 0.0)) }
            val denominator = expScores.sum().coerceAtLeast(1e-12)
            val selectedProbability = moves.indices.filter { moves[it].id == event.id }
                .sumOf { expScores[it] } / denominator
            val uniformProbability = moves.count { it.id == event.id }.toDouble() / moves.size
            val probability = (0.58 * selectedProbability + 0.42 * uniformProbability).coerceIn(1e-6, 1.0)
            logLikelihood += ln(probability)
        }
        return logLikelihood
    }


    private fun historyOpeningPolicyScore(hand: Int, move: Move): Double {
        val after = hand and (1 shl move.id).inv()
        val a = tileA[move.id]; val b = tileB[move.id]
        val mobility = if (after == 0) 0 else legalMoves(after, a, b, false).map { it.id }.distinct().size
        val doubles = if (a == b) 1.0 else 0.0
        return tilePips[move.id] * 0.09 + mobility * 0.42 + doubles * 0.35
    }

    private fun historyPolicyScore(hand: Int, move: Move, left: Int, right: Int): Double {
        val after = hand and (1 shl move.id).inv()
        val a = tileA[move.id]; val b = tileB[move.id]
        val nl: Int; val nr: Int
        if (move.side == 1) { nl = if (a == left) b else a; nr = right }
        else { nl = left; nr = if (a == right) b else a }
        val mobility = legalMoves(after, nl, nr, false).map { it.id }.distinct().size
        val support = countValue(after, nl) + countValue(after, nr)
        val doubles = (0 until MAX_TILE_ID).count { id ->
            (after and (1 shl id)) != 0 && tileA[id] == tileB[id] && tileA[id] != nl && tileA[id] != nr
        }
        return tilePips[move.id] * 0.11 + mobility * 0.48 + support * 0.25 - doubles * 0.24
    }

    private fun legalMoves(hand: Int, left: Int?, right: Int?, opening: Boolean): List<Move> {
        val result = ArrayList<Move>(14)
        for (id in 0 until MAX_TILE_ID) {
            if ((hand and (1 shl id)) == 0) continue
            val a = tileA[id]; val b = tileB[id]
            if (opening || (left == null && right == null)) {
                result.add(Move(id, 0))
                continue
            }
            if (left != null && (a == left || b == left)) result.add(Move(id, 1))
            if (right != null && (a == right || b == right)) result.add(Move(id, 2))
        }
        return result
    }

    private fun applyMove(state: State, move: Move): State {
        val bit = 1 shl move.id
        val actorHand = if (state.turn == 0) state.mine else state.rival
        val after = actorHand and bit.inv()
        val newMine = if (state.turn == 0) after else state.mine
        val newRival = if (state.turn == 1) after else state.rival
        val a = tileA[move.id]; val b = tileB[move.id]
        val newLeft: Int
        val newRight: Int
        when (move.side) {
            0 -> { newLeft = a; newRight = b }
            1 -> { newLeft = if (a == state.left) b else a; newRight = state.right }
            else -> { newLeft = state.left; newRight = if (a == state.right) b else a }
        }
        val history = mix64(state.history xor (move.id.toLong() shl 3) xor (move.side.toLong() shl 1) xor state.turn.toLong() xor 0x13579BDFL)
        return State(newMine, newRival, newLeft, newRight, true, state.played or bit,
            1 - state.turn, 0, history)
    }

    private fun pass(state: State): State {
        val history = mix64(state.history xor (0xF00D0000L + state.turn.toLong()))
        return state.copy(turn = 1 - state.turn, passStreak = state.passStreak + 1, history = history)
    }

    private fun terminalValue(state: State): Double {
        if (state.mine == 0 && state.rival == 0) return 0.0
        if (state.mine == 0) return 0.90 + 0.10 * (pipSum(state.rival) / 84.0)
        if (state.rival == 0) return -0.90 - 0.10 * (pipSum(state.mine) / 84.0)
        if (state.passStreak >= 2) {
            val myPoints = pipSum(state.mine)
            val rivalPoints = pipSum(state.rival)
            val difference = ((rivalPoints - myPoints) / 84.0).coerceIn(-1.0, 1.0)
            val winner = when {
                myPoints < rivalPoints -> 0.90
                myPoints > rivalPoints -> -0.90
                else -> 0.0
            }
            return winner + 0.10 * difference
        }
        return Double.NaN
    }

    private fun infoKey(state: State): InfoKey = InfoKey(
        state.turn,
        if (state.turn == 0) state.mine else state.rival,
        state.played,
        state.left,
        state.right,
        state.hasBoard,
        state.passStreak,
        state.history
    )

    private fun moveKey(move: Move): Int = move.id * 3 + move.side

    private fun idOf(a: Int, b: Int): Int? {
        if (a !in 0..6 || b !in 0..6) return null
        return tileId[a][b].takeIf { it >= 0 }
    }

    private fun countValue(mask: Int, value: Int): Int {
        var count = 0
        for (id in 0 until MAX_TILE_ID) if ((mask and (1 shl id)) != 0 && (tileA[id] == value || tileB[id] == value)) count++
        return count
    }

    private fun countDistinctValues(mask: Int): Int {
        var values = 0
        for (id in 0 until MAX_TILE_ID) if ((mask and (1 shl id)) != 0) {
            values = values or (1 shl tileA[id]) or (1 shl tileB[id])
        }
        return values.count()
    }

    private fun pipSum(mask: Int): Int {
        var total = 0
        for (id in 0 until MAX_TILE_ID) if ((mask and (1 shl id)) != 0) total += tilePips[id]
        return total
    }

    private fun Int.count(): Int = Integer.bitCount(this)

    private fun stateHash(a: Int, b: Int, c: Int, d: Int, extra: Long): Long =
        mix64(a.toLong() xor (b.toLong() shl 28) xor (c.toLong() shl 4) xor (d.toLong() shl 12) xor extra)

    private fun mix64(input: Long): Long {
        var z = input + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}
