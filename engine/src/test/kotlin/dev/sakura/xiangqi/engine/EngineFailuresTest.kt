package dev.sakura.xiangqi.engine
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
class EngineFailuresTest {
    @Test fun nativeAllocationErrorIsTypedThroughFutureWrappers() { assertEquals(EngineFailureReason.MEMORY,EngineFailures.reason(java.util.concurrent.ExecutionException(OutOfMemoryError("allocation")))) }
    @Test fun unexplainedProcessDeathDoesNotClaimConfirmedOom() { assertEquals(EngineFailureReason.INITIALIZATION_EXIT,EngineFailures.disconnected(false).reason); val failure=EngineFailures.disconnected(true); assertEquals(EngineFailureReason.NATIVE_EXIT,failure.reason); assertFalse(failure.message!!.contains("内存不足")) }
}
