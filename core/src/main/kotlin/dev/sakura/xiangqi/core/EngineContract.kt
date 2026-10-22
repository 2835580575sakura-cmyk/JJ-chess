package dev.sakura.xiangqi.core
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable data class AnalysisKey(val sessionId: String, val positionRevision: Long, val searchId: Long, val transformRevision: Long)
@Serializable data class EngineOptions(val threads: Int = 4, val hashMiB: Int = 256, val multiPv: Int = 1) {
    init { require(threads in 1..16 && hashMiB in 16..512 && multiPv == 1) }
}
@Serializable data class AnalysisRequest(val key: AnalysisKey, val history: PositionHistory, val movetimeMs: Int) {
    init { require(movetimeMs in 100..120000) }
}
@Serializable data class LegalPosition(val snapshot: PositionSnapshot, val legalMoves: List<UciMove>)
@Serializable data class EngineScore(val cp: Int? = null, val mate: Int? = null)
@Serializable enum class EngineFailureReason { GENERIC, MEMORY, NATIVE_EXIT, INITIALIZATION_EXIT }
class EngineFailureException(val reason:EngineFailureReason,message:String):IllegalStateException(message)
@Serializable sealed class EngineEvent {
    abstract val key: AnalysisKey
    @Serializable data class Info(override val key: AnalysisKey, val depth: Int, val nodes: Long, val nps: Long, val score: EngineScore, val pv: List<UciMove>) : EngineEvent()
    @Serializable data class BestMove(override val key: AnalysisKey, val move: UciMove?) : EngineEvent()
    @Serializable data class Stopped(override val key: AnalysisKey) : EngineEvent()
    @Serializable data class Failure(override val key: AnalysisKey, val message: String,val reason:EngineFailureReason=EngineFailureReason.GENERIC) : EngineEvent()
}
interface RulesGateway { suspend fun validate(history: PositionHistory): Result<LegalPosition> }
interface EngineGateway : RulesGateway {
    fun analyze(request: AnalysisRequest): Flow<EngineEvent>
    suspend fun cancel(searchId: Long)
    suspend fun close()
}
