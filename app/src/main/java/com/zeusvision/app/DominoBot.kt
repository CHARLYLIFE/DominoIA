package com.zeusvision.app

/** Motor heurístico básico para dominó doble-seis, 1 contra 1 y sin robo. */
object DominoBot {
    data class Tile(val a: Int, val b: Int) {
        val pips: Int get() = a + b
        val label: String get() = "$a-$b"
    }
    data class Suggestion(val tile: Tile, val side: String, val oriented: String, val reason: String)
    data class Result(val suggestion: Suggestion?, val message: String)

    fun suggest(handTiles: List<DominoVision.Tile>, left: Int?, right: Int?, leader: String): Result {
        if (handTiles.isEmpty()) return Result(null, "NO PUEDO LEER TU MANO")
        if (left == null || right == null) return Result(null, "NO PUEDO CONFIRMAR LA MESA")
        if (leader == "RIVAL") return Result(null, "SALE EL RIVAL · ESPERANDO SU JUGADA")

        val hand = handTiles.map { Tile(it.a, it.b) }
        val options = mutableListOf<Suggestion>()
        for (t in hand) {
            if (t.a == left || t.b == left) {
                val oriented = if (t.b == left) "${t.a}-${t.b}" else "${t.b}-${t.a}"
                options.add(Suggestion(t, "IZQUIERDA", oriented, "Conecta con el $left"))
            }
            if (t.a == right || t.b == right) {
                val oriented = if (t.a == right) "${t.a}-${t.b}" else "${t.b}-${t.a}"
                options.add(Suggestion(t, "DERECHA", oriented, "Conecta con el $right"))
            }
        }
        if (options.isEmpty()) return Result(null, "NO TIENES JUGADA LEGAL · PASAR")
        val best = options.distinctBy { "${it.tile.label}-${it.side}" }
            .maxWithOrNull(compareBy<Suggestion> { it.tile.pips }.thenBy { if (it.tile.a == it.tile.b) 1 else 0 })!!
        return Result(best, "JUGADA LEGAL")
    }
}
