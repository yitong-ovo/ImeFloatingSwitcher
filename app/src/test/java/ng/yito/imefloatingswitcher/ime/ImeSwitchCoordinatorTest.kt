package ng.yito.imefloatingswitcher.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeSwitchCoordinatorTest {
    @Test
    fun togglesBetweenConfiguredInputMethods() {
        assertEquals("secondary", ImeSwitchCoordinator.chooseTarget("primary", "primary", "secondary"))
        assertEquals("primary", ImeSwitchCoordinator.chooseTarget("secondary", "primary", "secondary"))
        assertEquals("secondary", ImeSwitchCoordinator.chooseTarget("other", "primary", "secondary"))
    }
}
