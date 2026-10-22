package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
class OverlayPolicyTest {
    @Test fun obscuringAlphaWithinSystemLimit() {
        assertEquals(.6f,OverlayPolicy.arrowAlpha(.8f),.001f)
        assertEquals(.4f,OverlayPolicy.arrowAlpha(.4f),.001f)
    }
    @Test fun samplingWaitsForFreshFrames() {
        val gate=OverlaySamplingGate(clock={1000L},requestHide={})
        assertEquals(1000L,gate.hideForSample())
        assertFalse(gate.acceptFrame(1000)); assertFalse(gate.acceptFrame(1001))
        assertFalse(gate.acceptFrame(1001)); assertTrue(gate.acceptFrame(1002))
    }
    @Test fun oneHideRequestWhileWaiting() {
        var hides=0; val gate=OverlaySamplingGate(clock={100L},requestHide={hides++})
        assertFalse(gate.acceptFrame(100)); assertFalse(gate.acceptFrame(200)); assertEquals(1,hides)
    }
    @Test fun transformChangeClearsArrow() {
        val store=HintStore(); val key=AnalysisKey("a",1,1,1)
        store.put(MoveHint(key,UciMove.parse("b0c2"),"马八进七")); store.transformChanged(2)
        assertNull(store.current)
    }
    @Test fun samplingSuppressionSurvivesNewResultsAndStaleCompletion() {
        val store=HintStore(); val key=AnalysisKey("a",1,1,1); val hint=MoveHint(key,UciMove.parse("b0c2"),"马八进七")
        store.beginSample(1); store.put(hint); assertNull(store.visible)
        store.beginSample(2); store.completeSample(1); assertNull(store.visible)
        store.completeSample(2); assertEquals(hint,store.visible)
    }
    @Test fun panelStaysOutsideBoard() {
        val board=FloatRect(40f,400f,540f,1000f)
        val y=OverlayPolicy.panelY(1280,board,150,700)!!
        assertTrue(y+150<400 || y>1000)
    }
}
