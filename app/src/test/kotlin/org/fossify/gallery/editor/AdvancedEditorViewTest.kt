package org.fossify.gallery.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import org.fossify.gallery.views.AdvancedEditorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdvancedEditorViewTest {
    private fun view() = AdvancedEditorView(RuntimeEnvironment.getApplication()).apply {
        layout(0, 0, 400, 400)
        setImage(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888), EditSize(4000, 2000))
        tool = AdvancedEditorView.Tool.DRAW
        inkColor = Color.RED
        brushWidth = 20f
    }

    private fun touch(view: AdvancedEditorView, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test
    fun `drawing maps through letterboxing to full resolution image coordinates`() {
        val view = view()
        var stroke: EditOperation.Stroke? = null
        view.onStroke = { stroke = it }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 150f)
        touch(view, MotionEvent.ACTION_UP, 200f, 200f)
        assertEquals(listOf(EditPoint(1000f, 500f), EditPoint(2000f, 1000f)), stroke!!.points)
        assertEquals(20f, stroke!!.width)
        assertEquals(Color.RED, stroke!!.color)
    }

    @Test
    fun `cancelled gestures and touches outside the image do not add undo steps`() {
        val view = view()
        var stroke: EditOperation.Stroke? = null
        view.onStroke = { stroke = it }
        assertFalse(touch(view, MotionEvent.ACTION_DOWN, 100f, 20f))
        touch(view, MotionEvent.ACTION_DOWN, 100f, 150f)
        touch(view, MotionEvent.ACTION_CANCEL, 200f, 200f)
        touch(view, MotionEvent.ACTION_UP, 200f, 200f)
        assertNull(stroke)
    }
}
