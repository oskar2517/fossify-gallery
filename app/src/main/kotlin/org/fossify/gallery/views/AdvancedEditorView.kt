package org.fossify.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.fossify.gallery.editor.EditOperation
import org.fossify.gallery.editor.EditPoint
import org.fossify.gallery.editor.EditRenderer
import org.fossify.gallery.editor.EditSize
import org.fossify.gallery.editor.EditorViewport

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
    private val viewport = EditorViewport()
    private val navigation = EditorNavigationGesture(viewport, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val imageBounds get() = viewport.bounds
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
        navigation.reset()
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    fun resetZoom() {
        cancelGesture()
        viewport.reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cancelGesture()
        updateBounds()
    }

    private fun updateBounds() {
        viewport.layout(width, height, logicalSize)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null) return false
        if (imageBounds.isEmpty) return false
        val navigating = navigation.onTouchEvent(event, allowSingleFingerPan = tool == Tool.NONE)
        if (navigating) points.clear()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureColor = inkColor
                gestureWidth = brushWidth
                points.clear()
                if (tool != Tool.NONE && imageBounds.contains(event.x, event.y)) {
                    points.add(viewport.imagePoint(event.x, event.y))
                }
            }

            MotionEvent.ACTION_MOVE -> continueStroke(event)

            MotionEvent.ACTION_UP -> {
                if (!navigating) {
                    finishGesture(viewport.imagePoint(event.x, event.y))
                    performClick()
                }
                cancelGesture()
            }

            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        invalidate()
        return true
    }

    private fun continueStroke(event: MotionEvent) {
        if (tool != Tool.DRAW || points.isEmpty()) return
        for (index in 0 until event.historySize) {
            points.add(viewport.imagePoint(event.getHistoricalX(index), event.getHistoricalY(index)))
        }
        points.add(viewport.imagePoint(event.x, event.y))
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
