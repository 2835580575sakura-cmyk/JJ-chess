package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
class BoardEditorTest {
    @Test fun missingKingCannotBeConfirmed() {
        val editor=BoardEditorState(FenCodec.decode(START_FEN).getOrThrow()); editor.set(Square(4,0),null)
        assertTrue(editor.snapshot().isFailure)
    }
    @Test fun sideToMoveIndependentOfPlayerAndOrientation() {
        val editor=BoardEditorState(FenCodec.decode(START_FEN).getOrThrow()); editor.sideToMove=Side.BLACK
        assertEquals(Side.BLACK,editor.snapshot().getOrThrow().sideToMove)
    }
}
