package com.zeusvision.app

/**
 * Punto unico de autorizacion para la primera jugada manual.
 * No consulta indicadores de turno: SALGO YO solo prevalece si el usuario
 * confirma al abridor y la vision temporal confirma mesa vacia + 7 fichas.
 */
object DominoOpeningGate {
    data class Check(val allowed: Boolean, val message: String)

    fun evaluate(manualSelection: Boolean, state: DominoGameState?): Check {
        if (!manualSelection) return Check(false, "SALIDA MANUAL NO SELECCIONADA")
        if (state == null) return Check(false, "ESTADO INICIAL NO CONFIRMADO")
        if (!state.roundStarter.equals("TÚ", ignoreCase = true)) {
            return Check(false, "SALIDA MANUAL NO SELECCIONADA")
        }
        if (state.confidence != DominoGameState.Confidence.CONFIRMED || !state.boardConfirmed) {
            return Check(false, "ESTADO INICIAL NO CONFIRMADO")
        }
        if (!state.boardIsEmpty || state.boardTiles.isNotEmpty() ||
            state.leftEnd != null || state.rightEnd != null) {
            return Check(false, "MESA NO CONFIRMADA COMO VACÍA")
        }
        if (state.ownHand.size != 7) {
            return Check(false, "MANO INCOMPLETA · ${state.ownHand.size}/7")
        }
        val seen = HashSet<Int>(7)
        for (tile in state.ownHand) {
            if (tile.a !in 0..6 || tile.b !in 0..6 || tile.confidence < 45) {
                return Check(false, "MANO INCONSISTENTE")
            }
            val lo = minOf(tile.a, tile.b)
            val hi = maxOf(tile.a, tile.b)
            val id = (lo * 7) - (lo * (lo - 1)) / 2 + (hi - lo)
            if (!seen.add(id)) return Check(false, "MANO INCONSISTENTE")
        }
        return Check(true, "SALIDA CONFIRMADA")
    }
}
