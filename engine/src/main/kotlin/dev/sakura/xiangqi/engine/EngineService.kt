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

class EngineService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var actor: EngineActor?=null
    @Volatile private var callback: IEngineCallback?=null
    private val initialization=Any()
    private fun send(text: String) { runCatching { callback?.onEvent(text) } }
    private fun message(kind: String,block: JsonObjectBuilder.()->Unit={})=buildJsonObject { put("kind",kind); block() }.toString()
    private fun error(id: Long?,text: String) { send(message("error") { id?.let { put("requestId",it) }; put("message",text) }) }
    private val binder=object:IEngineService.Stub() {
        override fun initialize(optionsJson: String,receiver: IEngineCallback) {
            callback=receiver
            scope.launch {
                val watchdog=scope.launch { delay(30000); error(null,"引擎初始化超时，请重试"); Process.killProcess(Process.myPid()) }
                try {
                    synchronized(initialization) {
                        val options=BinderProtocol.json.decodeFromString<EngineOptions>(optionsJson)
                        val current=actor
                        if(current!=null) current.updateOptions(options).get(5,TimeUnit.SECONDS) else {
                            val file=NetworkInstaller(filesDir,NetworkInstaller.NETWORK_SHA256) { assets.open("pikafish.nnue") }.ensureInstalled().getOrThrow()
                            actor=EngineActor(NativeEngine(file,options)) { send(BinderProtocol.encodeEvent(it)) }
                        }
                    }
                    send(message("ready") { put("pid",Process.myPid()) })
                } catch(t: Throwable) { error(null,t.message?:"引擎初始化失败") }
                finally { watchdog.cancel() }
            }
        }
        override fun validate(requestId: Long,historyJson: String) { scope.launch {
            try {
                require(historyJson.length<=1048576)
                val history=BinderProtocol.json.decodeFromString<PositionHistory>(historyJson)
                val result=checkNotNull(actor).validate(history).get(5,TimeUnit.SECONDS).getOrThrow()
                send(message("rules") { put("requestId",requestId); put("value",BinderProtocol.json.encodeToJsonElement(result)) })
            } catch(t: Throwable) { error(requestId,t.cause?.message?:t.message?:"局面无效"); if(t is TimeoutException) Process.killProcess(Process.myPid()) }
        } }
        override fun analyze(requestJson: String) { scope.launch {
            val request=runCatching { BinderProtocol.decodeRequest(requestJson) }.getOrElse { error(null,"分析请求无效"); return@launch }
            try { checkNotNull(actor).submit(request).get(5,TimeUnit.SECONDS) }
            catch(t: Throwable) { send(BinderProtocol.encodeEvent(EngineEvent.Failure(request.key,t.cause?.message?:t.message?:"搜索失败"))); if(t is TimeoutException) Process.killProcess(Process.myPid()) }
        } }
        override fun cancel(searchId: Long) { scope.launch {
            try { actor?.cancel(searchId)?.get(5,TimeUnit.SECONDS); send(message("cancelled") { put("searchId",searchId) }) }
            catch(t: Throwable) { error(null,"引擎停止超时，请重试"); Process.killProcess(Process.myPid()) }
        } }
        override fun shutdown() { actor?.close(); actor=null; stopSelf() }
    }
    override fun onBind(intent: Intent?): IBinder=binder
    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level!=android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            error(null,"内存不足，请使用较低配置重试"); actor?.close(); actor=null; stopSelf()
        }
    }
    override fun onLowMemory() { super.onLowMemory(); error(null,"内存不足，请使用较低配置重试"); actor?.close(); actor=null; stopSelf() }
    override fun onDestroy() { actor?.close(); actor=null; scope.cancel(); super.onDestroy() }
}
