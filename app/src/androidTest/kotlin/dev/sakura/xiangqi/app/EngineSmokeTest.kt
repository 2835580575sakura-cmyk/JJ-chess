package dev.sakura.xiangqi.app
import androidx.test.platform.app.InstrumentationRegistry
import dev.sakura.xiangqi.core.*
import dev.sakura.xiangqi.engine.BinderEngineGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
class EngineSmokeTest {
    @Test fun realBinderJniSearchAndCancel()=runBlocking {
        val gateway=BinderEngineGateway(InstrumentationRegistry.getInstrumentation().targetContext,EngineOptions(2,128))
        try {
            val history=PositionHistory(START_FEN,emptyList(),true); val valid=gateway.validate(history).getOrThrow()
            val key=AnalysisKey("device",1,1,1)
            val best=withTimeout(45000) { gateway.analyze(AnalysisRequest(key,history,1000)).filterIsInstance<EngineEvent.BestMove>().first() }
            assertEquals(key,best.key); assertTrue(valid.legalMoves.contains(requireNotNull(best.move)))
            val old=key.copy(searchId=2)
            val started=CompletableDeferred<Unit>()
            val running=launch { gateway.analyze(AnalysisRequest(old,history,30000)).collect { if(it is EngineEvent.Info)started.complete(Unit) } }
            withTimeout(15000) { started.await() }; gateway.cancel(2); running.cancelAndJoin()
            val newKey=key.copy(searchId=3,positionRevision=2)
            val nextHistory=history.copy(moves=listOf(requireNotNull(best.move))); val next=gateway.validate(nextHistory).getOrThrow()
            val result=withTimeout(10000) { gateway.analyze(AnalysisRequest(newKey,nextHistory,1000)).filterIsInstance<EngineEvent.BestMove>().first() }
            assertEquals(newKey,result.key); assertTrue(result.move in next.legalMoves)
        } finally { gateway.close() }
    }
}
