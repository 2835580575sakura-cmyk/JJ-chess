package dev.sakura.xiangqi.core
import org.junit.Assert.*
import org.junit.Test
class FenCodecTest {
    @Test fun invalidFenRejected() {
        for (fen in listOf("bad", "9/9/9/9/9/9/9/9/9/9 w - - 0 1", START_FEN.replace(" w ", " x "), START_FEN.replace("0 1", "99999999999999 1"))) {
            assertTrue(fen, FenCodec.decode(fen).isFailure)
        }
    }
    @Test fun blackTurnPreserved() {
        assertEquals(Side.BLACK, FenCodec.decode(START_FEN.replace(" w ", " b ")).getOrThrow().sideToMove)
    }
}
