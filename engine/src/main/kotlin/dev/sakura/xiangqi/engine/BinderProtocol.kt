package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

object BinderProtocol {
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=false }
    fun encodeRequest(value: AnalysisRequest)=json.encodeToString(value)
    fun decodeRequest(value: String): AnalysisRequest { require(value.length<=1048576); return json.decodeFromString(value) }
    fun encodeEvent(value: EngineEvent)=json.encodeToString(value)
    fun decodeEvent(value: String): EngineEvent { require(value.length<=1048576); return json.decodeFromString(value) }
}
class PendingRegistry<T> {
    private val pending=ConcurrentHashMap<Long,CompletableDeferred<T>>()
    fun register(id: Long)=CompletableDeferred<T>().also { check(pending.putIfAbsent(id,it)==null) }
    fun complete(id: Long,value: T) { pending.remove(id)?.complete(value) }
    fun fail(id: Long,error: Throwable) { pending.remove(id)?.completeExceptionally(error) }
    fun failAll(error: Throwable) { for(id in pending.keys) fail(id,error) }
}
