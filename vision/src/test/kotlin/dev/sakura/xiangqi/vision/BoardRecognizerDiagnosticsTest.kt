package dev.sakura.xiangqi.vision

import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.*

class BoardRecognizerDiagnosticsTest {
    private val rect=FloatRect(32f,36f,537f,579f)
    private val calibration=GridCalibration(rect,rect)
    private fun frame(name:String="cal6"):PixelFrame {
        val image=ImageIO.read(javaClass.getResourceAsStream("/jj/$name.png"))
        return PixelFrame(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width),1)
    }
    @Test fun invalidCoordinatesAreReportedAsCalibrationIssue() {
        val input=frame(); val outside=rect.copy(right=input.width.toFloat())
        val result=BoardRecognizer(TemplateClassifier.fromResource()).recognize(input,GridCalibration(outside,outside))
        assertNull(result.board); assertTrue(result.occluded)
        assertEquals(RecognitionIssue.OUT_OF_BOUNDS,result.recognitionIssue)
        assertEquals(0,result.unknownCellCount)
    }
    @Test fun unreadableSinglePieceReportsItsCountWithoutAcceptingBoard() {
        val input=frame(); val pixels=input.argb.copyOf()
        val center=calibration.center(Square(0,3),CoordinateSpace.CAPTURE)
        val radius=min(rect.width/8,rect.height/9)*.45f
        for(y in 0 until input.height)for(x in 0 until input.width) {
            if(hypot(x-center.x,y-center.y)<radius)pixels[y*input.width+x]=0xffe6be78.toInt()
        }
        val result=BoardRecognizer(TemplateClassifier.fromResource()).recognize(input.copy(argb=pixels),calibration)
        assertNull(result.board); assertTrue(result.occluded)
        assertEquals(RecognitionIssue.UNKNOWN_CELLS,result.recognitionIssue)
        assertEquals(1,result.unknownCellCount)
        assertNull(StabilityGate().accept(result))
    }
    @Test fun riverAnimationIsReportedSeparatelyFromUnreadablePieces() {
        val result=BoardRecognizer(TemplateClassifier.fromResource()).recognize(frame("animation18"),calibration)
        assertNull(result.board); assertTrue(result.occluded)
        assertEquals(RecognitionIssue.RIVER_OBSTRUCTION,result.recognitionIssue)
        assertEquals(0,result.unknownCellCount)
    }
    @Test fun readableBoardHasNoRecognitionIssue() {
        val result=BoardRecognizer(TemplateClassifier.fromResource()).recognize(frame(),calibration)
        assertNotNull(result.board)
        assertEquals(RecognitionIssue.NONE,result.recognitionIssue)
        assertEquals(0,result.unknownCellCount)
    }
}
