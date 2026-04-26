package com.ek.blectr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class ThrottleSteeringView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMove(normalizedSteering: Float, normalizedThrottle: Float)
        fun onRelease()
    }

    var listener: Listener? = null

    private val outerRect = RectF()
    private val knobRect = RectF()

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#40194779")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = Color.parseColor("#44EE781F")
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#B3FFD3B0")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#55194779")
    }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#88EE781F")
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#55194779")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#77EE781F")
    }

    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66000000")
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F0F8FAFC")
    }

    private val knobHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88FFFFFF")
    }

    private var centerX = 0f
    private var centerY = 0f
    private var knobCenterX = 0f
    private var knobCenterY = 0f
    private var maxOffsetX = 0f
    private var maxOffsetY = 0f
    private var knobWidth = 0f
    private var knobHeight = 0f
    private var cornerRadius = 0f
    private var targetKnobY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val inset = dp(10f)
        outerRect.set(inset, inset, w - inset, h - inset)
        centerX = outerRect.centerX()
        centerY = outerRect.centerY()
        knobWidth = outerRect.width() * 0.44f
        knobHeight = min(outerRect.height() * 0.18f, dp(68f))
        maxOffsetX = (outerRect.width() - knobWidth) / 2f - dp(10f)
        maxOffsetY = (outerRect.height() - knobHeight) / 2f - dp(14f)
        cornerRadius = dp(28f)
        targetKnobY = centerY
        resetKnob()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, glowPaint)
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, shellPaint)
        canvas.drawRoundRect(outerRect, cornerRadius, cornerRadius, strokePaint)
        canvas.drawRoundRect(
            outerRect.left + dp(12f),
            outerRect.top + dp(12f),
            outerRect.right - dp(12f),
            outerRect.bottom - dp(12f),
            dp(20f),
            dp(20f),
            framePaint,
        )

        canvas.drawLine(centerX, outerRect.top + dp(18f), centerX, outerRect.bottom - dp(18f), accentPaint)
        canvas.drawLine(outerRect.left + dp(18f), centerY, outerRect.right - dp(18f), centerY, gridPaint)

        val guideTop = outerRect.top + outerRect.height() * 0.25f
        val guideBottom = outerRect.top + outerRect.height() * 0.75f
        canvas.drawLine(outerRect.left + dp(28f), guideTop, outerRect.right - dp(28f), guideTop, gridPaint)
        canvas.drawLine(outerRect.left + dp(28f), guideBottom, outerRect.right - dp(28f), guideBottom, gridPaint)
        canvas.drawLine(centerX - dp(12f), outerRect.top + dp(12f), centerX + dp(12f), outerRect.top + dp(12f), tickPaint)
        canvas.drawLine(centerX - dp(12f), outerRect.bottom - dp(12f), centerX + dp(12f), outerRect.bottom - dp(12f), tickPaint)
        canvas.drawLine(outerRect.left + dp(12f), centerY - dp(12f), outerRect.left + dp(12f), centerY + dp(12f), tickPaint)
        canvas.drawLine(outerRect.right - dp(12f), centerY - dp(12f), outerRect.right - dp(12f), centerY + dp(12f), tickPaint)

        val steps = 5
        val tickStepY = maxOffsetY / steps
        for (i in 1..steps) {
            val yAbove = centerY - tickStepY * i
            val yBelow = centerY + tickStepY * i
            val tickW = if (i % 2 == 0) dp(14f) else dp(10f)
            canvas.drawLine(centerX - tickW, yAbove, centerX + tickW, yAbove, tickPaint)
            canvas.drawLine(centerX - tickW, yBelow, centerX + tickW, yBelow, tickPaint)
        }

        updateKnobRect()
        canvas.drawRoundRect(
            knobRect.left + dp(2f),
            knobRect.top + dp(4f),
            knobRect.right + dp(2f),
            knobRect.bottom + dp(4f),
            dp(18f),
            dp(18f),
            knobShadowPaint,
        )
        canvas.drawRoundRect(knobRect, dp(18f), dp(18f), knobPaint)
        canvas.drawRoundRect(
            knobRect.left + dp(10f),
            knobRect.top + dp(8f),
            knobRect.right - dp(10f),
            knobRect.top + dp(18f),
            dp(8f),
            dp(8f),
            knobHighlightPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateKnob(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                listener?.onMove(normalizedSteering(), normalizedThrottle())
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetKnob()
                invalidate()
                listener?.onRelease()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnob(x: Float, y: Float) {
        knobCenterX = x.coerceIn(centerX - maxOffsetX, centerX + maxOffsetX)
        targetKnobY = y.coerceIn(centerY - maxOffsetY, centerY + maxOffsetY)
        knobCenterY += (targetKnobY - knobCenterY) * 0.38f
    }

    private fun resetKnob() {
        knobCenterX = centerX
        targetKnobY = knobCenterY
    }

    private fun updateKnobRect() {
        knobRect.set(
            knobCenterX - knobWidth / 2f,
            knobCenterY - knobHeight / 2f,
            knobCenterX + knobWidth / 2f,
            knobCenterY + knobHeight / 2f,
        )
    }

    private fun normalizedSteering(): Float {
        return if (maxOffsetX <= 0f) 0f else ((knobCenterX - centerX) / maxOffsetX).coerceIn(-1f, 1f)
    }

    private fun normalizedThrottle(): Float {
        return if (maxOffsetY <= 0f) 0f else (-(knobCenterY - centerY) / maxOffsetY).coerceIn(-1f, 1f)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
