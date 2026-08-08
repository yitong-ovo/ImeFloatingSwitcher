package ng.yito.imefloatingswitcher.watcher

import ng.yito.imefloatingswitcher.model.ImeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatcherStateReducerTest {
    @Test
    fun shownIsImmediateButHiddenNeedsDebounce() {
        val reducer = WatcherStateReducer()
        assertTrue(reducer.reduce(ImeStatus.SHOWN, 0L, 0L, false).overlayVisible)
        assertTrue(reducer.reduce(ImeStatus.HIDDEN, 100L, 0L, false).overlayVisible)
        assertFalse(reducer.reduce(ImeStatus.HIDDEN, 600L, 0L, false).overlayVisible)
    }

    @Test
    fun unknownHoldsPreviousStateForTwoSeconds() {
        val reducer = WatcherStateReducer()
        assertTrue(reducer.reduce(ImeStatus.SHOWN, 0L, 0L, false).overlayVisible)
        assertTrue(reducer.reduce(ImeStatus.UNKNOWN, 0L, 0L, false).overlayVisible)
        assertFalse(reducer.reduce(ImeStatus.UNKNOWN, 2_001L, 0L, false).overlayVisible)
    }

    @Test
    fun switchProtectionPreventsImmediateHide() {
        val reducer = WatcherStateReducer()
        assertTrue(reducer.reduce(ImeStatus.SHOWN, 0L, 0L, false).overlayVisible)
        assertTrue(reducer.reduce(ImeStatus.HIDDEN, 500L, 2_000L, false).overlayVisible)
    }
}
