package org.fossify.gallery.views

import android.view.MotionEvent
import org.fossify.gallery.editor.EditorViewport
import kotlin.math.hypot

/** Once a second finger touches, suppress annotations until the entire gesture ends. */
internal class EditorNavigationGesture(private val viewport: EditorViewport, minimumSpan: Float) {
    private val minimumSpan = minimumSpan.coerceAtLeast(1f)
    private var navigating = false
    private var focusX = 0f
    private var focusY = 0f
    private var span = 0f

    fun reset() {
        navigating = false
        span = 0f
    }

    /** Returns true for navigation events, including the final finger lift. */
    fun onTouchEvent(event: MotionEvent, allowSingleFingerPan: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                navigating = allowSingleFingerPan
                trackPointers(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                navigating = true
                trackPointers(event)
            }

            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> {
                navigating = true
                trackPointers(event, event.actionIndex)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasNavigating = navigating
                reset()
                return wasNavigating
            }
        }
        return navigating
    }

    private fun move(event: MotionEvent) {
        if (!navigating) return
        val oldX = focusX
        val oldY = focusY
        val oldSpan = span
        trackPointers(event)
        val canScale = oldSpan >= minimumSpan && span >= minimumSpan
        viewport.transform(if (canScale) span / oldSpan else 1f, oldX, oldY, focusX, focusY)
    }

    private fun trackPointers(event: MotionEvent, excludedIndex: Int = -1) {
        val remaining = event.pointerCount - if (excludedIndex >= 0) 1 else 0
        val first = if (excludedIndex == 0) 1 else 0
        focusX = event.getX(first)
        focusY = event.getY(first)
        span = 0f
        if (remaining > 1) {
            val second = if (excludedIndex in 0..1) 2 else 1
            val otherX = event.getX(second)
            val otherY = event.getY(second)
            span = hypot(otherX - focusX, otherY - focusY)
            focusX = (focusX + otherX) / 2
            focusY = (focusY + otherY) / 2
        }
    }
}
