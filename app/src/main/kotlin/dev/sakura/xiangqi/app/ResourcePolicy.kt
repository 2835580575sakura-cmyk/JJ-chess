package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
sealed class ResourceAction { data object None:ResourceAction(); data object Pause:ResourceAction(); data class Retry(val options:EngineOptions):ResourceAction() }
class ResourcePolicy {
    fun onThermalStatus(status:Int):ResourceAction=if(status>=3)ResourceAction.Pause else ResourceAction.None
    fun onEngineFailure(reason:EngineFailureReason):ResourceAction=when(reason) {
        EngineFailureReason.MEMORY,EngineFailureReason.NATIVE_EXIT,EngineFailureReason.INITIALIZATION_EXIT -> ResourceAction.Retry(EngineOptions(2,128))
        else -> ResourceAction.None
    }
    fun onMemoryPressure():ResourceAction=ResourceAction.Retry(EngineOptions(2,128,1))
}
