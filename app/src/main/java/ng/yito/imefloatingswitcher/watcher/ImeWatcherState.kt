package ng.yito.imefloatingswitcher.watcher

import ng.yito.imefloatingswitcher.model.ImeStatus

enum class WatcherServiceState {
    STOPPED,
    WAITING_FOR_SHIZUKU,
    WAITING_FOR_PERMISSION,
    WATCHING_HIDDEN,
    WATCHING_VISIBLE,
    SWITCHING,
    DEGRADED_MANUAL_MODE,
    ERROR
}

data class WatcherDecision(
    val overlayVisible: Boolean,
    val state: WatcherServiceState
)

class WatcherStateReducer(
    private val unknownHoldMs: Long = 2_000L,
    private val hiddenMinimumMs: Long = 450L
) {
    private var overlayVisible = false
    private var unknownSince: Long? = null
    private var hiddenSince: Long? = null
    private var hiddenSamples = 0

    fun reduce(status: Int, now: Long, switchingUntil: Long, manualAlwaysVisible: Boolean): WatcherDecision {
        if (manualAlwaysVisible) {
            overlayVisible = true
            return WatcherDecision(true, WatcherServiceState.DEGRADED_MANUAL_MODE)
        }
        when (status) {
            ImeStatus.SHOWN -> {
                overlayVisible = true
                unknownSince = null
                hiddenSince = null
                hiddenSamples = 0
            }

            ImeStatus.HIDDEN -> {
                unknownSince = null
                if (hiddenSince == null) hiddenSince = now
                hiddenSamples++
                val protected = switchingUntil > now
                if (!protected && hiddenSamples >= 2 && now - (hiddenSince ?: now) >= hiddenMinimumMs) {
                    overlayVisible = false
                }
            }

            ImeStatus.UNKNOWN, ImeStatus.ERROR -> {
                hiddenSince = null
                hiddenSamples = 0
                if (unknownSince == null) unknownSince = now
                if (now - (unknownSince ?: now) > unknownHoldMs) overlayVisible = false
            }
        }
        val state = when {
            switchingUntil > now -> WatcherServiceState.SWITCHING
            status == ImeStatus.UNKNOWN || status == ImeStatus.ERROR -> WatcherServiceState.ERROR
            overlayVisible -> WatcherServiceState.WATCHING_VISIBLE
            else -> WatcherServiceState.WATCHING_HIDDEN
        }
        return WatcherDecision(overlayVisible, state)
    }
}
