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
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.gia.aicomposer.R
import com.gia.aicomposer.pipeline.CapturePipeline
import com.gia.aicomposer.pose.Joint
import com.gia.aicomposer.pose.PoseTemplate
import com.gia.aicomposer.util.PreviewGeometry
import kotlin.math.hypot
import kotlin.math.min

/**
 * Overlay duy nhất trên preview, vẽ trong một `onDraw` (dưới → trên):
 * lưới 1/3 → khung 3:4 → hình que → vòng tâm + cung giữ → đường nối + chấm vàng → chấm trắng.
 *
 * Gộp `CompositionOverlayView` (lưới, chấm trắng mục tiêu, chấm vàng chủ thể) và
 * `PoseOverlayView` (khung 3:4, hình que, vòng tâm) vì hai view trước đây tính vùng preview theo
 * hai quy ước aspect khác nhau nên chấm vàng và khung 3:4 lệch nhau, và mỗi frame bị
 * `invalidate()` hai lần. Giờ chỉ còn [previewAspect] và [PreviewGeometry].
 *
 * Toạ độ chuẩn hoá 0..1 theo vùng nội dung camera (đã trừ letterbox/pillarbox của FIT_CENTER).
 * View phủ nên không bao giờ nằm trong ảnh chụp (Preview và ImageCapture là 2 use case riêng).
 */
