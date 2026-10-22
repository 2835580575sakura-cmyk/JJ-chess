package dev.sakura.xiangqi.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.sakura.xiangqi.core.FloatRect
import dev.sakura.xiangqi.core.GridCalibration
import dev.sakura.xiangqi.vision.PixelFrame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE)
class CalibrationViewTest {
    private class Parent(context:Context):FrameLayout(context) {
        val ownership=mutableListOf<Boolean>()
        override fun requestDisallowInterceptTouchEvent(disallowIntercept:Boolean) {
            ownership.add(disallowIntercept); super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
    @Test fun verticalCornerDragKeepsTouchOwnershipUntilEndOrCancellation() {
        val context=RuntimeEnvironment.getApplication()
        val frame=PixelFrame(300,400,IntArray(300*400),1)
        val initial=FloatRect(40f,100f,240f,370f)
        val view=CalibrationView(context,frame,GridCalibration(initial,initial))
        val parent=Parent(context).apply { addView(view,FrameLayout.LayoutParams(300,400)) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY)); parent.layout(0,0,300,400)
        val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        fun touch(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(0,1,action,x,y,0)
            try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN,40f,100f)
        assertEquals(listOf(true),parent.ownership)
        touch(MotionEvent.ACTION_MOVE,40f,160f)
        assertEquals(160f,view.rect.top,0f)
        assertEquals(initial.right,view.rect.right,0f); assertEquals(initial.bottom,view.rect.bottom,0f)
        touch(MotionEvent.ACTION_UP,40f,160f)
        assertEquals(listOf(true,false),parent.ownership)
        touch(MotionEvent.ACTION_DOWN,40f,160f)
        touch(MotionEvent.ACTION_CANCEL,40f,180f)
        assertEquals(listOf(true,false,true,false),parent.ownership)
        assertEquals(160f,view.rect.top,0f)
    }
}
