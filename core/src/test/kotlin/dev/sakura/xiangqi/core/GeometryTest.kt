package dev.sakura.xiangqi.core
import org.junit.Assert.*
import org.junit.Test
class GeometryTest {
    @Test fun all90CentersMapBothOrientations() {
        val r=FloatRect(41f,367f,545f,910f)
        for(o in Orientation.entries) {
            val calibration=GridCalibration(r,r,o)
            for(rank in 0..9)for(file in 0..8) {
                val p=calibration.center(Square(file,rank),CoordinateSpace.DISPLAY)
                assertEquals(41f+504*(if(o==Orientation.RED_BOTTOM)file else 8-file)/8f,p.x,.001f)
                assertEquals(367f+543*(if(o==Orientation.RED_BOTTOM)9-rank else rank)/9f,p.y,.001f)
            }
        }
    }
    @Test fun letterboxIsAccountedFor() {
        val c=GridCalibration(FloatRect(20f,30f,420f,480f),FloatRect(40f,100f,840f,1000f))
        assertEquals(FloatPoint(40f,1000f),c.center(Square(0,0),CoordinateSpace.DISPLAY))
        assertEquals(FloatPoint(20f,480f),c.center(Square(0,0),CoordinateSpace.CAPTURE))
    }
    @Test fun invalidCornersRejected() { assertFalse(GridCalibration(FloatRect(Float.NaN,0f,2f,2f),FloatRect(0f,0f,2f,2f)).isValid()) }
}
