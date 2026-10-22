package dev.sakura.xiangqi.app
import android.content.Context
import dev.sakura.xiangqi.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
@Serializable private data class SavedCalibration(val calibration:GridCalibration,val frameWidth:Int,val frameHeight:Int,val displayWidth:Int,val displayHeight:Int,val gridVersion:Int=1)
class SettingsStore(context:Context) {
    private val prefs=context.getSharedPreferences("assistant",Context.MODE_PRIVATE)
    fun load():SessionConfig=runCatching { Json.decodeFromString<SessionConfig>(prefs.getString("config",null)?:return SessionConfig()) }.getOrDefault(SessionConfig())
    fun clearCalibration() { prefs.edit().remove("calibration").apply() }
    fun saveCalibration(calibration:GridCalibration,frameWidth:Int,frameHeight:Int,displayWidth:Int,displayHeight:Int) {
        require(calibration.isValid()); require(calibration.captureRect.right<frameWidth && calibration.captureRect.bottom<frameHeight && calibration.displayRect.right<displayWidth && calibration.displayRect.bottom<displayHeight)
        prefs.edit().putString("calibration",Json.encodeToString(SavedCalibration(calibration,frameWidth,frameHeight,displayWidth,displayHeight,gridVersion=2))).apply()
    }
    fun loadCalibration(frameWidth:Int,frameHeight:Int,displayWidth:Int,displayHeight:Int,orientation:Orientation):GridCalibration?=runCatching {
        val saved=Json.decodeFromString<SavedCalibration>(prefs.getString("calibration",null)?:return null)
        saved.calibration.takeIf { saved.gridVersion==2 && it.isValid() && saved.frameWidth==frameWidth && saved.frameHeight==frameHeight && saved.displayWidth==displayWidth && saved.displayHeight==displayHeight && it.orientation==orientation }
    }.getOrNull()
    fun save(config:SessionConfig) { prefs.edit().putString("config",Json.encodeToString(config)).apply() }
}
