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
        OverlayAction.PAUSE -> { userPaused=!userPaused; applyPause() }
        OverlayAction.STOP -> stop()
    } }
    private val sessionStore=SessionStore(context.filesDir)
    val recoveryCandidate get()=sessionStore.loadCandidate()
    val coordinator=SessionCoordinator(scope,BinderEngineGateway(context,config.options),overlay,overlay::screenSize,
        restoreCalibration={ frame -> val (w,h)=overlay.screenSize(); SettingsStore(context).loadCalibration(frame.width,frame.height,w,h,config.orientation) },
        onConfirmed={ position -> runCatching { sessionStore.save(position) } })
    private val policy=ResourcePolicy()
    private val power=context.getSystemService(PowerManager::class.java)
    private var thermalBlocked=false
    private val thermalListener=PowerManager.OnThermalStatusChangedListener { level ->
        thermalBlocked=level>=PowerManager.THERMAL_STATUS_SEVERE
        if(policy.onThermalStatus(level)==ResourceAction.Pause) { userPaused=true; applyPause(); overlay.status("手机过热，已暂停；降温后手动继续") }
    }
    private val handler=Handler(Looper.getMainLooper())
    private val gate=OverlaySamplingGate(requestHide={ handler.post { overlay.hideForSample(); gateHidden() } })
    private fun gateHidden() { gate.hideForSample() }
    var preview:PixelFrame?=null; private set
    private var visible=true; private var userPaused=false; private var ending=false; private var captureVisible=true
    init {
        context.registerComponentCallbacks(this)
        power.addThermalStatusListener(context.mainExecutor,thermalListener)
        thermalBlocked=power.currentThermalStatus>=PowerManager.THERMAL_STATUS_SEVERE
        if(thermalBlocked)userPaused=true
        coordinator.start(config)
        CaptureBus.sampleFilter={ timestamp -> !visible && !userPaused && !thermalBlocked && !ending && captureVisible && gate.acceptFrame(timestamp) }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.frames.collect { frame -> if(!visible && !userPaused) { preview=frame; coordinator.onFrame(frame) } } }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.events.collect { event -> when(event) {
            is CaptureEvent.Stopped -> stop(false)
            is CaptureEvent.Error -> stop()
            is CaptureEvent.Resized -> { userPaused=true; applyPause(); overlay.status("画面尺寸改变，请重新开始并校准") }
            is CaptureEvent.Visibility -> { captureVisible=event.visible; applyPause() }
            else -> Unit
        } } }
        scope.launch { coordinator.state.collect { state -> if(state.phase==SessionPhase.ERROR) {
            if(state.message.contains("内存"))memoryRetry() else { lastMessage=state.message; Toast.makeText(context,state.message,Toast.LENGTH_LONG).show(); stop() }
        } } }
        applyPause()
    }
    fun uiVisible(value:Boolean) { visible=value; overlay.setPanelVisible(!value); applyPause() }
    private fun applyPause() { if(ending)return; if(visible||userPaused||thermalBlocked||!captureVisible)coordinator.pause() else { gate.hideForSample(); coordinator.resume() } }
    fun pauseToggle() { userPaused=!userPaused; applyPause() }
    fun stop(stopCapture:Boolean=true) {
        if(ending)return; ending=true; context.unregisterComponentCallbacks(this); power.removeThermalStatusListener(thermalListener); CaptureBus.sampleFilter={false}; overlay.close()
        scope.launch { coordinator.stop().join(); overlay.close(); if(stopCapture)CaptureBus.stopRequest?.invoke(); if(current===this@AssistantRuntime)current=null; scope.cancel() }
    }
    override fun onConfigurationChanged(configuration:Configuration) { userPaused=true; applyPause(); overlay.status("屏幕配置改变，请停止后重新开始并校准") }
    override fun onLowMemory() { memoryRetry() }
    override fun onTrimMemory(level:Int) { if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level!=ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)memoryRetry() }
    private fun memoryRetry() {
        if(ending)return
        val retry=policy.onMemoryPressure() as ResourceAction.Retry
        SettingsStore(context).save(config.copy(options=retry.options))
        Toast.makeText(context,"内存不足，已停止。下次开始使用 2 线程 / 128 MiB，请手动重试。",Toast.LENGTH_LONG).show(); stop()
    }
    companion object {
        var lastMessage:String?=null; private set
        var current:AssistantRuntime?=null; private set
        fun create(context:Context,config:SessionConfig):AssistantRuntime { check(current==null); lastMessage=null; return AssistantRuntime(context.applicationContext,config).also { current=it } }
    }
}
