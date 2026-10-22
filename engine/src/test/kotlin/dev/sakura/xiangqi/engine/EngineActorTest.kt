package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class EngineActorTest {
    private fun request(id: Long)=AnalysisRequest(AnalysisKey("session",1,id,0),PositionHistory(START_FEN,emptyList(),true),3000)
    class Fake : NativeEnginePort {
        val calls=CopyOnWriteArrayList<String>()
        var gate=CountDownLatch(0)
        val waiting=CountDownLatch(1)
        val callbacks=ConcurrentHashMap<Long,(EngineEvent)->Unit>()
        override fun validate(history: PositionHistory)=Result.success(LegalPosition(FenCodec.decode(history.initialFen).getOrThrow(),emptyList()))
        override fun configure(options: EngineOptions) { calls.add("configure:${options.hashMiB}") }
        override fun start(request: AnalysisRequest, callback: (EngineEvent)->Unit) { calls.add("start:${request.key.searchId}"); callbacks[request.key.searchId]=callback }
        override fun requestStop() { calls.add("stop") }
        override fun awaitStopped() { calls.add("await"); waiting.countDown(); check(gate.await(2,TimeUnit.SECONDS)) }
        override fun close() { calls.add("close") }
    }
    @Test fun stopAcknowledgedBeforeNextPosition() {
        val fake=Fake(); val actor=EngineActor(fake){}
        actor.submit(request(1)).get(1,TimeUnit.SECONDS)
        fake.gate=CountDownLatch(1)
        val second=actor.submit(request(2))
        assertTrue(fake.waiting.await(1,TimeUnit.SECONDS)); assertFalse(fake.calls.contains("start:2"))
        fake.gate.countDown(); second.get(1,TimeUnit.SECONDS)
        assertTrue(fake.calls.indexOf("await") < fake.calls.indexOf("start:2")); actor.close().get()
    }
    @Test fun optionsWaitForSearchCompletion() {
        val fake=Fake(); val actor=EngineActor(fake){}
        actor.submit(request(1)).get(); fake.gate=CountDownLatch(1)
        val change=actor.updateOptions(EngineOptions(2,128,1))
        assertTrue(fake.waiting.await(1,TimeUnit.SECONDS)); assertFalse(fake.calls.contains("configure:128"))
        fake.gate.countDown(); change.get(); assertTrue(fake.calls.contains("configure:128")); actor.close().get()
    }
    @Test fun lateEventKeepsOriginalKey() {
        val fake=Fake(); val events=CopyOnWriteArrayList<EngineEvent>(); val actor=EngineActor(fake){events.add(it)}
        val first=request(1); actor.submit(first).get(); actor.submit(request(2)).get()
        fake.callbacks[1]!!.invoke(EngineEvent.BestMove(first.key,UciMove.parse("b0c2")))
        assertEquals(first.key,events.filterIsInstance<EngineEvent.BestMove>().last().key)
        actor.close().get()
    }
    @Test fun closeIsIdempotent() {
        val fake=Fake(); val actor=EngineActor(fake){}
        actor.close().get(1,TimeUnit.SECONDS); actor.close().get(1,TimeUnit.SECONDS)
        assertEquals(1,fake.calls.count { it=="close" })
    }
}
