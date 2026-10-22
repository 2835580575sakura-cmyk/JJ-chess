package dev.sakura.xiangqi.app
import android.media.Image
import dev.sakura.xiangqi.vision.PixelFrame
import java.nio.ByteBuffer
import kotlin.math.min
object FrameReader {
    fun readLatest(image:Image):PixelFrame {
        val plane=image.planes.first()
        return fromRgba(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride,image.timestamp)
    }
    fun fromRgba(buffer:ByteBuffer,width:Int,height:Int,rowStride:Int,pixelStride:Int,timestampNs:Long,targetWidth:Int=576):PixelFrame {
        require(width>0 && height>0 && pixelStride>=3 && rowStride>=width*pixelStride && targetWidth>0)
        val w=min(width,targetWidth); val h=(height.toLong()*w/width).toInt().coerceAtLeast(1)
        val pixels=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val offset=(y.toLong()*height/h).toInt()*rowStride+(x.toLong()*width/w).toInt()*pixelStride
            require(offset+2<buffer.limit()) { "录屏缓冲区不完整" }
            val r=buffer.get(offset).toInt() and 255; val g=buffer.get(offset+1).toInt() and 255; val b=buffer.get(offset+2).toInt() and 255
            pixels[y*w+x]=(255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PixelFrame(w,h,pixels,timestampNs)
    }
}
