package com.zeusvision.app

import java.text.Normalizer

/**
 * Filtro temporal de capturas. Una lectura nueva no llega al motor estrategico
 * hasta permanecer estable en varias capturas consecutivas.
 */
class DominoTemporalTracker(private val requiredStableFrames: Int = 2) {
    data class Update(
        val confidence: DominoGameState.Confidence,
        val gameState: DominoGameState?,
        val status: String,
        val lastEvent: DominoGameState.GameEvent? = null
    )

    private var candidateSignature: String? = null
    private var candidateCount = 0
    private var stableAnalysis: DominoVision.Analysis? = null
    private var stableSignature: String? = null
    private val passEvents = ArrayList<DominoGameState.GameEvent>()
    private val moveEvents = ArrayList<DominoGameState.GameEvent>()
    private var lastEvent: DominoGameState.GameEvent? = null
    private var candidateTurn = DominoVision.ActivePlayer.UNKNOWN
    private var candidateTurnCount = 0
    private var confirmedTurn = DominoVision.ActivePlayer.UNKNOWN
    private var confirmedTurnConfidence = 0
    private var conflictSignature: String? = null
    private var conflictFrameCount = 0
    private var lastMatchHeader: DominoMatchHeaderRecognizer.Header? = null

    init { require(requiredStableFrames >= 2) }

    @Synchronized
    fun reset() {
        candidateSignature = null
        candidateCount = 0
        stableAnalysis = null
        stableSignature = null
        passEvents.clear()
        moveEvents.clear()
        lastEvent = null
        candidateTurn = DominoVision.ActivePlayer.UNKNOWN
        candidateTurnCount = 0
        confirmedTurn = DominoVision.ActivePlayer.UNKNOWN
        confirmedTurnConfidence = 0
        conflictSignature = null
        conflictFrameCount = 0
        lastMatchHeader = null
    }

