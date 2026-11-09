package com.zeusvision.app

import java.util.Random
import java.util.concurrent.atomic.AtomicLong
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
 *  - Priorizacion PUCT y evaluacion de frontera con una red neuronal local
 *    opcional. Sin modelo compatible, conserva el comportamiento IS-MCTS base.
 *
 * El entrenamiento se ejecuta fuera de Android; la inferencia es local/offline.
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
    private const val C_PUCT = 0.62
    private const val NEURAL_ROLLOUT_DEPTH = 12
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
        var lastObservedTurn: DominoVision.ActivePlayer = DominoVision.ActivePlayer.UNKNOWN
        var reliable: Boolean = true
        var passedValuesByUser: Int = 0
        var passedValuesByRival: Int = 0
        val enemyPlays = mutableListOf<EnemyPlay>()
        val passConstraints = mutableListOf<PassConstraint>()

        fun reset(newStarter: String) {
            starter = newStarter
            previous = null
            lastActor = null
            lastObservedTurn = DominoVision.ActivePlayer.UNKNOWN
            reliable = true
            passedValuesByUser = 0
            passedValuesByRival = 0
            enemyPlays.clear()
            passConstraints.clear()
        }
    }

    private data class Observation(
        val handMask: Int,
        val playedMask: Int,
        val unknownMask: Int,
        val opponentCount: Int,
        val passedValuesByUser: Int,
        val passedValuesByRival: Int,
        val left: Int?,
        val right: Int,
        val hasBoard: Boolean,
        val posterior: DominoOpponentInference.Posterior,
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
        val history: Long,
        val passedValuesByUser: Int = 0,
        val passedValuesByRival: Int = 0
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

    private class Edge(val move: Move, val prior: Double) {
        var visits: Int = 0
        var valueSum: Double = 0.0 // utilidad desde la perspectiva de ZEUS
    }

    private class Node(moves: List<Move>, priors: DoubleArray? = null) {
        var visits: Int = 0
        val hasPolicyPrior: Boolean = priors != null
        val edges = LinkedHashMap<Int, Edge>()
        val unexpanded = moves.map { moveKey(it) }.toMutableList()

        init {
            val priorByMove = HashMap<Int, Double>()
            if (priors != null && priors.size == moves.size) {
                for (i in moves.indices) priorByMove[moveKey(moves[i])] = priors[i]
            }
            val uniform = if (moves.isEmpty()) 0.0 else 1.0 / moves.size
            for (move in moves) {
                val key = moveKey(move)
                edges[key] = Edge(move, priorByMove[key] ?: uniform)
            }
        }
    }

    private data class SearchResult(
        val move: Move,
        val iterations: Int,
        val validDeals: Int,
        val value: Double,
        val cancelled: Boolean = false
    )

    private data class DecisionKey(
        val handMask: Int,
        val boardMask: Int,
        val left: Int,
        val right: Int,
        val leader: String,
        val historyHash: Long,
        val userScore: Int,
        val opponentScore: Int,
        val opponentProfileHash: Long
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
    private val searchGeneration = AtomicLong(0L)
    private var lastDecisionKey: DecisionKey? = null
    private var lastDecisionResult: Result? = null
    @Volatile private var neuralModel: DominoNeuralModel? = null

    /** Instala un modelo validado. Pasar null desactiva la red y conserva IS-MCTS. */
    @Synchronized
    fun installNeuralModel(model: DominoNeuralModel?) {
        neuralModel = model
        lastDecisionKey = null
        lastDecisionResult = null
    }

    fun isNeuralModelLoaded(): Boolean = neuralModel != null

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

    /** Invalida cualquier busqueda en curso para que una captura nueva no muestre una sugerencia obsoleta. */
    fun cancelPendingSearch() {
        searchGeneration.incrementAndGet()
    }

    /** API anterior conservada por compatibilidad con llamadas existentes. */
    @Synchronized
    fun suggest(
        handTiles: List<DominoVision.Tile>,
        left: Int?,
        right: Int?,
        leader: String
    ): Result = suggest(handTiles, left, right, leader, emptyList(), DominoVision.ActivePlayer.USER)

    /** API anterior conservada: las llamadas antiguas se interpretan como turno del usuario. */
    @Synchronized
    fun suggest(
        handTiles: List<DominoVision.Tile>,
        left: Int?,
        right: Int?,
        leader: String,
        boardTiles: List<DominoVision.BoardTile>
    ): Result = suggest(handTiles, left, right, leader, boardTiles, DominoVision.ActivePlayer.USER)

    /**
     * Ruta explicita para la salida manual. Se usa solo si la capa temporal
     * confirma una mesa vacia y las siete fichas propias. Ignora ambos
     * indicadores de turno por diseno, pero conserva todas las validaciones
     * de fichas, particion del reparto, inferencia y legalidad.
     */
    @Synchronized
    fun suggestOpening(state: DominoGameState): Result {
        val openingCheck = DominoOpeningGate.evaluate(manualSelection = true, state = state)
        if (!openingCheck.allowed) return Result(null, openingCheck.message)
        DominoExpertHeuristics.setMatchScore(state.userScore, state.opponentScore)
        DominoOpponentProfile.observe(state.passHistory + state.moveHistory)
        tracker.reset("TÚ")
        lastDecisionKey = null
        lastDecisionResult = null
        return suggest(state.ownHand, null, null, "TÚ", emptyList(), DominoVision.ActivePlayer.UNKNOWN)
    }

    /** Entrada principal 1.3: solo acepta estados temporales confirmados. */
    @Synchronized
    fun suggest(state: DominoGameState): Result {
        if (state.confidence != DominoGameState.Confidence.CONFIRMED) {
            return Result(null, "ESTADO NO CONFIRMADO · ESPERANDO", "no se ejecuta busqueda sobre una lectura inestable")
        }
        if (!state.boardConfirmed) {
            return Result(null, "NO PUEDO CONFIRMAR LA MESA", "la lectura temporal no confirma la mesa")
        }
        DominoOpponentProfile.observe(state.passHistory + state.moveHistory)
        DominoExpertHeuristics.setMatchScore(state.userScore, state.opponentScore)
        synchronizeTrackerFromState(state)
        // La ruta normal nunca infiere la salida por el valor predeterminado del líder.
        // Solo suggestOpening(), invocado tras pulsar SALGO YO, puede ignorar los indicadores.
        if (!state.turnConfirmed) {
            return Result(null, "NO PUEDO CONFIRMAR EL TURNO", "la lectura del aro/turno no alcanza la confianza minima")
        }
        if (state.currentTurn == DominoVision.ActivePlayer.OPPONENT) {
            return Result(null, "TURNO DEL RIVAL · ESPERANDO", "no se ejecuta estrategia durante el turno rival")
        }
        return suggest(state.ownHand, state.leftEnd, state.rightEnd, state.roundStarter,
            state.boardTiles, state.currentTurn)
    }

    /** API con turno visual explícito. El líder de la ronda NO es el turno actual. */
    @Synchronized
    fun suggest(
        handTiles: List<DominoVision.Tile>,
        left: Int?,
        right: Int?,
        leader: String,
        boardTiles: List<DominoVision.BoardTile>,
        activePlayer: DominoVision.ActivePlayer
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

        updateTracker(parsedHand, boardMask, left, right, normalizedLeader, activePlayer)

        val confirmedOpening = boardMask == 0 && left == null && right == null &&
            parsedHand.count() == PLAYER_TILES
        if (confirmedOpening) {
            // SALGO YO confirma la salida inicial. En este único caso la selección
            // manual prevalece sobre ambos indicadores visuales; después se exige
            // detección normal del turno en todas las jugadas restantes.
            if (normalizedLeader == "RIVAL") {
                return Result(null, "SALIDA DEL RIVAL · ESPERANDO", trackerDiagnostics())
            }
        } else {
            if (activePlayer == DominoVision.ActivePlayer.UNKNOWN) {
                return Result(null, "NO PUEDO IDENTIFICAR EL TURNO", trackerDiagnostics())
            }
            if (activePlayer == DominoVision.ActivePlayer.OPPONENT) {
                return Result(null, "TURNO DEL RIVAL · ESPERANDO", trackerDiagnostics())
            }
        }

        if (boardMask == 0 && left == null && right == null) {
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

    /**
     * Importa el historial que ya reconstruyo el filtro temporal. Asi la capa
     * visual y el motor no mantienen dos historiales rivales independientes.
     */
    private fun synchronizeTrackerFromState(state: DominoGameState) {
        val handMask = parseHand(state.ownHand) ?: return
        val boardMask = parseBoard(state.boardTiles) ?: return
        val starter = if (state.roundStarter.equals("RIVAL", true)) "RIVAL" else "TÚ"
        val imported = Tracker()
        imported.reset(starter)

        val events = (state.passHistory + state.moveHistory).sortedBy { it.timestampMs }
        for (event in events) {
            if (event.confidence != DominoGameState.Confidence.CONFIRMED) {
                imported.reliable = false
                continue
            }
            val left = event.leftBefore
            val right = event.rightBefore
            when (event.type) {
                DominoGameState.EventType.OPPONENT_PLAY -> {
                    val a = event.tileA ?: continue
                    val b = event.tileB ?: continue
                    val id = idOf(a, b) ?: continue
                    imported.enemyPlays.add(EnemyPlay(id, left ?: -1, right ?: -1,
                        imported.enemyPlays.size))
                    imported.lastActor = 1
                }
                DominoGameState.EventType.USER_PLAY -> imported.lastActor = 0
                DominoGameState.EventType.OPPONENT_PASS -> {
                    if (left in 0..6 && right in 0..6) {
                        imported.passConstraints.add(PassConstraint(left!!, right!!,
                            imported.enemyPlays.size))
                        imported.passedValuesByRival = imported.passedValuesByRival or
                            (1 shl left) or (1 shl right)
                    } else imported.reliable = false
                }
                DominoGameState.EventType.USER_PASS -> {
                    if (left in 0..6 && right in 0..6) {
                        imported.passedValuesByUser = imported.passedValuesByUser or
                            (1 shl left!!) or (1 shl right!!)
                    }
                }
                DominoGameState.EventType.CONFLICT -> imported.reliable = false
                DominoGameState.EventType.NEW_ROUND -> Unit
            }
        }
        imported.previous = Snapshot(handMask, boardMask, state.leftEnd, state.rightEnd)
        imported.lastObservedTurn = state.currentTurn
        tracker = imported
    }

    private fun opponentProfileHash(): Long {
        val p = DominoOpponentProfile.snapshot()
        var hash = p.observedPlays.toLong() * 31L + p.observedPasses
        hash = mix64(hash xor p.meanPipsPerPlay.toBits())
        hash = mix64(hash xor p.earlyDoubleRate.toBits())
        return hash
    }

    private fun calculateOrReuse(observation: Observation, leader: String): Result {
        val key = DecisionKey(
            observation.handMask,
            observation.playedMask,
            observation.left ?: -1,
            observation.right,
            leader,
            observation.publicHistoryHash,
            DominoExpertHeuristics.currentMatchContext().userScore ?: -1,
            DominoExpertHeuristics.currentMatchContext().opponentScore ?: -1,
            opponentProfileHash()
        )
        if (key == lastDecisionKey) lastDecisionResult?.let { return it }
        val generation = searchGeneration.get()

        val exact = if (observation.hasBoard &&
            observation.handMask.count() + observation.opponentCount <= 8 &&
            observation.posterior.compatibleHandCount == 1) {
            DominoExactSolver.solve(
                ownHandMask = observation.handMask,
                posterior = observation.posterior,
                left = observation.left,
                right = observation.right,
                hasBoard = true
            )
        } else null
        if (searchGeneration.get() != generation) {
            return Result(null, "ESTADO CAMBIÓ · RECALCULANDO", "se descartó una búsqueda anterior")
        }
        val result = if (exact != null) {
            val move = Move(exact.actionKey / 3, exact.actionKey % 3)
            val side = when (move.side) { 0 -> "SALIDA"; 1 -> "IZQUIERDA"; else -> "DERECHA" }
            val suggestion = suggestionFor(move, side, exact.nodes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                observation.left, observation.right)
            Result(suggestion, "SOLUCIÓN EXACTA · MANO RIVAL DEDUCIDA",
                "Solver minimax exacto con una unica mano rival compatible; nodos=${exact.nodes}; " +
                    "P(victoria)=${"%.1f".format(java.util.Locale.US, exact.winProbability * 100)}%; " +
                    "margen=${"%.2f".format(java.util.Locale.US, exact.expectedPipMargin)}; ${trackerDiagnostics()}")
        } else {
            val search = runSearch(observation, generation)
            if (search.cancelled || searchGeneration.get() != generation) {
                return Result(null, "ESTADO CAMBIÓ · RECALCULANDO", "se canceló la búsqueda por una captura más reciente")
            }
            if (search.validDeals == 0) {
                Result(null, "NO PUEDO VALIDAR LAS MANOS OCULTAS", trackerDiagnostics())
            } else resultToPublic(search, observation)
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

        val posterior = DominoOpponentInference.infer(DominoOpponentInference.Request(
            knownHandMask = handMask,
            playedMask = playedMask,
            unknownMask = unknown,
            opponentCount = opponentCount,
            useActionLikelihood = false
        )) ?: return null
        val obs = Observation(
            handMask, playedMask, unknown, opponentCount, 0, 0, left, right ?: -1,
            board.isNotEmpty(), posterior, 0x5EEDL
        )
        val rootMoves = if (board.isEmpty()) legalMoves(handMask, null, null, true)
            else legalMoves(handMask, left, right, false)
        if (rootMoves.isEmpty()) return null
        val result = search(obs, iterations.coerceIn(80, 2500), 700_000_000L, seed)
        if (result.cancelled || result.validDeals == 0 ||
            rootMoves.none { it.id == result.move.id && it.side == result.move.side }) return null
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
        val passes = if (reliable) tracker.passConstraints.map {
            DominoOpponentInference.PassConstraint(it.left, it.right, it.opponentPlaysAtThatTime)
        } else emptyList()
        val enemyPlays = if (reliable) tracker.enemyPlays.map {
            DominoOpponentInference.EnemyPlay(it.id, it.leftBefore, it.rightBefore)
        } else emptyList()
        val posterior = DominoOpponentInference.infer(DominoOpponentInference.Request(
            knownHandMask = handMask,
            playedMask = boardMask,
            unknownMask = unknownMask,
            opponentCount = opponentCount,
            enemyPlays = enemyPlays,
            passConstraints = passes,
            useActionLikelihood = true
        )) ?: return null
        val historyHash = if (reliable) trackerHash() else stateHash(handMask, boardMask, left ?: -1, right ?: -1, leader.hashCode().toLong())
        return Observation(handMask, boardMask, unknownMask, opponentCount,
            if (reliable) tracker.passedValuesByUser else 0,
            if (reliable) tracker.passedValuesByRival else 0,
            left, right ?: -1, left != null && right != null, posterior, historyHash)
    }

    private fun updateTracker(
        handMask: Int,
        boardMask: Int,
        left: Int?,
        right: Int?,
        leader: String,
        activePlayer: DominoVision.ActivePlayer
    ) {
        if (tracker.starter != leader) tracker.reset(leader)
        val previous = tracker.previous
        val current = Snapshot(handMask, boardMask, left, right)

        // Si el aro pasa de rival a usuario y la mesa no cambia, el rival paso.
        // Esta señal se observa en tiempo real, sin esperar a una ficha nueva.
        if (previous != null &&
            tracker.lastObservedTurn == DominoVision.ActivePlayer.OPPONENT &&
            activePlayer == DominoVision.ActivePlayer.USER &&
            boardMask == previous.boardMask && left == previous.left && right == previous.right &&
            previous.left != null && previous.right != null) {
            tracker.passConstraints.add(PassConstraint(previous.left, previous.right, tracker.enemyPlays.size))
            tracker.passedValuesByRival = tracker.passedValuesByRival or
                (1 shl previous.left) or (1 shl previous.right)
            // Aunque ZEUS se active a mitad de la ronda, los eventos observados
            // desde este momento sí son evidencia válida.
            tracker.reliable = true
        }
        if (activePlayer != DominoVision.ActivePlayer.UNKNOWN) tracker.lastObservedTurn = activePlayer

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
                tracker.passedValuesByUser = 0
                tracker.passedValuesByRival = 0
                tracker.lastActor = null
            }
            tracker.previous = current
            return
        }

        if (addedCount != 1 || removed.count() > 1) {
            tracker.reliable = false
            tracker.enemyPlays.clear()
            tracker.passConstraints.clear()
            tracker.passedValuesByUser = 0
            tracker.passedValuesByRival = 0
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
            tracker.passedValuesByUser = 0
            tracker.passedValuesByRival = 0
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
                tracker.passedValuesByRival = tracker.passedValuesByRival or (1 shl oldLeft) or (1 shl oldRight)
            } else if (passer == 0 && oldLeft != null && oldRight != null) {
                // El usuario solo puede pasar si no tenia ninguna jugada.
                if (legalMoves(previous.handMask, oldLeft, oldRight, false).isNotEmpty()) {
                    tracker.reliable = false
                    tracker.enemyPlays.clear()
                    tracker.passConstraints.clear()
                    tracker.passedValuesByUser = 0
                    tracker.passedValuesByRival = 0
                } else {
                    tracker.passedValuesByUser = tracker.passedValuesByUser or (1 shl oldLeft) or (1 shl oldRight)
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
        h = mix64(h xor tracker.passedValuesByUser.toLong() xor (tracker.passedValuesByRival.toLong() shl 12))
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
        val likely = observation.posterior.topTileIds(3).joinToString(",") { (id, p) ->
            val x = tileA[id]; val y = tileB[id]
            "$x-$y:${"%.0f".format(java.util.Locale.US, p * 100)}%"
        }
        val diagnostics = "IS-MCTS=${result.iterations}; repartos=${result.validDeals}; " +
            "manosCompatibles=${observation.posterior.compatibleHandCount}; P(rival)=$likely; " +
            "valor=${"%.3f".format(java.util.Locale.US, result.value)}; ${trackerDiagnostics()}"
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
        val engineLabel = if (neuralModel != null) "IS-MCTS + RED" else "IS-MCTS"
        val reason = "$engineLabel · ${iterations} sim"
        return Suggestion(tile, side, oriented, reason)
    }

    private fun runSearch(observation: Observation, generation: Long = searchGeneration.get()): SearchResult {
        val seed = System.nanoTime() xor observation.publicHistoryHash xor observation.handMask.toLong()
        val remainingTiles = observation.handMask.count() + observation.opponentCount
        val baseTarget = when {
            remainingTiles <= 4 -> 2200
            remainingTiles <= 6 -> 1800
            remainingTiles <= 9 -> 1250
            else -> TARGET_ITERATIONS
        }
        // Si el posterior conserva muchas manos con pesos similares, dedicar un
        // poco mas de muestras al IS-MCTS; si esta muy concentrado, no malgastar
        // iteraciones. El limite temporal global permanece intacto.
        val posterior = observation.posterior
        val maxEntropy = kotlin.math.ln(posterior.compatibleHandCount.coerceAtLeast(2).toDouble()) / kotlin.math.ln(2.0)
        val normalizedEntropy = if (maxEntropy <= 0.0) 0.0
            else (posterior.entropyBits / maxEntropy).coerceIn(0.0, 1.0)
        val uncertaintyFactor = when {
            normalizedEntropy >= 0.80 -> 1.10
            normalizedEntropy <= 0.35 -> 0.92
            else -> 1.0
        }
        val adaptiveTarget = (baseTarget * uncertaintyFactor).toInt().coerceAtLeast(MIN_ITERATIONS)
        return search(observation, adaptiveTarget, MAX_SEARCH_NANOS, seed, generation)
    }

    private fun search(
        observation: Observation,
        targetIterations: Int,
        maxNanos: Long,
        seed: Long,
        generation: Long = searchGeneration.get()
    ): SearchResult {
        val rootMoves = if (!observation.hasBoard) legalMoves(observation.handMask, null, null, true)
            else legalMoves(observation.handMask, observation.left, observation.right, false)
        if (rootMoves.isEmpty()) return SearchResult(Move(0, 0), 0, 0, -1.0)

        val random = Random(seed)
        val nodes = HashMap<InfoKey, Node>(4096)
        // Root IS-MCTS state uses a placeholder opponent mask with the known
        // count only; identities remain hidden and are never fed to the network.
        val rootOpponentPlaceholder = firstUnknownMask(observation.unknownMask, observation.opponentCount)
        val rootState = State(observation.handMask, rootOpponentPlaceholder, observation.left ?: -1, observation.right,
            observation.hasBoard, observation.playedMask, 0, 0, observation.publicHistoryHash,
            observation.passedValuesByUser, observation.passedValuesByRival)
        val rootKey = infoKey(rootState)
        nodes[rootKey] = createNode(rootState, rootMoves)
        val start = System.nanoTime()
        var iterations = 0
        var validDeals = 0

        while (iterations < targetIterations) {
            if (searchGeneration.get() != generation) {
                return SearchResult(rootMoves.first(), iterations, validDeals, 0.0, cancelled = true)
            }
            if (iterations >= MIN_ITERATIONS && System.nanoTime() - start >= maxNanos) break
            val sampled = sampleOpponentHand(observation, random) ?: break
            validDeals++
            val root = State(
                observation.handMask, sampled, observation.left ?: -1, observation.right,
                observation.hasBoard, observation.playedMask, 0, 0, observation.publicHistoryHash,
                observation.passedValuesByUser, observation.passedValuesByRival
            )
            runIteration(root, nodes, random)
            iterations++
        }

        val root = nodes[rootKey] ?: return SearchResult(rootMoves.first(), iterations, validDeals, 0.0)
        val lookahead = try {
            DominoSelectiveLookahead.evaluateRootMoves(
                ownHandMask = observation.handMask,
                posterior = observation.posterior,
                left = observation.left,
                right = if (observation.hasBoard) observation.right else null,
                hasBoard = observation.hasBoard,
                actionKeys = rootMoves.map { moveKey(it) },
                seed = seed,
                sampleCount = 8,
                depthAfterRoot = 2,
                timeBudgetNanos = 28_000_000L
            )
        } catch (_: Exception) { null }
        val chosen = root.edges.values
            .filter { it.visits > 0 }
            .maxWithOrNull(compareBy<Edge> {
                val mcts = it.valueSum / it.visits
                val shallow = lookahead?.get(moveKey(it.move))
                if (shallow == null) mcts else 0.90 * mcts + 0.10 * shallow
            }.thenBy { it.visits })
            ?: root.edges.values.maxByOrNull { it.visits }
            ?: return SearchResult(rootMoves.first(), iterations, validDeals, 0.0)
        val mean = if (chosen.visits == 0) 0.0 else chosen.valueSum / chosen.visits
        return SearchResult(chosen.move, iterations, validDeals, mean,
            cancelled = searchGeneration.get() != generation)
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
            val node = nodes.getOrPut(key) { createNode(state, moves) }
            node.visits++

            val edge = if (node.unexpanded.isNotEmpty()) {
                val moveKey = if (node.hasPolicyPrior) {
                    node.unexpanded.maxByOrNull { node.edges[it]?.prior ?: 0.0 }
                        ?: node.unexpanded[random.nextInt(node.unexpanded.size)]
                } else {
                    node.unexpanded[random.nextInt(node.unexpanded.size)]
                }
                node.unexpanded.remove(moveKey)
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
            val neuralPrior = if (node.hasPolicyPrior) {
                C_PUCT * edge.prior * sqrt(node.visits + 1.0) / (1.0 + edge.visits)
            } else 0.0
            val jitter = random.nextDouble() * 1e-7
            val score = exploitation + exploration + neuralPrior + jitter
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
        val modelAvailable = neuralModel != null
        val rolloutLimit = if (modelAvailable) NEURAL_ROLLOUT_DEPTH else MAX_PLY
        while (ply < rolloutLimit) {
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
        val terminal = terminalValue(state)
        if (!terminal.isNaN()) return terminal
        val prediction = neuralModel?.predict(neuralFeatures(state))
        if (prediction != null) {
            val roundValue = 0.78f * (2f * prediction.roundWinProbability - 1f) +
                0.22f * prediction.roundMargin
            val matchValue = 2f * prediction.matchWinProbability - 1f
            // La cabeza de encuentro aporta una señal adicional aprendida en
            // partidas hasta 100 puntos; la ronda sigue dominando la evaluacion.
            val actorValue = (0.72f * roundValue + 0.28f * matchValue)
                .coerceIn(-1f, 1f).toDouble()
            return if (state.turn == 0) actorValue else -actorValue
        }
        return 0.0
    }

    private fun createNode(state: State, moves: List<Move>): Node {
        val actorHand = if (state.turn == 0) state.mine else state.rival
        val expertScores = DoubleArray(moves.size) { index ->
            val move = moves[index]
            DominoExpertHeuristics.scoreMove(
                actorHand = actorHand,
                moveId = move.id,
                side = move.side,
                actor = state.turn,
                left = state.left,
                right = state.right,
                hasBoard = state.hasBoard,
                passedValuesByUser = state.passedValuesByUser,
                passedValuesByRival = state.passedValuesByRival
            )
        }
        val expertPrior = DominoExpertHeuristics.softmax(expertScores)
        val model = neuralModel
        val prediction = try { model?.predict(neuralFeatures(state)) } catch (_: Exception) { null }
        if (prediction == null) return Node(moves, expertPrior)
        val keys = moves.map { moveKey(it) }
        val neuralPrior = DominoNeuralModel.maskedProbabilities(prediction.policyLogits, keys)
        // La red conserva el peso dominante; la heuristica solo aporta un sesgo suave.
        val blended = DoubleArray(moves.size) { i -> 0.88 * neuralPrior[i] + 0.12 * expertPrior[i] }
        val total = blended.sum().coerceAtLeast(1e-12)
        for (i in blended.indices) blended[i] /= total
        return Node(moves, blended)
    }

    private fun firstUnknownMask(unknownMask: Int, count: Int): Int {
        var mask = 0
        var selected = 0
        for (id in 0 until MAX_TILE_ID) {
            if ((unknownMask and (1 shl id)) != 0 && selected < count) {
                mask = mask or (1 shl id)
                selected++
            }
        }
        return mask
    }

    /**
     * Vector de 89 entradas. Solo contiene la mano privada del jugador al turno
     * y datos publicos; nunca incluye la mano oculta del adversario.
     * Orden exacto compartido con training/zeus_train.py.
     */
    private fun neuralFeatures(state: State): FloatArray {
        val features = FloatArray(DominoNeuralModel.INPUT_SIZE)
        val actorHand = if (state.turn == 0) state.mine else state.rival
        val opponentHand = if (state.turn == 0) state.rival else state.mine
        var index = 0
        for (id in 0 until MAX_TILE_ID) features[index++] = if ((actorHand and (1 shl id)) != 0) 1f else 0f
        for (id in 0 until MAX_TILE_ID) features[index++] = if ((state.played and (1 shl id)) != 0) 1f else 0f
        for (value in 0..6) features[index++] = if (state.hasBoard && state.left == value) 1f else 0f
        for (value in 0..6) features[index++] = if (state.hasBoard && state.right == value) 1f else 0f
        val opponentCount = opponentHand.count().coerceIn(0, 7)
        for (count in 0..7) features[index++] = if (opponentCount == count) 1f else 0f
        val passes = state.passStreak.coerceIn(0, 2)
        for (count in 0..2) features[index++] = if (passes == count) 1f else 0f
        features[index++] = if (state.hasBoard) 1f else 0f
        val opponentPassedValues = if (state.turn == 0) state.passedValuesByRival else state.passedValuesByUser
        for (value in 0..6) features[index++] = if ((opponentPassedValues and (1 shl value)) != 0) 1f else 0f
        check(index == DominoNeuralModel.INPUT_SIZE)
        return features
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
        val base = pipsReleased * style.pipWeight +
            mobility * style.mobilityWeight +
            support * style.supportWeight +
            blockChance * style.blockWeight +
            distinctValues * 0.08 - orphanDoubles * style.doublePenalty
        val expert = DominoExpertHeuristics.scoreMove(
            actorHand = actorHand,
            moveId = move.id,
            side = move.side,
            actor = state.turn,
            left = state.left,
            right = state.right,
            hasBoard = state.hasBoard,
            passedValuesByUser = state.passedValuesByUser,
            passedValuesByRival = state.passedValuesByRival
        )
        return base + expert * 0.30
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

    private fun sampleOpponentHand(observation: Observation, random: Random): Int? =
        if (observation.posterior.compatibleHandCount == 0) null else observation.posterior.sample(random)

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
            1 - state.turn, 0, history, state.passedValuesByUser, state.passedValuesByRival)
    }

    private fun pass(state: State): State {
        val history = mix64(state.history xor (0xF00D0000L + state.turn.toLong()))
        val passed = if (state.hasBoard) (1 shl state.left) or (1 shl state.right) else 0
        return if (state.turn == 0) {
            state.copy(turn = 1, passStreak = state.passStreak + 1, history = history,
                passedValuesByUser = state.passedValuesByUser or passed)
        } else {
            state.copy(turn = 0, passStreak = state.passStreak + 1, history = history,
                passedValuesByRival = state.passedValuesByRival or passed)
        }
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
