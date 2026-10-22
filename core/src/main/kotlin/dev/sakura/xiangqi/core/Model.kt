package dev.sakura.xiangqi.core

import kotlinx.serialization.Serializable

const val START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
@Serializable enum class Side { RED, BLACK; fun opposite() = if (this == RED) BLACK else RED }
@Serializable enum class PieceKind(val fen: Char) { ROOK('r'), KNIGHT('n'), ELEPHANT('b'), ADVISOR('a'), KING('k'), CANNON('c'), PAWN('p') }
@Serializable data class Piece(val side: Side, val kind: PieceKind)
@Serializable data class Square(val file: Int, val rank: Int) {
    init { require(file in 0..8 && rank in 0..9) { "棋盘坐标越界" } }
    override fun toString() = "${'a' + file}$rank"
    companion object {
        fun parse(text: String): Square {
            require(text.matches(Regex("[a-i][0-9]"))) { "走法坐标无效" }
            return Square(text[0] - 'a', text[1] - '0')
        }
    }
}
@Serializable data class UciMove(val from: Square, val to: Square) {
    init { require(from != to) { "起点和终点相同" } }
    override fun toString() = "$from$to"
    companion object { fun parse(text: String): UciMove { require(text.length == 4); return UciMove(Square.parse(text.take(2)), Square.parse(text.takeLast(2))) } }
}
@Serializable data class Board(val cells: List<Piece?>) {
    init { require(cells.size == 90) }
    operator fun get(square: Square): Piece? = cells[square.rank * 9 + square.file]
    fun with(square: Square, piece: Piece?): Board = Board(cells.toMutableList().also { it[square.rank * 9 + square.file] = piece })
    fun apply(move: UciMove): Board {
        val piece = requireNotNull(this[move.from]) { "起点没有棋子" }
        return with(move.from, null).with(move.to, piece)
    }
}
@Serializable data class PositionSnapshot(val board: Board, val sideToMove: Side, val halfmoveClock: Int = 0, val fullmoveNumber: Int = 1)
@Serializable data class PositionHistory(val initialFen: String, val moves: List<UciMove>, val historyComplete: Boolean)
@Serializable data class ConfirmedPosition(val snapshot: PositionSnapshot, val history: PositionHistory, val positionRevision: Long)
data class BoardObservation(val board: Board?, val confidence: List<Float>, val timestampNs: Long, val motion: Float = 0f, val occluded: Boolean = false)
