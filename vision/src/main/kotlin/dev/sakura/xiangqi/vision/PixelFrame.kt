package dev.sakura.xiangqi.vision
import kotlin.math.*
data class PixelFrame(val width: Int,val height: Int,val argb: IntArray,val timestampNs: Long) {
    init { require(width in 1..8192 && height in 1..8192 && argb.size.toLong()==width.toLong()*height) }
    fun pixel(x: Int,y: Int)=argb[y.coerceIn(0,height-1)*width+x.coerceIn(0,width-1)]
    fun rgb(x: Float,y: Float):FloatArray {
        val x0=floor(x).toInt(); val y0=floor(y).toInt(); val dx=x-x0; val dy=y-y0
        val pixels=intArrayOf(pixel(x0,y0),pixel(x0+1,y0),pixel(x0,y0+1),pixel(x0+1,y0+1))
        return FloatArray(3) { channel ->
            val shift=16-channel*8
            val a=(pixels[0] ushr shift and 255)*(1-dx)+(pixels[1] ushr shift and 255)*dx
            val b=(pixels[2] ushr shift and 255)*(1-dx)+(pixels[3] ushr shift and 255)*dx
            a*(1-dy)+b*dy
        }
    }
    fun resized(w: Int,h: Int):PixelFrame {
        val output=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val c=rgb((x+.5f)*width/w-.5f,(y+.5f)*height/h-.5f)
            output[y*w+x]=(255 shl 24) or (c[0].roundToInt().coerceIn(0,255) shl 16) or (c[1].roundToInt().coerceIn(0,255) shl 8) or c[2].roundToInt().coerceIn(0,255)
        }
        return PixelFrame(w,h,output,timestampNs)
    }
    fun rotated180()=PixelFrame(width,height,argb.reversedArray(),timestampNs)
}
