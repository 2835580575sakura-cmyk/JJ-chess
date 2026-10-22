package dev.sakura.xiangqi.core
import org.junit.Assert.*
import org.junit.Test
class MoveNotationTest {
    @Test fun redAndBlackHorseDirections() {
        val red=FenCodec.decode(START_FEN).getOrThrow()
        assertEquals("马二进三",MoveNotation.format(red,UciMove.parse("h0g2"),Side.RED))
        assertEquals("马2进3",MoveNotation.format(red.copy(sideToMove=Side.BLACK),UciMove.parse("b9c7"),Side.BLACK))
    }
    @Test fun cannonHorizontalAndKingVertical() {
        val p=FenCodec.decode(START_FEN).getOrThrow()
        assertEquals("炮二平五",MoveNotation.format(p,UciMove.parse("h2e2"),Side.RED))
        assertEquals("帅五进一",MoveNotation.format(p,UciMove.parse("e0e1"),Side.RED))
    }
    @Test fun sameFileRooksDisambiguated() {
        val base=FenCodec.decode(START_FEN).getOrThrow()
        val p=base.copy(board=base.board.with(Square(8,0),null).with(Square(0,2),Piece(Side.RED,PieceKind.ROOK)))
        assertEquals("前车进一",MoveNotation.format(p,UciMove.parse("a2a3"),Side.RED))
        assertEquals("后车进一",MoveNotation.format(p,UciMove.parse("a0a1"),Side.RED))
    }
}
