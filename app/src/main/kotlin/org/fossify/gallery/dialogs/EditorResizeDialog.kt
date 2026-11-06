package org.fossify.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.databinding.DialogResizeImageBinding
import org.fossify.gallery.editor.EditSize
import kotlin.math.roundToInt

/** Resize an edit without saving it, including optional upscaling. */
class EditorResizeDialog(
    private val activity: BaseSimpleActivity,
    size: EditSize,
    private val callback: (EditSize) -> Unit
) {
    private val fields = DialogResizeImageBinding.inflate(activity.layoutInflater)
    private val ratio = size.width.toDouble() / size.height

    init {
        fields.resizeImageWidth.setText(size.width.toString())
        fields.resizeImageHeight.setText(size.height.toString())
        fields.resizeImageWidth.doAfterTextChanged {
            if (fields.resizeImageWidth.hasFocus() && fields.keepAspectRatio.isChecked) updateHeight()
        }
        fields.resizeImageHeight.doAfterTextChanged {
            if (fields.resizeImageHeight.hasFocus() && fields.keepAspectRatio.isChecked) {
                it.toString().toIntOrNull()?.let { height ->
                    fields.resizeImageWidth.setText((height * ratio).roundToInt().toString())
                }
            }
        }
        fields.keepAspectRatio.setOnCheckedChangeListener { _, checked -> if (checked) updateHeight() }
        activity.getAlertDialogBuilder().setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null).apply {
                activity.setupDialogStuff(fields.root, this, R.string.editor_resize) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm(dialog) }
                }
            }
    }

    private fun updateHeight() {
        fields.resizeImageWidth.text.toString().toIntOrNull()?.let {
            fields.resizeImageHeight.setText((it / ratio).roundToInt().toString())
        }
    }

    private fun confirm(dialog: AlertDialog) {
        val width = fields.resizeImageWidth.text.toString().toIntOrNull() ?: 0
        val height = fields.resizeImageHeight.text.toString().toIntOrNull() ?: 0
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) {
            activity.toast(R.string.editor_invalid_size)
        } else {
            dialog.dismiss()
            callback(EditSize(width, height))
        }
    }

    companion object {
        private const val MAX_PIXELS = 100_000_000L
    }
}
