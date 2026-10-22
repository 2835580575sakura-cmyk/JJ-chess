package dev.sakura.xiangqi.app
import dev.sakura.xiangqi.core.*
import org.junit.Assert.*
import org.junit.Test
class ResourcePolicyTest {
    @Test fun severeThermalPausesSearch() { assertEquals(ResourceAction.Pause,ResourcePolicy().onThermalStatus(3)) }
    @Test fun memoryFallbackNeedsRetry() { assertEquals(ResourceAction.Retry(EngineOptions(2,128,1)),ResourcePolicy().onMemoryPressure()) }
    @Test fun normalTemperatureDoesNotAutoResume() { val p=ResourcePolicy(); p.onThermalStatus(3); assertEquals(ResourceAction.None,p.onThermalStatus(0)) }
}
