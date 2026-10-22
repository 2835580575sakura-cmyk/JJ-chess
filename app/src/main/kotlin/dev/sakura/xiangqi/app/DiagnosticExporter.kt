package dev.sakura.xiangqi.app
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.sakura.xiangqi.core.FloatRect
import dev.sakura.xiangqi.vision.PixelFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.*
import kotlin.math.*
@Serializable enum class DiagnosticCode { RECOGNITION_UNCERTAIN,NEEDS_CONFIRMATION,ENGINE_ERROR,CAPTURE_STOPPED,THERMAL_PAUSE,MEMORY_RETRY }
@Serializable data class DiagnosticEvent(val elapsedMs:Long,val code:DiagnosticCode)
class DiagnosticExporter(private val context:Context) {
    var lastFile:File?=null; private set
    fun export(boardCrop:PixelFrame,log:List<DiagnosticEvent>):Uri {
        val directory=File(context.cacheDir,"diagnostics").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target=File(directory,"${UUID.randomUUID()}.zip"); val temp=File(directory,target.name+".tmp")
        try {
            ZipOutputStream(temp.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("board.png")); val bitmap=Bitmap.createBitmap(boardCrop.argb,boardCrop.width,boardCrop.height,Bitmap.Config.ARGB_8888)
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,zip)) } finally { bitmap.recycle() }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("events.json")); zip.write(Json.encodeToString(log.takeLast(100)).toByteArray()); zip.closeEntry()
            }
            check(temp.renameTo(target)); lastFile=target
            return Uri.Builder().scheme("content").authority(context.packageName+".diagnostics").appendPath(target.name).build()
        } finally { temp.delete() }
    }
    companion object {
        fun crop(frame:PixelFrame,rect:FloatRect):PixelFrame {
            require(rect.valid()); val left=floor(rect.left).toInt().coerceIn(0,frame.width-1); val top=floor(rect.top).toInt().coerceIn(0,frame.height-1)
            val right=ceil(rect.right).toInt().coerceIn(left+1,frame.width); val bottom=ceil(rect.bottom).toInt().coerceIn(top+1,frame.height)
            return PixelFrame(right-left,bottom-top,IntArray((right-left)*(bottom-top)) { i -> frame.pixel(left+i%(right-left),top+i/(right-left)) },frame.timestampNs)
        }
    }
}
