package dev.sakura.xiangqi.vision
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
class StabilityGateTest {
    private val board=FenCodec.decode(START_FEN).getOrThrow().board
    private fun observation(t: Long,motion: Float=0f,occluded: Boolean=false)=BoardObservation(board,List(90){1f},t,motion,occluded)
    @Test fun requiresThreeStableSamples() {
        val gate=StabilityGate(); assertNull(gate.accept(observation(0))); assertNull(gate.accept(observation(400000000)))
        assertNotNull(gate.accept(observation(800000000)))
    }
    @Test fun animationResetsStability() {
        val gate=StabilityGate(); gate.accept(observation(0)); gate.accept(observation(400000000))
        assertNull(gate.accept(observation(800000000,occluded=true)))
        assertNull(gate.accept(observation(1200000000)))
    }
    @Test fun movementAndLowConfidenceRejected() {
        val gate=StabilityGate()
        assertNull(gate.accept(observation(0,motion=.2f)))
        assertNull(gate.accept(observation(400000000).copy(confidence=List(90){.2f})))
    }
    @Test fun repeatedTimestampDoesNotCountAsNewFrame() {
        val gate=StabilityGate(); repeat(4) { assertNull(gate.accept(observation(0))) }
    }
}
