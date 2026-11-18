package com.gia.aicomposer.util

import android.graphics.PointF
import android.graphics.RectF

/**
 * PreviewView dùng ScaleType.FIT_CENTER nên phần nội dung camera nằm giữa view,
 * có thể chừa viền đen hai bên hoặc trên dưới. Mọi toạ độ overlay 0..1 phải được
 * chiếu qua rect nội dung này để không bị lệch.
 */
object PreviewGeometry {

    fun contentRect(viewWidth: Int, viewHeight: Int, contentAspect: Float): RectF {
        if (viewWidth <= 0 || viewHeight <= 0 || contentAspect <= 0f) return RectF()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        return if (viewAspect > contentAspect) {
            val width = viewHeight * contentAspect
            val left = (viewWidth - width) / 2f
            RectF(left, 0f, left + width, viewHeight.toFloat())
        } else {
            val height = viewWidth / contentAspect
            val top = (viewHeight - height) / 2f
            RectF(0f, top, viewWidth.toFloat(), top + height)
        }
    }

    fun toView(point: PointF, content: RectF): PointF =
        PointF(
            content.left + point.x * content.width(),
            content.top + point.y * content.height(),
        )

    fun toNormalized(x: Float, y: Float, content: RectF): PointF {
        if (content.width() <= 0f || content.height() <= 0f) return PointF(0.5f, 0.5f)
        return PointF(
            ((x - content.left) / content.width()).coerceIn(-0.5f, 1.5f),
            ((y - content.top) / content.height()).coerceIn(-0.5f, 1.5f),
        )
    }

    /**
     * Lật một toạ độ chuẩn hoá quanh tâm 0.5 — dùng cho camera trước, vì preview đã bị lật ngang
     * còn khung phân tích thì không.
     */
    fun mirrorX(x: Float): Float = 1f - x

    /** Lật một toạ độ x **đã map sang view** quanh trục giữa của [content]. */
    fun mirrorXInView(x: Float, content: RectF): Float = content.left + content.right - x

    fun rectToView(rect: RectF, content: RectF): RectF = RectF(
        content.left + rect.left * content.width(),
        content.top + rect.top * content.height(),
        content.left + rect.right * content.width(),
        content.top + rect.bottom * content.height(),
    )

    /** Tỉ lệ aspect chuẩn hoá (width/height) cho một khung 3:4 thật trong ảnh. */
    fun normalizedAspect(pixelAspect: Float, targetAspect: Float): Float =
        targetAspect / pixelAspect
}
