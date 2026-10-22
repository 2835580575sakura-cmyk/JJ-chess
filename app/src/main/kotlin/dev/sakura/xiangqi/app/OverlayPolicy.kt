package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import kotlin.math.*
data class MoveHint(val key:AnalysisKey,val move:UciMove,val text:String)
class HintStore {
    var current:MoveHint?=null; private set
    private var sampling=false
    private var generation=0L
    val visible:MoveHint? get()=current.takeUnless { sampling }
    fun beginSample(generation:Long) { if(generation>=this.generation) { this.generation=generation; sampling=true } }
    fun suppress() { sampling=true }
    fun completeSample(generation:Long):Boolean { if(generation!=this.generation)return false; sampling=false; return true }
    fun put(hint:MoveHint) { current=hint }
    fun clear() { current=null }
    fun transformChanged(revision:Long) { if(current?.key?.transformRevision!=revision)clear() }
}
object OverlayPolicy {
    fun arrowAlpha(systemLimit:Float)=min(.6f,if(systemLimit.isFinite())systemLimit.coerceIn(0f,1f) else 0f)
    fun panelY(screenHeight:Int,board:FloatRect?,height:Int,requested:Int):Int? {
        if(board==null)return requested.coerceIn(0,(screenHeight-height).coerceAtLeast(0))
        val margin=board.height/9*.55f
        val maxAbove=(board.top-margin-height-8).toInt()
        if(maxAbove>=0)return requested.coerceIn(0,maxAbove)
        val minBelow=(board.bottom+board.height/9*1.8f).toInt()
        return minBelow.takeIf { it+height<=screenHeight }
    }
}
interface HintOverlay {
    fun setCalibration(calibration:GridCalibration)
    fun show(key:AnalysisKey,move:UciMove,text:String):Boolean
    fun clear()
    fun hideForSample()
    fun restore(key:AnalysisKey):Boolean
    fun beginSample(generation:Long) { hideForSample() }
    fun completeSample(generation:Long,key:AnalysisKey?) { if(key!=null)restore(key) }
    fun status(text:String)
}
