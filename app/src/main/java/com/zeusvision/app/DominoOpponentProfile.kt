package com.zeusvision.app

import java.util.LinkedHashSet

/**
 * Perfil estadistico temporal del rival durante un encuentro.
 * Solo utiliza jugadas y pases observados publicamente; no identifica ni
 * conserva perfiles permanentes de personas entre encuentros.
 */
object DominoOpponentProfile {
    data class Snapshot(
        val observedPlays: Int,
        val observedPasses: Int,
        val meanPipsPerPlay: Double,
        val highPipRate: Double,
        val earlyDoubleRate: Double,
        val passRate: Double,
        val confidence: Double
    ) {
        val pointShedding: Double get() = (
            0.55 * ((meanPipsPerPlay - 4.0) / 8.0).coerceIn(0.0, 1.0) +
                0.45 * highPipRate.coerceIn(0.0, 1.0)
            ) * confidence
        val earlyDoubleRelease: Double get() = earlyDoubleRate * confidence
        val lowSample: Boolean get() = observedPlays < MIN_ACTIONS
        val label: String get() = when {
            lowSample -> "MUESTRA INSUFICIENTE"
            pointShedding >= 0.62 -> "DESCARGA DE PUNTOS"
            pointShedding <= 0.30 -> "CONSERVADOR"
            earlyDoubleRelease >= 0.55 -> "LIBERA DOBLES TEMPRANO"
            else -> "EQUILIBRADO"
        }
    }

    private const val MIN_ACTIONS = 4
    private const val MAX_SEEN_EVENTS = 512
    private val seen = LinkedHashSet<String>()
    private var plays = 0
    private var passes = 0
    private var totalPips = 0
    private var highPipPlays = 0
    private var earlyDoublePlays = 0
    private var earlyOpportunities = 0
    private var opponentPlaysThisRound = 0

    @Synchronized
    fun reset() {
        seen.clear()
        clearRoundStats()
    }

    @Synchronized
    fun observe(events: List<DominoGameState.GameEvent>): Snapshot {
        for (event in events.sortedBy { it.timestampMs }) {
            val key = listOf(event.timestampMs, event.type.name, event.tileA ?: -1,
                event.tileB ?: -1, event.leftBefore ?: -1, event.rightBefore ?: -1).joinToString(":")
            if (!seen.add(key)) continue
            while (seen.size > MAX_SEEN_EVENTS) seen.remove(seen.first())
            // El perfil se conserva entre manos de la misma partida y solo se
            // reinicia al detectar el comienzo de un encuentro nuevo.
            if (event.type == DominoGameState.EventType.NEW_MATCH) {
                clearRoundStats()
                continue
            }
            if (event.type == DominoGameState.EventType.NEW_ROUND) {
                opponentPlaysThisRound = 0
                continue
            }
            if (event.confidence != DominoGameState.Confidence.CONFIRMED) continue
            when (event.type) {
                DominoGameState.EventType.NEW_ROUND, DominoGameState.EventType.NEW_MATCH -> Unit
                DominoGameState.EventType.OPPONENT_PLAY -> {
                    val a = event.tileA ?: continue
                    val b = event.tileB ?: continue
                    if (a !in 0..6 || b !in 0..6) continue
                    val pips = a + b
                    plays++
                    totalPips += pips
                    if (pips >= 8) highPipPlays++
                    if (opponentPlaysThisRound < 3) {
                        earlyOpportunities++
                        if (a == b) earlyDoublePlays++
                    }
                    opponentPlaysThisRound++
                }
                DominoGameState.EventType.OPPONENT_PASS -> passes++
                else -> Unit
            }
        }
        return snapshotUnsafe()
    }

    private fun clearRoundStats() {
        plays = 0
        passes = 0
        totalPips = 0
        highPipPlays = 0
        earlyDoublePlays = 0
        earlyOpportunities = 0
        opponentPlaysThisRound = 0
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshotUnsafe()

    private fun snapshotUnsafe(): Snapshot {
        val actions = plays + passes
        val mean = if (plays == 0) 6.0 else totalPips.toDouble() / plays
        val confidence = (plays.toDouble() / (plays + 4.0)).coerceIn(0.0, 1.0)
        return Snapshot(
            observedPlays = plays,
            observedPasses = passes,
            meanPipsPerPlay = mean,
            highPipRate = if (plays == 0) 0.0 else highPipPlays.toDouble() / plays,
            earlyDoubleRate = if (earlyOpportunities == 0) 0.0 else earlyDoublePlays.toDouble() / earlyOpportunities,
            passRate = if (actions == 0) 0.0 else passes.toDouble() / actions,
            confidence = confidence
        )
    }
}
