package com.gia.aicomposer.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.gia.aicomposer.R
import com.gia.aicomposer.pose.Joint
import com.gia.aicomposer.pose.PoseTemplate
import com.gia.aicomposer.util.PreviewGeometry
import kotlin.math.min

/**
 * Overlay vẽ trên PreviewView: hình que của dáng + khung 3:4 + vòng tròn tâm.
 * Đây là View phủ riêng nên không bao giờ nằm trong ảnh chụp
 * (Preview và ImageCapture là hai use case độc lập của CameraX).
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val accentColor = ContextCompat.getColor(context, R.color.guide_accent)
    private val alignedColor = ContextCompat.getColor(context, R.color.guide_accent_aligned)

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val posePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val cornerPath = Path()

    /** aspect (w/h) của nội dung camera hiển thị trong PreviewView FIT_CENTER. */
    var previewAspect: Float = 0.75f
        set(value) {
            field = value
            invalidate()
        }

    var pose: PoseTemplate? = null
        set(value) {
            field = value
            invalidate()
        }

    var showPose: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var frameCenter: PointF = PointF(0.5f, 0.5f)
        set(value) {
            field = PointF(value.x, value.y)
            invalidate()
        }

    var frameHeightFraction: Float = 0.76f
        set(value) {
            field = value
            invalidate()
        }

    var frameNormAspect: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    var showFrame: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showCenterTarget: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var aligned: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var holdProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val content = PreviewGeometry.contentRect(width, height, previewAspect)
        if (content.width() <= 0f) return

        val halfWidth = frameNormAspect * frameHeightFraction / 2f
        val halfHeight = frameHeightFraction / 2f
        val normalizedFrame = RectF(
            frameCenter.x - halfWidth,
            frameCenter.y - halfHeight,
            frameCenter.x + halfWidth,
            frameCenter.y + halfHeight,
        )
        val frame = PreviewGeometry.rectToView(normalizedFrame, content)

        if (showFrame) drawGuideFrame(canvas, frame)
        if (showPose) pose?.let { drawStickFigure(canvas, it, frame) }
        if (showCenterTarget) drawCenterTarget(canvas)
    }

    private fun drawGuideFrame(canvas: Canvas, frame: RectF) {
        framePaint.color = if (aligned) alignedColor else Color.WHITE
        framePaint.alpha = if (aligned) 235 else 170
        framePaint.pathEffect = null

        val corner = min(30f * density, min(frame.width(), frame.height()) * 0.20f)
        cornerPath.reset()
        // góc trên trái
        cornerPath.moveTo(frame.left, frame.top + corner)
        cornerPath.lineTo(frame.left, frame.top)
        cornerPath.lineTo(frame.left + corner, frame.top)
        // góc trên phải
        cornerPath.moveTo(frame.right - corner, frame.top)
        cornerPath.lineTo(frame.right, frame.top)
        cornerPath.lineTo(frame.right, frame.top + corner)
        // góc dưới phải
        cornerPath.moveTo(frame.right, frame.bottom - corner)
        cornerPath.lineTo(frame.right, frame.bottom)
        cornerPath.lineTo(frame.right - corner, frame.bottom)
        // góc dưới trái
        cornerPath.moveTo(frame.left + corner, frame.bottom)
        cornerPath.lineTo(frame.left, frame.bottom)
        cornerPath.lineTo(frame.left, frame.bottom - corner)
        canvas.drawPath(cornerPath, framePaint)
    }

    private fun drawStickFigure(canvas: Canvas, template: PoseTemplate, frame: RectF) {
        val availableHeight = frame.height() * 0.92f
        val availableWidth = frame.width() * 0.92f
        if (availableHeight <= 0f || availableWidth <= 0f) return

        var poseHeight = availableHeight
        var poseWidth = poseHeight * template.aspect
        if (poseWidth > availableWidth) {
            poseWidth = availableWidth
            poseHeight = poseWidth / template.aspect.coerceAtLeast(0.1f)
        }

        val left = frame.centerX() - poseWidth / 2f
        val top = frame.top + (frame.height() - poseHeight) / 2f
        // Chừa HEAD_ROOM phía trên để đầu không bị cắt.
        val headRoom = poseHeight * HEAD_ROOM
        val usableHeight = poseHeight - headRoom

        // Đổi keypoint 0..1 sang toạ độ view một lần, dùng cho cả xương, khớp và đầu.
        val viewPoints = template.joints.mapValues { (_, point) ->
            PointF(left + point.x * poseWidth, top + headRoom + point.y * usableHeight)
        }

        posePaint.color = accentColor
        posePaint.alpha = 235
        jointPaint.color = accentColor
        jointPaint.alpha = 235

        for ((start, end) in template.bones) {
            val startPoint = viewPoints[start] ?: continue
            val endPoint = viewPoints[end] ?: continue
            canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, posePaint)
        }

        for (viewPoint in viewPoints.values) {
            canvas.drawCircle(viewPoint.x, viewPoint.y, 2.6f * density, jointPaint)
        }

        // Đầu là hình tròn rỗng cho dễ nhìn.
        viewPoints[Joint.HEAD]?.let { head ->
            canvas.drawCircle(
                head.x,
                head.y,
                (poseWidth * 0.075f).coerceAtLeast(7f * density),
                posePaint,
            )
        }
    }

    private fun drawCenterTarget(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = 20f * density
        val toleranceRadius = 15f * density

        targetPaint.color = if (aligned) alignedColor else Color.WHITE
        targetPaint.alpha = if (aligned) 235 else 200
        targetPaint.pathEffect = null
        canvas.drawCircle(centerX, centerY, radius, targetPaint)

        targetPaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        targetPaint.alpha = 130
        canvas.drawCircle(centerX, centerY, toleranceRadius, targetPaint)
        targetPaint.pathEffect = null

        val tick = 7f * density
        canvas.drawLine(centerX - tick, centerY, centerX + tick, centerY, targetPaint)
        canvas.drawLine(centerX, centerY - tick, centerX, centerY + tick, targetPaint)

        if (holdProgress > 0f) {
            arcPaint.color = alignedColor
            val arcRect = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius,
            )
            canvas.drawArc(arcRect, -90f, 360f * holdProgress, false, arcPaint)
        }
    }

    companion object {
        private const val HEAD_ROOM = 0.06f
    }
}
