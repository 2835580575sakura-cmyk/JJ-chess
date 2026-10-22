package dev.sakura.xiangqi.app

import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RapidReplyTest {
    private val initial=FenCodec.decode(START_FEN).getOrThrow()
    private val cal=GridCalibration(FloatRect(40f,366f,545f,909f),FloatRect(40f,366f,545f,909f),transformRevision=1)
    private fun moves(vararg moves:String)=moves.map(UciMove::parse)
    private fun boardAfter(vararg moves:String)=moves.fold(initial.board) { board,move -> board.apply(UciMove.parse(move)) }
    private fun observation(board:Board,timestamp:Long=2_000_000_000)=BoardObservation(board,List(90){1f},timestamp)

    @Test fun skippedIntermediateBoardKeepsBothPliesAndSearchesAgainWithoutConfirmation()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val confirmed=mutableListOf<ConfirmedPosition>()
        val c=SessionCoordinator(backgroundScope,g,o,onConfirmed={confirmed.add(it)})
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        val firstKey=c.state.value.key!!
        g.events.emit(EngineEvent.BestMove(firstKey,UciMove.parse("b0c2"))); runCurrent()
        assertNotNull(o.hint)

        val firstPair=boardAfter("b0c2","b9c7")
        c.onObservation(observation(firstPair),cal).join(); runCurrent()
        assertEquals(SessionPhase.ANALYZING,c.state.value.phase)
        assertEquals(2,g.requests.size)
        assertEquals(moves("b0c2","b9c7"),g.requests.last().history.moves)
        assertEquals(firstPair,c.state.value.position!!.board)
        assertEquals(Side.RED,c.state.value.position!!.sideToMove)
        assertEquals(2,c.state.value.position!!.fullmoveNumber)
        assertTrue(c.state.value.historyComplete)
        assertEquals(2,confirmed.size)
        assertNull(o.hint)
        assertNotEquals(firstKey,c.state.value.key)

        g.events.emit(EngineEvent.BestMove(firstKey,UciMove.parse("b0c2"))); runCurrent()
        assertNull(o.hint)
        g.events.emit(EngineEvent.BestMove(c.state.value.key!!,UciMove.parse("h0g2"))); runCurrent()
        assertNotNull(o.hint)

        val secondPair=boardAfter("b0c2","b9c7","h0g2","h9g7")
        c.onObservation(observation(secondPair,3_000_000_000),cal).join(); runCurrent()
        assertEquals(SessionPhase.ANALYZING,c.state.value.phase)
        assertEquals(3,g.requests.size)
        assertEquals(moves("b0c2","b9c7","h0g2","h9g7"),g.requests.last().history.moves)
        assertEquals(secondPair,c.state.value.position!!.board)
        assertEquals(Side.RED,c.state.value.position!!.sideToMove)
        assertEquals(3,c.state.value.position!!.fullmoveNumber)
        assertTrue(g.requests.last().history.historyComplete)
        assertEquals(3,confirmed.size)
        assertNull(o.hint)
        c.stop().join()
    }

    @Test fun illegalOpponentReplyStillRequiresConfirmation()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        g.events.emit(EngineEvent.BestMove(c.state.value.key!!,UciMove.parse("b0c2"))); runCurrent()

        c.onObservation(observation(boardAfter("b0c2","b9b7")),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(1,g.requests.size)
        assertEquals(2,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun threePlyJumpStillRequiresConfirmation()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        c.onObservation(observation(boardAfter("b0c2","b9c7","h0g2")),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(1,g.requests.size)
        assertEquals(1,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun twoMovesAcrossPauseGapStillRequireConfirmation()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        c.pause().join(); c.resume().join()
        c.onObservation(observation(boardAfter("b0c2","b9c7")),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(1,g.requests.size)
        assertEquals(1,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun confirmationWithoutFreshTrackedFrameCannotReconcileTwoMoves()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(boardAfter("b0c2","b9c7")),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(1,g.requests.size); assertEquals(1,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun longSamplingGapCannotReconcileTwoMoves()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        c.onObservation(observation(boardAfter("b0c2","b9c7"),6_000_000_001),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(1,g.requests.size); assertEquals(1,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun repeatedOrOlderTimestampCannotReconcileTwoMoves()=runTest {
        for(timestamp in listOf(1_000_000_000L,999_999_999L)) {
            val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
            val c=SessionCoordinator(backgroundScope,g,o)
            c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
            c.onObservation(observation(initial.board,1_000_000_000),cal).join()
            c.onObservation(observation(boardAfter("b0c2","b9c7"),timestamp),cal).join(); runCurrent()
            assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
            assertEquals(1,g.requests.size); assertEquals(1,g.rulesCalls)
            assertNull(c.state.value.key); assertNull(o.hint)
            c.stop().join()
        }
    }

    @Test fun unstableOrUnknownObservationCannotReconcileTwoMoves()=runTest {
        for(sample in listOf(
            observation(boardAfter("b0c2","b9c7")).copy(motion=.3f),
            BoardObservation(null,List(90){0f},1,occluded=true,recognitionIssue=RecognitionIssue.UNKNOWN_CELLS,unknownCellCount=90)
        )) {
            val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
            val c=SessionCoordinator(backgroundScope,g,o)
            c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
            c.onObservation(observation(initial.board,1_000_000_000),cal).join()
            g.events.emit(EngineEvent.BestMove(c.state.value.key!!,UciMove.parse("b0c2"))); runCurrent()
            c.onObservation(sample,cal).join(); runCurrent()
            assertEquals(SessionPhase.WAITING_BOARD,c.state.value.phase)
            assertEquals(1,g.requests.size); assertEquals(1,g.rulesCalls)
            assertNull(c.state.value.key); assertNull(o.hint)
            c.stop().join()
        }
    }

    @Test fun manuallyCorrectedBaselineStillRequiresConfirmationWhenTwoMovesAppear()=runTest {
        val g=SessionCoordinatorTest.Gateway(); val o=SessionCoordinatorTest.Overlay()
        val c=SessionCoordinator(backgroundScope,g,o)
        val raw=initial.board.with(Square(1,0),Piece(Side.RED,PieceKind.CANNON))
        c.start(SessionConfig()).join(); c.onObservation(observation(raw),cal).join()
        c.pause().join(); c.confirm(initial,cal).join(); c.resume().join()
        c.onObservation(observation(boardAfter("b0c2","b9c7"),3_000_000_000),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertTrue(g.requests.isEmpty()); assertEquals(1,g.rulesCalls)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun twoPlyReturnToHistoricalBoardStillRequiresConfirmation()=runTest {
        val delegate=SessionCoordinatorTest.Gateway()
        val g=object:EngineGateway by delegate {
            override suspend fun validate(history:PositionHistory):Result<LegalPosition> = delegate.validate(history).map { valid ->
                val reversals=if(valid.snapshot.sideToMove==Side.RED)moves("c2b0") else moves("c7b9")
                valid.copy(legalMoves=valid.legalMoves+reversals.filter { valid.snapshot.board[it.from]?.side==valid.snapshot.sideToMove })
            }
        }
        val o=SessionCoordinatorTest.Overlay(); val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(boardAfter("b0c2"),1_000_000_000),cal).join()
        c.onObservation(observation(boardAfter("b0c2","b9c7"),2_000_000_000),cal).join(); runCurrent()
        val calls=delegate.rulesCalls

        c.onObservation(observation(initial.board,3_000_000_000),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(listOf(0),c.state.value.pendingHistoryIndices)
        assertEquals(calls,delegate.rulesCalls)
        assertEquals(2,delegate.requests.size)
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun twoLegalPathsToSameBoardRequireConfirmationWithoutChangingHistory()=runTest {
        val start=PositionSnapshot(Board(List(90){null})
            .with(Square.parse("e0"),Piece(Side.RED,PieceKind.KING))
            .with(Square.parse("e9"),Piece(Side.BLACK,PieceKind.KING))
            .with(Square.parse("e5"),Piece(Side.RED,PieceKind.PAWN))
            .with(Square.parse("b1"),Piece(Side.RED,PieceKind.ROOK))
            .with(Square.parse("b2"),Piece(Side.BLACK,PieceKind.PAWN))
            .with(Square.parse("c1"),Piece(Side.BLACK,PieceKind.PAWN))
            .with(Square.parse("c2"),Piece(Side.BLACK,PieceKind.PAWN)),Side.RED)
        FenCodec.decode(FenCodec.encode(start)).getOrThrow()
        val firstPath=moves("b1b2","c2b2"); val secondPath=moves("b1c1","c2c1")
        val observed=firstPath.fold(start.board) { board,move -> board.apply(move) }
        assertEquals(observed,secondPath.fold(start.board) { board,move -> board.apply(move) })
        val delegate=SessionCoordinatorTest.Gateway()
        val g=object:EngineGateway by delegate {
            override suspend fun validate(history:PositionHistory):Result<LegalPosition> = delegate.validate(history).map { valid ->
                val legal=when(history.moves) {
                    emptyList<UciMove>() -> listOf(firstPath.first(),secondPath.first())
                    firstPath.take(1) -> firstPath.takeLast(1)
                    secondPath.take(1) -> secondPath.takeLast(1)
                    else -> emptyList()
                }
                valid.copy(legalMoves=legal)
            }
        }
        val o=SessionCoordinatorTest.Overlay(); val confirmed=mutableListOf<ConfirmedPosition>()
        val c=SessionCoordinator(backgroundScope,g,o,onConfirmed={confirmed.add(it)})
        c.start(SessionConfig()).join(); c.confirm(start,cal).join(); runCurrent()
        c.onObservation(observation(start.board,1_000_000_000),cal).join()

        c.onObservation(observation(observed),cal).join(); runCurrent()
        assertEquals(SessionPhase.NEEDS_SYNC,c.state.value.phase)
        assertEquals(3,delegate.rulesCalls); assertEquals(1,delegate.requests.size)
        assertEquals(1,confirmed.size); assertTrue(confirmed.single().history.moves.isEmpty())
        assertEquals(start.board,c.historyCandidate(0)!!.snapshot.board); assertNull(c.historyCandidate(1))
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }

    @Test fun intermediateNativeFailureKeepsEngineFailureReasonWithoutAppendingHistory()=runTest {
        val delegate=SessionCoordinatorTest.Gateway()
        val g=object:EngineGateway by delegate {
            override suspend fun validate(history:PositionHistory):Result<LegalPosition> {
                val valid=delegate.validate(history)
                return if(history.moves==moves("b0c2"))Result.failure(EngineFailureException(EngineFailureReason.MEMORY,"引擎内存不足")) else valid
            }
        }
        val o=SessionCoordinatorTest.Overlay(); val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        c.onObservation(observation(initial.board,1_000_000_000),cal).join()
        delegate.events.emit(EngineEvent.BestMove(c.state.value.key!!,UciMove.parse("b0c2"))); runCurrent()
        assertNotNull(o.hint)

        c.onObservation(observation(boardAfter("b0c2","b9c7")),cal).join(); runCurrent()
        assertEquals(SessionPhase.ERROR,c.state.value.phase)
        assertEquals(EngineFailureReason.MEMORY,c.state.value.failureReason)
        assertEquals(2,delegate.rulesCalls); assertEquals(1,delegate.requests.size)
        assertTrue(c.historyCandidate(0)!!.history.moves.isEmpty()); assertNull(c.historyCandidate(1))
        assertNull(c.state.value.key); assertNull(o.hint)
        c.stop().join()
    }
}
