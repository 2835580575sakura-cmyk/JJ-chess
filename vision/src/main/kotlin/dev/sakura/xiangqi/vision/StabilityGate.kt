package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
class StabilityGate {
    private var board:Board?=null
    private var count=0
    private var lastTimestamp=Long.MIN_VALUE
    fun reset() { board=null; count=0; lastTimestamp=Long.MIN_VALUE }
    fun accept(observation: BoardObservation):BoardObservation? {
        if(observation.timestampNs<=lastTimestamp)return null
        lastTimestamp=observation.timestampNs
        if(observation.board==null || observation.occluded || observation.motion>.10f || observation.confidence.size!=90 || observation.confidence.any { it<.70f }) { board=null; count=0; return null }
        if(board==observation.board)count++ else { board=observation.board; count=1 }
        return observation.takeIf { count>=3 }
    }
}
