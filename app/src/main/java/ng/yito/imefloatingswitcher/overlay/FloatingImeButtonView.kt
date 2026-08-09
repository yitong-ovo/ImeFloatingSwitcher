package ng.yito.imefloatingswitcher.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min

class FloatingImeButtonView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var onDragTo: ((Int, Int) -> Unit)? = null
    private var targetLabel = "切换"
    private var subtypeLabel: String? = null
    private var subtypeDescription: String? = null
    private var buttonNumber = 1
    private var visualDiameterPx = 0
    private var switching = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragging = false
    private var windowX = 0
    private var windowY = 0

    init {
        isClickable = true
        contentDescription = "切换输入法"
    }

    fun setOnDragListener(listener: (Int, Int) -> Unit) {
        onDragTo = listener
    }

    fun setState(
        targetLabel: String,
        switching: Boolean,
        subtypeLabel: String? = null,
        subtypeDescription: String? = null,
        buttonNumber: Int = 1
    ) {
        this.targetLabel = targetLabel.ifBlank { "切换" }.take(4)
        this.subtypeLabel = subtypeLabel?.ifBlank { null }?.take(2)
        this.subtypeDescription = subtypeDescription?.ifBlank { null }
        this.buttonNumber = buttonNumber
        this.switching = switching
        contentDescription = when {
            switching -> "悬浮按钮$buttonNumber，正在切换输入法"
            this.subtypeDescription != null ->
                "悬浮按钮$buttonNumber，切换到${this.targetLabel}，记忆语言${this.subtypeDescription}"
            else -> "悬浮按钮$buttonNumber，切换到${this.targetLabel}"
        }
        invalidate()
    }

    fun setVisualDiameter(diameterPx: Int) {
        visualDiameterPx = diameterPx.coerceAtLeast(1)
        invalidate()
    }

    fun setWindowPosition(x: Int, y: Int) {
        windowX = x
        windowY = y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = (if (visualDiameterPx > 0) visualDiameterPx else min(width, height))
            .coerceAtMost(min(width, height))
            .toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (diameter / 2f - resources.displayMetrics.density).coerceAtLeast(1f)
        fillPaint.color = if (switching) Color.rgb(92, 92, 96) else Color.rgb(37, 99, 235)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        if (switching || subtypeLabel == null) {
            textPaint.textSize = if (targetLabel.length <= 2) diameter * 0.30f else diameter * 0.22f
            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(if (switching) "…" else targetLabel, centerX, baseline, textPaint)
            return
        }

        textPaint.textSize = if (targetLabel.length <= 2) diameter * 0.27f else diameter * 0.20f
        canvas.drawText(targetLabel, centerX, centerY - diameter * 0.04f, textPaint)
        textPaint.textSize = diameter * 0.18f
        canvas.drawText(subtypeLabel.orEmpty(), centerX, centerY + diameter * 0.25f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = windowX
                downWindowY = windowY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) dragging = true
                if (dragging) {
                    onDragTo?.invoke(downWindowX + dx.toInt(), downWindowY + dy.toInt())
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
