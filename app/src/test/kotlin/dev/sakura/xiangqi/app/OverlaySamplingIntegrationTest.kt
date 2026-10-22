package dev.sakura.xiangqi.app
import android.view.View
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE)
class OverlaySamplingIntegrationTest {
    @Test fun bestMoveCannotRedrawDuringSampleAndOldCompletionCannotReleaseNewSample() {
        ShadowSettings.setCanDrawOverlays(true)
        val overlay=OverlayController(RuntimeEnvironment.getApplication()){}
        val key=AnalysisKey("s",1,1,1)
        overlay.setCalibration(GridCalibration(FloatRect(40f,366f,545f,909f),FloatRect(40f,366f,545f,909f),transformRevision=1))
        val arrow=overlay.javaClass.getDeclaredField("arrow").apply { isAccessible=true }.get(overlay) as View
        try {
            overlay.beginSample(1); assertTrue(overlay.show(key,UciMove.parse("b0c2"),"马八进七")); assertEquals(View.GONE,arrow.visibility)
            overlay.beginSample(2); overlay.completeSample(1,key); assertEquals(View.GONE,arrow.visibility)
            assertFalse(overlay.restore(key)); overlay.completeSample(2,key); assertEquals(View.VISIBLE,arrow.visibility)
        } finally { overlay.close() }
    }
}
