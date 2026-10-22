package dev.sakura.xiangqi.app
import android.app.Activity
import android.content.Intent
interface ProjectionPlatform { fun startForeground(); fun open(resultCode: Int,data: Intent):ProjectionSession }
interface ProjectionSession { fun registerStop(callback:()->Unit); fun createDisplay(); fun releaseDisplay(); fun releaseReader(); fun stopProjection() }
sealed class CaptureEvent {
    data object Started:CaptureEvent()
    data object Stopped:CaptureEvent()
    data class Resized(val width:Int,val height:Int):CaptureEvent()
    data class Visibility(val visible:Boolean):CaptureEvent()
    data class Error(val message:String):CaptureEvent()
}
class ProjectionController(private val platform:ProjectionPlatform,private val emit:(CaptureEvent)->Unit) {
    enum class State { IDLE, STARTING, CAPTURING, STOPPED }
    @Volatile var state=State.IDLE; private set
    private var session:ProjectionSession?=null
    @Synchronized fun begin(resultCode:Int,consentData:Intent) {
        if(state==State.STARTING || state==State.CAPTURING || resultCode!=Activity.RESULT_OK)return
        state=State.STARTING
        try {
            platform.startForeground(); session=platform.open(resultCode,consentData)
            session!!.registerStop { stop() }; session!!.createDisplay()
            state=State.CAPTURING; emit(CaptureEvent.Started)
        } catch(t:Throwable) { emit(CaptureEvent.Error(t.message?:"无法启动录屏")); stop() }
    }
    @Synchronized fun onResized(width:Int,height:Int) { if(state==State.CAPTURING && width>0 && height>0)emit(CaptureEvent.Resized(width,height)) }
    @Synchronized fun stop() {
        if(state==State.STOPPED)return
        state=State.STOPPED; val old=session; session=null
        old?.let { runCatching { it.releaseDisplay() }; runCatching { it.releaseReader() }; runCatching { it.stopProjection() } }
        emit(CaptureEvent.Stopped)
    }
}
