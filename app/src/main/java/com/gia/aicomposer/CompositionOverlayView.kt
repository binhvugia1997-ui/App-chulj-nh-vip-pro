package com.gia.aicomposer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.gia.aicomposer.util.PreviewGeometry
import kotlin.math.hypot

/**
 * Overlay ve tren preview: luoi 1/3, cham trang (muc tieu) va cham vang
 * (vi tri chu the do tracker uoc luong). Cham vao man hinh de doi vi tri
 * cham trang muc tieu.
 *
 * Toa do chuan hoa 0..1 theo khung hinh preview (sau khi xoay ve dung).
 * PreviewView dung FIT_CENTER nen phai tinh hinh chu nhat letterbox that
 * cua preview de ve dung vi tri.
 */
class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Diem muc tieu (toa do chuan hoa). Mac dinh: giao diem 1/3 tren-phai. */
    var targetPoint: Pair<Float, Float> = Pair(2f / 3f, 1f / 3f)

    /** Diem chu the do tracker uoc luong (toa do chuan hoa). */
    var subjectPoint: Pair<Float, Float> = Pair(0.5f, 0.5f)

    var locked: Boolean = false

    /** Ti le khung hinh (rong/cao) cua luong preview, dung de tinh letterbox. */
    var previewAspectRatio: Float = 4f / 3f

    /**
     * Camera truoc: preview bi lat ngang con khung phan tich thi khong. Diem chuan hoa van luu
     * theo khong gian camera (khong mirror), chi luc ve moi lat quanh truc giua cua vung preview.
     */
    var mirrorHorizontally: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onTargetMoved: (() -> Unit)? = null

    private val gridPaint = Paint().apply {
        color = Color.WHITE
        alpha = 130
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val targetPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val targetDotPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val subjectPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        color = Color.YELLOW
        alpha = 170
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val lockedPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
    }

    /** Hinh chu nhat (left, top, right, bottom) noi preview that su ve. */
    fun previewRect(): FloatArray {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return floatArrayOf(0f, 0f, vw, vh)
        val viewAspect = vw / vh
        return if (viewAspect > previewAspectRatio) {
            // View rong hon preview: pillarbox 2 ben.
            val w = vh * previewAspectRatio
            val left = (vw - w) / 2f
            floatArrayOf(left, 0f, left + w, vh)
        } else {
            // View cao hon preview: letterbox tren duoi.
            val h = vw / previewAspectRatio
            val top = (vh - h) / 2f
            floatArrayOf(0f, top, vw, top + h)
        }
    }

    private fun toViewX(fx: Float, rect: FloatArray): Float = rect[0] + fx * (rect[2] - rect[0])
    private fun toViewY(fy: Float, rect: FloatArray): Float = rect[1] + fy * (rect[3] - rect[1])

    fun distanceToTarget(): Float {
        val dx = subjectPoint.first - targetPoint.first
        val dy = subjectPoint.second - targetPoint.second
        return hypot(dx, dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = previewRect()
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val w = right - left
        val h = bottom - top

        // Luoi 1/3.
        val x1 = left + w / 3f
        val x2 = left + 2f * w / 3f
        val y1 = top + h / 3f
        val y2 = top + 2f * h / 3f
        canvas.drawLine(x1, top, x1, bottom, gridPaint)
        canvas.drawLine(x2, top, x2, bottom, gridPaint)
        canvas.drawLine(left, y1, right, y1, gridPaint)
        canvas.drawLine(left, y2, right, y2, gridPaint)

        val axisSum = left + right

        // Cham trang muc tieu.
        val tx = toViewX(targetPoint.first, rect).let { if (mirrorHorizontally) axisSum - it else it }
        val ty = toViewY(targetPoint.second, rect)
        canvas.drawCircle(tx, ty, 46f, if (locked) lockedPaint else targetPaint)
        canvas.drawCircle(tx, ty, 10f, targetDotPaint)

        // Cham vang chu the + duong noi huong dan.
        val sx = toViewX(subjectPoint.first, rect).let { if (mirrorHorizontally) axisSum - it else it }
        val sy = toViewY(subjectPoint.second, rect)
        canvas.drawLine(sx, sy, tx, ty, linePaint)
        canvas.drawCircle(sx, sy, 22f, subjectPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val rect = previewRect()
            val fx = ((event.x - rect[0]) / (rect[2] - rect[0])).coerceIn(0f, 1f)
            val fy = ((event.y - rect[1]) / (rect[3] - rect[1])).coerceIn(0f, 1f)
            // Diem cham luu theo khong gian camera nen phai lat lai cho khop hinh dang thay.
            targetPoint = Pair(if (mirrorHorizontally) PreviewGeometry.mirrorX(fx) else fx, fy)
            locked = false
            onTargetMoved?.invoke()
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }
}
