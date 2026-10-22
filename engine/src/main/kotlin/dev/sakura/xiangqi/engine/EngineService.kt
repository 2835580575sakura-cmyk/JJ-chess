package dev.sakura.xiangqi.engine
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class EngineService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var actor:EngineActor?=null
    @Volatile private var callback:IEngineCallback?=null
    @Volatile private var initializing=false
    @Volatile private var destroyed=false
    private val initialization=Any()
    private val ingress=EngineCommandIngress({actor}) { command -> scope.launch { command() } }
    private fun send(text:String) { if(!destroyed)runCatching { callback?.onEvent(text) } }
    private fun message(kind:String,block:JsonObjectBuilder.()->Unit={})=buildJsonObject { put("kind",kind); block() }.toString()
    private fun error(id:Long?,text:String,reason:EngineFailureReason=EngineFailureReason.GENERIC) {
        send(message("error") { id?.let { put("requestId",it) }; put("message",text); put("reason",reason.name) })
    }
    private fun shutdown() {
        ingress.invalidate()
        val terminateInitialization=initializing
        actor?.close(); actor=null; stopSelf()
        // Initialization may be inside a blocking C++ allocator. Only our isolated process is terminated.
        if(terminateInitialization)Process.killProcess(Process.myPid())
    }
    private val binder=object:IEngineService.Stub() {
        override fun initialize(optionsJson:String,receiver:IEngineCallback) {
            val ticket=ingress.newSession(); callback=receiver
            scope.launch {
                val watchdog=scope.launch { delay(30000); if(ingress.isCurrent(ticket)) { error(null,"引擎初始化超时，请重试"); Process.killProcess(Process.myPid()) } }
                try {
                    synchronized(initialization) {
                        val activated=ingress.ifCurrent(ticket) { if(!destroyed) { initializing=true; true } else false }?:false
                        if(!activated)return@synchronized
                        try {
                            val options=BinderProtocol.json.decodeFromString<EngineOptions>(optionsJson)
                            val current=actor
                            if(current!=null) { current.close().get(5,TimeUnit.SECONDS); actor=null }
                            run {
                                val file=NetworkInstaller(filesDir,NetworkInstaller.NETWORK_SHA256) { assets.open("pikafish.nnue") }.ensureInstalled().getOrThrow()
                                val candidate=EngineActor(NativeEngine(file,options)) { event -> if(ingress.isCurrent(ticket))send(BinderProtocol.encodeEvent(event)) }
                                val published=ingress.ifCurrent(ticket) { if(!destroyed) { actor=candidate; true } else false }?:false
                                if(!published)candidate.close()
                            }
                        } finally { initializing=false }
                    }
                    if(ingress.isCurrent(ticket)&&!destroyed)send(message("ready") { put("pid",Process.myPid()) })
                } catch(t:Throwable) { if(ingress.isCurrent(ticket))error(null,t.message?:"引擎初始化失败",EngineFailures.reason(t)) }
                finally { watchdog.cancel() }
            }
        }
        override fun validate(requestId:Long,historyJson:String) {
            val ticket=ingress.currentGeneration()
            scope.launch {
                try {
                    require(historyJson.length<=1048576)
                    val history=BinderProtocol.json.decodeFromString<PositionHistory>(historyJson)
                    val current=ingress.ifCurrent(ticket) { actor }?:return@launch
                    val result=current.validate(history).get(5,TimeUnit.SECONDS).getOrThrow()
                    if(ingress.isCurrent(ticket))send(message("rules") { put("requestId",requestId); put("value",BinderProtocol.json.encodeToJsonElement(result)) })
                } catch(t:Throwable) {
                    if(ingress.isCurrent(ticket))error(requestId,t.cause?.message?:t.message?:"局面无效",EngineFailures.reason(t))
                    if(t is TimeoutException)Process.killProcess(Process.myPid())
                }
            }
        }
        override fun analyze(requestJson:String) {
            val ticket=ingress.currentGeneration()
            val request=runCatching { BinderProtocol.decodeRequest(requestJson) }.getOrElse { error(null,"分析请求无效"); return }
            // Registration happens synchronously at Binder ingress, not in a later IO coroutine.
            val future=ingress.analyze(request,ticket)
            scope.launch {
                try { future.get(5,TimeUnit.SECONDS) }
                catch(t:Throwable) {
                    if(ingress.isCurrent(ticket))send(BinderProtocol.encodeEvent(EngineEvent.Failure(request.key,t.cause?.message?:t.message?:"搜索失败",EngineFailures.reason(t))))
                    if(t is TimeoutException)Process.killProcess(Process.myPid())
                }
            }
        }
        override fun cancel(searchId:Long) {
            val ticket=ingress.currentGeneration(); val future=ingress.cancel(searchId,ticket)
            scope.launch {
                try { future.get(5,TimeUnit.SECONDS); if(ingress.isCurrent(ticket))send(message("cancelled") { put("searchId",searchId) }) }
                catch(t:Throwable) { if(ingress.isCurrent(ticket)) { error(null,"引擎停止超时，请重试"); Process.killProcess(Process.myPid()) } }
            }
        }
        override fun shutdown() { this@EngineService.shutdown() }
    }
    override fun onBind(intent:Intent?):IBinder=binder
    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level!=android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            error(null,"内存不足，请使用较低配置重试",EngineFailureReason.MEMORY); shutdown()
        }
    }
    override fun onLowMemory() { super.onLowMemory(); error(null,"内存不足，请使用较低配置重试",EngineFailureReason.MEMORY); shutdown() }
    override fun onDestroy() { destroyed=true; ingress.invalidate(); val inFlight=initializing; actor?.close(); actor=null; scope.cancel(); super.onDestroy(); if(inFlight)Process.killProcess(Process.myPid()) }
}
