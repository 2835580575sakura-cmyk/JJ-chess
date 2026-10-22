package dev.sakura.xiangqi.app
import android.content.*
import android.content.res.Configuration
import android.os.PowerManager
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import dev.sakura.xiangqi.engine.BinderEngineGateway
import dev.sakura.xiangqi.vision.PixelFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
class AssistantRuntime private constructor(private val context:Context,val config:SessionConfig):ComponentCallbacks2 {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val overlay=OverlayController(context) { event -> when(event) {
        OverlayAction.OPEN -> { uiVisible(true); context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        OverlayAction.PAUSE -> pauseToggle()
        OverlayAction.STOP -> stop()
    } }
    private val sessionStore=SessionStore(context.filesDir)
    val recoveryCandidate get()=sessionStore.loadCandidate()
    val coordinator=SessionCoordinator(scope,BinderEngineGateway(context,config.options),overlay,overlay::screenSize,
        restoreCalibration={ frame -> val (w,h)=overlay.screenSize(); SettingsStore(context).loadCalibration(frame.width,frame.height,w,h,config.orientation) },
        onConfirmed={ position -> runCatching { sessionStore.save(position) } },
        classifier=TemplateStore(context.filesDir).load()?:dev.sakura.xiangqi.vision.TemplateClassifier.fromResource())
    private val policy=ResourcePolicy()
    private val power=context.getSystemService(PowerManager::class.java)
    private var thermalBlocked=false
    private var thermalPaused=false
    private val thermalListener=PowerManager.OnThermalStatusChangedListener { level ->
        if(!config.pauseOnOverheat)return@OnThermalStatusChangedListener
        thermalBlocked=level>=PowerManager.THERMAL_STATUS_SEVERE
        if(policy.onThermalStatus(level)==ResourceAction.Pause) { userPaused=true; thermalPaused=true }
        applyPause()
    }
    private val handler=Handler(Looper.getMainLooper())
    private val gate=OverlaySamplingGate(requestHide={ handler.post { beginSample() } })
    private fun beginSample() { overlay.hideForSample(); gate.hideForSample(); overlay.beginSample(gate.generation) }
    var preview:PixelFrame?=null; private set
    private var visible=true; private var userPaused=false; private var ending=false; private var captureVisible=true
    private var appliedPaused:Boolean?=null
    private var appliedPauseMessage:String?=null
    init {
        context.registerComponentCallbacks(this)
        power.addThermalStatusListener(context.mainExecutor,thermalListener)
        thermalBlocked=config.pauseOnOverheat && power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        if(thermalBlocked) { userPaused=true; thermalPaused=true }
        coordinator.start(config)
        CaptureBus.sampleGeneration={ gate.acceptedGeneration }
        CaptureBus.sampleFilter={ timestamp -> !visible && !userPaused && !thermalBlocked && !ending && captureVisible && gate.acceptFrame(timestamp) }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.frames.collect { frame -> if(!visible && !userPaused) { preview=frame; coordinator.onFrame(frame) } } }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.events.collect { event -> when(event) {
            is CaptureEvent.Stopped -> stop(false)
            is CaptureEvent.Error -> stop()
            is CaptureEvent.Resized -> coordinatesChanged()
            is CaptureEvent.Visibility -> { captureVisible=event.visible; applyPause() }
            else -> Unit
        } } }
        scope.launch { coordinator.state.collect { state -> if(state.phase==SessionPhase.ERROR) {
            if(policy.onEngineFailure(state.failureReason?:dev.sakura.xiangqi.core.EngineFailureReason.GENERIC) is ResourceAction.Retry)memoryRetry(state.failureReason==dev.sakura.xiangqi.core.EngineFailureReason.MEMORY) else { lastMessage=state.message; Toast.makeText(context,state.message,Toast.LENGTH_LONG).show(); stop() }
        } } }
        applyPause()
    }
    fun uiVisible(value:Boolean) { visible=value; overlay.setPanelVisible(!value); applyPause() }
    private fun applyPause() {
        if(ending)return
        val message=when {
            thermalBlocked -> "手机过热，已暂停；降温后手动继续"
            thermalPaused -> "手机已降温，仍暂停；请手动继续并校验当前局面"
            userPaused -> "已手动暂停；继续前请校验当前局面"
            visible -> "进入助手后已暂停，请校验当前局面再返回 JJ"
            !captureVisible -> "录屏内容不可见，已暂停；恢复后请校验棋盘及行棋方"
            else -> null
        }
        val paused=message!=null
        if(appliedPaused==paused && appliedPauseMessage==message)return
        appliedPaused=paused; appliedPauseMessage=message
        if(paused)coordinator.pause(checkNotNull(message)) else { beginSample(); coordinator.resume() }
    }
    fun pauseToggle() {
        if(thermalBlocked) { userPaused=true; thermalPaused=true }
        else { userPaused=!userPaused; thermalPaused=false }
        applyPause()
    }
    fun stop(stopCapture:Boolean=true) {
        if(ending)return; ending=true; context.unregisterComponentCallbacks(this); power.removeThermalStatusListener(thermalListener); CaptureBus.sampleFilter={false}; overlay.close()
        if(stopCapture)CaptureBus.stopRequest?.invoke()
        scope.launch { coordinator.stop().join(); overlay.close(); if(current===this@AssistantRuntime)current=null; scope.cancel() }
    }
    override fun onConfigurationChanged(configuration:Configuration) { coordinatesChanged() }
    private fun coordinatesChanged() { SettingsStore(context).clearCalibration(); lastMessage="画面尺寸或屏幕配置改变，已停止；请重新开始并校准"; coordinator.invalidateCoordinates(); stop() }
    override fun onLowMemory() { memoryRetry() }
    override fun onTrimMemory(level:Int) { if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level!=ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)memoryRetry() }
    private fun memoryRetry(confirmedMemory:Boolean=true) {
        if(ending)return
        val retry=policy.onMemoryPressure() as ResourceAction.Retry
        SettingsStore(context).save(config.copy(options=retry.options))
        lastMessage=if(confirmedMemory)"内存不足，已停止。下次使用 2 线程 / 128 MiB，请手动重试。" else "引擎意外退出，原因未确定。下次用 2 线程 / 128 MiB 保守配置手动重试。"
        Toast.makeText(context,lastMessage,Toast.LENGTH_LONG).show(); stop()
    }
    companion object {
        var lastMessage:String?=null; private set
        var current:AssistantRuntime?=null; private set
        fun create(context:Context,config:SessionConfig):AssistantRuntime { check(current==null); lastMessage=null; return AssistantRuntime(context.applicationContext,config).also { current=it } }
    }
}
