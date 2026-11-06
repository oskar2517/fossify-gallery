package org.fossify.gallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class EditHistoryTest {
    @Test
    fun `undo and redo restore dimensions through a chain of tools`() {
        val original = EditHistory(EditSize(4000, 3000))
        val cropped = original.add(EditOperation.Crop(500, 400, 3500, 2400))
        val rotated = cropped.add(EditOperation.Rotate())
        val resized = rotated.add(EditOperation.Resize(1000, 1500))
        assertEquals(EditSize(3000, 2000), cropped.currentSize)
        assertEquals(EditSize(2000, 3000), rotated.currentSize)
        assertEquals(EditSize(1000, 1500), resized.currentSize)
        assertEquals(rotated.currentSize, resized.undo().currentSize)
        assertEquals(cropped.currentSize, resized.undo().undo().currentSize)
        assertEquals(original.currentSize, resized.undo().undo().undo().currentSize)
        assertEquals(resized, resized.undo().undo().redo().redo())
        assertFalse(original.canUndo)
        assertFalse(resized.canRedo)
    }

    @Test
    fun `a new edit after undo discards only the redo branch`() {
        val history = EditHistory(EditSize(100, 80))
            .add(EditOperation.Rotate()).add(EditOperation.Flip(true)).undo()
            .add(EditOperation.Resize(40, 50))
        assertEquals(listOf(EditOperation.Rotate(), EditOperation.Resize(40, 50)), history.operations)
        assertFalse(history.canRedo)
        assertTrue(history.undo().canRedo)
    }

    @Test
    fun `session serialization preserves annotations and redo history`() {
        val history = EditHistory(EditSize(3000, 2000))
            .add(EditOperation.Text("Hello\nworld", EditPoint(100f, 200f), 90f, -1))
            .add(EditOperation.Stroke(listOf(EditPoint(300f, 400f), EditPoint(500f, 600f)), -65536, 12f))
            .add(EditOperation.Rotate()).undo()
        val bytes = ByteArrayOutputStream().also { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(history) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as EditHistory }
        assertEquals(history, restored)
        assertEquals(EditSize(2000, 3000), restored.redo().currentSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `crop outside the current document is rejected`() {
        EditHistory(EditSize(100, 80)).add(EditOperation.Crop(0, 0, 101, 80))
    }
}
