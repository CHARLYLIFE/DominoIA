package com.zeusvision.app

import java.text.Normalizer

/** Lee con cautela el marcador superior de la nueva aplicación. */
object DominoMatchHeaderRecognizer {
    data class Header(
        val userScore: Int?,
        val opponentScore: Int?,
        val target: Int?,
        val roundNumber: Int?
    ) {
        val isFreshMatch: Boolean get() = userScore == 0 && opponentScore == 0 && roundNumber == 1
    }

    private val numberToken = Regex("\\b\\d{1,3}(?:[.,]\\d{1,2})?\\b")
    private val userScorePattern = Regex("\\btu\\s*(\\d{1,3})\\b")
    private val targetPattern = Regex("\\ba\\s*(\\d{1,3})\\b")
    private val roundPattern = Regex("\\bmano\\s*(\\d{1,2})\\b")

    /**
     * Solo asigna scores cuando aparecen el score de Tú y el objetivo, y puede
     * identificar exactamente otro entero válido en el segmento del marcador.
     * Importes decimales como "180,00 CUP" quedan excluidos.
     */
    fun parse(rawText: String): Header {
        if (rawText.isBlank()) return Header(null, null, null, null)
        val text = Normalizer.normalize(rawText.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('\n', ' ')
        val user = userScorePattern.find(text)
        val target = targetPattern.find(text)
        val round = roundPattern.find(text)
        val targetValue = target?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..300 }
        val roundValue = round?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..200 }
        val userValue = user?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..300 }

        var opponentValue: Int? = null
        if (user != null && target != null && target.range.first > user.range.last) {
            val prefix = text.substring(0, target.range.first)
            val candidateTokens = numberToken.findAll(prefix).filter { token ->
                !token.value.contains('.') && !token.value.contains(',') &&
                    token.range.first !in user.range && token.value.toIntOrNull()?.let { value ->
                        value in 0..(targetValue ?: 0)
                    } == true
            }.toList()
            val selected = candidateTokens.lastOrNull()
            opponentValue = selected?.value?.toIntOrNull()
        }
        return Header(
            userScore = userValue,
            opponentScore = opponentValue,
            target = targetValue,
            roundNumber = roundValue
        )
    }
}
