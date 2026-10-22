package dev.sakura.xiangqi.app
import android.app.Activity
import android.content.Intent
import dev.sakura.xiangqi.vision.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE)
class ProjectionLifecycleTest {
    class Platform:ProjectionPlatform {
        var created=0; var displayReleased=0; var readerReleased=0; var projectionStopped=0; val order=mutableListOf<String>()
        var onStop:(()->Unit)?=null
        override fun startForeground() { order.add("foreground") }
        override fun open(resultCode:Int,data:Intent):ProjectionSession=object:ProjectionSession {
            override fun registerStop(callback:()->Unit) { order.add("callback"); onStop=callback }
            override fun createDisplay() { order.add("display"); created++ }
            override fun releaseDisplay() { displayReleased++ }
            override fun releaseReader() { readerReleased++ }
            override fun stopProjection() { projectionStopped++; onStop?.invoke() }
        }
    }
    @Test fun doubleStartCreatesOneSession() {
        val p=Platform(); val c=ProjectionController(p){}
        c.begin(Activity.RESULT_OK,Intent()); c.begin(Activity.RESULT_OK,Intent()); assertEquals(1,p.created); c.stop()
    }
    @Test fun cancelledConsentStartsNothing() {
        val p=Platform(); val c=ProjectionController(p){}
        c.begin(Activity.RESULT_CANCELED,Intent()); assertEquals(0,p.created); assertTrue(p.order.isEmpty())
    }
    @Test fun callbackRegisteredBeforeDisplay() {
        val p=Platform(); val c=ProjectionController(p){}; c.begin(Activity.RESULT_OK,Intent())
        assertEquals(listOf("foreground","callback","display"),p.order); c.stop()
    }
    @Test fun stopIsIdempotent() {
        val p=Platform(); val c=ProjectionController(p){}; c.begin(Activity.RESULT_OK,Intent()); c.stop(); c.stop()
        assertEquals(1,p.displayReleased); assertEquals(1,p.readerReleased); assertEquals(1,p.projectionStopped)
    }
    @Test fun rowPaddingDoesNotShiftPixels() {
        val buffer=ByteBuffer.allocate(24)
        for((i,rgb) in listOf(0 to intArrayOf(255,0,0),4 to intArrayOf(0,255,0),12 to intArrayOf(0,0,255),16 to intArrayOf(255,255,255))) {
            buffer.put(i,rgb[0].toByte()); buffer.put(i+1,rgb[1].toByte()); buffer.put(i+2,rgb[2].toByte()); buffer.put(i+3,255.toByte())
        }
        val frame=FrameReader.fromRgba(buffer,2,2,12,4,123,2)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt()),frame.argb)
        assertEquals(123L,frame.timestampNs)
    }
    @Test fun resizeInvalidatesCalibration() {
        val events=mutableListOf<CaptureEvent>(); val c=ProjectionController(Platform()){events.add(it)}
        c.begin(Activity.RESULT_OK,Intent()); c.onResized(500,900)
        assertEquals(CaptureEvent.Resized(500,900),events.last())
        c.stop(); events.clear(); c.onResized(600,1000); assertTrue(events.isEmpty())
    }
}
