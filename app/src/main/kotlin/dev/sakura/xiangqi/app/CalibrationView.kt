package dev.sakura.xiangqi.app
import android.content.Context
import android.graphics.*
import android.view.*
import dev.sakura.xiangqi.core.*
import dev.sakura.xiangqi.vision.PixelFrame
import kotlin.math.*
class CalibrationView(context:Context,frame:PixelFrame,calibration:GridCalibration):View(context) {
    private val bitmap=Bitmap.createBitmap(frame.argb,frame.width,frame.height,Bitmap.Config.ARGB_8888)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private var scale=1f; private var offsetX=0f; private var offsetY=0f; private var selected=0
    var rect=calibration.captureRect; private set
    override fun onDraw(canvas:Canvas) {
        canvas.drawColor(Color.DKGRAY); scale=min(width/bitmap.width.toFloat(),height/bitmap.height.toFloat()); offsetX=(width-bitmap.width*scale)/2; offsetY=(height-bitmap.height*scale)/2
        canvas.save(); canvas.translate(offsetX,offsetY); canvas.scale(scale,scale); canvas.drawBitmap(bitmap,0f,0f,null)
        paint.color=Color.CYAN; paint.strokeWidth=2/scale
        for(i in 0..8)canvas.drawLine(rect.left+rect.width*i/8,rect.top,rect.left+rect.width*i/8,rect.bottom,paint)
        for(i in 0..9)canvas.drawLine(rect.left,rect.top+rect.height*i/9,rect.right,rect.top+rect.height*i/9,paint)
        canvas.drawCircle(rect.left,rect.top,12/scale,paint); canvas.drawCircle(rect.right,rect.bottom,12/scale,paint); canvas.restore()
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        val x=((e.x-offsetX)/scale).coerceIn(1f,bitmap.width-2f); val y=((e.y-offsetY)/scale).coerceIn(1f,bitmap.height-2f)
        if(e.actionMasked==MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            selected=if(hypot(x-rect.left,y-rect.top)<hypot(x-rect.right,y-rect.bottom))0 else 1
        }
        if(e.actionMasked==MotionEvent.ACTION_MOVE || e.actionMasked==MotionEvent.ACTION_UP) {
            rect=if(selected==0)FloatRect(min(x,rect.right-80),min(y,rect.bottom-90),rect.right,rect.bottom) else FloatRect(rect.left,rect.top,max(x,rect.left+80),max(y,rect.top+90))
            invalidate()
        }
        if(e.actionMasked==MotionEvent.ACTION_UP || e.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
}
