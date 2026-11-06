package org.fossify.gallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {
    @Test
    fun `square crop stays square when the sampled preview has rounded dimensions`() {
        val points = listOf(EditPoint(341.5f, 0f), EditPoint(1706.5f, 1365f))
        val crop = CropGeometry.fromPreview(EditSize(3000, 2000), EditSize(2048, 1365), points, 1 to 1)
        assertEquals(EditOperation.Crop(500, 0, 2500, 2000), crop)
    }

    @Test
    fun `free crop maps corners into the current document dimensions`() {
        val crop = CropGeometry.fromPreview(
            EditSize(4000, 3000), EditSize(2000, 1500),
            listOf(EditPoint(250f, 300f), EditPoint(1750f, 1200f))
        )
        assertEquals(EditOperation.Crop(500, 600, 3500, 2400), crop)
    }

    @Test
    fun `rounding at image edges cannot produce an invalid crop`() {
        val crop = CropGeometry.fromPreview(
            EditSize(3000, 2000), EditSize(2048, 1365),
            listOf(EditPoint(-0.01f, -0.01f), EditPoint(2048.01f, 1365.01f))
        )
        assertEquals(EditOperation.Crop(0, 0, 3000, 2000), crop)
    }
}
