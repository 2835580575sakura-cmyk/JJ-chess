package dev.sakura.xiangqi.app
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
class SettingsStore(context:Context) {
    private val prefs=context.getSharedPreferences("assistant",Context.MODE_PRIVATE)
    fun load():SessionConfig=runCatching { Json.decodeFromString<SessionConfig>(prefs.getString("config",null)?:return SessionConfig()) }.getOrDefault(SessionConfig())
    fun save(config:SessionConfig) { prefs.edit().putString("config",Json.encodeToString(config)).apply() }
}
