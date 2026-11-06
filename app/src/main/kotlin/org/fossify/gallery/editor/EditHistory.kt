package org.fossify.gallery.editor

import java.io.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

/** Dimensions and coordinates in this model are image pixels, never screen pixels. */
data class EditSize(val width: Int, val height: Int) : Serializable {
    init {
        require(width > 0 && height > 0)
    }

    fun limitedTo(edge: Int): EditSize {
        require(edge > 0)
        val scale = minOf(1.0, edge.toDouble() / max(width, height))
        return EditSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class EditPoint(val x: Float, val y: Float) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed interface EditOperation : Serializable {
    fun outputSize(input: EditSize): EditSize = input

    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) : EditOperation {
        override fun outputSize(input: EditSize): EditSize {
            require(left >= 0 && top >= 0 && right <= input.width && bottom <= input.height)
            return EditSize(right - left, bottom - top)
        }
    }

    data class Rotate(val clockwiseTurns: Int = 1) : EditOperation {
        init {
            require(clockwiseTurns in 1..MAX_QUARTER_TURNS)
        }

        override fun outputSize(input: EditSize) =
            if (clockwiseTurns % 2 == 0) input else EditSize(input.height, input.width)

        companion object {
            private const val MAX_QUARTER_TURNS = 3
        }
    }

    data class Flip(val horizontal: Boolean) : EditOperation

    data class Resize(val width: Int, val height: Int) : EditOperation {
        override fun outputSize(input: EditSize) = EditSize(width, height)
    }

    data class Stroke(val points: List<EditPoint>, val color: Int, val width: Float) : EditOperation {
        init {
            require(points.isNotEmpty() && width.isFinite() && width > 0)
        }
    }

    /** Text is committed at the chosen position; undo removes the whole insertion. */
    data class Text(val text: String, val position: EditPoint, val size: Float, val color: Int) : EditOperation {
        init {
            require(text.isNotBlank() && size.isFinite() && size > 0)
        }
    }
}

/** An operation log avoids keeping a full-resolution bitmap for every undo step. */
data class EditHistory(
    val originalSize: EditSize,
    val operations: List<EditOperation> = emptyList(),
    val position: Int = operations.size
) : Serializable {
    init {
        require(position in 0..operations.size)
    }

    val canUndo get() = position > 0
    val canRedo get() = position < operations.size
    val appliedOperations get() = operations.take(position)
    val currentSize get() = appliedOperations.fold(originalSize) { size, operation -> operation.outputSize(size) }

    fun add(operation: EditOperation): EditHistory {
        operation.outputSize(currentSize)
        return copy(operations = appliedOperations + operation, position = position + 1)
    }

    fun undo() = if (canUndo) copy(position = position - 1) else this
    fun redo() = if (canRedo) copy(position = position + 1) else this

    companion object {
        private const val serialVersionUID = 1L
    }
}
