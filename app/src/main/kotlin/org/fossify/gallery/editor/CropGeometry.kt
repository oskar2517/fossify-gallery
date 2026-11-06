package org.fossify.gallery.editor

import kotlin.math.min
import kotlin.math.roundToInt

object CropGeometry {
    /** Use the cropper's floating-point corners to avoid losing pixels to preview rounding. */
    fun fromPreview(
        size: EditSize,
        previewSize: EditSize,
        points: List<EditPoint>,
        aspectRatio: Pair<Int, Int>? = null
    ): EditOperation.Crop {
        require(points.isNotEmpty())
        val left = points.minOf { it.x }.toDouble() * size.width / previewSize.width
        val right = points.maxOf { it.x }.toDouble() * size.width / previewSize.width
        val top = points.minOf { it.y }.toDouble() * size.height / previewSize.height
        val bottom = points.maxOf { it.y }.toDouble() * size.height / previewSize.height
        if (aspectRatio == null) {
            val x = left.roundToInt().coerceIn(0, size.width - 1)
            val y = top.roundToInt().coerceIn(0, size.height - 1)
            return EditOperation.Crop(
                x, y, right.roundToInt().coerceIn(x + 1, size.width), bottom.roundToInt().coerceIn(y + 1, size.height)
            )
        }
        require(aspectRatio.first > 0 && aspectRatio.second > 0)
        val unit = min((right - left) / aspectRatio.first, (bottom - top) / aspectRatio.second)
        val width = (unit * aspectRatio.first).roundToInt().coerceIn(1, size.width)
        val height = (unit * aspectRatio.second).roundToInt().coerceIn(1, size.height)
        val x = ((left + right - width) / 2).roundToInt().coerceIn(0, size.width - width)
        val y = ((top + bottom - height) / 2).roundToInt().coerceIn(0, size.height - height)
        return EditOperation.Crop(x, y, x + width, y + height)
    }
}