class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val poseColor = ContextCompat.getColor(context, R.color.guide_accent)
    private val alignedColor = ContextCompat.getColor(context, R.color.guide_accent_aligned)

    /** aspect (rộng/cao) của nội dung camera hiển thị trong PreviewView FIT_CENTER. */
    var previewAspect: Float = 0.75f

    /** Điểm mục tiêu (chạm để đổi). Mặc định: giao điểm 1/3 trên-phải. */
    var targetPoint: Pair<Float, Float> = Pair(2f / 3f, 1f / 3f)

    /** Vị trí chủ thể do SubjectTracker ước lượng. */
    var subjectPoint: Pair<Float, Float> = Pair(0.5f, 0.5f)

    /** Đã giữ đủ lâu và chụp: chấm mục tiêu chuyển vành xanh. */
    var locked: Boolean = false

    /**
     * Camera trước: PreviewView tự lật hình còn khung phân tích thì không, nên mọi điểm vẽ phải
     * lật lại quanh trục giữa của vùng nội dung.
     */
    var mirrorHorizontally: Boolean = false

    var pose: PoseTemplate? = null

    var showPose: Boolean = true

    /** Khung 3:4 chỉ có nghĩa khi đang có hình que, nên suy ra thay vì giữ cờ riêng. */
    val showFrame: Boolean
        get() = showPose && pose != null

    var frameCenter: PointF = PointF(0.5f, 0.5f)

    var frameHeightFraction: Float = CapturePipeline.FRAME_HEIGHT_FRACTION

    var frameNormAspect: Float = CapturePipeline.FRAME_NORM_ASPECT

    var showCenterTarget: Boolean = false

    var aligned: Boolean = false

    var holdProgress: Float = 0f

    var onTargetMoved: (() -> Unit)? = null

    // Chấm/vòng của lớp chấm vàng-trắng giữ đơn vị px như bản cũ; phần hình que theo dp.
    private val gridPaint = Paint().apply {
        color = Color.WHITE
        alpha = 60
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val accentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        alpha = 190
    }
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    /** Vòng tròn trắng/xanh dùng chung cho chấm mục tiêu và vòng tâm. */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = alignedColor
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val cornerPath = Path()

    /** Hình chữ nhật nội dung camera thật sự vẽ trong view. */
    fun contentRect(): RectF = PreviewGeometry.contentRect(width, height, previewAspect)

    /** Khoảng cách chuẩn hoá giữa chấm vàng và chấm trắng; ngưỡng tự chụp so với giá trị này. */
    fun distanceToTarget(): Float {
        val dx = orientedX(subjectPoint.first) - targetPoint.first
        val dy = subjectPoint.second - targetPoint.second
        return hypot(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val content = contentRect()
        if (content.width() <= 0f) return

        drawGrid(canvas, content)
        if (showFrame) {
            val frame = frameRect(content)
            drawGuideFrame(canvas, frame)
            pose?.let { drawStickFigure(canvas, it, frame) }
        }
        if (showCenterTarget) drawCenterTarget(canvas)
        drawTracking(canvas, content)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val content = contentRect()
            if (content.width() <= 0f) return false
            val point = PreviewGeometry.toNormalized(event.x, event.y, content)
            targetPoint = Pair(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
            locked = false
            onTargetMoved?.invoke()
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ------------------------------------------------------------ các lớp vẽ

    private fun drawGrid(canvas: Canvas, content: RectF) {
        val x1 = content.left + content.width() / 3f
        val x2 = content.left + 2f * content.width() / 3f
        val y1 = content.top + content.height() / 3f
        val y2 = content.top + 2f * content.height() / 3f
        canvas.drawLine(x1, content.top, x1, content.bottom, gridPaint)
        canvas.drawLine(x2, content.top, x2, content.bottom, gridPaint)
        canvas.drawLine(content.left, y1, content.right, y1, gridPaint)
        canvas.drawLine(content.left, y2, content.right, y2, gridPaint)
    }

    private fun frameRect(content: RectF): RectF {
        val halfWidth = frameNormAspect * frameHeightFraction / 2f
        val halfHeight = frameHeightFraction / 2f
        // Lật toạ độ chuẩn hoá quanh 0.5 cho cùng kết quả với lật toạ độ view quanh contentRect.
        val centerX = orientedX(frameCenter.x)
        return PreviewGeometry.rectToView(
            RectF(
                centerX - halfWidth,
                frameCenter.y - halfHeight,
                centerX + halfWidth,
                frameCenter.y + halfHeight,
            ),
            content,
        )
    }

    private fun drawGuideFrame(canvas: Canvas, frame: RectF) {
        framePaint.color = if (aligned) alignedColor else Color.WHITE
        framePaint.alpha = if (aligned) 235 else 170

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

        posePaint.color = poseColor
        posePaint.alpha = 235
        jointPaint.color = poseColor
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

        ringPaint.color = if (aligned) alignedColor else Color.WHITE
        ringPaint.alpha = if (aligned) 235 else 200
        ringPaint.strokeWidth = 2f * density
        ringPaint.pathEffect = null
        canvas.drawCircle(centerX, centerY, radius, ringPaint)

        ringPaint.pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
        ringPaint.alpha = 130
        canvas.drawCircle(centerX, centerY, 15f * density, ringPaint)
        ringPaint.pathEffect = null

        val tick = 7f * density
        canvas.drawLine(centerX - tick, centerY, centerX + tick, centerY, ringPaint)
        canvas.drawLine(centerX, centerY - tick, centerX, centerY + tick, ringPaint)

        if (holdProgress > 0f) {
            canvas.drawArc(
                RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                -90f,
                360f * holdProgress.coerceIn(0f, 1f),
                false,
                arcPaint,
            )
        }
    }

    private fun drawTracking(canvas: Canvas, content: RectF) {
        val target = PreviewGeometry.toView(PointF(targetPoint.first, targetPoint.second), content)
        val subject = PreviewGeometry.toView(
            PointF(orientedX(subjectPoint.first), subjectPoint.second),
            content,
        )

        accentLinePaint.color = accentColor
        subjectPaint.color = accentColor
        canvas.drawLine(subject.x, subject.y, target.x, target.y, accentLinePaint)
        canvas.drawCircle(subject.x, subject.y, SUBJECT_DOT_RADIUS_PX, subjectPaint)

        ringPaint.color = if (locked) alignedColor else Color.WHITE
        ringPaint.strokeWidth = if (locked) LOCKED_RING_STROKE_PX else TARGET_RING_STROKE_PX
        canvas.drawCircle(target.x, target.y, TARGET_RING_RADIUS_PX, ringPaint)
        canvas.drawCircle(target.x, target.y, TARGET_DOT_RADIUS_PX, dotPaint)
    }

    private fun orientedX(x: Float): Float =
        if (mirrorHorizontally) PreviewGeometry.mirrorX(x) else x

    companion object {
        private const val HEAD_ROOM = 0.06f

        private const val TARGET_RING_RADIUS_PX = 46f
        private const val TARGET_RING_STROKE_PX = 4f
        private const val LOCKED_RING_STROKE_PX = 6f
        private const val TARGET_DOT_RADIUS_PX = 10f
        private const val SUBJECT_DOT_RADIUS_PX = 22f
    }
}
