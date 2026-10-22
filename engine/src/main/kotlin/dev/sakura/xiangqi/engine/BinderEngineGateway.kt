package dev.sakura.xiangqi.engine
import android.content.*
import android.os.IBinder
import android.os.Process
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BinderEngineGateway(context: Context,private val options: EngineOptions=EngineOptions()):EngineGateway {
    private val context=context.applicationContext
    private val mutex=Mutex()
    @Volatile private var closed=false
    @Volatile private var initialized=false
    @Volatile private var remote:IEngineService?=null
    @Volatile private var bound=false
    private var connected=CompletableDeferred<IEngineService>()
    private var ready=CompletableDeferred<Unit>()
    private var enginePid=0
    private val ids=AtomicLong()
    private val rules=PendingRegistry<LegalPosition>()
    private val cancellations=PendingRegistry<Unit>()
    private val channels=ConcurrentHashMap<AnalysisKey,Channel<EngineEvent>>()
    private fun disconnected(error: Throwable) {
        remote=null; ready.completeExceptionally(error); connected.completeExceptionally(error)
        rules.failAll(error); cancellations.failAll(error)
        channels.values.forEach { it.close(error) }; channels.clear()
    }
    private val callback=object:IEngineCallback.Stub() {
        override fun onEvent(text: String) {
            runCatching {
                val obj=BinderProtocol.json.parseToJsonElement(text).jsonObject
                when(obj["kind"]?.jsonPrimitive?.content) {
                    "ready" -> { enginePid=obj["pid"]!!.jsonPrimitive.int; initialized=true; ready.complete(Unit) }
                    "rules" -> rules.complete(obj["requestId"]!!.jsonPrimitive.long,BinderProtocol.json.decodeFromJsonElement(obj["value"]!!))
                    "cancelled" -> cancellations.complete(obj["searchId"]!!.jsonPrimitive.long,Unit)
                    "error" -> { val error=EngineFailureException(runCatching { EngineFailureReason.valueOf(obj["reason"]?.jsonPrimitive?.content?:"GENERIC") }.getOrDefault(EngineFailureReason.GENERIC),obj["message"]?.jsonPrimitive?.content?:"引擎错误"); val id=obj["requestId"]?.jsonPrimitive?.long; if(id!=null) rules.fail(id,error) else disconnected(error) }
                    else -> { val event=BinderProtocol.decodeEvent(text); channels[event.key]?.let { channel -> channel.trySend(event); if(event is EngineEvent.BestMove || event is EngineEvent.Failure || event is EngineEvent.Stopped) channel.close() } }
                }
            }.onFailure { disconnected(IllegalStateException("引擎通信错误",it)) }
        }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,binder: IBinder) {
            if(closed)return
            try {
                val service=IEngineService.Stub.asInterface(binder); remote=service
                binder.linkToDeath({ if(!closed)disconnected(EngineFailures.disconnected(initialized)) },0)
                connected.complete(service); service.initialize(BinderProtocol.json.encodeToString(options),callback)
            } catch(t: Throwable) { disconnected(t) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { if(!closed)disconnected(EngineFailures.disconnected(initialized)) }
        override fun onBindingDied(name: ComponentName?) { disconnected(IllegalStateException("引擎绑定失效，请重试")) }
        override fun onNullBinding(name: ComponentName?) { disconnected(IllegalStateException("无法绑定引擎服务")) }
    }
    private suspend fun service():IEngineService {
        check(!closed) { "会话已停止" }
        mutex.withLock {
            check(!closed) { "会话已停止" }
            if(!bound) {
                connected=CompletableDeferred(); ready=CompletableDeferred()
                bound=context.bindService(Intent(context,EngineService::class.java),connection,Context.BIND_AUTO_CREATE)
                check(bound) { "无法启动引擎服务" }
            }
        }
        return try { withTimeout(35000) { val result=connected.await(); ready.await(); check(!closed) { "会话已停止" }; result } } catch(t:TimeoutCancellationException) { throw EngineFailureException(EngineFailureReason.INITIALIZATION_EXIT,"引擎初始化超时，原因未确定") }
    }
    override suspend fun validate(history: PositionHistory):Result<LegalPosition> = runCatching {
        FenCodec.decode(history.initialFen).getOrThrow()
        val service=service(); val id=ids.incrementAndGet(); val result=rules.register(id)
        service.validate(id,BinderProtocol.json.encodeToString(history))
        try { withTimeout(7000) { result.await() } } catch(t:TimeoutCancellationException) { throw IllegalStateException("规则校验超时，请重试",t) }
    }
    override fun analyze(request: AnalysisRequest):Flow<EngineEvent> = callbackFlow {
        val channel=Channel<EngineEvent>(Channel.UNLIMITED)
        channels[request.key]=channel
        val forwarding=launch { try { for(event in channel) send(event); close() } catch(t: Throwable) { close(t) } }
        try { service().analyze(BinderProtocol.encodeRequest(request)) }
        catch(t: Throwable) { close(t) }
        awaitClose { channels.remove(request.key,channel); channel.close(); forwarding.cancel() }
    }
    override suspend fun cancel(searchId: Long) {
        val service=remote?:return; val acknowledgement=cancellations.register(searchId)
        try { service.cancel(searchId); withTimeout(6500) { acknowledgement.await() } }
        catch(t: Throwable) { if(enginePid>0 && enginePid!=Process.myPid()) Process.killProcess(enginePid); val failure=if(t is TimeoutCancellationException)IllegalStateException("引擎停止超时，请重试",t) else t; disconnected(failure); throw failure }
    }
    override suspend fun close() {
        if(closed)return
        closed=true
        val endpoint=remote
        disconnected(IllegalStateException("会话已停止"))
        runCatching { endpoint?.shutdown() }
        if(bound) runCatching { context.unbindService(connection) }
        bound=false; enginePid=0; disconnected(IllegalStateException("会话已停止"))
    }
}
