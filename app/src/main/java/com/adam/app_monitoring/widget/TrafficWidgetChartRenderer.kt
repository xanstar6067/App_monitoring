package com.adam.app_monitoring.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.adam.app_monitoring.core.model.ChartPoint
import java.time.LocalTime
import kotlin.math.max

internal object TrafficWidgetChartRenderer {
    private const val WIDTH = 720
    private const val WEEK_HEIGHT = 120
    private const val HOURLY_HEIGHT = 86

    private val accent = Color.rgb(61, 245, 194)
    private val blue = Color.rgb(91, 158, 245)
    private val red = Color.rgb(245, 91, 91)
    private val muted = Color.rgb(72, 82, 101)
    private val label = Color.rgb(133, 145, 166)

    fun weekly(points: List<ChartPoint>): Bitmap {
        val visible = points.takeLast(7)
        val bitmap = Bitmap.createBitmap(WIDTH, WEEK_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baselineY = 92f
        val chartTop = 6f
        val values = visible.map { it.wifiBytes + it.mobileBytes }
        val maxValue = max(1L, values.maxOrNull() ?: 0L)
        val slotCount = max(visible.size, 7)
        val slotOffset = slotCount - visible.size
        val slotWidth = WIDTH.toFloat() / slotCount
        val barWidth = slotWidth * 0.48f
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = label
            textSize = 18f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawLine(
            0f,
            baselineY,
            WIDTH.toFloat(),
            baselineY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 124, 138, 165)
                strokeWidth = 1f
            }
        )
        visible.forEachIndexed { index, point ->
            val value = values[index]
            val height = if (value <= 0) 3f else {
                ((value.toDouble() / maxValue.toDouble()) * (baselineY - chartTop))
                    .toFloat()
                    .coerceAtLeast(5f)
            }
            val centerX = slotWidth * (slotOffset + index) + slotWidth / 2f
            barPaint.color = when {
                index == visible.lastIndex -> accent
                value == maxValue -> red
                else -> Color.argb(115, 61, 245, 194)
            }
            canvas.drawRoundRect(
                centerX - barWidth / 2f,
                baselineY - height,
                centerX + barWidth / 2f,
                baselineY,
                6f,
                6f,
                barPaint
            )
            labelPaint.color = if (index == visible.lastIndex) accent else label
            canvas.drawText(point.label, centerX, 116f, labelPaint)
        }
        if (visible.isEmpty()) drawEmptyState(canvas, baselineY)
        return bitmap
    }

    fun hourly(points: List<ChartPoint>): Bitmap {
        val valuesByHour = points.associateBy { it.label.toIntOrNull() ?: -1 }
        val values = (0..23).map { hour ->
            valuesByHour[hour]?.let { it.wifiBytes + it.mobileBytes } ?: 0L
        }
        val bitmap = Bitmap.createBitmap(WIDTH, HOURLY_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baselineY = 82f
        val maxValue = max(1L, values.maxOrNull() ?: 0L)
        val currentHour = LocalTime.now().hour
        val gap = 5f
        val barWidth = (WIDTH - gap * 23) / 24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        values.forEachIndexed { index, value ->
            val height = if (value <= 0) 3f else {
                ((value.toDouble() / maxValue.toDouble()) * 76.0)
                    .toFloat()
                    .coerceAtLeast(4f)
            }
            paint.color = when {
                index > currentHour -> Color.argb(170, 72, 82, 101)
                index == values.indexOf(maxValue) -> Color.argb(205, 91, 158, 245)
                index == values.indexOfLast { it > 0L } -> blue
                value <= 0 -> Color.argb(55, 91, 158, 245)
                else -> Color.argb(82, 91, 158, 245)
            }
            val left = index * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                baselineY - height,
                left + barWidth,
                baselineY,
                4f,
                4f,
                paint
            )
        }
        if (points.isEmpty()) drawEmptyState(canvas, baselineY)
        return bitmap
    }

    private fun drawEmptyState(canvas: Canvas, baselineY: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            strokeWidth = 2f
        }
        canvas.drawLine(0f, baselineY, WIDTH.toFloat(), baselineY, paint)
    }
}
