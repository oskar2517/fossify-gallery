package org.fossify.gallery.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditRendererTest {
    private fun source() = Bitmap.createBitmap(intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE,
        Color.CYAN, Color.MAGENTA, Color.YELLOW
    ), 3, 2, Bitmap.Config.ARGB_8888)

    private fun pixels(bitmap: Bitmap): List<Int> = (0 until bitmap.height).flatMap { y ->
        (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) }
    }

    @Test
    fun `rotation preserves every pixel and swaps dimensions`() {
        val image = source()
        val history = EditHistory(EditSize(3, 2)).add(EditOperation.Rotate())
        val output = EditRenderer.render(image, history)
        assertEquals(2, output.width)
        assertEquals(3, output.height)
        assertEquals(
            listOf(Color.CYAN, Color.RED, Color.MAGENTA, Color.GREEN, Color.YELLOW, Color.BLUE), pixels(output)
        )
        assertFalse(image.isRecycled)
        output.recycle()
        image.recycle()
    }

    @Test
    fun `both flip directions and crop act on the current image`() {
        val image = source()
        val history = EditHistory(EditSize(3, 2)).add(EditOperation.Flip(true))
            .add(EditOperation.Flip(false)).add(EditOperation.Crop(0, 0, 2, 2))
        val output = EditRenderer.render(image, history)
        assertEquals(listOf(Color.YELLOW, Color.MAGENTA, Color.BLUE, Color.GREEN), pixels(output))
        output.recycle()
        image.recycle()
    }

    @Test
    fun `export uses document resolution independently of preview resolution`() {
        val image = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val history = EditHistory(EditSize(3000, 2000))
            .add(EditOperation.Crop(300, 200, 2700, 1800))
            .add(EditOperation.Rotate())
        val preview = EditRenderer.render(image, history, previewEdge = 400)
        val output = EditRenderer.render(image, history)
        assertEquals(400, maxOf(preview.width, preview.height))
        assertEquals(1600, output.width)
        assertEquals(2400, output.height)
        val encoded = ByteArrayOutputStream().also {
            assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertEquals(1600, decoded.width)
        assertEquals(2400, decoded.height)
        listOf(preview, output, decoded, image).forEach(Bitmap::recycle)
    }

    @Test
    fun `brush image coordinates remain aligned in preview export and after rotation`() {
        val image = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val history = EditHistory(EditSize(1000, 600))
            .add(EditOperation.Stroke(listOf(EditPoint(300f, 200f), EditPoint(700f, 200f)), Color.RED, 20f))
            .add(EditOperation.Rotate())
        val preview = EditRenderer.render(image, history, 500)
        val output = EditRenderer.render(image, history)
        assertEquals(Color.RED, output.getPixel(400, 500))
        assertEquals(Color.RED, preview.getPixel(200, 250))
        assertEquals(Color.WHITE, output.getPixel(300, 500))
        listOf(preview, output, image).forEach(Bitmap::recycle)
    }

    @Test
    fun `text and dot are exported and each can be undone`() {
        val image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val original = EditHistory(EditSize(800, 600))
        val text = original.add(EditOperation.Text("Hello\nworld", EditPoint(100f, 100f), 80f, Color.GREEN))
        val annotated = text.add(EditOperation.Stroke(listOf(EditPoint(600f, 400f)), Color.RED, 30f))
        val output = EditRenderer.render(image, annotated)
        assertEquals(Color.RED, output.getPixel(600, 400))
        assertTrue((100 until 280).any { y -> (100 until 400).any { x -> Color.green(output.getPixel(x, y)) > 100 } })
        val undone = EditRenderer.render(image, annotated.undo().undo())
        assertTrue(pixels(undone).all { it == Color.TRANSPARENT })
        listOf(output, undone, image).forEach(Bitmap::recycle)
    }

    @Test
    fun `resize and redo produce the requested output dimensions`() {
        val image = source()
        val history = EditHistory(EditSize(3, 2)).add(EditOperation.Resize(30, 20))
        val resized = EditRenderer.render(image, history.undo().redo())
        assertEquals(30, resized.width)
        assertEquals(20, resized.height)
        resized.recycle()
        image.recycle()
    }

    @Test
    fun `cancelled render does not recycle the caller's source`() {
        val image = source()
        var cancelled = false
        try {
            EditRenderer.render(image, EditHistory(EditSize(3, 2)).add(EditOperation.Rotate())) {
                throw CancellationException()
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(image.isRecycled)
        assertEquals(Color.RED, image.getPixel(0, 0))
        image.recycle()
    }
}
