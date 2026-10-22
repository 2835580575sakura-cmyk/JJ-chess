package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.vision.PixelFrame
import dev.sakura.xiangqi.core.FloatRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.zip.ZipFile
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE)
class DiagnosticExporterTest {
    @Test fun exportContainsOnlyBoardCrop() {
        val frame=PixelFrame(100,200,IntArray(20000){0xffaabbcc.toInt()},1)
        val crop=DiagnosticExporter.crop(frame,FloatRect(10f,40f,90f,160f))
        assertEquals(80,crop.width); assertEquals(120,crop.height)
        val exporter=DiagnosticExporter(RuntimeEnvironment.getApplication())
        val uri=exporter.export(crop,listOf(DiagnosticEvent(1,DiagnosticCode.RECOGNITION_UNCERTAIN)))
        assertEquals("content",uri.scheme)
        ZipFile(exporter.lastFile!!).use { zip -> assertEquals(setOf("board.png","events.json"),zip.entries().asSequence().map { it.name }.toSet()); val logs=zip.getInputStream(zip.getEntry("events.json")).reader().readText(); assertFalse(logs.contains("token",true)); assertTrue(logs.contains("RECOGNITION_UNCERTAIN")) }
    }
}
