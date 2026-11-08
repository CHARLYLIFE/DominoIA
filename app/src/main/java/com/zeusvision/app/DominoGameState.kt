package com.zeusvision.app

/**
 * Estado inmutable compartido por la vision temporal y el motor estrategico.
 * No contiene identificadores de fichas privadas del rival.
 */
data class DominoGameState(
    val ownHand: List<DominoVision.Tile>,
    val boardTiles: List<DominoVision.BoardTile>,
    val leftEnd: Int?,
    val rightEnd: Int?,
    val opponentTileCount: Int?,
    val currentTurn: DominoVision.ActivePlayer,
    val roundStarter: String,
    val boardIsEmpty: Boolean,
    val confidence: Confidence,
    val passHistory: List<GameEvent>,
    val moveHistory: List<GameEvent>,
    val userScore: Int? = null,
    val opponentScore: Int? = null,
    val recognizedText: String = "",
    val turnConfidence: Int = 0
) {
    enum class Confidence { CONFIRMED, PROBABLE, UNKNOWN }

    enum class EventType { USER_PLAY, OPPONENT_PLAY, USER_PASS, OPPONENT_PASS, NEW_ROUND, CONFLICT }

    data class GameEvent(
        val type: EventType,
        val tileA: Int? = null,
        val tileB: Int? = null,
        val leftBefore: Int? = null,
        val rightBefore: Int? = null,
        val timestampMs: Long,
        val confidence: Confidence
    )

    val turnConfirmed: Boolean
        get() = confidence == Confidence.CONFIRMED &&
            currentTurn != DominoVision.ActivePlayer.UNKNOWN && turnConfidence >= 65

    val boardConfirmed: Boolean
        get() = confidence == Confidence.CONFIRMED &&
            (boardIsEmpty || (leftEnd in 0..6 && rightEnd in 0..6 && boardTiles.isNotEmpty()))
}
