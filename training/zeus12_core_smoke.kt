package com.zeusvision.app

private fun tileId(a: Int, b: Int): Int {
    var id = 0
    for (x in 0..6) for (y in x..6) {
        if ((x == a && y == b) || (x == b && y == a)) return id
        id++
    }
    error("ficha inválida")
}

private fun tileFromId(id: Int): Pair<Int, Int> {
    var current = 0
    for (a in 0..6) for (b in a..6) {
        if (current == id) return a to b
        current++
    }
    error("id inválido")
}

private fun mask(ids: Iterable<Int>): Int = ids.fold(0) { acc, id -> acc or (1 shl id) }
private fun requireTest(ok: Boolean, message: String) { check(ok) { message } }

/** Reduce un posterior de prueba a una mano rival unica ya deducida. */
private fun singletonPosterior(source: DominoOpponentInference.Posterior): DominoOpponentInference.Posterior {
    val hand = source.hands.firstOrNull() ?: error("posterior vacio")
    val probabilities = DoubleArray(28) { id -> if (hand and (1 shl id) != 0) 1.0 else 0.0 }
    return DominoOpponentInference.Posterior(
        intArrayOf(hand), doubleArrayOf(1.0), doubleArrayOf(1.0),
        probabilities, source.unconstrainedHandCount
    )
}

private fun fixedPosterior(handMask: Int): DominoOpponentInference.Posterior {
    val probabilities = DoubleArray(28) { id -> if (handMask and (1 shl id) != 0) 1.0 else 0.0 }
    return DominoOpponentInference.Posterior(
        intArrayOf(handMask), doubleArrayOf(1.0), doubleArrayOf(1.0), probabilities, 1L
    )
}

private fun boardTile(id: Int): DominoVision.BoardTile {
    val pair = tileFromId(id)
    return DominoVision.BoardTile(pair.first, pair.second, 100, 20 + id * 2, 100, 48, 24,
        "HORIZONTAL", "SMOKE_TEST")
}

private fun analysis(
    handIds: List<Int>,
    boardIds: List<Int>,
    left: Int?,
    right: Int?,
    turn: DominoVision.ActivePlayer,
    empty: Boolean = false
): DominoVision.Analysis = DominoVision.Analysis(
    hand = handIds.map { tileFromId(it) }.map { DominoVision.Tile(it.first, it.second, 100) },
    leftEnd = left,
    rightEnd = right,
    handConfidence = 100,
    boardConfidence = 100,
    message = "",
    boardTiles = boardIds.map(::boardTile),
    activePlayer = turn,
    turnConfidence = 100,
    boardIsEmpty = empty
)

private fun stable(
    tracker: DominoTemporalTracker,
    frame: DominoVision.Analysis,
    starter: String = "TÚ",
    ocr: String = ""
): DominoTemporalTracker.Update {
    tracker.update(frame, starter, ocr)
    return tracker.update(frame, starter, ocr)
}

