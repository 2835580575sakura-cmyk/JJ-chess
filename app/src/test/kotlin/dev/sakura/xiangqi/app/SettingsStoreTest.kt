package dev.sakura.xiangqi.app
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.sakura.xiangqi.core.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],manifest=Config.NONE)
class SettingsStoreTest {
    @Test fun settingsPersistAcrossInstances() {
        val context=RuntimeEnvironment.getApplication()
        val config=SessionConfig(playerSide=Side.BLACK,movetimeMs=30000,orientation=Orientation.BLACK_BOTTOM)
        SettingsStore(context).save(config); assertEquals(config,SettingsStore(context).load())
    }
    @Test fun corruptSettingsUseSafeDefaults() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("assistant",0).edit().putString("config","broken").commit()
        assertEquals(15000,SettingsStore(context).load().movetimeMs)
    }
}
