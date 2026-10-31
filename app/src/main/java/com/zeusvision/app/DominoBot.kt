package com.zeusvision.app

/** Motor de prueba: dominó doble-seis, 1 contra 1, sin robo. */
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

    data class Result(val suggestion: Suggestion?, val message: String)

    fun suggest(handText: String, leftText: String, rightText: String): Result {
        val hand = parseHand(handText) ?: return Result(null, "Revisa la mano. Usa fichas como 6-5, 2-2, 0-3.")
        if (hand.isEmpty()) return Result(null, "Introduce al menos una ficha.")

        if (!validEnd(leftText)) return Result(null, "El extremo izquierdo debe ser 0–6 o '-'.")
        if (!validEnd(rightText)) return Result(null, "El extremo derecho debe ser 0–6 o '-'.")
        val left = parseEnd(leftText)
        val right = parseEnd(rightText)
        if ((left == null) != (right == null)) {
            return Result(null, "Para una mesa iniciada, introduce los dos extremos. Para mesa vacía, usa '-' en ambos.")
        }

        if (left == null) {
            val chosen = hand.maxWithOrNull(compareBy<Tile> { if (it.a == it.b) 1 else 0 }.thenBy { it.pips })!!
            return Result(Suggestion(chosen, "CENTRO", chosen.label, "Mesa vacía: abre con la ficha de mayor valor, priorizando dobles."), "Jugada legal")
        }

        val candidates = mutableListOf<Suggestion>()
        hand.forEach { tile ->
            if (tile.a == left || tile.b == left) {
                val oriented = if (tile.b == left) "${tile.a}-${tile.b}" else "${tile.b}-${tile.a}"
                candidates += Suggestion(tile, "IZQUIERDA", oriented, "Conecta ${tile.label} con el extremo $left.")
            }
            if (tile.a == right || tile.b == right) {
                val oriented = if (tile.a == right) "${tile.a}-${tile.b}" else "${tile.b}-${tile.a}"
                candidates += Suggestion(tile, "DERECHA", oriented, "Conecta ${tile.label} con el extremo $right.")
            }
        }
        if (candidates.isEmpty()) return Result(null, "No tienes jugadas legales. En esta prueba, pulsa PASAR.")

        // Heurística deliberadamente sencilla: desprenderse de muchos puntos y priorizar dobles.
        val best = candidates.maxWithOrNull(
            compareBy<Suggestion> { it.tile.pips }
                .thenBy { if (it.tile.a == it.tile.b) 1 else 0 }
        )!!
        val reason = "Mayor puntuación de ficha entre las opciones legales; ${best.side.lowercase()} conecta con ${if (best.side == "IZQUIERDA") left else right}."
        return Result(best.copy(reason = reason), "Jugada legal encontrada")
    }

    private fun parseHand(text: String): List<Tile>? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        val tokens = cleaned.split(Regex("[,;\\s]+" )).filter { it.isNotBlank() }
        val result = mutableListOf<Tile>()
        for (token in tokens) {
            val parts = token.split(Regex("[-:]"))
            if (parts.size != 2) return null
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            if (a !in 0..6 || b !in 0..6) return null
            result += Tile(a, b)
        }
        return result
    }

    private fun validEnd(text: String): Boolean {
        if (text.trim() == "-") return true
        val value = text.trim().toIntOrNull() ?: return false
        return value in 0..6
    }

    private fun parseEnd(text: String): Int? = if (text.trim() == "-") null else text.trim().toInt()
}
