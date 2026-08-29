package com.adam.app_monitoring.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.util.DisplayMetrics
import com.adam.app_monitoring.core.util.SpeedIconText

/**
 * Draws a monochrome notification small icon with the current speed and unit.
 * ALPHA_8 lets System UI tint it like a regular status-bar icon.
 */
internal class StatusBarSpeedIconRenderer(context: Context) {
    private val iconSize = iconSizeForDensity(context.resources.displayMetrics.densityDpi)
    private val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(bitmap)
    private val centerX = bitmap.width / 2f

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = bitmap.height * VALUE_AREA_RATIO * TEXT_SIZE_MULTIPLIER
    }
    private val valueBaseline = run {
        val textHeight = kotlin.math.abs(valuePaint.ascent() + valuePaint.descent())
        kotlin.math.abs((bitmap.height * VALUE_AREA_RATIO - textHeight) / 3f) + textHeight
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = bitmap.height * (1f - VALUE_AREA_RATIO) * TEXT_SIZE_MULTIPLIER
    }
    private val unitBaseline = run {
        val unitAreaHeight = bitmap.height * (1f - VALUE_AREA_RATIO)
        val textHeight = kotlin.math.abs(unitPaint.ascent() + unitPaint.descent())
        bitmap.height - kotlin.math.abs((unitAreaHeight - textHeight) / 4f)
    }

    @Synchronized
    fun create(text: SpeedIconText): Icon {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        valuePaint.textScaleX = if (text.value.length == 3) 0.75f else 0.9f
        canvas.drawText(text.value, centerX, valueBaseline, valuePaint)

        unitPaint.textScaleX = if (text.unit.length <= 3) 1.05f else 0.9f
        canvas.drawText(text.unit, centerX, unitBaseline, unitPaint)

        return Icon.createWithBitmap(bitmap)
    }

    private companion object {
        const val VALUE_AREA_RATIO = 0.65f
        const val TEXT_SIZE_MULTIPLIER = 1.2f

        fun iconSizeForDensity(densityDpi: Int): Int = when (densityDpi) {
            DisplayMetrics.DENSITY_LOW,
            DisplayMetrics.DENSITY_MEDIUM -> 24
            DisplayMetrics.DENSITY_HIGH -> 36
            DisplayMetrics.DENSITY_XHIGH -> 48
            DisplayMetrics.DENSITY_XXHIGH -> 72
            DisplayMetrics.DENSITY_XXXHIGH -> 96
            else -> 72
        }
    }
}
