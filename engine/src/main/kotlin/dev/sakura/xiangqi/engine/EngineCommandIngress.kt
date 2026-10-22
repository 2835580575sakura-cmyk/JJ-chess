package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.AnalysisRequest
import java.util.concurrent.CompletableFuture
/** Records cancellation at Binder ingress, before potentially delayed IO dispatch. */
class EngineCommandIngress(private val actor:()->EngineActor?,private val dispatch:(()->Unit)->Unit) {
    private var generation=0L
    private var cancelledThrough=0L
    @Synchronized fun newSession():Long { generation++; cancelledThrough=0; return generation }
    @Synchronized fun invalidate() { generation++; cancelledThrough=Long.MAX_VALUE }
    @Synchronized fun currentGeneration()=generation
    @Synchronized fun isCurrent(value:Long)=value==generation
    @Synchronized fun <T> ifCurrent(value:Long,block:()->T):T?=if(value==generation)block() else null
    fun analyze(request:AnalysisRequest,ticket:Long=currentGeneration()):CompletableFuture<Unit> {
        val result=CompletableFuture<Unit>()
        dispatch {
            try {
                val queued=synchronized(this) {
                    if(ticket!=generation || request.key.searchId<=cancelledThrough)null
                    else checkNotNull(actor()) { "引擎未初始化" }.submit(request)
                }
                if(queued==null)result.complete(Unit)
                else queued.whenComplete { _,error -> if(error!=null)result.completeExceptionally(error) else result.complete(Unit) }
            } catch(t:Throwable) { result.completeExceptionally(t) }
        }
        return result
    }
    @Synchronized fun cancel(searchId:Long,ticket:Long=currentGeneration()):CompletableFuture<Unit> {
        if(ticket!=generation)return CompletableFuture.completedFuture(Unit)
        cancelledThrough=maxOf(cancelledThrough,searchId)
        return actor()?.cancel(searchId)?:CompletableFuture.completedFuture(Unit)
    }
}
