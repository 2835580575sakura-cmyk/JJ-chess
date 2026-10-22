package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
object EngineFailures {
    fun reason(error:Throwable):EngineFailureReason {
        var cause:Throwable?=error
        repeat(16) { val current=cause?:return EngineFailureReason.GENERIC; if(current is OutOfMemoryError)return EngineFailureReason.MEMORY; cause=current.cause }
        return EngineFailureReason.GENERIC
    }
    fun disconnected(initialized:Boolean)=EngineFailureException(
        if(initialized)EngineFailureReason.NATIVE_EXIT else EngineFailureReason.INITIALIZATION_EXIT,
        if(initialized)"引擎进程意外退出，原因尚未确定" else "引擎初始化时意外退出，原因尚未确定"
    )
}
