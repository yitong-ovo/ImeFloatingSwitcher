package ng.yito.imefloatingswitcher.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import ng.yito.imefloatingswitcher.ime.ImeSwitchCoordinator
import ng.yito.imefloatingswitcher.settings.AppSettings

class OverlayController(
    context: Context,
    private val savePosition: (Int, Int) -> Unit,
    private val onClick: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val density = appContext.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: FloatingImeButtonView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    @Volatile private var attached = false
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
            val button = ensureView() ?: return@runOnMain
            val target = if (primaryId != null && secondaryId != null) {
                ImeSwitchCoordinator.chooseTarget(currentImeId, primaryId, secondaryId)
            } else null
            button.setState(targetLabel(target), switching)
            updateSizeIfNeeded(button)
        }
    }

    fun showTest(settings: AppSettings = currentSettings) {
        runOnMain {
            if (!canDrawOverlays()) return@runOnMain
            currentSettings = settings
            val button = ensureView() ?: return@runOnMain
            button.setState("测试", false)
            updateSizeIfNeeded(button)
        }
    }

    fun hide() = runOnMain { hideInternal() }

    fun isShown(): Boolean = attached && view?.isAttachedToWindow == true

    private fun ensureView(): FloatingImeButtonView? {
        view?.let { existing ->
            if (attached && existing.isAttachedToWindow) return existing
            discardStaleWindow(existing)
        }
        val size = dp(currentSettings.buttonSizeDp)
        val metrics = appContext.resources.displayMetrics
        val defaultX = (metrics.widthPixels - size - dp(16)).coerceAtLeast(0)
        val defaultY = (metrics.heightPixels / 2 - size / 2).coerceAtLeast(dp(24))
        val x = (currentSettings.positionX ?: defaultX).coerceIn(0, maxX(metrics, size))
        val y = (currentSettings.positionY ?: defaultY).coerceIn(0, maxY(metrics, size))
        val params = WindowManager.LayoutParams(
            size,
            size,
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
        val newView = FloatingImeButtonView(appContext)
        newView.setWindowPosition(x, y)
        newView.setOnClickListener { onClick() }
        newView.setOnDragListener { x, y ->
            val activeParams = layoutParams ?: return@setOnDragListener
            val metrics = appContext.resources.displayMetrics
            val size = dp(currentSettings.buttonSizeDp)
            activeParams.x = x.coerceIn(0, maxX(metrics, size))
            activeParams.y = y.coerceIn(0, maxY(metrics, size))
            newView.setWindowPosition(activeParams.x, activeParams.y)
            runCatching { windowManager.updateViewLayout(newView, activeParams) }
                .onFailure { discardStaleWindow(newView) }
            savePosition(activeParams.x, activeParams.y)
        }
        try {
            windowManager.addView(newView, params)
            view = newView
            layoutParams = params
            attached = true
            onVisibilityChanged(true)
            return newView
        } catch (_: RuntimeException) {
            view = null
            layoutParams = null
            attached = false
            onVisibilityChanged(false)
            return null
        }
    }

    private fun updateSizeIfNeeded(button: FloatingImeButtonView) {
        val params = layoutParams ?: return
        val size = dp(currentSettings.buttonSizeDp)
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        runCatching {
            windowManager.updateViewLayout(button, params)
            button.layoutParams = params
        }.onFailure { discardStaleWindow(button) }
    }

    private fun hideInternal() {
        view?.let { active ->
            runCatching { windowManager.removeViewImmediate(active) }
        }
        view = null
        layoutParams = null
        attached = false
        onVisibilityChanged(false)
    }

    private fun discardStaleWindow(stale: FloatingImeButtonView) {
        runCatching { windowManager.removeViewImmediate(stale) }
        if (view === stale) {
            view = null
            layoutParams = null
            attached = false
            onVisibilityChanged(false)
        }
    }

    private fun maxX(metrics: android.util.DisplayMetrics, size: Int): Int =
        (metrics.widthPixels - size).coerceAtLeast(0)

    private fun maxY(metrics: android.util.DisplayMetrics, size: Int): Int =
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
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }
}
