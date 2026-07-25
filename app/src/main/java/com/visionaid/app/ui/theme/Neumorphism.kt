package com.visionaid.app.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies a raised Neumorphic shadow (outer drop shadow).
 * Equivalent to CSS: `box-shadow: 8px 8px 16px #d1d9e6, -8px -8px 16px #ffffff`
 */
fun Modifier.neoRaised(
    cornerRadius: Dp = 16.dp,
    blurRadius: Dp = 16.dp,
    offset: Dp = 8.dp,
    lightShadowColor: Color = NeoShadowLight,
    darkShadowColor: Color = NeoShadowDark
) = this.drawBehind {
    val cornerRadiusPx = cornerRadius.toPx()
    val blurRadiusPx = blurRadius.toPx()
    val offsetPx = offset.toPx()
    
    drawIntoCanvas { canvas ->
        if (size.width <= 0f || size.height <= 0f || size.width.isNaN() || size.height.isNaN()) return@drawIntoCanvas
        
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = android.graphics.Color.TRANSPARENT
        
        if (blurRadiusPx > 0) {
            frameworkPaint.maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }

        // Draw light shadow (top-left)
        paint.color = lightShadowColor
        canvas.translate(-offsetPx, -offsetPx)
        canvas.drawRoundRect(
            left = 0f, top = 0f, right = size.width, bottom = size.height,
            radiusX = cornerRadiusPx, radiusY = cornerRadiusPx, paint = paint
        )
        canvas.translate(offsetPx, offsetPx)

        // Draw dark shadow (bottom-right)
        paint.color = darkShadowColor
        canvas.translate(offsetPx, offsetPx)
        canvas.drawRoundRect(
            left = 0f, top = 0f, right = size.width, bottom = size.height,
            radiusX = cornerRadiusPx, radiusY = cornerRadiusPx, paint = paint
        )
        canvas.translate(-offsetPx, -offsetPx)
    }
}

/**
 * Applies a pressed Neumorphic shadow (inner drop shadow).
 * Equivalent to CSS: `box-shadow: inset 4px 4px 8px #d1d9e6, inset -4px -4px 8px #ffffff`
 */
fun Modifier.neoPressed(
    cornerRadius: Dp = 16.dp,
    blurRadius: Dp = 8.dp,
    offset: Dp = 4.dp,
    lightShadowColor: Color = NeoShadowLight,
    darkShadowColor: Color = NeoShadowDark
) = this.drawWithContent {
    drawContent()
    
    val cornerRadiusPx = cornerRadius.toPx()
    val blurRadiusPx = blurRadius.toPx()
    val offsetPx = offset.toPx()
    
    drawIntoCanvas { canvas ->
        if (size.width <= 0f || size.height <= 0f || size.width.isNaN() || size.height.isNaN()) return@drawIntoCanvas
        
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = android.graphics.Color.TRANSPARENT
        
        if (blurRadiusPx > 0) {
            frameworkPaint.maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }

        val strokePaint = Paint().apply {
            style = androidx.compose.ui.graphics.PaintingStyle.Stroke
            strokeWidth = blurRadiusPx * 2
        }
        val frameworkStrokePaint = strokePaint.asFrameworkPaint()
        if (blurRadiusPx > 0) {
            frameworkStrokePaint.maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
        }

        canvas.save()
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, size.width, size.height),
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            )
        }
        canvas.clipPath(path)

        // Draw light inset shadow
        strokePaint.color = lightShadowColor
        canvas.translate(offsetPx, offsetPx)
        canvas.drawPath(path, strokePaint)
        canvas.translate(-offsetPx, -offsetPx)

        // Draw dark inset shadow
        strokePaint.color = darkShadowColor
        canvas.translate(-offsetPx, -offsetPx)
        canvas.drawPath(path, strokePaint)
        canvas.translate(offsetPx, offsetPx)
        
        canvas.restore()
    }
}