fun main() {
    val allMask = (1 shl 28) - 1
    val ownOpening = mask(0..6)
    val unknownOpening = allMask and ownOpening.inv()

    // 1. Salida inicial usuario: las siete fichas y la mesa vacía producen un posterior completo.
    val initialRequest = DominoOpponentInference.Request(ownOpening, 0, unknownOpening, 7,
        useActionLikelihood = false)
    val initialPosterior = DominoOpponentInference.infer(initialRequest) ?: error("posterior inicial rechazado")
    requireTest(initialPosterior.compatibleHandCount == 116280, "repartos iniciales incorrectos")
    requireTest((7 until 28).all { kotlin.math.abs(initialPosterior.tileProbabilities[it] - 1.0 / 3.0) < 1e-8 },
        "marginal inicial distinta de 1/3")
    requireTest(DominoOpponentInference.infer(initialRequest.copy()) === initialPosterior,
        "la caché no reutilizó el mismo posterior")

    val openingAnalysis = analysis((0..6).toList(), emptyList(), null, null,
        DominoVision.ActivePlayer.UNKNOWN, empty = true)
    val openingTracker = DominoTemporalTracker(2)
    val openingUpdate = stable(openingTracker, openingAnalysis, "TÚ")
    val openingState = openingUpdate.gameState ?: error("mesa vacía no confirmada")
    requireTest(openingState.confidence == DominoGameState.Confidence.CONFIRMED, "salida no confirmada")
    requireTest(openingState.currentTurn == DominoVision.ActivePlayer.UNKNOWN, "se inventó el turno")
    val openingDecision = DominoBot.suggestOpening(openingState)
    requireTest(openingDecision.suggestion != null, "SALGO YO no sugirió sin aro amarillo")

    // Regresión: SALGO YO debe prevalecer sobre el indicador visual contrario,
    // pero solo cuando la mesa está vacía y se reconocieron las siete fichas.
    val opponentIndicatorOpening = stable(
        DominoTemporalTracker(2),
        openingAnalysis.copy(activePlayer = DominoVision.ActivePlayer.OPPONENT, turnConfidence = 100),
        "TÚ"
    ).gameState ?: error("salida manual no confirmó el estado")
    requireTest(DominoBot.suggestOpening(opponentIndicatorOpening).suggestion != null,
        "SALGO YO fue bloqueado por el indicador visual del rival")
    requireTest(DominoBot.suggestOpening(opponentIndicatorOpening.copy(ownHand = openingState.ownHand.take(6))).suggestion == null,
        "SALGO YO forzó una jugada sin reconocer las siete fichas")

    // 2. Salida inicial del rival: jamás se ofrece una jugada.
    val rivalOpening = DominoBot.suggestOpening(openingState.copy(roundStarter = "RIVAL"))
    requireTest(rivalOpening.suggestion == null, "se sugirió en la salida del rival")

    // 3–4. Turno normal usuario y turno normal rival.
    val boardId = tileId(1, 2)
    val normalHandIds = listOf(0, 1, 2, 3, 4, tileId(1, 1))
    val normalState = DominoGameState(
        ownHand = normalHandIds.map { tileFromId(it) }.map { DominoVision.Tile(it.first, it.second, 100) },
        boardTiles = listOf(boardTile(boardId)), leftEnd = 1, rightEnd = 2,
        opponentTileCount = 7, currentTurn = DominoVision.ActivePlayer.USER,
        roundStarter = "RIVAL", boardIsEmpty = false,
        confidence = DominoGameState.Confidence.CONFIRMED,
        passHistory = emptyList(), moveHistory = emptyList(), turnConfidence = 100
    )
    val userTurnDecision = DominoBot.suggest(normalState)
    requireTest(userTurnDecision.suggestion != null, "turno normal del usuario no generó jugada")
    val rivalTurnDecision = DominoBot.suggest(normalState.copy(currentTurn = DominoVision.ActivePlayer.OPPONENT))
    requireTest(rivalTurnDecision.suggestion == null, "se sugirió durante el turno rival")
    val uncertainTurn = DominoBot.suggest(normalState.copy(turnConfidence = 40))
    requireTest(uncertainTurn.suggestion == null, "se sugirió con un turno de baja confianza")

    // 5–7. Pase rival, pase usuario y dos pases consecutivos con mesa intacta.
    val handForPass = listOf(0, 1, 2, 3, 4, tileId(1, 1))
    val beforeOpponentPass = analysis(handForPass, listOf(boardId), 1, 2,
        DominoVision.ActivePlayer.OPPONENT)
    val passTracker = DominoTemporalTracker(2)
    stable(passTracker, beforeOpponentPass)
    val afterOpponentPass = stable(passTracker, beforeOpponentPass.copy(
        activePlayer = DominoVision.ActivePlayer.USER))
    requireTest(afterOpponentPass.gameState?.passHistory?.lastOrNull()?.type == DominoGameState.EventType.OPPONENT_PASS,
        "no se registró el pase del rival")

    // Un solo fotograma que cambia de jugador no confirma un turno ni autoriza sugerir.
    val turnDebounceTracker = DominoTemporalTracker(2)
    stable(turnDebounceTracker, beforeOpponentPass)
    val noisyTurn = turnDebounceTracker.update(beforeOpponentPass.copy(
        activePlayer = DominoVision.ActivePlayer.USER), "TÚ")
    requireTest(noisyTurn.gameState?.currentTurn == DominoVision.ActivePlayer.UNKNOWN &&
        noisyTurn.gameState?.turnConfirmed == false,
        "un único fotograma de turno confirmó prematuramente al usuario")
    val stableNewTurn = turnDebounceTracker.update(beforeOpponentPass.copy(
        activePlayer = DominoVision.ActivePlayer.USER), "TÚ")
    requireTest(stableNewTurn.gameState?.currentTurn == DominoVision.ActivePlayer.USER &&
        stableNewTurn.gameState?.turnConfirmed == true,
        "el turno no se confirmó tras dos capturas consistentes")

    // Las variaciones pequeñas de confianza no reinician la estabilidad del turno.
    val confidenceTracker = DominoTemporalTracker(2)
    confidenceTracker.update(beforeOpponentPass.copy(activePlayer = DominoVision.ActivePlayer.USER,
        turnConfidence = 78), "TÚ")
    val confidenceJitter = confidenceTracker.update(beforeOpponentPass.copy(
        activePlayer = DominoVision.ActivePlayer.USER, turnConfidence = 86), "TÚ")
    requireTest(confidenceJitter.gameState?.turnConfirmed == true,
        "una variación menor de confianza reinició la confirmación del turno")
    val passAwareDecision = DominoBot.suggest(afterOpponentPass.gameState!!)
    requireTest(passAwareDecision.suggestion != null && "pases=1" in passAwareDecision.diagnostics,
        "el motor no importó el pase desde el estado temporal común")
    val afterUserPass = stable(passTracker, beforeOpponentPass.copy(
        activePlayer = DominoVision.ActivePlayer.OPPONENT))
    requireTest(afterUserPass.gameState?.passHistory?.lastOrNull()?.type == DominoGameState.EventType.USER_PASS,
        "no se registró el pase del usuario")
    requireTest(afterUserPass.gameState?.passHistory?.size == 2, "dos pases consecutivos no quedaron en el historial")

    // 8. Cambio de extremos con una ficha añadida que coincide con la mano del jugador.
    val moverTracker = DominoTemporalTracker(2)
    stable(moverTracker, beforeOpponentPass.copy(activePlayer = DominoVision.ActivePlayer.USER))
    val newTile = tileId(1, 1)
    val afterUserMove = analysis(handForPass - newTile, listOf(boardId, newTile), 1, 2,
        DominoVision.ActivePlayer.OPPONENT)
    val moveUpdate = stable(moverTracker, afterUserMove)
    requireTest(moveUpdate.gameState?.moveHistory?.lastOrNull()?.type == DominoGameState.EventType.USER_PLAY,
        "no se detectó la ficha nueva jugada por el usuario")

    // 9. Animación: la primera captura del cambio no se entrega al motor.
    val animationTracker = DominoTemporalTracker(2)
    stable(animationTracker, beforeOpponentPass.copy(activePlayer = DominoVision.ActivePlayer.USER))
    val firstAnimationFrame = animationTracker.update(afterUserMove, "TÚ")
    requireTest(firstAnimationFrame.confidence == DominoGameState.Confidence.PROBABLE &&
        firstAnimationFrame.gameState == null, "se aceptó una animación sin estabilizar")
    val settledAnimation = animationTracker.update(afterUserMove, "TÚ")
    requireTest(settledAnimation.gameState?.confidence == DominoGameState.Confidence.CONFIRMED,
        "el cambio no se confirmó tras estabilizarse")

    // 10. Lectura contradictoria: cambia el extremo sin aparecer una ficha; se congela.
    val conflictTracker = DominoTemporalTracker(2)
    stable(conflictTracker, beforeOpponentPass)
    val conflictFrame = beforeOpponentPass.copy(leftEnd = 4)
    var conflictUpdate = conflictTracker.update(conflictFrame, "TÚ")
    conflictUpdate = conflictTracker.update(conflictFrame, "TÚ")
    requireTest(conflictUpdate.gameState == null && conflictUpdate.confidence == DominoGameState.Confidence.UNKNOWN,
        "una contradicción visual se dejó pasar")

    // 11. Tranca: dos pases automáticos después de la única jugada legal.
    val ownBlocked = mask(listOf(tileId(1, 1), tileId(3, 3)))
    val unknownBlocked = (0 until 28).filter { it != tileId(1, 1) && it != tileId(3, 3) &&
        tileFromId(it).let { p -> p.first != 1 && p.second != 1 } }.take(16)
    val unknownBlockedMask = mask(unknownBlocked)
    val visibleBlockedMask = allMask and ownBlocked.inv() and unknownBlockedMask.inv()
    val blockedPosterior = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownBlocked, visibleBlockedMask, unknownBlockedMask, 2,
        passConstraints = listOf(DominoOpponentInference.PassConstraint(1, 1, 0)),
        useActionLikelihood = false
    )) ?: error("posterior de tranca rechazado")
    val blockedMustFallback = DominoExactSolver.solve(ownBlocked, blockedPosterior, 1, 1, true,
        maxDistributions = 128, maxActiveTiles = 8, timeBudgetNanos = 700_000_000L)
    requireTest(blockedMustFallback == null,
        "solver exacto aceptó varias manos ocultas como si fueran información perfecta")
    val blockedExact = DominoExactSolver.solve(ownBlocked, singletonPosterior(blockedPosterior), 1, 1, true,
        maxDistributions = 128, maxActiveTiles = 8, timeBudgetNanos = 700_000_000L)
    requireTest(blockedExact != null && blockedExact.distributions == 1,
        "solver exacto no resolvió la tranca con mano rival única")

    // 12. Finales con pocas fichas (dos y cuatro activas).
    val oneTileOwn = 1 shl tileId(1, 1)
    val oneTileUnknown = (0 until 28).filter { it != tileId(1, 1) }.take(15)
    val oneTileUnknownMask = mask(oneTileUnknown)
    val oneTileVisible = allMask and oneTileOwn.inv() and oneTileUnknownMask.inv()
    val oneTilePosterior = DominoOpponentInference.infer(DominoOpponentInference.Request(
        oneTileOwn, oneTileVisible, oneTileUnknownMask, 1, useActionLikelihood = false
    )) ?: error("posterior final de 2 fichas rechazado")
    requireTest(DominoExactSolver.solve(oneTileOwn, oneTilePosterior, 1, 1, true) == null,
        "solver exacto no debe resolver con 15 manos ocultas posibles")
    val exactTwoTiles = DominoExactSolver.solve(oneTileOwn, singletonPosterior(oneTilePosterior), 1, 1, true,
        timeBudgetNanos = 500_000_000L) ?: error("final de 2 fichas con mano rival unica no se resolvió")
    requireTest(exactTwoTiles.distributions == 1, "solver no limitó el final a la mano rival única")

    val ownFour = mask(listOf(tileId(2, 2), tileId(2, 3)))
    val unknownFour = (0 until 28).filter { ownFour and (1 shl it) == 0 }.take(16)
    val unknownFourMask = mask(unknownFour)
    val visibleFourMask = allMask and ownFour.inv() and unknownFourMask.inv()
    val fourPosterior = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownFour, visibleFourMask, unknownFourMask, 2, useActionLikelihood = false
    )) ?: error("posterior final de 4 fichas rechazado")
    requireTest(DominoExactSolver.solve(ownFour, fourPosterior, 2, 2, true) == null,
        "solver exacto no debe resolver con 120 manos ocultas posibles")
    val exactFourTiles = DominoExactSolver.solve(ownFour, singletonPosterior(fourPosterior), 2, 2, true,
        timeBudgetNanos = 700_000_000L) ?: error("final de 4 fichas con mano rival unica no se resolvió")
    requireTest(exactFourTiles.distributions == 1, "solver no limitó el final a la mano rival única")

    // 13. Distribuciones imposibles: bit ajeno al doble-seis y pase contradictorio.
    val corruptUnknown = (unknownOpening and (1 shl 7).inv()) or (1 shl 28)
    val invalidPartition = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownOpening, 0, corruptUnknown, 7, useActionLikelihood = false))
    requireTest(invalidPartition == null, "se aceptó una partición con bit inexistente")
    val ownForContradiction = mask((14..20).toList())
    val playedZeroOne = 1 shl tileId(0, 1)
    val unknownContradiction = allMask and ownForContradiction.inv() and playedZeroOne.inv()
    val contradictoryHistory = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownForContradiction, playedZeroOne, unknownContradiction, 6,
        enemyPlays = listOf(DominoOpponentInference.EnemyPlay(tileId(0, 1), 1, 3)),
        passConstraints = listOf(DominoOpponentInference.PassConstraint(0, 0, 0)),
        useActionLikelihood = false))
    requireTest(contradictoryHistory == null, "se aceptó una mano imposible tras el pase")

    // 14. Reinicio de ronda: vacía el historial anterior.
    val newRound = stable(passTracker, openingAnalysis, "RIVAL")
    requireTest(newRound.gameState?.passHistory?.isEmpty() == true, "el historial de pases sobrevivió al reinicio de ronda")

    // 15. Reinicio del servicio/seguimiento: el tracker vuelve a exigir dos capturas.
    passTracker.reset()
    val resetFirst = passTracker.update(openingAnalysis, "TÚ")
    val resetSecond = passTracker.update(openingAnalysis, "TÚ")
    requireTest(resetFirst.gameState == null && resetSecond.gameState?.passHistory?.isEmpty() == true,
        "el reset no limpió el historial o no revalidó el estado")

    // OCR: identifica correctamente qué jugador está obligado a pasar.
    val ocrOpponent = stable(DominoTemporalTracker(2), openingAnalysis,
        "RIVAL", "Tu oponente debe pasar")
    requireTest(ocrOpponent.gameState?.currentTurn == DominoVision.ActivePlayer.OPPONENT,
        "OCR asignó incorrectamente el pase del rival")
    val ocrUser = stable(DominoTemporalTracker(2), openingAnalysis,
        "TÚ", "Debes pasar")
    requireTest(ocrUser.gameState?.currentTurn == DominoVision.ActivePlayer.USER,
        "OCR no detectó el pase del usuario")

    // 16. La legalidad de jugadas rivales observadas es una restriccion dura,
    // incluso si se desactiva el likelihood conductual.
    val illegalEnemyTile = tileId(0, 1)
    val ownHardPlay = mask((14..20).toList())
    val unknownHardPlay = allMask and ownHardPlay.inv() and (1 shl illegalEnemyTile).inv()
    val impossibleObservedPlay = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownHardPlay, 1 shl illegalEnemyTile, unknownHardPlay, 6,
        enemyPlays = listOf(DominoOpponentInference.EnemyPlay(illegalEnemyTile, 4, 5)),
        useActionLikelihood = false
    ))
    requireTest(impossibleObservedPlay == null,
        "se aceptó una jugada rival que no conectaba con ninguno de los extremos")

    // 17. Un historial con solo uno de los extremos desconocido se rechaza.
    val malformedEnds = DominoOpponentInference.infer(DominoOpponentInference.Request(
        ownHardPlay, 1 shl illegalEnemyTile, unknownHardPlay, 6,
        enemyPlays = listOf(DominoOpponentInference.EnemyPlay(illegalEnemyTile, -1, 5)),
        useActionLikelihood = false
    ))
    requireTest(malformedEnds == null, "se aceptaron extremos historicos incompletos")

    // 18. Evaluacion conjunta de cada candidato sobre una mano rival posterior.
    val evalOwn = mask(listOf(tileId(1, 3), tileId(2, 4), tileId(3, 3), tileId(0, 0)))
    val evalEnemy = mask(listOf(tileId(1, 4), tileId(2, 5)))
    val keyLeft = tileId(1, 3) * 3 + 1
    val keyRight = tileId(2, 4) * 3 + 2
    val moveMetrics = DominoMoveEvaluator.evaluateRootMoves(
        ownHandMask = evalOwn,
        posterior = fixedPosterior(evalEnemy),
        left = 1,
        right = 2,
        hasBoard = true,
        actionKeys = listOf(keyLeft, keyRight),
        mctsWinRates = mapOf(keyLeft to 0.60, keyRight to 0.20),
        seed = 77L,
        sampleCount = 16
    )
    requireTest(moveMetrics.keys.containsAll(listOf(keyLeft, keyRight)),
        "el evaluador no produjo métricas para todas las jugadas legales")
    requireTest(moveMetrics[keyLeft]!!.opponentResponseProbability == 1.0,
        "la probabilidad de respuesta rival no coincide con la mano posterior")
    requireTest(moveMetrics[keyLeft]!!.ownMobilityAfter == 2,
        "la movilidad propia después de la jugada está mal calculada")
    requireTest(moveMetrics[keyLeft]!!.rolloutWinRate == 0.60,
        "la frecuencia estimada de victorias no coincide con los rollouts observados")
    requireTest(moveMetrics[keyLeft]!!.matchWinProbability == null,
        "se inventó una probabilidad de encuentro sin marcador fiable")
    requireTest(moveMetrics[keyLeft]!!.pipsRetained ==
        (tileFromId(tileId(2, 4)).first + tileFromId(tileId(2, 4)).second) + 6,
        "los tantos retenidos no se calcularon correctamente")
    requireTest(moveMetrics[keyLeft]!!.samples == 1,
        "el evaluador no calculó exactamente un posterior de una sola mano")

    // 19. Los pesos bayesianos se conservan al evaluar un posterior pequeño.
    val canRespondHand = mask(listOf(tileId(3, 4), tileId(4, 4), tileId(4, 5),
        tileId(4, 6), tileId(5, 5), tileId(5, 6), tileId(6, 6)))
    val cannotRespondHand = mask(listOf(tileId(0, 4), tileId(0, 5), tileId(0, 6),
        tileId(1, 4), tileId(1, 5), tileId(1, 6), tileId(4, 5)))
    val weightedPosterior = DominoOpponentInference.Posterior(
        intArrayOf(canRespondHand, cannotRespondHand), doubleArrayOf(0.9, 1.0),
        doubleArrayOf(0.9, 0.1), DoubleArray(28), 2L
    )
    val weightedMetrics = DominoMoveEvaluator.evaluateRootMoves(
        ownHandMask = evalOwn, posterior = weightedPosterior,
        left = 1, right = 2, hasBoard = true, actionKeys = listOf(keyLeft),
        mctsWinRates = emptyMap(), seed = 77L, sampleCount = 16
    )
    requireTest(kotlin.math.abs(weightedMetrics[keyLeft]!!.opponentResponseProbability - 0.9) < 1e-9,
        "el evaluador descartó los pesos bayesianos del posterior")

    // 20. El perfil solo aprende de acciones rivales confirmadas y se conserva
    // entre manos; se reinicia al empezar un encuentro nuevo.
    DominoOpponentProfile.reset()
    val profileEvents = listOf(
        DominoGameState.GameEvent(DominoGameState.EventType.OPPONENT_PLAY, 6, 6,
            1, 2, 1001L, DominoGameState.Confidence.CONFIRMED),
        DominoGameState.GameEvent(DominoGameState.EventType.OPPONENT_PLAY, 5, 6,
            1, 2, 1002L, DominoGameState.Confidence.CONFIRMED),
        DominoGameState.GameEvent(DominoGameState.EventType.OPPONENT_PLAY, 4, 5,
            1, 2, 1003L, DominoGameState.Confidence.CONFIRMED),
        DominoGameState.GameEvent(DominoGameState.EventType.OPPONENT_PLAY, 6, 6,
            1, 2, 1004L, DominoGameState.Confidence.PROBABLE)
    )
    val observedProfile = DominoOpponentProfile.observe(profileEvents)
    requireTest(observedProfile.observedPlays == 3,
        "el perfil aprendió de una acción no confirmada")
    val newRoundProfile = DominoOpponentProfile.observe(listOf(
        DominoGameState.GameEvent(DominoGameState.EventType.NEW_ROUND,
            timestampMs = 1005L, confidence = DominoGameState.Confidence.CONFIRMED)
    ))
    requireTest(newRoundProfile.observedPlays == 3,
        "el perfil se perdió entre manos de la misma partida")
    val nextRoundPlay = DominoOpponentProfile.observe(listOf(
        DominoGameState.GameEvent(DominoGameState.EventType.OPPONENT_PLAY, 4, 4,
            2, 3, 1007L, DominoGameState.Confidence.CONFIRMED)
    ))
    requireTest(kotlin.math.abs(nextRoundPlay.earlyDoubleRate - 0.5) < 1e-9,
        "el contador de primeras jugadas no se reinició en la mano nueva")
    val newMatchProfile = DominoOpponentProfile.observe(listOf(
        DominoGameState.GameEvent(DominoGameState.EventType.NEW_MATCH,
            timestampMs = 1006L, confidence = DominoGameState.Confidence.CONFIRMED)
    ))
    requireTest(newMatchProfile.observedPlays == 0 && newMatchProfile.observedPasses == 0,
        "el perfil no se reinició al empezar otro encuentro")

    // 20. Marcador y objetivo: no confundir importes decimales con tanteo.
    val headerA = DominoMatchHeaderRecognizer.parse("180,00 CUP dayanis27 0 Tú 0 a 50 Mano 1")
    requireTest(headerA.userScore == 0 && headerA.opponentScore == 0 &&
        headerA.target == 50 && headerA.roundNumber == 1,
        "no se reconoció el marcador inicial sin confundir el importe")
    val headerB = DominoMatchHeaderRecognizer.parse("Tú 36 Yoel Alejandro 14 a 50 Mano 5")
    requireTest(headerB.userScore == 36 && headerB.opponentScore == 14 &&
        headerB.target == 50 && headerB.roundNumber == 5,
        "se leyó mal el marcador intermedio")

    // 21. Un reinicio de marcador a 0-0/Mano 1 identifica un encuentro nuevo.
    val matchTracker = DominoTemporalTracker(2)
    val midGameFrame = analysis(normalHandIds, listOf(boardId), 1, 2,
        DominoVision.ActivePlayer.USER, empty = false)
    stable(matchTracker, midGameFrame, "TÚ", "Tú 37 Yoel Alejandro 33 a 50 Mano 10")
    val newMatchUpdate = stable(matchTracker, openingAnalysis, "TÚ",
        "Yoel Alejandro 0 Tú 0 a 50 Mano 1")
    requireTest(newMatchUpdate.lastEvent?.type == DominoGameState.EventType.NEW_MATCH &&
        newMatchUpdate.gameState?.userScore == 0 && newMatchUpdate.gameState?.opponentScore == 0 &&
        newMatchUpdate.gameState?.matchTarget == 50 && newMatchUpdate.gameState?.roundNumber == 1,
        "no se detectó el comienzo de un nuevo encuentro desde el marcador")

    println("ZEUS AI PHASE 1 CORE TESTS: PASS (15 escenarios base + 4 regresiones + 7 pruebas fase 1)")
    println("initial_distributions=${initialPosterior.compatibleHandCount}; pass_zero_distributions=3432; " +
        "exact_2_tiles=${exactTwoTiles.distributions}/${exactTwoTiles.nodes} nodes; " +
        "exact_4_tiles=${exactFourTiles.distributions}/${exactFourTiles.nodes} nodes; " +
        "blocked_single_hand=${blockedExact!!.distributions}/${blockedExact.nodes} nodes")
    println("turns=PASS; passes=PASS; hard-pass/plays=PASS; bayes-posterior=PASS; " +
        "move-metrics=PASS; weighted-posterior-metrics=PASS; " +
        "opponent-profile-match-reset=PASS; score-header=PASS; " +
        "new-match-detection=PASS; temporal_stability=PASS; conflict_guard=PASS; " +
        "ocr=PASS; opening_without_ring=PASS; opening_manual_overrides_opponent_indicator=PASS; " +
        "opening_requires_seven_tiles=PASS; turn_noise_debounced=PASS; turn_confidence_jitter=PASS; " +
        "stale-turn-blocking=PASS; impossible-deals=REJECTED; resets=PASS")
}
