package org.fossify.gallery.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import org.fossify.gallery.views.AdvancedEditorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    private fun pointers(view: AdvancedEditorView, action: Int, vararg positions: Pair<Float, Float>) {
        val properties = positions.indices.map { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = positions.map { (px, py) ->
            MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 1f; size = 1f }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            0L, 0L, action, positions.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    private fun pinch(view: AdvancedEditorView) {
        touch(view, MotionEvent.ACTION_DOWN, 150f, 200f)
        pointers(view, SECOND_FINGER_DOWN, 150f to 200f, 250f to 200f)
        pointers(view, MotionEvent.ACTION_MOVE, 100f to 200f, 300f to 200f)
        pointers(view, SECOND_FINGER_UP, 100f to 200f, 300f to 200f)
        touch(view, MotionEvent.ACTION_UP, 100f, 200f)
    }

    private fun strokeAt(view: AdvancedEditorView, x: Float, y: Float): EditOperation.Stroke {
        var stroke: EditOperation.Stroke? = null
        view.onStroke = { stroke = it }
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        return requireNotNull(stroke)
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
        // Background touches are accepted so a pinch can start in the letterbox area.
        assertTrue(touch(view, MotionEvent.ACTION_DOWN, 100f, 20f))
        touch(view, MotionEvent.ACTION_UP, 100f, 20f)
        touch(view, MotionEvent.ACTION_DOWN, 100f, 150f)
        touch(view, MotionEvent.ACTION_CANCEL, 200f, 200f)
        touch(view, MotionEvent.ACTION_UP, 200f, 200f)
        assertNull(stroke)
    }

    @Test
    fun `pinching cancels the incomplete stroke and never commits stray marks`() {
        val view = view()
        var stroke: EditOperation.Stroke? = null
        view.onStroke = { stroke = it }
        pinch(view)
        assertNull(stroke)
        // A finger moving after the other finger lifts must still navigate, not draw.
        touch(view, MotionEvent.ACTION_DOWN, 150f, 200f)
        pointers(view, SECOND_FINGER_DOWN, 150f to 200f, 250f to 200f)
        pointers(view, SECOND_FINGER_UP, 150f to 200f, 250f to 200f)
        touch(view, MotionEvent.ACTION_MOVE, 180f, 200f)
        touch(view, MotionEvent.ACTION_UP, 180f, 200f)
        assertNull(stroke)
    }

    @Test
    fun `drawing while zoomed exports the stroke at the original image coordinates`() {
        val view = view()
        pinch(view)
        val stroke = strokeAt(view, 100f, 150f)
        assertEquals(EditPoint(1500f, 750f), stroke.points.single())
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = EditRenderer.render(source, EditHistory(EditSize(4000, 2000)).add(stroke))
        assertEquals(4000, output.width)
        assertEquals(2000, output.height)
        assertEquals(Color.RED, output.getPixel(1500, 750))
        assertEquals(Color.WHITE, output.getPixel(1000, 500))
        output.recycle()
        source.recycle()
    }

    @Test
    fun `two finger panning works during drawing and clamps at the image edge`() {
        val view = view()
        pinch(view)
        touch(view, MotionEvent.ACTION_DOWN, 100f, 200f)
        pointers(view, SECOND_FINGER_DOWN, 100f to 200f, 300f to 200f)
        pointers(view, MotionEvent.ACTION_MOVE, 150f to 200f, 350f to 200f)
        pointers(view, SECOND_FINGER_UP, 150f to 200f, 350f to 200f)
        touch(view, MotionEvent.ACTION_UP, 150f, 200f)
        assertEquals(EditPoint(1250f, 1000f), strokeAt(view, 100f, 200f).points.single())

        touch(view, MotionEvent.ACTION_DOWN, 100f, 200f)
        pointers(view, SECOND_FINGER_DOWN, 100f to 200f, 300f to 200f)
        pointers(view, MotionEvent.ACTION_MOVE, 1100f to 200f, 1300f to 200f)
        pointers(view, SECOND_FINGER_UP, 1100f to 200f, 1300f to 200f)
        touch(view, MotionEvent.ACTION_UP, 1100f, 200f)
        assertEquals(EditPoint(0f, 1000f), strokeAt(view, 0f, 200f).points.single())
    }

    @Test
    fun `text placement uses the zoomed image coordinates`() {
        val view = view()
        view.tool = AdvancedEditorView.Tool.TEXT
        pinch(view)
        var position: EditPoint? = null
        view.onTextPosition = { position = it }
        touch(view, MotionEvent.ACTION_DOWN, 300f, 250f)
        touch(view, MotionEvent.ACTION_UP, 300f, 250f)
        assertEquals(EditPoint(2500f, 1250f), position)
    }

    @Test
    fun `zoom is preserved when replacing the preview after an annotation or undo`() {
        val view = view()
        pinch(view)
        view.setImage(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888), EditSize(4000, 2000))
        assertEquals(EditPoint(1500f, 750f), strokeAt(view, 100f, 150f).points.single())
        view.resetZoom()
        assertEquals(EditPoint(1000f, 500f), strokeAt(view, 100f, 150f).points.single())
    }

    @Test
    fun `one finger pans with the annotation tools closed without adding edits`() {
        val view = view()
        pinch(view)
        view.tool = AdvancedEditorView.Tool.NONE
        touch(view, MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(view, MotionEvent.ACTION_MOVE, 150f, 200f)
        touch(view, MotionEvent.ACTION_UP, 150f, 200f)
        view.tool = AdvancedEditorView.Tool.DRAW
        assertEquals(EditPoint(1250f, 1000f), strokeAt(view, 100f, 200f).points.single())
    }

    @Test
    fun `pinching off center keeps the chosen image detail under the fingers`() {
        val view = view()
        touch(view, MotionEvent.ACTION_DOWN, 100f, 200f)
        pointers(view, SECOND_FINGER_DOWN, 100f to 200f, 150f to 200f)
        pointers(view, MotionEvent.ACTION_MOVE, 75f to 200f, 175f to 200f)
        pointers(view, SECOND_FINGER_UP, 75f to 200f, 175f to 200f)
        touch(view, MotionEvent.ACTION_UP, 75f, 200f)
        assertEquals(EditPoint(1250f, 1000f), strokeAt(view, 125f, 200f).points.single())
    }

    companion object {
        private const val SECOND_FINGER_DOWN =
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        private const val SECOND_FINGER_UP =
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }
}
