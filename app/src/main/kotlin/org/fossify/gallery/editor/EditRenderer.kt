package org.fossify.gallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** The same renderer drives the sampled preview and export at the document's actual dimensions. */
object EditRenderer {
    private const val QUARTER_TURN_DEGREES = 90f
    private fun newBitmap(size: EditSize) = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)

    /** Does not take ownership of [source]. The caller owns the returned bitmap. */
    fun render(
        source: Bitmap,
        history: EditHistory,
        previewEdge: Int? = null,
        checkCancelled: () -> Unit = {}
    ): Bitmap {
        val operations = history.appliedOperations
        val sizes = operations.runningFold(history.originalSize) { size, operation -> operation.outputSize(size) }
        val finalSize = sizes.last()
        val renderedSize = previewEdge?.let(finalSize::limitedTo) ?: finalSize
        // Compose geometry instead of allocating an image-sized buffer for each transform.
        // With rectangular crops and quarter turns, the final image bounds also clip all earlier crops.
        val suffixTransforms = Array(operations.size + 1) { Matrix() }
        suffixTransforms[operations.size].setScale(
            renderedSize.width.toFloat() / finalSize.width,
            renderedSize.height.toFloat() / finalSize.height
        )
        for (index in operations.indices.reversed()) {
            checkCancelled()
            suffixTransforms[index].setConcat(suffixTransforms[index + 1], geometry(operations[index], sizes[index]))
        }
        checkCancelled()
        val bitmap = newBitmap(renderedSize)
        var completed = false
        try {
            val canvas = Canvas(bitmap)
            val sourceTransform = Matrix().apply {
                setScale(
                    history.originalSize.width.toFloat() / source.width,
                    history.originalSize.height.toFloat() / source.height
                )
                postConcat(suffixTransforms[0])
            }
            canvas.drawBitmap(source, sourceTransform, bitmapPaint())
            operations.forEachIndexed { index, operation ->
                checkCancelled()
                if (operation is EditOperation.Stroke || operation is EditOperation.Text) {
                    canvas.save()
                    canvas.concat(suffixTransforms[index + 1])
                    drawAnnotation(canvas, operation, sizes[index])
                    canvas.restore()
                }
            }
            checkCancelled()
            completed = true
            return bitmap
        } finally {
            if (!completed) bitmap.recycle()
        }
    }

    private fun geometry(operation: EditOperation, size: EditSize) = Matrix().apply {
        when (operation) {
            is EditOperation.Crop -> setTranslate(-operation.left.toFloat(), -operation.top.toFloat())
            is EditOperation.Rotate -> {
                setRotate(operation.clockwiseTurns * QUARTER_TURN_DEGREES)
                when (operation.clockwiseTurns) {
                    1 -> postTranslate(size.height.toFloat(), 0f)
                    2 -> postTranslate(size.width.toFloat(), size.height.toFloat())
                    else -> postTranslate(0f, size.width.toFloat())
                }
            }

            is EditOperation.Flip -> {
                setScale(if (operation.horizontal) -1f else 1f, if (operation.horizontal) 1f else -1f)
                postTranslate(if (operation.horizontal) size.width.toFloat() else 0f,
                    if (operation.horizontal) 0f else size.height.toFloat())
            }

            is EditOperation.Resize -> setScale(
                operation.width.toFloat() / size.width,
                operation.height.toFloat() / size.height
            )
            else -> Unit
        }
    }

    fun drawAnnotation(canvas: Canvas, operation: EditOperation, size: EditSize) {
        when (operation) {
            is EditOperation.Stroke -> drawStroke(canvas, operation)
            is EditOperation.Text -> drawText(canvas, operation, size)
            else -> Unit
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: EditOperation.Stroke) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color
            strokeWidth = stroke.width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        val first = stroke.points.first()
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x, first.y, stroke.width / 2, paint)
        } else {
            val path = Path().apply {
                moveTo(first.x, first.y)
                stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun drawText(canvas: Canvas, text: EditOperation.Text, size: EditSize) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text.color
            textSize = text.size
        }
        val layout = StaticLayout.Builder.obtain(
            text.text, 0, text.text.length, paint,
            (size.width - text.position.x).toInt().coerceAtLeast(1)
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        canvas.save()
        canvas.translate(text.position.x, text.position.y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun bitmapPaint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
}
