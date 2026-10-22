package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.EngineOptions
sealed class ResourceAction { data object None:ResourceAction(); data object Pause:ResourceAction(); data class Retry(val options:EngineOptions):ResourceAction() }
class ResourcePolicy {
    fun onThermalStatus(status:Int):ResourceAction=if(status>=3)ResourceAction.Pause else ResourceAction.None
    fun onMemoryPressure():ResourceAction=ResourceAction.Retry(EngineOptions(2,128,1))
}
