package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class BinderProtocolTest {
    @Test fun protocolRoundTrip() {
        val request=AnalysisRequest(AnalysisKey("s",2,3,4),PositionHistory(START_FEN,listOf(UciMove.parse("b0c2")),true),15000)
        assertEquals(request,BinderProtocol.decodeRequest(BinderProtocol.encodeRequest(request)))
        val event:EngineEvent=EngineEvent.BestMove(request.key,UciMove.parse("b9c7"))
        assertEquals(event,BinderProtocol.decodeEvent(BinderProtocol.encodeEvent(event)))
    }
    @Test fun binderDeathClearsRequest() = runBlocking {
        val registry=PendingRegistry<String>(); val first=registry.register(1); val second=registry.register(2)
        registry.failAll(IllegalStateException("engine disconnected"))
        assertTrue(first.isCompleted); assertTrue(second.isCompleted)
        assertTrue(runCatching { first.await() }.isFailure)
        assertTrue(runCatching { second.await() }.isFailure)
    }
}
