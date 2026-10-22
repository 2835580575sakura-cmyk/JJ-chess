package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
class EngineCommandIngressTest {
    private fun request(id:Long)=AnalysisRequest(AnalysisKey("s",1,id,1),PositionHistory(START_FEN,emptyList(),true),3000)
    @Test fun cancelledBeforeDelayedAnalyzeDispatchNeverStarts() {
        val native=EngineActorTest.Fake(); val actor=EngineActor(native){}; val pending=mutableListOf<()->Unit>()
        val ingress=EngineCommandIngress({actor}) { pending.add(it) }
        ingress.newSession()
        val old=ingress.analyze(request(1)); ingress.cancel(1).get(1,TimeUnit.SECONDS)
        val next=ingress.analyze(request(2)); pending.toList().reversed().forEach { it() }
        old.get(1,TimeUnit.SECONDS); next.get(1,TimeUnit.SECONDS)
        assertFalse(native.calls.contains("start:1")); assertTrue(native.calls.contains("start:2")); actor.close().get()
    }
    @Test fun previousSessionDispatchCannotReachReplacementActor() {
        val native=EngineActorTest.Fake(); val actor=EngineActor(native){}; val pending=mutableListOf<()->Unit>()
        val ingress=EngineCommandIngress({actor}) { pending.add(it) }; ingress.newSession(); val old=ingress.analyze(request(1))
        ingress.newSession(); pending.forEach { it() }; old.get(1,TimeUnit.SECONDS)
        assertFalse(native.calls.any { it.startsWith("start") }); actor.close().get()
    }
}
