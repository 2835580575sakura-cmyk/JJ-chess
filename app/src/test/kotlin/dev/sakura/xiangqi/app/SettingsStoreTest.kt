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
    @Test fun automaticThermalPauseIsOptInAndChoicePersists() {
        val store=SettingsStore(RuntimeEnvironment.getApplication())
        assertFalse(store.load().pauseOnOverheat)
        store.save(store.load().copy(pauseOnOverheat=true))
        assertTrue(SettingsStore(RuntimeEnvironment.getApplication()).load().pauseOnOverheat)
        store.save(store.load().copy(pauseOnOverheat=false))
        assertFalse(SettingsStore(RuntimeEnvironment.getApplication()).load().pauseOnOverheat)
    }
    @Test fun legacyDefaultUsesCoolThreadsAndExplicitPerformanceChoicePersists() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("assistant",0).edit().putString("config","{}").commit()
        val store=SettingsStore(context)
        assertEquals(2,store.load().options.threads)
        val performance=store.load().copy(options=EngineOptions(threads=4))
        store.save(performance)
        assertEquals(4,SettingsStore(context).load().options.threads)
    }
    @Test fun legacyCalibrationDoesNotOverrideNewPhoneGridDetection() {
        val context=RuntimeEnvironment.getApplication()
        val legacy="""{"calibration":{"captureRect":{"left":40.0,"top":366.0,"right":545.0,"bottom":909.0},"displayRect":{"left":40.0,"top":366.0,"right":545.0,"bottom":909.0}},"frameWidth":576,"frameHeight":1280,"displayWidth":576,"displayHeight":1280}"""
        context.getSharedPreferences("assistant",0).edit().putString("calibration",legacy).commit()
        assertNull(SettingsStore(context).loadCalibration(576,1280,576,1280,Orientation.RED_BOTTOM))
    }
    @Test fun settingsPersistAcrossInstances() {
        val context=RuntimeEnvironment.getApplication()
        val config=SessionConfig(playerSide=Side.BLACK,movetimeMs=30000,orientation=Orientation.BLACK_BOTTOM)
        SettingsStore(context).save(config); assertEquals(config,SettingsStore(context).load())
    }
    @Test fun calibrationOnlyRestoredForMatchingFrameAndDisplay() {
        val store=SettingsStore(RuntimeEnvironment.getApplication())
        val cal=GridCalibration(FloatRect(40f,366f,545f,909f),FloatRect(100f,915f,1362f,2272f))
        store.saveCalibration(cal,576,1280,1440,3200)
        assertEquals(cal,store.loadCalibration(576,1280,1440,3200,Orientation.RED_BOTTOM))
        assertNull(store.loadCalibration(576,1280,1080,2400,Orientation.RED_BOTTOM))
        assertNull(store.loadCalibration(576,1280,1440,3200,Orientation.BLACK_BOTTOM))
    }
    @Test fun corruptSettingsUseSafeDefaults() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("assistant",0).edit().putString("config","broken").commit()
        assertEquals(15000,SettingsStore(context).load().movetimeMs)
    }
}
