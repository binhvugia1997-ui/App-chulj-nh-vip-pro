package com.gia.aicomposer.util

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * ImageAnalysis được cấu hình OUTPUT_IMAGE_FORMAT_RGBA_8888 nên plane 0 đã là RGBA,
 * không cần chuyển YUV. Hàm tự dựng Bitmap để không phụ thuộc extension của CameraX.
 */
fun ImageProxy.toArgbBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val imageWidth = width
    val imageHeight = height

    val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
    val row = ByteArray(rowStride)
    val pixels = IntArray(imageWidth * imageHeight)

    buffer.rewind()
    for (y in 0 until imageHeight) {
        val remaining = buffer.remaining()
        if (remaining <= 0) break
        val toRead = minOf(remaining, rowStride)
        buffer.get(row, 0, toRead)
        var x = 0
        while (x < imageWidth) {
            val index = x * pixelStride
            if (index + 3 < toRead) {
                val r = row[index].toInt() and 0xFF
                val g = row[index + 1].toInt() and 0xFF
                val b = row[index + 2].toInt() and 0xFF
                val a = row[index + 3].toInt() and 0xFF
                pixels[y * imageWidth + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            x++
        }
    }
    bitmap.setPixels(pixels, 0, imageWidth, 0, 0, imageWidth, imageHeight)
    return bitmap
}

/** Kích thước ảnh sau khi MediaPipe/ML Kit xoay theo rotationDegrees. */
fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees % 180 == 0) width to height else height to width
