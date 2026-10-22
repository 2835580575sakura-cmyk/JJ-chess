package dev.sakura.xiangqi.app
import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.sakura.xiangqi.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
class AssistantFlowTest {
    @Test fun freshManualConsentCaptureToVisibleHint()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        assumeTrue("Run with -Pandroid.testInstrumentationRunnerArguments.runCaptureFlow=true and manually grant system capture",InstrumentationRegistry.getArguments().getString("runCaptureFlow")=="true")
        assumeTrue("Grant overlay permission in system settings before the test",Settings.canDrawOverlays(context))
        val activity=instrumentation.startActivitySync(Intent(context,CaptureFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val runtime=withTimeout(90000) { while(AssistantRuntime.current==null)delay(100); AssistantRuntime.current!! }
            val observed=withTimeout(30000) { while(runtime.coordinator.state.value.position==null)delay(100); runtime.coordinator.state.value }
            assertEquals(FenCodec.decode(START_FEN).getOrThrow().board,observed.position!!.board)
            withContext(Dispatchers.Main) { runtime.coordinator.confirm(observed.position!!,observed.calibration!!).join() }
            withTimeout(15000) { while(runtime.coordinator.state.value.phase!=SessionPhase.READY) { assertNotEquals(SessionPhase.ERROR,runtime.coordinator.state.value.phase); delay(100) } }
            assertNotNull(runtime.coordinator.state.value.key)
            instrumentation.runOnMainSync { runtime.stop() }
        } finally { instrumentation.runOnMainSync { AssistantRuntime.current?.stop(); activity.finish() } }
    }
}
