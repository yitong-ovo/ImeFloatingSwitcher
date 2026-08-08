package ng.yito.imefloatingswitcher.watcher

import ng.yito.imefloatingswitcher.model.ImeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePollingPolicyTest {
    private val policy = AdaptivePollingPolicy()

    @Test
    fun usesFastIntervalDuringSwitch() {
        assertEquals(100L, policy.nextDelay(ImeStatus.SHOWN, 100L, 0L, 200L, 0))
    }

    @Test
    fun usesBurstIntervalAfterChange() {
        assertEquals(200L, policy.nextDelay(ImeStatus.SHOWN, 500L, 400L, 0L, 0))
    }

    @Test
    fun backsOffAfterThreeErrors() {
        assertEquals(3_000L, policy.nextDelay(ImeStatus.ERROR, 5_000L, 0L, 0L, 3))
    }
}