    @Synchronized
    fun update(
        analysis: DominoVision.Analysis,
        roundStarter: String,
        recognizedText: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ): Update {
        if (!isPlausible(analysis)) {
            candidateSignature = null
            candidateCount = 0
            candidateTurn = DominoVision.ActivePlayer.UNKNOWN
            candidateTurnCount = 0
            return Update(DominoGameState.Confidence.UNKNOWN, null,
                "LECTURA INCONSISTENTE · ESPERANDO OTRA CAPTURA")
        }

        val rawTurn = resolveTurn(analysis.activePlayer, recognizedText)
        val explicitPassActor = hasExplicitPassActor(recognizedText)
        val rawTurnConfidence = if (explicitPassActor) 95 else analysis.turnConfidence
        val turnBeforeUpdate = confirmedTurn
        val stabilizedTurn = stabilizeTurn(rawTurn, rawTurnConfidence, explicitPassActor)
        val effectiveAnalysis = analysis.copy(
            activePlayer = stabilizedTurn.first,
            turnConfidence = stabilizedTurn.second
        )
        // La estabilidad del estado de la mesa se calcula por separado del turno.
        // Así, pequeños parpadeos del aro no reinician toda la lectura.
        val signature = signature(effectiveAnalysis)
        if (signature == candidateSignature) candidateCount++ else {
            candidateSignature = signature
            candidateCount = 1
        }

        if (candidateCount < requiredStableFrames) {
            return Update(DominoGameState.Confidence.PROBABLE, null,
                "CAMBIO DETECTADO · CONFIRMANDO")
        }

        val previous = stableAnalysis
        val previousSignature = stableSignature
        val confirmedTurnChanged = previous != null &&
            turnBeforeUpdate != DominoVision.ActivePlayer.UNKNOWN &&
            effectiveAnalysis.activePlayer != DominoVision.ActivePlayer.UNKNOWN &&
            turnBeforeUpdate != effectiveAnalysis.activePlayer
        val eventBefore = if (previous != null &&
            previous.activePlayer == DominoVision.ActivePlayer.UNKNOWN &&
            turnBeforeUpdate != DominoVision.ActivePlayer.UNKNOWN) {
            previous.copy(activePlayer = turnBeforeUpdate)
        } else previous
        var event = if (eventBefore != null && (previousSignature != signature || confirmedTurnChanged))
            reconstructEvent(eventBefore, effectiveAnalysis, recognizedText, timestampMs) else null
        val parsedHeader = DominoMatchHeaderRecognizer.parse(recognizedText)
        val previousHeader = lastMatchHeader
        val matchHeader = DominoMatchHeaderRecognizer.Header(
            userScore = parsedHeader.userScore ?: previousHeader?.userScore,
            opponentScore = parsedHeader.opponentScore ?: previousHeader?.opponentScore,
            target = parsedHeader.target ?: previousHeader?.target,
            roundNumber = parsedHeader.roundNumber ?: previousHeader?.roundNumber
        )
        val startsNewMatch = parsedHeader.isFreshMatch && previousHeader != null &&
            !previousHeader.isFreshMatch && (
                (previousHeader.userScore ?: 0) > 0 || (previousHeader.opponentScore ?: 0) > 0 ||
                    (previousHeader.roundNumber ?: 1) > 1
                )
        if (startsNewMatch) {
            event = DominoGameState.GameEvent(
                DominoGameState.EventType.NEW_MATCH,
                timestampMs = timestampMs,
                confidence = DominoGameState.Confidence.CONFIRMED
            )
        }
        if (parsedHeader.userScore != null || parsedHeader.opponentScore != null ||
            parsedHeader.target != null || parsedHeader.roundNumber != null) {
            lastMatchHeader = matchHeader
        }

        // Una contradiccion no se convierte en estado confirmado por repeticion inmediata.
        // Tras tres capturas estables se acepta como nueva linea base, sin inventar eventos,
        // y se descarta el historial que ya no puede reconstruirse con seguridad.
        if (event?.type == DominoGameState.EventType.CONFLICT) {
            if (conflictSignature == signature) conflictFrameCount++ else {
                conflictSignature = signature
                conflictFrameCount = 1
            }
            if (conflictFrameCount < 3) {
                return Update(DominoGameState.Confidence.UNKNOWN, null,
                    "CAMBIO CONTRADICTORIO · ESPERANDO ESTABILIDAD")
            }
            passEvents.clear()
            moveEvents.clear()
            conflictSignature = null
            conflictFrameCount = 0
            lastEvent = null
            event = null
        } else {
            conflictSignature = null
            conflictFrameCount = 0
        }

        if (event != null) {
            if (event.type == DominoGameState.EventType.NEW_ROUND ||
                event.type == DominoGameState.EventType.NEW_MATCH) {
                passEvents.clear()
                moveEvents.clear()
            }
            val destination = if (event.type == DominoGameState.EventType.USER_PASS ||
                event.type == DominoGameState.EventType.OPPONENT_PASS) passEvents else moveEvents
            destination.add(event)
            while (destination.size > 64) destination.removeAt(0)
            lastEvent = event
        }

        stableAnalysis = effectiveAnalysis
        stableSignature = signature
        val opponentCount = estimateOpponentCount(effectiveAnalysis)
        val stateConfidence = when {
            effectiveAnalysis.handConfidence < 55 -> DominoGameState.Confidence.UNKNOWN
            effectiveAnalysis.boardIsEmpty && effectiveAnalysis.boardTiles.isEmpty() &&
                effectiveAnalysis.leftEnd == null && effectiveAnalysis.rightEnd == null -> DominoGameState.Confidence.CONFIRMED
            effectiveAnalysis.boardKnown && effectiveAnalysis.boardTiles.isNotEmpty() -> DominoGameState.Confidence.CONFIRMED
            else -> DominoGameState.Confidence.UNKNOWN
        }
        val gameState = DominoGameState(
            ownHand = effectiveAnalysis.hand.toList(),
            boardTiles = effectiveAnalysis.boardTiles.toList(),
            leftEnd = effectiveAnalysis.leftEnd,
            rightEnd = effectiveAnalysis.rightEnd,
            opponentTileCount = opponentCount,
            currentTurn = effectiveAnalysis.activePlayer,
            roundStarter = if (roundStarter.equals("RIVAL", true)) "RIVAL" else "TÚ",
            boardIsEmpty = effectiveAnalysis.boardIsEmpty,
            confidence = stateConfidence,
            passHistory = passEvents.toList(),
            moveHistory = moveEvents.toList(),
            userScore = matchHeader.userScore,
            opponentScore = matchHeader.opponentScore,
            matchTarget = matchHeader.target,
            roundNumber = matchHeader.roundNumber,
            recognizedText = recognizedText.take(500),
            turnConfidence = effectiveAnalysis.turnConfidence
        )
        val status = when {
            stateConfidence == DominoGameState.Confidence.UNKNOWN -> "ESTADO DESCONOCIDO · NO SUGERIR"
            !gameState.turnConfirmed && !isUserOpening(gameState) ->
                "TURNO NO CONFIRMADO · ESPERANDO"
            effectiveAnalysis.activePlayer == DominoVision.ActivePlayer.OPPONENT && !isUserOpening(gameState) ->
                "TURNO DEL RIVAL · ESPERANDO"
            else -> "ESTADO CONFIRMADO"
        }
        return Update(stateConfidence, gameState, status, event)
    }

