package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.fossify.gallery.editor.EditOperation
import org.fossify.gallery.editor.EditPoint
import org.fossify.gallery.editor.EditRenderer
import org.fossify.gallery.editor.EditSize
import kotlin.math.min

class AdvancedEditorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Tool { NONE, DRAW, TEXT }

    var tool = Tool.NONE
        set(value) {
            cancelGesture()
            field = value
        }
    var inkColor = Color.WHITE
    var brushWidth = 1f
    var onStroke: ((EditOperation.Stroke) -> Unit)? = null
    var onTextPosition: ((EditPoint) -> Unit)? = null
    private var bitmap: Bitmap? = null
    private var logicalSize = EditSize(1, 1)
    private val imageBounds = RectF()
    private val points = ArrayList<EditPoint>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var gestureColor = inkColor
    private var gestureWidth = brushWidth

    fun setImage(image: Bitmap?, size: EditSize = logicalSize) {
        cancelGesture()
        bitmap = image
        logicalSize = size
        updateBounds()
        invalidate()
    }

    fun cancelGesture() {
        points.clear()
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture()
        updateBounds()
    }

    private fun updateBounds() {
        val scale = min(width.toFloat() / logicalSize.width, height.toFloat() / logicalSize.height)
        val imageWidth = logicalSize.width * scale
        val imageHeight = logicalSize.height * scale
        imageBounds.set(
            (width - imageWidth) / 2, (height - imageHeight) / 2,
            (width + imageWidth) / 2, (height + imageHeight) / 2
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, null, imageBounds, paint) }
        if (tool == Tool.DRAW && points.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(imageBounds)
            canvas.translate(imageBounds.left, imageBounds.top)
            canvas.scale(imageBounds.width() / logicalSize.width, imageBounds.height() / logicalSize.height)
            EditRenderer.drawAnnotation(canvas, EditOperation.Stroke(points, gestureColor, gestureWidth), logicalSize)
            canvas.restore()
        }
    }

    private fun imagePoint(x: Float, y: Float) = EditPoint(
        ((x - imageBounds.left) / imageBounds.width() * logicalSize.width).coerceIn(0f, logicalSize.width.toFloat()),
        ((y - imageBounds.top) / imageBounds.height() * logicalSize.height).coerceIn(0f, logicalSize.height.toFloat())
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null) return false
        if (tool == Tool.NONE || imageBounds.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageBounds.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureColor = inkColor
                gestureWidth = brushWidth
                points.clear()
                points.add(imagePoint(event.x, event.y))
            }

            MotionEvent.ACTION_MOVE -> {
                if (points.isEmpty()) return true
                if (tool == Tool.DRAW) {
                    for (index in 0 until event.historySize) {
                        points.add(imagePoint(event.getHistoricalX(index), event.getHistoricalY(index)))
                    }
                    points.add(imagePoint(event.x, event.y))
                }
            }

            MotionEvent.ACTION_UP -> {
                finishGesture(imagePoint(event.x, event.y))
                performClick()
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
        }
        invalidate()
        return true
    }

    private fun finishGesture(position: EditPoint) {
        if (points.isEmpty()) return
        if (tool == Tool.DRAW) {
            if (position != points.last()) points.add(position)
            val stroke = EditOperation.Stroke(points.toList(), gestureColor, gestureWidth)
            cancelGesture()
            onStroke?.invoke(stroke)
        } else {
            cancelGesture()
            onTextPosition?.invoke(position)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
