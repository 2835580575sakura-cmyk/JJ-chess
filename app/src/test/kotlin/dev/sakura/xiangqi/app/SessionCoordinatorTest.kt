package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    class Gateway:EngineGateway {
        val events=MutableSharedFlow<EngineEvent>(extraBufferCapacity=32)
        val requests=mutableListOf<AnalysisRequest>(); var rulesCalls=0; var closes=0
        var stopBarrier:CompletableDeferred<Unit>?=null; var terminal=false; var reject=false
        override suspend fun validate(history:PositionHistory):Result<LegalPosition> = runCatching {
            rulesCalls++; check(!reject) { "非法局面" }
            var p=FenCodec.decode(history.initialFen).getOrThrow()
            for(m in history.moves)p=p.copy(board=p.board.apply(m),sideToMove=p.sideToMove.opposite())
            val moves=(if(p.sideToMove==Side.RED)listOf("b0c2","h0g2","a3a4") else listOf("b9c7","h9g7","a6a5")).map { UciMove.parse(it) }.filter { p.board[it.from]?.side==p.sideToMove }
            LegalPosition(p,if(terminal)emptyList() else moves)
        }
        override fun analyze(request:AnalysisRequest):Flow<EngineEvent> { requests.add(request); return events }
        override suspend fun cancel(searchId:Long) { stopBarrier?.await() }
        override suspend fun close() { closes++ }
    }
    class Overlay:HintOverlay {
        var hint:MoveHint?=null; var allowed=true
        override fun setCalibration(calibration:GridCalibration) {}
        override fun show(key:AnalysisKey,move:UciMove,text:String):Boolean { if(!allowed)return false; hint=MoveHint(key,move,text); return true }
        override fun clear() { hint=null }
        override fun hideForSample() {}
        override fun restore(key:AnalysisKey)=hint?.key==key
        override fun status(text:String) {}
    }
    private val initial=FenCodec.decode(START_FEN).getOrThrow()
    private val cal=GridCalibration(FloatRect(40f,366f,545f,909f),FloatRect(40f,366f,545f,909f),transformRevision=1)
    @Test fun staleResultsRejectedForAllFourVersions()=runTest {
        val gateway=Gateway(); val overlay=Overlay(); val c=SessionCoordinator(backgroundScope,gateway,overlay)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        val key=c.state.value.key!!
        for(old in listOf(key.copy(sessionId="old"),key.copy(positionRevision=0),key.copy(searchId=0),key.copy(transformRevision=0))) {
            gateway.events.emit(EngineEvent.Info(old,8,1000,100,EngineScore(cp=100),emptyList())); gateway.events.emit(EngineEvent.BestMove(old,UciMove.parse("b0c2"))); runCurrent(); assertNull(overlay.hint); assertNull(c.state.value.info)
        }
        gateway.events.emit(EngineEvent.BestMove(key,UciMove.parse("b0c2"))); runCurrent()
        assertEquals(key,overlay.hint!!.key); c.stop().join()
    }
    @Test fun clearsHintAndWaitsForStopBeforeNewRules()=runTest {
        val gateway=Gateway(); val overlay=Overlay(); val c=SessionCoordinator(backgroundScope,gateway,overlay)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
        val key=c.state.value.key!!
        gateway.events.emit(EngineEvent.BestMove(key,UciMove.parse("b0c2"))); runCurrent()
        gateway.stopBarrier=CompletableDeferred()
        val moved=initial.board.apply(UciMove.parse("b0c2")); val job=c.onObservation(BoardObservation(moved,List(90){1f},1),cal)
        runCurrent(); assertNull(overlay.hint); assertEquals(1,gateway.rulesCalls)
        gateway.stopBarrier!!.complete(Unit); job.join(); runCurrent()
        assertEquals(2,gateway.rulesCalls); assertEquals(SessionPhase.WAITING_OPPONENT,c.state.value.phase)
        gateway.events.emit(EngineEvent.BestMove(key,UciMove.parse("b0c2"))); runCurrent(); assertNull(overlay.hint)
        c.stop().join()
    }
    @Test fun illegalAndEmptyBestMoveNeverDrawn()=runTest {
        for(move in listOf(UciMove.parse("b0b2"),null)) {
            val gateway=Gateway(); val overlay=Overlay(); val c=SessionCoordinator(backgroundScope,gateway,overlay)
            c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
            gateway.events.emit(EngineEvent.BestMove(c.state.value.key!!,move)); runCurrent()
            assertNull(overlay.hint); assertEquals(SessionPhase.ERROR,c.state.value.phase); c.stop().join()
        }
    }
    @Test fun stopIsIdempotentAndOpponentTurnHasNoArrow()=runTest {
        val gateway=Gateway(); val overlay=Overlay(); val c=SessionCoordinator(backgroundScope,gateway,overlay)
        c.start(SessionConfig(playerSide=Side.BLACK)).join(); c.confirm(initial,cal).join(); runCurrent()
        assertEquals(0,gateway.requests.size); assertEquals(SessionPhase.WAITING_OPPONENT,c.state.value.phase)
        c.stop().join(); c.stop().join(); assertEquals(1,gateway.closes)
    }
    @Test fun terminalAndIllegalPositionDoNotSearch()=runTest {
        for(terminal in listOf(true,false)) {
            val g=Gateway().apply { this.terminal=terminal; reject=!terminal }; val o=Overlay(); val c=SessionCoordinator(backgroundScope,g,o)
            c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent()
            assertTrue(g.requests.isEmpty()); assertNull(o.hint); assertEquals(if(terminal)SessionPhase.READY else SessionPhase.ERROR,c.state.value.phase); c.stop().join()
        }
    }
    @Test fun disconnectAndRevokedOverlayClearHint()=runTest {
        for(revoked in listOf(true,false)) {
            val g=Gateway(); val o=Overlay().apply { allowed=!revoked }; val c=SessionCoordinator(backgroundScope,g,o)
            c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent(); val key=c.state.value.key!!
            g.events.emit(if(revoked)EngineEvent.BestMove(key,UciMove.parse("b0c2")) else EngineEvent.Failure(key,"断开")); runCurrent()
            assertEquals(SessionPhase.ERROR,c.state.value.phase); assertNull(o.hint); c.stop().join(); c.stop().join()
        }
    }
    @Test fun pauseRejectsLateResultsAndResumeWaitsForFreshBoard()=runTest {
        val g=Gateway(); val o=Overlay(); val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.confirm(initial,cal).join(); runCurrent(); val key=c.state.value.key!!
        c.pause().join(); g.events.emit(EngineEvent.BestMove(key,UciMove.parse("b0c2"))); runCurrent(); assertNull(o.hint)
        c.resume().join(); runCurrent(); assertEquals(1,g.requests.size)
        c.onObservation(BoardObservation(initial.board,List(90){1f},1),cal).join(); runCurrent(); assertEquals(2,g.requests.size); assertNotEquals(key,c.state.value.key); c.stop().join()
    }
    @Test fun oldSessionCandidateCannotReplaceNewBoard()=runTest {
        val g=Gateway(); val o=Overlay(); val c=SessionCoordinator(backgroundScope,g,o)
        c.start(SessionConfig()).join(); c.onObservation(BoardObservation(initial.board.apply(UciMove.parse("b0c2")),List(90){1f},1),cal).join()
        c.confirmCandidate(GameTracker().start(initial,true),cal).join()
        assertEquals(SessionPhase.ERROR,c.state.value.phase); assertTrue(g.requests.isEmpty()); assertNull(o.hint); c.stop().join()
    }
    @Test fun scoreUsesPlayerPerspective() {
        assertEquals(-120,ScorePresentation.playerCp(EngineScore(cp=120),Side.RED,Side.BLACK))
        assertEquals(120,ScorePresentation.playerCp(EngineScore(cp=120),Side.RED,Side.RED))
    }
}
