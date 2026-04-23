package com.ek.blectr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMove(normalizedX: Float, normalizedY: Float)
        fun onRelease()
    }

    var listener: Listener? = null

    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        color = Color.parseColor("#44EE781F")
    }

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#B3FFD3B0")
    }

    private val outerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33194779")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#44194779")
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#66EE781F")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#66194779")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.parseColor("#88EE781F")
    }

    private val knobShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#7A000000")
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2F8FAFC")
    }

    private val knobHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#99FFFFFF")
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEE781F")
    }

    private val centerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#D9FFD3B0")
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f
    private var knobX = 0f
    private var knobY = 0f
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) * 0.40f
        knobRadius = baseRadius * 0.38f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawCircle(centerX, centerY, baseRadius * 1.04f, outerGlowPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, outerFillPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, outerPaint)
        canvas.drawCircle(centerX, centerY, baseRadius * 0.72f, ringPaint)
        canvas.drawCircle(centerX, centerY, baseRadius * 0.46f, ringPaint)

        canvas.drawLine(centerX - baseRadius * 0.62f, centerY, centerX + baseRadius * 0.62f, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - baseRadius * 0.62f, centerX, centerY + baseRadius * 0.62f, gridPaint)
        canvas.drawLine(centerX - baseRadius * 0.1f, centerY - baseRadius * 0.92f, centerX + baseRadius * 0.1f, centerY - baseRadius * 0.92f, tickPaint)
        canvas.drawLine(centerX - baseRadius * 0.1f, centerY + baseRadius * 0.92f, centerX + baseRadius * 0.1f, centerY + baseRadius * 0.92f, tickPaint)
        canvas.drawLine(centerX - baseRadius * 0.92f, centerY - baseRadius * 0.1f, centerX - baseRadius * 0.92f, centerY + baseRadius * 0.1f, tickPaint)
        canvas.drawLine(centerX + baseRadius * 0.92f, centerY - baseRadius * 0.1f, centerX + baseRadius * 0.92f, centerY + baseRadius * 0.1f, tickPaint)

        canvas.drawArc(
            centerX - baseRadius * 0.78f,
            centerY - baseRadius * 0.78f,
            centerX + baseRadius * 0.78f,
            centerY + baseRadius * 0.78f,
            45f,
            90f,
            false,
            axisPaint
        )

        canvas.drawArc(
            centerX - baseRadius * 0.78f,
            centerY - baseRadius * 0.78f,
            centerX + baseRadius * 0.78f,
            centerY + baseRadius * 0.78f,
            225f,
            90f,
            false,
            axisPaint
        )

        canvas.drawCircle(knobX + dp(2f), knobY + dp(4f), knobRadius * 1.02f, knobShadowPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX - knobRadius * 0.22f, knobY - knobRadius * 0.22f, knobRadius * 0.42f, knobHighlightPaint)
        canvas.drawCircle(centerX, centerY, knobRadius * 0.18f, centerDotPaint)
        canvas.drawCircle(centerX, centerY, knobRadius * 0.18f, centerStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateKnob(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                listener?.onMove(normalizedX(), normalizedY())
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
        val dx = x - centerX
        val dy = y - centerY
        val distance = hypot(dx, dy)
        val maxDistance = baseRadius - knobRadius
        if (distance > maxDistance && distance > 0f) {
            val scale = maxDistance / distance
            knobX = centerX + dx * scale
            knobY = centerY + dy * scale
        } else {
            knobX = x
            knobY = y
        }
    }

    private fun resetKnob() {
        knobX = centerX
        knobY = centerY
    }

    private fun normalizedX(): Float {
        return if (baseRadius <= 0f) 0f else ((knobX - centerX) / (baseRadius - knobRadius)).coerceIn(-1f, 1f)
    }

    private fun normalizedY(): Float {
        return if (baseRadius <= 0f) 0f else ((knobY - centerY) / (baseRadius - knobRadius)).coerceIn(-1f, 1f)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
