package dev.sakura.xiangqi.app

import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE,shadows=[MainActivityRecoveryTest.ThermalListeners::class])
class AssistantRuntimePauseTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val power get()=context.getSystemService(PowerManager::class.java)

    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        AssistantRuntime.current?.stop()
        Dispatchers.resetMain()
    }

    private fun assertRepeatedResumeKeepsSamplingInterval(notify:(AssistantRuntime)->Unit) {
        val runtime=AssistantRuntime.create(context,SessionConfig(pauseOnOverheat=true))
        runtime.uiVisible(false)
        val filter=checkNotNull(CaptureBus.sampleFilter)
        val time=System.nanoTime()+1_000_000_000L
        assertFalse(filter(time))
        assertTrue("两张隐藏后新鲜帧应开始正常采样",filter(time+1))
        val generation=CaptureBus.sampleGeneration!!.invoke()

        notify(runtime)

        assertFalse(filter(time+2))
        assertFalse("重复的可见通知不能重启采样并跳过采样间隔",filter(time+3))
        assertEquals(generation,CaptureBus.sampleGeneration!!.invoke())
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
    }

    @Test fun repeatedCaptureVisibleNotificationDoesNotRestartSampling() {
        assertRepeatedResumeKeepsSamplingInterval {
            assertTrue(CaptureBus.events.tryEmit(CaptureEvent.Visibility(true)))
        }
    }

    @Test fun repeatedUiHiddenNotificationDoesNotRestartSampling() {
        assertRepeatedResumeKeepsSamplingInterval { it.uiVisible(false) }
    }

    @Test fun defaultOverheatPreferenceKeepsSamplingAndManualPauseAvailable() {
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        val runtime=AssistantRuntime.create(context,SessionConfig())
        runtime.uiVisible(false)
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
        val filter=checkNotNull(CaptureBus.sampleFilter)
        val time=System.nanoTime()+1_000_000_000L
        assertFalse(filter(time))
        assertTrue(filter(time+1))

        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
        assertFalse(filter(time+2))
        assertFalse("关闭过热暂停时，温度通知不能重启采样",filter(time+3))

        runtime.pauseToggle()
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        assertFalse(filter(time+4))
        runtime.pauseToggle()
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
        assertFalse(filter(time+5))
        assertTrue("关闭过热暂停仍可手动暂停和继续",filter(time+6))
    }

    @Test fun continuingWhileHotKeepsPauseUntilOneManualContinueAfterCooling() {
        val runtime=AssistantRuntime.create(context,SessionConfig(pauseOnOverheat=true))
        runtime.uiVisible(false)
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        assertTrue(runtime.coordinator.state.value.message.contains("过热"))

        runtime.pauseToggle()
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)

        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        val filter=checkNotNull(CaptureBus.sampleFilter)
        val time=System.nanoTime()+1_000_000_000L
        repeat(3) { assertFalse("降温后手动继续前不能采样",filter(time+it)) }
        runtime.pauseToggle()
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
        assertFalse(filter(time+3))
        assertTrue("降温后一次继续即可恢复新鲜画面采样",filter(time+4))
    }

    @Test fun initialSevereTemperatureExplainsPauseAcrossUiAndCaptureNotifications() {
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        val runtime=AssistantRuntime.create(context,SessionConfig(pauseOnOverheat=true))
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        assertTrue(runtime.coordinator.state.value.message.contains("过热"))

        runtime.uiVisible(false)
        assertTrue(CaptureBus.events.tryEmit(CaptureEvent.Visibility(false)))
        runtime.uiVisible(true)
        assertTrue(runtime.coordinator.state.value.message.contains("过热"))
        assertFalse(CaptureBus.sampleFilter!!.invoke(System.nanoTime()+1_000_000_000L))
    }

    @Test fun severeTemperatureOverridesAnExistingUiPauseAndCoolingStillNeedsContinue() {
        val runtime=AssistantRuntime.create(context,SessionConfig(pauseOnOverheat=true))
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertTrue(runtime.coordinator.state.value.message.contains("过热"))

        shadowOf(power).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        runtime.uiVisible(false)
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        runtime.pauseToggle()
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
    }

    @Test fun captureHiddenIntervalStillPausesUntilCaptureReturns() {
        val runtime=AssistantRuntime.create(context,SessionConfig(pauseOnOverheat=true))
        runtime.uiVisible(false)
        assertTrue(CaptureBus.events.tryEmit(CaptureEvent.Visibility(false)))
        assertEquals(SessionPhase.PAUSED,runtime.coordinator.state.value.phase)
        assertFalse(CaptureBus.sampleFilter!!.invoke(System.nanoTime()+1_000_000_000L))

        assertTrue(CaptureBus.events.tryEmit(CaptureEvent.Visibility(true)))
        assertEquals(SessionPhase.WAITING_BOARD,runtime.coordinator.state.value.phase)
    }
}
