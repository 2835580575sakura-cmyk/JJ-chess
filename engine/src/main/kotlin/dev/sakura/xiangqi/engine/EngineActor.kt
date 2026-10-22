package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class EngineActor(private val native: NativeEnginePort, private val emit: (EngineEvent)->Unit) {
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"pikafish-commands") }
    @Volatile private var active: AnalysisRequest?=null
    private var closing: CompletableFuture<Unit>?=null
    private fun <T> queue(block: ()->T): CompletableFuture<T> {
        val future=CompletableFuture<T>()
        try { worker.execute { try { future.complete(block()) } catch(t: Throwable) { future.completeExceptionally(t) } } }
        catch(t: Throwable) { future.completeExceptionally(t) }
        return future
    }
    private fun stopCurrent() {
        val request=active ?: return
        native.requestStop(); native.awaitStopped(); active=null
        emit(EngineEvent.Stopped(request.key))
    }
    fun submit(request: AnalysisRequest): CompletableFuture<Unit> = queue {
        stopCurrent(); active=request
        native.start(request) { event -> emit(event) }
    }
    fun cancel(searchId: Long): CompletableFuture<Unit> {
        if(active?.key?.searchId==searchId) native.requestStop()
        return queue { if(active?.key?.searchId==searchId) stopCurrent() }
    }
    fun validate(history: PositionHistory): CompletableFuture<Result<LegalPosition>> = queue { stopCurrent(); native.validate(history) }
    fun updateOptions(options: EngineOptions): CompletableFuture<Unit> = queue { stopCurrent(); native.configure(options) }
    @Synchronized fun close(): CompletableFuture<Unit> = closing ?: queue { stopCurrent(); native.close(); worker.shutdown() }.also { closing=it }
}
