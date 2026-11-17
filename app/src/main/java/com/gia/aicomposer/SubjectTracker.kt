package com.gia.aicomposer

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Heuristic v0.1 — KHONG phai model AI that.
 *
 * Uoc luong "diem chu the" tu mat phang do sang (Y) cua khung hinh:
 * chia anh thanh luoi 32x18, tinh do sang trung binh tung o, lay trong tam
 * co trong so theo do tuong phan (|do sang o - do sang trung binh khung|).
 * Chu the (nguoi/vat) thuong tuong phan manh hon nen hinh nen, nen trong
 * tam nay thuong roi gan chu the. Lam muot bang EMA de cham vang khong giat.
 *
 * GIOI HAN v0.1:
 * - Chi la heuristic do sang/tuong phan, khong hieu noi dung anh. Nen
 *   nhieu chi tiet hoac nen sang toi manh co the keo cham vang lech.
 * - Se thay bang model that sau: ML Kit Object Detection hoac model
 *   TFLite (phan doan/vat the) chay tren ImageAnalysis.
 *
 * Tra ve toa do chuan hoa 0..1 theo khung hinh SAU KHI XOAY ve dung
 * (x: trai->phai, y: tren->duoi), hoac null neu khong du tuong phan.
 */
class SubjectTracker {

    private var smoothX = 0.5f
    private var smoothY = 0.4f
    private var initialized = false

    fun analyze(image: ImageProxy): Pair<Float, Float>? {
        val yPlane = image.planes[0]
        val buffer: ByteBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees

        val cols = 32
        val rows = 18
        val cellW = width / cols
        val cellH = height / rows
        if (cellW < 2 || cellH < 2) return null

        // Do sang trung binh tung o (lay mau cach 2 pixel cho nhanh).
        val cellMean = FloatArray(cols * rows)
        var frameSum = 0f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var sum = 0
                var n = 0
                var y = r * cellH
                while (y < (r + 1) * cellH) {
                    var x = c * cellW
                    while (x < (c + 1) * cellW) {
                        val idx = y * rowStride + x
                        if (idx < buffer.capacity()) {
                            sum += buffer.get(idx).toInt() and 0xFF
                            n++
                        }
                        x += 2
                    }
                    y += 2
                }
                val mean = if (n > 0) sum.toFloat() / n else 0f
                cellMean[r * cols + c] = mean
                frameSum += mean
            }
        }
        val frameMean = frameSum / (cols * rows)

        // Trong tam co trong so theo binh phuong do tuong phan.
        var wSum = 0f
        var cx = 0f
        var cy = 0f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val contrast = abs(cellMean[r * cols + c] - frameMean)
                val w = contrast * contrast
                wSum += w
                cx += w * (c + 0.5f) / cols
                cy += w * (r + 0.5f) / rows
            }
        }
        if (wSum <= 0f) return null
        val un = cx / wSum // toa do sensor: 0..1 theo chieu ngang buffer
        val vn = cy / wSum // toa do sensor: 0..1 theo chieu doc buffer

        // Xoay toa do sensor ve khung hinh hien thi dung.
        val (dx, dy) = when (rotation) {
            90 -> Pair(1f - vn, un)
            180 -> Pair(1f - un, 1f - vn)
            270 -> Pair(vn, 1f - un)
            else -> Pair(un, vn)
        }

        // Lam muot EMA.
        if (!initialized) {
            smoothX = dx
            smoothY = dy
            initialized = true
        } else {
            smoothX = 0.7f * smoothX + 0.3f * dx
            smoothY = 0.7f * smoothY + 0.3f * dy
        }
        return Pair(smoothX, smoothY)
    }
}