    // La selección SALGO YO confirma al abridor de esta ronda. Solo se acepta
    // mientras la mesa está vacía y las siete fichas propias están reconocidas.
    private fun isUserOpening(state: DominoGameState): Boolean =
        state.boardIsEmpty && state.ownHand.size == 7 && state.roundStarter == "TÚ"

    /**
     * Exige dos lecturas consistentes para cambiar de jugador. Una señal OCR explícita
     * de pase puede confirmarlo inmediatamente. Un fotograma UNKNOWN no reutiliza el
     * turno antiguo: es más seguro esperar que sugerir durante el turno equivocado.
     */
    private fun stabilizeTurn(
        raw: DominoVision.ActivePlayer,
        confidence: Int,
        explicitPassActor: Boolean
    ): Pair<DominoVision.ActivePlayer, Int> {
        if (raw == DominoVision.ActivePlayer.UNKNOWN) {
            candidateTurn = DominoVision.ActivePlayer.UNKNOWN
            candidateTurnCount = 0
            return DominoVision.ActivePlayer.UNKNOWN to 0
        }
        if (raw == candidateTurn) candidateTurnCount++ else {
            candidateTurn = raw
            candidateTurnCount = 1
        }
        if (explicitPassActor) candidateTurnCount = 2
        if (candidateTurnCount >= 2) {
            confirmedTurn = raw
            confirmedTurnConfidence = confidence.coerceIn(0, 100)
            return confirmedTurn to confirmedTurnConfidence
        }
        return DominoVision.ActivePlayer.UNKNOWN to 0
    }

    private fun estimateOpponentCount(a: DominoVision.Analysis): Int? {
        val ownPlayed = 7 - a.hand.size
        val opponentPlayed = a.boardTiles.size - ownPlayed
        val count = 7 - opponentPlayed
        return count.takeIf { it in 0..7 }
    }

    private fun isPlausible(a: DominoVision.Analysis): Boolean {
        if (a.hand.size > 7 || a.boardTiles.size > 14 || a.handConfidence < 45) return false
        val handIds = a.hand.map { tileId(it.a, it.b) ?: return false }
        val boardIds = a.boardTiles.map { tileId(it.a, it.b) ?: return false }
        if (handIds.size != handIds.distinct().size || boardIds.size != boardIds.distinct().size) return false
        if (handIds.any { it in boardIds }) return false
        if (a.boardIsEmpty) return a.boardTiles.isEmpty() && a.leftEnd == null && a.rightEnd == null
        if (a.boardTiles.isEmpty()) return false
        if (a.leftEnd !in 0..6 || a.rightEnd !in 0..6) return false
        return a.boardConfidence >= 50
    }

