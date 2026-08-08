package ng.yito.imefloatingswitcher.watcher

import ng.yito.imefloatingswitcher.model.ImeStatus

class AdaptivePollingPolicy(
    val hiddenStableMs: Long = 1_000L,
    val shownStableMs: Long = 400L,
    val changedMs: Long = 200L,
    val switchingMs: Long = 100L,
    val errorMs: Long = 3_000L,
    val changeBurstWindowMs: Long = 2_000L
) {
    fun nextDelay(
        status: Int,
        now: Long,
        stateChangedAt: Long,
        switchingUntil: Long,
        consecutiveErrors: Int
    ): Long {
        if (switchingUntil > now) return switchingMs
        if (consecutiveErrors >= 3) return errorMs
        if (stateChangedAt > 0 && now - stateChangedAt < changeBurstWindowMs) return changedMs
        return when (status) {
            ImeStatus.SHOWN -> shownStableMs
            ImeStatus.HIDDEN -> hiddenStableMs
            ImeStatus.UNKNOWN, ImeStatus.ERROR -> errorMs
            else -> hiddenStableMs
        }
    }
}
