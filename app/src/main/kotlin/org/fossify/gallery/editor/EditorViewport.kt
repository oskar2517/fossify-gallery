package org.fossify.gallery.editor

import android.graphics.RectF
import kotlin.math.min

/** A display-only transform: editing and export always use the document's pixel coordinates. */
class EditorViewport {
    val bounds = RectF()
    private var zoom = 1f
    private var centerX = CENTER_FRACTION
    private var centerY = CENTER_FRACTION
    private var viewWidth = 0
    private var viewHeight = 0
    private var imageSize = EditSize(1, 1)

    fun layout(width: Int, height: Int, size: EditSize) {
        if (size != imageSize) {
            zoom = 1f
            centerX = CENTER_FRACTION
            centerY = CENTER_FRACTION
        }
        viewWidth = width
        viewHeight = height
        imageSize = size
        updateBounds()
    }

    fun reset() {
        zoom = 1f
        centerX = CENTER_FRACTION
        centerY = CENTER_FRACTION
        updateBounds()
    }

    /** Keep the image point under the old finger centroid under the new centroid. */
    fun transform(scaleFactor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (bounds.isEmpty || !scaleFactor.isFinite() || scaleFactor <= 0) return
        val focusX = (fromX - bounds.left) / bounds.width()
        val focusY = (fromY - bounds.top) / bounds.height()
        zoom = (zoom * scaleFactor).coerceIn(1f, MAX_ZOOM)
        val fitScale = min(viewWidth.toFloat() / imageSize.width, viewHeight.toFloat() / imageSize.height)
        centerX = focusX - (toX - viewWidth / 2f) / (imageSize.width * fitScale * zoom)
        centerY = focusY - (toY - viewHeight / 2f) / (imageSize.height * fitScale * zoom)
        updateBounds()
    }

    fun imagePoint(x: Float, y: Float) = EditPoint(
        ((x - bounds.left) / bounds.width() * imageSize.width).coerceIn(0f, imageSize.width.toFloat()),
        ((y - bounds.top) / bounds.height() * imageSize.height).coerceIn(0f, imageSize.height.toFloat())
    )

    private fun updateBounds() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            bounds.setEmpty()
            return
        }
        val fitScale = min(viewWidth.toFloat() / imageSize.width, viewHeight.toFloat() / imageSize.height)
        val imageWidth = imageSize.width * fitScale * zoom
        val imageHeight = imageSize.height * fitScale * zoom
        centerX = clampCenter(centerX, imageWidth, viewWidth)
        centerY = clampCenter(centerY, imageHeight, viewHeight)
        val left = viewWidth / 2f - centerX * imageWidth
        val top = viewHeight / 2f - centerY * imageHeight
        bounds.set(left, top, left + imageWidth, top + imageHeight)
    }

    private fun clampCenter(center: Float, imageExtent: Float, viewExtent: Int): Float {
        if (imageExtent <= viewExtent) return CENTER_FRACTION
        val halfVisible = viewExtent / (2 * imageExtent)
        return center.coerceIn(halfVisible, 1 - halfVisible)
    }

    companion object {
        private const val CENTER_FRACTION = 0.5f
        private const val MAX_ZOOM = 16f
    }
}
