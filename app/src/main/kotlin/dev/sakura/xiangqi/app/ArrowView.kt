package dev.sakura.xiangqi.app
import android.content.Context
import android.graphics.*
import android.view.View
import dev.sakura.xiangqi.core.*
import kotlin.math.*
class ArrowView(context:Context):View(context) {
    var calibration:GridCalibration?=null
    var hint:MoveHint?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cal=calibration?:return; val h=hint?:return
        if(h.key.transformRevision!=cal.transformRevision)return
        val a=cal.center(h.move.from,CoordinateSpace.DISPLAY); val b=cal.center(h.move.to,CoordinateSpace.DISPLAY)
        val step=min(cal.displayRect.width/8,cal.displayRect.height/9)
        paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND
        paint.color=Color.WHITE; paint.strokeWidth=step*.11f; canvas.drawLine(a.x,a.y,b.x,b.y,paint)
        paint.color=Color.rgb(0,155,255); paint.strokeWidth=step*.065f; canvas.drawLine(a.x,a.y,b.x,b.y,paint)
        canvas.drawCircle(a.x,a.y,step*.18f,paint); canvas.drawCircle(b.x,b.y,step*.22f,paint)
        val angle=atan2(b.y-a.y,b.x-a.x); val size=step*.28f
        val path=Path().apply { moveTo(b.x,b.y); lineTo(b.x-cos(angle-.65f)*size,b.y-sin(angle-.65f)*size); lineTo(b.x-cos(angle+.65f)*size,b.y-sin(angle+.65f)*size); close() }
        paint.style=Paint.Style.FILL; canvas.drawPath(path,paint)
    }
}
