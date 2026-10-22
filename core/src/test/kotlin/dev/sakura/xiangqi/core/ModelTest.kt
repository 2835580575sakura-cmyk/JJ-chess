package dev.sakura.xiangqi.core

import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    @Test fun startFenRoundTrip() {
        val p = FenCodec.decode(START_FEN).getOrThrow()
        assertEquals(32, p.board.cells.count { it != null })
        assertEquals(Side.RED, p.sideToMove)
        assertEquals(Piece(Side.RED, PieceKind.ROOK), p.board[Square(0, 0)])
        assertEquals(START_FEN, FenCodec.encode(p))
    }
    @Test fun coordinatesAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { Square(9, 0) }
        assertThrows(IllegalArgumentException::class.java) { Square(0, -1) }
    }
    @Test fun moveCopiesBoardAndRemovesCapture() {
        val p = FenCodec.decode(START_FEN).getOrThrow()
        val next = p.board.apply(UciMove.parse("b0c2"))
        assertNull(next[Square(1, 0)])
        assertEquals(Piece(Side.RED, PieceKind.KNIGHT), next[Square(2, 2)])
        assertNotNull(p.board[Square(1, 0)])
    }
    @Test fun invalidCalibrationRejected() {
        assertFalse(GridCalibration(FloatRect(1f, 1f, 1f, 5f), FloatRect(1f, 1f, 1f, 5f)).isValid())
    }
}
