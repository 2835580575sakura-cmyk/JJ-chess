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
                    "ready" -> { enginePid=obj["pid"]!!.jsonPrimitive.int; ready.complete(Unit) }
                    "rules" -> rules.complete(obj["requestId"]!!.jsonPrimitive.long,BinderProtocol.json.decodeFromJsonElement(obj["value"]!!))
                    "cancelled" -> cancellations.complete(obj["searchId"]!!.jsonPrimitive.long,Unit)
                    "error" -> { val error=IllegalStateException(obj["message"]?.jsonPrimitive?.content?:"引擎错误"); val id=obj["requestId"]?.jsonPrimitive?.long; if(id!=null) rules.fail(id,error) else disconnected(error) }
                    else -> { val event=BinderProtocol.decodeEvent(text); channels[event.key]?.let { channel -> channel.trySend(event); if(event is EngineEvent.BestMove || event is EngineEvent.Failure || event is EngineEvent.Stopped) channel.close() } }
                }
            }.onFailure { disconnected(IllegalStateException("引擎通信错误",it)) }
        }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,binder: IBinder) {
            try {
                val service=IEngineService.Stub.asInterface(binder); remote=service
                binder.linkToDeath({ disconnected(IllegalStateException("引擎进程已断开，请重新开始")) },0)
                connected.complete(service); service.initialize(BinderProtocol.json.encodeToString(options),callback)
            } catch(t: Throwable) { disconnected(t) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { disconnected(IllegalStateException("引擎进程已断开，请重试")) }
        override fun onBindingDied(name: ComponentName?) { disconnected(IllegalStateException("引擎绑定失效，请重试")) }
        override fun onNullBinding(name: ComponentName?) { disconnected(IllegalStateException("无法绑定引擎服务")) }
    }
    private suspend fun service():IEngineService {
        mutex.withLock {
            if(!bound) {
                connected=CompletableDeferred(); ready=CompletableDeferred()
                bound=context.bindService(Intent(context,EngineService::class.java),connection,Context.BIND_AUTO_CREATE)
                check(bound) { "无法启动引擎服务" }
            }
        }
        return withTimeout(35000) { val result=connected.await(); ready.await(); result }
    }
    override suspend fun validate(history: PositionHistory):Result<LegalPosition> = runCatching {
        FenCodec.decode(history.initialFen).getOrThrow()
        val service=service(); val id=ids.incrementAndGet(); val result=rules.register(id)
        service.validate(id,BinderProtocol.json.encodeToString(history))
        withTimeout(7000) { result.await() }
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
        catch(t: Throwable) { if(enginePid>0 && enginePid!=Process.myPid()) Process.killProcess(enginePid); disconnected(t); throw t }
    }
    override suspend fun close() {
        runCatching { remote?.shutdown() }
        if(bound) runCatching { context.unbindService(connection) }
        bound=false; enginePid=0; disconnected(IllegalStateException("会话已停止"))
    }
}
