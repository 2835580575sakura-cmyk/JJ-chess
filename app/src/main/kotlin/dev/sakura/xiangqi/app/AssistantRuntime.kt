package dev.sakura.xiangqi.app
import android.content.*
import android.os.Handler
import android.os.Looper
import dev.sakura.xiangqi.engine.BinderEngineGateway
import dev.sakura.xiangqi.vision.PixelFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
class AssistantRuntime private constructor(private val context:Context,val config:SessionConfig) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val overlay=OverlayController(context) { event -> when(event) {
        OverlayAction.OPEN -> { uiVisible(true); context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        OverlayAction.PAUSE -> { userPaused=!userPaused; applyPause() }
        OverlayAction.STOP -> stop()
    } }
    val coordinator=SessionCoordinator(scope,BinderEngineGateway(context,config.options),overlay,overlay::screenSize)
    private val handler=Handler(Looper.getMainLooper())
    private val gate=OverlaySamplingGate(requestHide={ handler.post { overlay.hideForSample(); gateHidden() } })
    private fun gateHidden() { gate.hideForSample() }
    var preview:PixelFrame?=null; private set
    private var visible=true; private var userPaused=false; private var ending=false; private var captureVisible=true
    init {
        coordinator.start(config)
        CaptureBus.sampleFilter={ timestamp -> !visible && !userPaused && !ending && captureVisible && gate.acceptFrame(timestamp) }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.frames.collect { frame -> if(!visible && !userPaused) { preview=frame; coordinator.onFrame(frame) } } }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { CaptureBus.events.collect { event -> when(event) {
            is CaptureEvent.Stopped -> stop(false)
            is CaptureEvent.Error -> stop()
            is CaptureEvent.Resized -> { userPaused=true; applyPause(); overlay.status("画面尺寸改变，请重新开始并校准") }
            is CaptureEvent.Visibility -> { captureVisible=event.visible; applyPause() }
            else -> Unit
        } } }
        applyPause()
    }
    fun uiVisible(value:Boolean) { visible=value; overlay.setPanelVisible(!value); applyPause() }
    private fun applyPause() { if(ending)return; if(visible||userPaused||!captureVisible)coordinator.pause() else { gate.hideForSample(); coordinator.resume() } }
    fun pauseToggle() { userPaused=!userPaused; applyPause() }
    fun stop(stopCapture:Boolean=true) {
        if(ending)return; ending=true; CaptureBus.sampleFilter={false}; overlay.close()
        scope.launch { coordinator.stop().join(); overlay.close(); if(stopCapture)CaptureBus.stopRequest?.invoke(); if(current===this@AssistantRuntime)current=null; scope.cancel() }
    }
    companion object {
        var current:AssistantRuntime?=null; private set
        fun create(context:Context,config:SessionConfig):AssistantRuntime { check(current==null); return AssistantRuntime(context.applicationContext,config).also { current=it } }
    }
}
