package com.gia.aicomposer.vision

import android.graphics.RectF
import com.gia.aicomposer.model.DepthBand
import com.gia.aicomposer.model.SceneObject

/**
 * Tìm vùng trống lớn nhất trong khung: rải lưới 8x12, đánh dấu ô bị vật thể gần/giữa che,
 * rồi tìm hình chữ nhật trống lớn nhất (thuật toán histogram + stack).
 * Vùng trống cao >= 0.5 khung là điều kiện cho dáng FULL_BODY.
 */
internal object FreeRegionFinder {

    private const val COLS = 8
    private const val ROWS = 12

    fun find(objects: List<SceneObject>): RectF {
        val occupied = Array(ROWS) { BooleanArray(COLS) }

        for (obj in objects) {
            if (obj.depth == DepthBand.FAR) continue
            val weight = if (obj.depth == DepthBand.NEAR) 1f else 0.6f
            if (obj.confidence * weight < 0.4f) continue
            for (row in 0 until ROWS) {
                val cy = (row + 0.5f) / ROWS
                for (col in 0 until COLS) {
                    val cx = (col + 0.5f) / COLS
                    if (obj.box.contains(cx, cy)) occupied[row][col] = true
                }
            }
        }

        val rect = largestRectangle(occupied)
        if (rect == null || rect.area <= 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            rect.left.toFloat() / COLS,
            rect.top.toFloat() / ROWS,
            rect.right.toFloat() / COLS,
            rect.bottom.toFloat() / ROWS,
        )
    }

    private data class GridRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Int get() = (right - left) * (bottom - top)
    }

    private fun largestRectangle(grid: Array<BooleanArray>): GridRect? {
        val cols = grid[0].size
        val heights = IntArray(cols)
        var best: GridRect? = null
        var bestArea = 0

        for (row in grid.indices) {
            for (col in 0 until cols) {
                heights[col] = if (grid[row][col]) 0 else heights[col] + 1
            }
            val stack = ArrayDeque<Int>()
            for (col in 0..cols) {
                val current = if (col == cols) 0 else heights[col]
                while (stack.isNotEmpty() && heights[stack.last()] > current) {
                    val height = heights[stack.removeLast()]
                    val start = if (stack.isEmpty()) 0 else stack.last() + 1
                    val area = height * (col - start)
                    if (area > bestArea && height > 0) {
                        bestArea = area
                        best = GridRect(start, row - height + 1, col, row + 1)
                    }
                }
                if (col < cols) stack.addLast(col)
            }
        }
        return best
    }
}