    private fun reconstructEvent(
        before: DominoVision.Analysis,
        after: DominoVision.Analysis,
        ocr: String,
        now: Long
    ): DominoGameState.GameEvent? {
        val beforeBoard = before.boardTiles.mapNotNull { tileId(it.a, it.b) }.toSet()
        val afterBoard = after.boardTiles.mapNotNull { tileId(it.a, it.b) }.toSet()
        val added = afterBoard - beforeBoard
        val removed = beforeBoard - afterBoard
        val beforeHand = before.hand.mapNotNull { tileId(it.a, it.b) }.toSet()
        val afterHand = after.hand.mapNotNull { tileId(it.a, it.b) }.toSet()
        val handRemoved = beforeHand - afterHand

        if (after.boardIsEmpty && after.hand.size == 7 && before.boardTiles.isNotEmpty()) {
            return DominoGameState.GameEvent(DominoGameState.EventType.NEW_ROUND,
                timestampMs = now, confidence = DominoGameState.Confidence.CONFIRMED)
        }
        if (removed.isNotEmpty() || added.size > 1) {
            return DominoGameState.GameEvent(DominoGameState.EventType.CONFLICT,
                timestampMs = now, confidence = DominoGameState.Confidence.UNKNOWN)
        }
        if (added.size == 1) {
            val id = added.first()
            val pair = tileFromId(id)
            val userPlayed = handRemoved.size == 1 && handRemoved.first() == id
            val opponentPlayed = handRemoved.isEmpty()
            if ((!userPlayed && !opponentPlayed) ||
                (userPlayed && before.activePlayer == DominoVision.ActivePlayer.OPPONENT) ||
                (opponentPlayed && before.activePlayer == DominoVision.ActivePlayer.USER)) {
                return DominoGameState.GameEvent(DominoGameState.EventType.CONFLICT,
                    timestampMs = now, confidence = DominoGameState.Confidence.UNKNOWN)
            }
            val actorConfidence = if (
                (userPlayed && before.activePlayer == DominoVision.ActivePlayer.USER) ||
                (opponentPlayed && before.activePlayer == DominoVision.ActivePlayer.OPPONENT)
            ) DominoGameState.Confidence.CONFIRMED else DominoGameState.Confidence.PROBABLE
            return DominoGameState.GameEvent(
                if (userPlayed) DominoGameState.EventType.USER_PLAY else DominoGameState.EventType.OPPONENT_PLAY,
                pair.first, pair.second, before.leftEnd, before.rightEnd, now,
                actorConfidence
            )
        }
        if (beforeBoard == afterBoard && before.leftEnd == after.leftEnd && before.rightEnd == after.rightEnd) {
            if (handRemoved.isNotEmpty() || beforeHand != afterHand) {
                return DominoGameState.GameEvent(DominoGameState.EventType.CONFLICT,
                    timestampMs = now, confidence = DominoGameState.Confidence.UNKNOWN)
            }
            val passText = isPassMessage(ocr)
            val type = when {
                before.activePlayer == DominoVision.ActivePlayer.OPPONENT &&
                    after.activePlayer == DominoVision.ActivePlayer.USER -> DominoGameState.EventType.OPPONENT_PASS
                before.activePlayer == DominoVision.ActivePlayer.USER &&
                    after.activePlayer == DominoVision.ActivePlayer.OPPONENT -> DominoGameState.EventType.USER_PASS
                else -> null
            }
            if (type != null) return DominoGameState.GameEvent(type,
                leftBefore = before.leftEnd, rightBefore = before.rightEnd, timestampMs = now,
                confidence = if (before.activePlayer != after.activePlayer || passText)
                    DominoGameState.Confidence.CONFIRMED else DominoGameState.Confidence.PROBABLE)
            return null
        }
        // Los extremos no pueden variar sin que aparezca exactamente una ficha.
        if (before.leftEnd != after.leftEnd || before.rightEnd != after.rightEnd ||
            beforeHand != afterHand) {
            return DominoGameState.GameEvent(DominoGameState.EventType.CONFLICT,
                timestampMs = now, confidence = DominoGameState.Confidence.UNKNOWN)
        }
        return null
    }

