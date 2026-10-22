package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties
import javax.imageio.ImageIO
class RecognitionReplayTest {
    private val labels=Properties().apply { load(RecognitionReplayTest::class.java.getResourceAsStream("/jj/labels.properties")) }
    private val rect=FloatRect(32f,36f,537f,579f)
    private val calibration=GridCalibration(rect,rect)
    private fun frame(name: String):PixelFrame {
        val im=ImageIO.read(javaClass.getResourceAsStream("/jj/$name.png"))
        return PixelFrame(im.width,im.height,im.getRGB(0,0,im.width,im.height,null,0,im.width),0)
    }
    private fun checkBoard(name: String) {
        val recognizer=BoardRecognizer(TemplateClassifier.fromResource())
        val actual=recognizer.recognize(frame(name),calibration)
        val expected=FenCodec.decode(labels.getProperty(name)).getOrThrow().board
        assertNotNull("$name confidence=${actual.confidence}",actual.board)
        assertEquals(name,expected.cells,actual.board!!.cells)
    }
    @Test fun calibrationFramesAll90() { checkBoard("cal6"); checkBoard("cal10") }
    @Test fun heldOutFrameAll90() { checkBoard("holdout8") }
    @Test fun largeCaptureAnimationRejected() {
        for(name in listOf("animation18","animation27")) {
            val actual=BoardRecognizer(TemplateClassifier.fromResource()).recognize(frame(name),calibration)
            assertTrue(name,actual.board==null || actual.occluded)
        }
    }
    @Test fun scaledBoardKeepsLabels() {
        val input=frame("cal6"); val scaled=input.resized(852,923)
        val r=FloatRect(rect.left*1.5f,rect.top*923/615,rect.right*1.5f,rect.bottom*923/615)
        val actual=BoardRecognizer(TemplateClassifier.fromResource()).recognize(scaled,GridCalibration(r,r))
        assertEquals(FenCodec.decode(labels.getProperty("cal6")).getOrThrow().board,actual.board)
    }
    @Test fun flippedBoardKeepsCanonicalIdentity() {
        val input=frame("cal6").rotated180()
        val r=FloatRect(568-1-rect.right,615-1-rect.bottom,568-1-rect.left,615-1-rect.top)
        val actual=BoardRecognizer(TemplateClassifier.fromResource()).recognize(input,GridCalibration(r,r,Orientation.BLACK_BOTTOM))
        assertEquals(FenCodec.decode(labels.getProperty("cal6")).getOrThrow().board,actual.board)
    }
    @Test fun locatorFindsThemeGrid() {
        val found=GridLocator.locate(frame("cal6"))
        assertNotNull(found)
        assertEquals(rect.left,found!!.captureRect.left,5f)
        assertEquals(rect.top,found.captureRect.top,5f)
    }
    @Test fun automaticCalibrationRecognizesFullFrame() {
        val board=frame("cal6"); val pixels=IntArray(576*1280){0xff223322.toInt()}
        for(y in 0 until board.height) board.argb.copyInto(pixels,(y+330)*576+8,y*board.width,(y+1)*board.width)
        val full=PixelFrame(576,1280,pixels,0)
        val found=requireNotNull(GridLocator.locate(full))
        val actual=BoardRecognizer(TemplateClassifier.fromResource()).recognize(full,found)
        assertEquals(FenCodec.decode(labels.getProperty("cal6")).getOrThrow().board,actual.board)
    }
    @Test fun compressedBoardKeepsLabels() {
        val image=ImageIO.read(javaClass.getResourceAsStream("/jj/cal10.png"))
        val bytes=java.io.ByteArrayOutputStream(); ImageIO.write(image,"jpg",bytes)
        val decoded=ImageIO.read(java.io.ByteArrayInputStream(bytes.toByteArray()))
        val frame=PixelFrame(decoded.width,decoded.height,decoded.getRGB(0,0,decoded.width,decoded.height,null,0,decoded.width),0)
        val actual=BoardRecognizer(TemplateClassifier.fromResource()).recognize(frame,calibration)
        assertEquals(FenCodec.decode(labels.getProperty("cal10")).getOrThrow().board,actual.board)
    }
}
