package dev.sakura.xiangqi.app

import android.app.AlertDialog
import android.os.PowerManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE,shadows=[MainActivityRecoveryTest.ThermalListeners::class])
class MainActivityRecoveryTest {
    @Implements(PowerManager::class)
    class ThermalListeners:ShadowPowerManager() {
        // Robolectric handles the one-argument overload only; preserve its listener bookkeeping.
        @Implementation protected fun addThermalStatusListener(executor:Executor,listener:PowerManager.OnThermalStatusChangedListener) {
            super.addThermalStatusListener(listener)
        }
    }
    private var controller:ActivityController<MainActivity>?=null
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        AssistantRuntime.current?.stop()
        controller?.pause()?.stop()?.destroy()
        Dispatchers.resetMain()
    }
    private fun activity():MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller=it }.get()
    private fun button(root:View,label:String):Button? {
        if(root is Button && root.text.toString()==label)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)button(root.getChildAt(i),label)?.let { return it }
        return null
    }
    @Test fun unreadableBoardOpensRecoveryChoicesWithoutGuessingPosition() {
        val activity=activity(); val runtime=AssistantRuntime.create(activity,SessionConfig())
        button(activity.window.decorView,"校验棋子 / 当前行棋方")!!.performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("未识别棋盘时应提供恢复入口",dialog)
        assertEquals("适配标准开局",dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals("校准棋盘",dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        assertNull(runtime.coordinator.state.value.position)
        assertNull(runtime.coordinator.state.value.key)
    }
    @Test fun adaptationIsAccessibleBeforeRecognitionAndExplainsMissingCapture() {
        val activity=activity()
        val adapt=button(activity.window.decorView,"适配棋子（标准开局）")
        assertNotNull("新皮肤应有独立适配入口",adapt)
        adapt!!.performClick()
        assertEquals("请先开始分析",ShadowToast.getTextOfLatestToast())
    }
}
