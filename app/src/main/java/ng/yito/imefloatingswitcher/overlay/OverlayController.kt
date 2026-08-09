package ng.yito.imefloatingswitcher.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import ng.yito.imefloatingswitcher.ime.ImeSubtypeLabel
import ng.yito.imefloatingswitcher.ime.ImeSwitchCoordinator
import ng.yito.imefloatingswitcher.ime.InputMethodSubtypeLabelResolver
import ng.yito.imefloatingswitcher.settings.AppSettings

class OverlayController(
    context: Context,
    private val savePosition: (Int, Int, Int) -> Unit,
    private val onClick: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    private data class WindowHandle(
        val view: FloatingImeButtonView,
        val params: WindowManager.LayoutParams
    )

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val subtypeLabelResolver = InputMethodSubtypeLabelResolver(appContext)
    private val density = appContext.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windows = mutableMapOf<Int, WindowHandle>()
    @Volatile private var attachedCount = 0
    @Volatile private var expectedCount = 0
    private var visibilityReported = false
    private var currentSettings = AppSettings()

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun update(
        settings: AppSettings,
        currentImeId: String?,
        primaryId: String?,
        secondaryId: String?,
        switching: Boolean
    ) {
        runOnMain {
            currentSettings = settings
            if (!canDrawOverlays()) {
                hideInternal()
                return@runOnMain
            }
            val target = if (primaryId != null && secondaryId != null) {
                ImeSwitchCoordinator.chooseTarget(currentImeId, primaryId, secondaryId)
            } else {
                null
            }
            val subtype = target?.let {
                subtypeLabelResolver.resolve(it, settings.rememberedSubtypeHash(it))
            }
            syncWindows(settings, targetLabel(target), subtype, switching)
        }
    }

    fun showTest(settings: AppSettings = currentSettings) {
        runOnMain {
            if (!canDrawOverlays()) return@runOnMain
            currentSettings = settings
            syncWindows(settings, "测试", null, switching = false)
        }
    }

    fun hide() = runOnMain { hideInternal() }

    fun isShown(): Boolean = expectedCount > 0 && attachedCount == expectedCount

    private fun syncWindows(
        settings: AppSettings,
        targetLabel: String,
        subtype: ImeSubtypeLabel?,
        switching: Boolean
    ) {
        expectedCount = settings.overlayButtonCount
        windows.keys.filter { it >= expectedCount }.forEach(::removeWindow)
        repeat(expectedCount) { index ->
            val handle = ensureWindow(index, settings) ?: return@repeat
            handle.view.setState(
                targetLabel = targetLabel,
                switching = switching,
                subtypeLabel = subtype?.shortLabel,
                subtypeDescription = subtype?.fullLabel,
                buttonNumber = index + 1
            )
            updateWindow(index, handle, settings)
        }
        updateVisibilityState()
    }

    private fun ensureWindow(index: Int, settings: AppSettings): WindowHandle? {
        windows[index]?.let { existing ->
            if (existing.view.isAttachedToWindow) return existing
            discardStaleWindow(index, existing.view)
        }
        val button = settings.overlayButton(index)
        val touchSize = dp(maxOf(button.sizeDp, MIN_TOUCH_SIZE_DP))
        val metrics = appContext.resources.displayMetrics
        val defaultPosition = defaultPosition(index, metrics, touchSize)
        val x = (button.positionX ?: defaultPosition.first).coerceIn(0, maxX(metrics, touchSize))
        val y = (button.positionY ?: defaultPosition.second).coerceIn(0, maxY(metrics, touchSize))
        val params = WindowManager.LayoutParams(
            touchSize,
            touchSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val view = FloatingImeButtonView(appContext).apply {
            setVisualDiameter(dp(button.sizeDp))
            setWindowPosition(x, y)
            setOnClickListener { onClick() }
            setOnDragListener { newX, newY ->
                val active = windows[index] ?: return@setOnDragListener
                val activeMetrics = appContext.resources.displayMetrics
                active.params.x = newX.coerceIn(0, maxX(activeMetrics, active.params.width))
                active.params.y = newY.coerceIn(0, maxY(activeMetrics, active.params.height))
                setWindowPosition(active.params.x, active.params.y)
                runCatching { windowManager.updateViewLayout(this, active.params) }
                    .onFailure { discardStaleWindow(index, this) }
                savePosition(index, active.params.x, active.params.y)
            }
        }
        return try {
            windowManager.addView(view, params)
            WindowHandle(view, params).also { windows[index] = it }
        } catch (_: RuntimeException) {
            updateVisibilityState()
            null
        }
    }

    private fun updateWindow(index: Int, handle: WindowHandle, settings: AppSettings) {
        val button = settings.overlayButton(index)
        handle.view.setVisualDiameter(dp(button.sizeDp))
        val touchSize = dp(maxOf(button.sizeDp, MIN_TOUCH_SIZE_DP))
        val metrics = appContext.resources.displayMetrics
        val oldX = handle.params.x
        val oldY = handle.params.y
        val sizeChanged = handle.params.width != touchSize || handle.params.height != touchSize
        handle.params.width = touchSize
        handle.params.height = touchSize
        handle.params.x = handle.params.x.coerceIn(0, maxX(metrics, touchSize))
        handle.params.y = handle.params.y.coerceIn(0, maxY(metrics, touchSize))
        handle.view.setWindowPosition(handle.params.x, handle.params.y)
        if (sizeChanged || oldX != handle.params.x || oldY != handle.params.y) {
            runCatching { windowManager.updateViewLayout(handle.view, handle.params) }
                .onFailure { discardStaleWindow(index, handle.view) }
            if (oldX != handle.params.x || oldY != handle.params.y) {
                savePosition(index, handle.params.x, handle.params.y)
            }
        }
    }

    private fun hideInternal() {
        windows.values.toList().forEach { handle ->
            runCatching { windowManager.removeViewImmediate(handle.view) }
        }
        windows.clear()
        updateVisibilityState()
    }

    private fun removeWindow(index: Int) {
        val handle = windows.remove(index) ?: return
        runCatching { windowManager.removeViewImmediate(handle.view) }
    }

    private fun discardStaleWindow(index: Int, stale: FloatingImeButtonView) {
        runCatching { windowManager.removeViewImmediate(stale) }
        if (windows[index]?.view === stale) windows.remove(index)
        updateVisibilityState()
    }

    private fun updateVisibilityState() {
        attachedCount = windows.size
        val visible = expectedCount > 0 && attachedCount == expectedCount
        if (visibilityReported != visible) {
            visibilityReported = visible
            onVisibilityChanged(visible)
        }
    }

    private fun defaultPosition(index: Int, metrics: DisplayMetrics, size: Int): Pair<Int, Int> {
        val sideMargin = dp(16)
        val right = (metrics.widthPixels - size - sideMargin).coerceAtLeast(0)
        val centerY = (metrics.heightPixels / 2 - size / 2).coerceAtLeast(dp(24))
        return when (index) {
            1 -> sideMargin.coerceAtMost(maxX(metrics, size)) to centerY
            2 -> right to (centerY + size + dp(24)).coerceAtMost(maxY(metrics, size))
            else -> right to centerY
        }
    }

    private fun maxX(metrics: DisplayMetrics, size: Int): Int =
        (metrics.widthPixels - size).coerceAtLeast(0)

    private fun maxY(metrics: DisplayMetrics, size: Int): Int =
        (metrics.heightPixels - size).coerceAtLeast(0)

    private fun targetLabel(targetId: String?): String {
        if (targetId == null) return "设置"
        return when {
            targetId.contains("doubao", ignoreCase = true) -> "豆包"
            targetId.contains("google.android.inputmethod.latin", ignoreCase = true) -> "G"
            else -> "切换"
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val MIN_TOUCH_SIZE_DP = 48
    }
}
