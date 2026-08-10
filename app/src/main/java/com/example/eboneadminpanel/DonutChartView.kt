package com.example.eboneadminpanel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Segment(val value: Float, val color: Int)

    private var segments: List<Segment> = emptyList()
    private var centerText: String = ""

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E3E8F5")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111111")
        textAlign = Paint.Align.CENTER
    }

    fun setData(segments: List<Segment>, centerText: String) {
        this.segments = segments
        this.centerText = centerText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val strokeWidth = width * 0.16f
        arcPaint.strokeWidth = strokeWidth
        trackPaint.strokeWidth = strokeWidth
        textPaint.textSize = width * 0.16f

        val inset = strokeWidth / 2
        val rect = RectF(inset, inset, width - inset, height - inset)

        canvas.drawOval(rect, trackPaint)

        val total = segments.sumOf { it.value.toDouble() }.toFloat()
        if (total > 0) {
            var startAngle = -90f
            for (segment in segments) {
                val sweep = (segment.value / total) * 360f
                arcPaint.color = segment.color
                canvas.drawArc(rect, startAngle, sweep, false, arcPaint)
                startAngle += sweep
            }
        }

        canvas.drawText(centerText, width / 2f, height / 2f + textPaint.textSize / 3, textPaint)
    }
}