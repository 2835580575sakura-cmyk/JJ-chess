package dev.sakura.xiangqi.core
import org.junit.Assert.*
import org.junit.Test
class TrackingTest {
    private val initial=FenCodec.decode(START_FEN).getOrThrow()
    private fun observation(board: Board)=BoardObservation(board,List(90){1f},1)
    private fun step(tracker: GameTracker,move: String):ConfirmedPosition {
        val m=UciMove.parse(move)
        return (tracker.accept(observation(tracker.current!!.snapshot.board.apply(m)),listOf(m)) as TrackingUpdate.Confirmed).position
    }
    @Test fun legalMoveAppendsOnce() {
        val tracker=GameTracker(); tracker.start(initial,true)
        val after=step(tracker,"b0c2")
        assertEquals(1,after.history.moves.size); assertEquals(Side.BLACK,after.snapshot.sideToMove)
        assertTrue(tracker.accept(observation(after.snapshot.board),emptyList()) is TrackingUpdate.Unchanged)
        assertEquals(after.positionRevision,tracker.current!!.positionRevision)
    }
    @Test fun undoRestoresTurn() {
        val tracker=GameTracker(); tracker.start(initial,true); val first=step(tracker,"b0c2"); val second=step(tracker,"b9c7")
        assertTrue(tracker.accept(observation(first.snapshot.board),emptyList()) is TrackingUpdate.NeedsConfirmation)
        val undo=tracker.confirmRecovery(first.snapshot,1)
        assertEquals(Side.BLACK,undo.snapshot.sideToMove); assertEquals(1,undo.history.moves.size)
        assertTrue(undo.positionRevision>second.positionRevision)
    }
    @Test fun restartClearsHistory() {
        val tracker=GameTracker(); tracker.start(initial,true); step(tracker,"b0c2")
        tracker.confirmRecovery(initial,0)
        assertTrue(tracker.current!!.history.moves.isEmpty()); assertTrue(tracker.current!!.history.historyComplete)
    }
    @Test fun flipPreservesIdentity() {
        val tracker=GameTracker(); val before=tracker.start(initial,true)
        assertTrue(tracker.accept(observation(initial.board),emptyList()) is TrackingUpdate.Unchanged)
        assertEquals(before,tracker.current)
    }
    @Test fun missedMoveRequiresResync() {
        val tracker=GameTracker(); tracker.start(initial,true)
        val jumped=initial.board.apply(UciMove.parse("b0c2")).apply(UciMove.parse("b9c7"))
        assertTrue(tracker.accept(observation(jumped),listOf(UciMove.parse("b0c2"))) is TrackingUpdate.Unsynced)
    }
    @Test fun ambiguousRepeatedBoardRequiresConfirmation() {
        val tracker=GameTracker(); tracker.start(initial,true)
        for(m in listOf("b0c2","b9c7","c2b0","c7b9","a3a4")) step(tracker,m)
        val update=tracker.accept(observation(initial.board),emptyList()) as TrackingUpdate.NeedsConfirmation
        assertEquals(listOf(0,4),update.historyIndices)
    }
    @Test fun midgameHasIncompleteHistory() {
        val tracker=GameTracker()
        val mid=tracker.start(PositionSnapshot(initial.board.apply(UciMove.parse("b0c2")),Side.BLACK),false)
        assertFalse(mid.history.historyComplete)
    }
}