    private fun resolveTurn(
        visualTurn: DominoVision.ActivePlayer,
        rawText: String
    ): DominoVision.ActivePlayer {
        val text = normalizeText(rawText)
        return when {
            // Mensaje visible bajo el avatar del usuario en la nueva app.
            "tu turno" in text || "toca una ficha iluminada" in text ->
                DominoVision.ActivePlayer.USER
            // Mensaje de actividad del rival: "Juega [nombre]...".
            Regex("\\bjuega\\b").containsMatchIn(text) ->
                DominoVision.ActivePlayer.OPPONENT
            // La pantalla muestra "Pasas: no tienes ficha" despues del pase del usuario;
            // el turno que sigue es el del rival.
            "pasas" in text && ("no tienes ficha" in text || "sin ficha" in text) ->
                DominoVision.ActivePlayer.OPPONENT
            // "Tu oponente debe pasar" identifica al rival como jugador que tiene
            // que ejecutar el pase. Si el OCR describe el pase ya realizado, el aro
            // visual determina quien recibe el turno siguiente.
            ("oponente" in text || "rival" in text) &&
                ("debe pasar" in text || "debes pasar" in text) ->
                DominoVision.ActivePlayer.OPPONENT
            ("oponente" in text || "rival" in text) && "paso" in text ->
                if (visualTurn != DominoVision.ActivePlayer.UNKNOWN) visualTurn
                else DominoVision.ActivePlayer.USER
            // Frase dirigida al usuario: es el usuario quien tiene que pasar.
            "debes pasar" in text || "tienes que pasar" in text ->
                DominoVision.ActivePlayer.USER
            else -> visualTurn
        }
    }

    private fun normalizeText(raw: String): String =
        Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun hasExplicitPassActor(raw: String): Boolean {
        val s = normalizeText(raw)
        val opponentPhrase = ("oponente" in s || "rival" in s) &&
            ("debe pasar" in s || "debes pasar" in s || "pasa" in s || "paso" in s)
        val userPhrase = ("pasas" in s && ("no tienes ficha" in s || "sin ficha" in s)) ||
            "debes pasar" in s || "tienes que pasar" in s
        return opponentPhrase || userPhrase
    }

    private fun isPassMessage(raw: String): Boolean {
        val s = normalizeText(raw)
        // "Paso" aparece sobre el avatar del jugador que pasa; "Pasas: no tienes ficha"
        // identifica el pase propio. El actor final se deduce con el cambio del aro azul.
        return listOf("paso", "pasas", "no tienes ficha", "sin ficha", "debe pasar",
            "debes pasar", "oponente debe pasar", "rival debe pasar",
            "tu oponente pasa", "tu rival pasa", "pass").any { it in s }
    }

    private fun signature(a: DominoVision.Analysis): String {
        val hand = a.hand.mapNotNull { tileId(it.a, it.b) }.sorted().joinToString(",")
        val board = a.boardTiles.mapNotNull { tile ->
            val id = tileId(tile.a, tile.b) ?: return@mapNotNull null
            // Cuantizar evita que una oscilacion de uno o dos pixeles bloquee la estabilidad.
            "$id:${tile.x / 12}:${tile.y / 12}:${tile.width / 12}:${tile.height / 12}:${tile.orientation}"
        }.sorted().joinToString(",")
        // El turno tiene su propio filtro de dos capturas. No se incluye aquí para
        // que un indicador intermitente no haga oscilar toda la lectura de la mesa.
        return "$hand|$board|${a.leftEnd}|${a.rightEnd}|${a.boardIsEmpty}"
    }

    private fun tileId(a: Int, b: Int): Int? {
        if (a !in 0..6 || b !in 0..6) return null
        var id = 0
        for (x in 0..6) for (y in x..6) {
            if ((x == a && y == b) || (x == b && y == a)) return id
            id++
        }
        return null
    }

    private fun tileFromId(id: Int): Pair<Int, Int> {
        var current = 0
        for (a in 0..6) for (b in a..6) {
            if (current == id) return a to b
            current++
        }
        error("tile id invalido")
    }
}
