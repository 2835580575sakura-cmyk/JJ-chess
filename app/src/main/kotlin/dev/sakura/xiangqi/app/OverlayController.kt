package dev.sakura.xiangqi.app
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import dev.sakura.xiangqi.core.*
import kotlin.math.*
enum class OverlayAction { OPEN, PAUSE, STOP }
class OverlayController(private val context:Context,private val action:(OverlayAction)->Unit):HintOverlay {
    private val wm=context.getSystemService(WindowManager::class.java)
    private val store=HintStore()
    private var calibration:GridCalibration?=null
    private val arrow=ArrowView(context)
    private val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(6)); setBackgroundColor(0xee173042.toInt()) }
    private val text=TextView(context).apply { setTextColor(Color.WHITE); textSize=14f; text="等待棋盘"; maxLines=5 }
    private val arrowParams=params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,true)
    private val panelParams=params(dp(230),WindowManager.LayoutParams.WRAP_CONTENT,false).apply { x=dp(8); y=dp(40) }
    private var attached=false
    private var panelVisible=true
    init {
        panel.addView(text)
        val buttons=LinearLayout(context)
        for((label,event) in listOf("校验" to OverlayAction.OPEN,"暂停" to OverlayAction.PAUSE,"停止" to OverlayAction.STOP)) buttons.addView(Button(context).apply { this.text=label; textSize=12f; setPadding(0,0,0,0); setOnClickListener { action(event) } },LinearLayout.LayoutParams(0,dp(40),1f))
        panel.addView(buttons)
        var initialX=0; var initialY=0; var downX=0f; var downY=0f
        text.setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX=panelParams.x; initialY=panelParams.y; downX=event.rawX; downY=event.rawY; true }
                MotionEvent.ACTION_MOVE -> { panelParams.x=initialX+(event.rawX-downX).toInt(); panelParams.y=initialY+(event.rawY-downY).toInt(); positionPanel(); true }
                MotionEvent.ACTION_UP -> { if(abs(event.rawX-downX)+abs(event.rawY-downY)<dp(8))action(OverlayAction.OPEN); true }
                else -> false
            }
        }
    }
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).roundToInt()
    private fun params(w:Int,h:Int,passThrough:Boolean)=WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(passThrough)WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.LEFT
        if(Build.VERSION.SDK_INT>=30) { setFitInsetsTypes(0); layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        if(passThrough) alpha=OverlayPolicy.arrowAlpha(if(Build.VERSION.SDK_INT>=31)context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else .8f)
    }
    @Suppress("DEPRECATION") fun screenSize():Pair<Int,Int> { val d=DisplayMetrics(); wm.defaultDisplay.getRealMetrics(d); return d.widthPixels to d.heightPixels }
    private fun ensure():Boolean {
        if(!Settings.canDrawOverlays(context)) { close(); return false }
        if(attached)return true
        return runCatching { arrow.visibility=View.GONE; wm.addView(arrow,arrowParams); wm.addView(panel,panelParams); attached=true; positionPanel(); true }.getOrElse { runCatching { wm.removeViewImmediate(arrow) }; runCatching { wm.removeViewImmediate(panel) }; false }
    }
    private fun positionPanel() {
        if(!attached)return
        val (w,h)=screenSize(); val height=panel.height.takeIf { it>0 }?:dp(150)
        val y=OverlayPolicy.panelY(h,calibration?.displayRect,height,panelParams.y)
        panel.visibility=if(panelVisible && y!=null)View.VISIBLE else View.GONE
        panelParams.x=panelParams.x.coerceIn(0,(w-dp(230)).coerceAtLeast(0)); if(y!=null)panelParams.y=y
        runCatching { wm.updateViewLayout(panel,panelParams) }
    }
    override fun setCalibration(calibration:GridCalibration) { require(calibration.isValid()); this.calibration=calibration; store.transformChanged(calibration.transformRevision); arrow.calibration=calibration; if(store.current==null)arrow.visibility=View.GONE; positionPanel() }
    override fun show(key:AnalysisKey,move:UciMove,text:String):Boolean {
        if(calibration?.transformRevision!=key.transformRevision || !ensure())return false
        store.put(MoveHint(key,move,text)); arrow.hint=store.current; arrow.visibility=View.VISIBLE; arrow.invalidate(); this.text.text=text; return true
    }
    override fun clear() { store.clear(); arrow.hint=null; arrow.visibility=View.GONE; arrow.invalidate() }
    override fun hideForSample() { arrow.visibility=View.GONE }
    override fun restore(key:AnalysisKey):Boolean { val h=store.current?:return false; if(h.key!=key || !attached)return false; arrow.hint=h; arrow.visibility=View.VISIBLE; arrow.invalidate(); return true }
    override fun status(text:String) { this.text.text=text; ensure(); positionPanel() }
    fun setPanelVisible(visible:Boolean) { panelVisible=visible; positionPanel() }
    fun close() { clear(); if(attached) { runCatching { wm.removeViewImmediate(arrow) }; runCatching { wm.removeViewImmediate(panel) }; attached=false } }
}
