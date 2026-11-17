package com.gia.aicomposer.vision

/**
 * Fallback cuối cùng của bước tìm khung: chia ảnh thu nhỏ thành lưới ô và chọn ô
 * có phương sai độ sáng cao nhất (vùng "có gì đó" thay vì nền phẳng).
 * Trả toạ độ chuẩn hoá 0..1 theo ảnh đã xoay đúng chiều.
 */
internal object SaliencyFinder {

    private const val SMALL_WIDTH = 24
    private const val SMALL_HEIGHT = 32
    private const val BLOCK = 4

    fun brightestBusyCell(bitmap: android.graphics.Bitmap, rotationDegrees: Int): Pair<Float, Float>? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, SMALL_WIDTH, SMALL_HEIGHT, true)
        val pixels = IntArray(SMALL_WIDTH * SMALL_HEIGHT)
        small.getPixels(pixels, 0, SMALL_WIDTH, 0, 0, SMALL_WIDTH, SMALL_HEIGHT)
        if (small !== bitmap) small.recycle()

        var bestX = SMALL_WIDTH / 2
        var bestY = SMALL_HEIGHT / 2
        var bestScore = -1.0

        var blockY = 0
        while (blockY < SMALL_HEIGHT) {
            var blockX = 0
            while (blockX < SMALL_WIDTH) {
                var sum = 0.0
                var sumSq = 0.0
                var count = 0
                for (y in blockY until minOf(blockY + BLOCK, SMALL_HEIGHT)) {
                    for (x in blockX until minOf(blockX + BLOCK, SMALL_WIDTH)) {
                        val pixel = pixels[y * SMALL_WIDTH + x]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                        sum += luma
                        sumSq += luma * luma
                        count++
                    }
                }
                if (count > 0) {
                    val mean = sum / count
                    val variance = (sumSq / count) - mean * mean
                    if (variance > bestScore) {
                        bestScore = variance
                        bestX = blockX + BLOCK / 2
                        bestY = blockY + BLOCK / 2
                    }
                }
                blockX += BLOCK
            }
            blockY += BLOCK
        }

        var nx = (bestX.toFloat() / SMALL_WIDTH).coerceIn(0f, 1f)
        var ny = (bestY.toFloat() / SMALL_HEIGHT).coerceIn(0f, 1f)

        when (rotationDegrees % 360) {
            90 -> {
                val rotatedX = 1f - ny
                val rotatedY = nx
                nx = rotatedX
                ny = rotatedY
            }
            180 -> {
                nx = 1f - nx
                ny = 1f - ny
            }
            270 -> {
                val rotatedX = ny
                val rotatedY = 1f - nx
                nx = rotatedX
                ny = rotatedY
            }
            else -> Unit
        }
        return nx.coerceIn(0.2f, 0.8f) to ny.coerceIn(0.2f, 0.8f)
    }
}
