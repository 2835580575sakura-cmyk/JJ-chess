package dev.sakura.xiangqi.vision

import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO

class OpeningAdaptationTest {
    private val rect=FloatRect(32f,36f,537f,579f)
    private val cal=GridCalibration(rect,rect)
    private fun frame(name:String):PixelFrame {
        val image=ImageIO.read(javaClass.getResourceAsStream("/jj/$name.png"))
        return PixelFrame(image.width,image.height,image.getRGB(0,0,image.width,image.height,null,0,image.width),0)
    }
    @Test fun openingAdaptationSurvivesStorageAndRecognizesLaterMoves() {
        val adapted=TemplateClassifier.fromOpening(frame("start"),cal).getOrThrow()
        val loaded=TemplateClassifier.fromBytes(adapted.toBytes()).getOrThrow()
        val later=BoardRecognizer(loaded).recognize(frame("holdout8"),cal)
        assertNotNull("Adapted templates must recognize a different position",later.board)
        val labels=java.util.Properties().apply { load(OpeningAdaptationTest::class.java.getResourceAsStream("/jj/labels.properties")) }
        assertEquals(FenCodec.decode(labels.getProperty("holdout8")).getOrThrow().board,later.board)
        assertEquals(32,later.board!!.cells.count { it!=null })
        assertEquals(Piece(Side.RED,PieceKind.PAWN),later.board!![Square(2,4)])
        val flipped=frame("holdout8").rotated180()
        val r=FloatRect(567-rect.right,614-rect.bottom,567-rect.left,614-rect.top)
        val rotated=BoardRecognizer(loaded).recognize(flipped,GridCalibration(r,r,Orientation.BLACK_BOTTOM))
        assertEquals(later.board,rotated.board)
    }
    @Test fun movedOrAnimatedBoardCannotBeUsedAsOpeningLabels() {
        for(name in listOf("cal6","animation18","animation27")) {
            assertTrue(name,TemplateClassifier.fromOpening(frame(name),cal).isFailure)
        }
    }
    @Test fun corruptedTemplateFileIsRejected() {
        assertTrue(TemplateClassifier.fromBytes(byteArrayOf(0,1,2)).isFailure)
        val bytes=TemplateClassifier.fromOpening(frame("start"),cal).getOrThrow().toBytes()
        assertTrue(TemplateClassifier.fromBytes(bytes.copyOf(bytes.size-5)).isFailure)
    }
}
