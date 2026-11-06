package org.fossify.gallery.activities

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import androidx.activity.addCallback
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFileOutputStreamSync
import org.fossify.commons.extensions.getFinalUriFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getMimeTypeFromUri
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getRealPathFromURI
import org.fossify.commons.extensions.onSeekBarChangeListener
import org.fossify.commons.extensions.rescanPaths
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityAdvancedEditBinding
import org.fossify.gallery.dialogs.EditorResizeDialog
import org.fossify.gallery.dialogs.SaveAsDialog
import org.fossify.gallery.editor.CropGeometry
import org.fossify.gallery.editor.EditHistory
import org.fossify.gallery.editor.EditOperation
import org.fossify.gallery.editor.EditPoint
import org.fossify.gallery.editor.EditRenderer
import org.fossify.gallery.editor.EditSize
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.ensureWritablePath
import org.fossify.gallery.extensions.fixDateTaken
import org.fossify.gallery.extensions.getCompressionFormatFromUri
import org.fossify.gallery.extensions.proposeNewFilePath
import org.fossify.gallery.extensions.readExif
import org.fossify.gallery.extensions.writeExif
import org.fossify.gallery.views.AdvancedEditorView
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import kotlin.math.min

// This activity coordinates the independent editing tools, export and Android lifecycle callbacks.
@Suppress("TooManyFunctions")
class AdvancedEditActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAdvancedEditBinding::inflate)
    private lateinit var sourceUri: Uri
    private var history: EditHistory? = null
    private var sourcePreview: Bitmap? = null
    private var preview: Bitmap? = null
    private var busy = true
    private var cropping = false
    private var cropRatio: Pair<Int, Int>? = null
    private var pendingText: String? = null
    private var brushProgress = DEFAULT_INK_SIZE
    private var textProgress = DEFAULT_INK_SIZE
    private var sessionName = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.advancedEditorContent))
        setupTopAppBar(binding.advancedEditorAppbar, NavigationIcon.Arrow)
        sourceUri = intent.data ?: run {
            toast(R.string.invalid_image_path)
            finish()
            return
        }
        if (sourceUri.scheme !in listOf("content", "file")) {
            toast(R.string.unknown_file_location)
            finish()
            return
        }
        savedInstanceState?.getString(SESSION)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { sessionName = it }
        binding.advancedEditorImage.inkColor = savedInstanceState?.getInt(INK_COLOR) ?: config.lastEditorDrawColor
        brushProgress = savedInstanceState?.getInt(BRUSH_SIZE, DEFAULT_INK_SIZE) ?: DEFAULT_INK_SIZE
        textProgress = savedInstanceState?.getInt(TEXT_SIZE, DEFAULT_INK_SIZE) ?: DEFAULT_INK_SIZE
        setupActions()
        onBackPressedDispatcher.addCallback(this) {
            when {
                busy -> Unit
                cropping -> closeCrop()
                binding.advancedEditorImage.tool != AdvancedEditorView.Tool.NONE -> {
                    selectTool(AdvancedEditorView.Tool.NONE)
                }
                history?.canUndo == true -> confirmDiscard()
                else -> finish()
            }
        }
        updateControls()
        loadImage()
    }

    private fun setupMenuActions() = with(binding) {
        advancedEditorToolbar.setOnMenuItemClickListener { item ->
            if (busy || cropping) return@setOnMenuItemClickListener true
            when (item.itemId) {
                R.id.editor_undo -> changeHistory { it.undo() }
                R.id.editor_redo -> changeHistory { it.redo() }
                R.id.editor_fit_image -> advancedEditorImage.resetZoom()
                R.id.save_as -> saveAs()
                R.id.overwrite_original -> overwriteOriginal()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        advancedEditorToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupActions() = with(binding) {
        setupMenuActions()
        advancedEditorCropButton.setOnClickListener { startCrop() }
        advancedEditorRotate.setOnClickListener { commit(EditOperation.Rotate()) }
        advancedEditorFlip.setOnClickListener {
            getAlertDialogBuilder()
                .setItems(arrayOf(
                    getString(R.string.editor_flip_horizontal), getString(R.string.editor_flip_vertical)
                )) { _, index ->
                    commit(EditOperation.Flip(horizontal = index == 0))
                }.show()
        }
        advancedEditorResize.setOnClickListener { resize() }
        advancedEditorText.setOnClickListener { addText() }
        advancedEditorDraw.setOnClickListener { selectTool(AdvancedEditorView.Tool.DRAW) }
        advancedEditorDone.setOnClickListener { selectTool(AdvancedEditorView.Tool.NONE) }
        advancedEditorImage.onStroke = { commit(it) }
        advancedEditorImage.onTextPosition = { point ->
            pendingText?.let { text ->
                val size = history?.currentSize ?: return@let
                val operation = EditOperation.Text(text, point, textSize(size), advancedEditorImage.inkColor)
                selectTool(AdvancedEditorView.Tool.NONE)
                commit(operation)
            }
        }
        advancedEditorColor.setOnClickListener {
            ColorPickerDialog(this@AdvancedEditActivity, advancedEditorImage.inkColor) { confirmed, color ->
                if (confirmed) {
                    advancedEditorImage.inkColor = color
                    config.lastEditorDrawColor = color
                    updateInkColor()
                }
            }
        }
        advancedEditorInkSize.onSeekBarChangeListener {
            if (advancedEditorImage.tool == AdvancedEditorView.Tool.TEXT) textProgress = it else brushProgress = it
            updateBrushWidth()
        }
        advancedEditorApplyCrop.setOnClickListener { applyCrop() }
        advancedEditorCancelCrop.setOnClickListener { closeCrop() }
        advancedEditorAspect.setOnClickListener {
            val labels = arrayOf(getString(R.string.editor_crop_free), "1:1", "4:3", "3:4", "16:9", "9:16")
            getAlertDialogBuilder().setItems(labels) { _, index ->
                cropRatio = CROP_RATIOS[index]
                advancedEditorCrop.setFixedAspectRatio(cropRatio != null)
                cropRatio?.let { advancedEditorCrop.setAspectRatio(it.first, it.second) }
            }.show()
        }
        updateInkColor()
    }

    private fun loadImage() {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val restored = restoreHistory()
                    val size = restored?.originalSize ?: readSourceSize()
                    val target = Glide.with(applicationContext).asBitmap().load(sourceUri)
                        .disallowHardwareConfig().format(DecodeFormat.PREFER_ARGB_8888)
                        .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                        .fitCenter().submit(PREVIEW_EDGE, PREVIEW_EDGE)
                    try {
                        val sampled = target.get()
                        val document = restored ?: EditHistory(size ?: EditSize(sampled.width, sampled.height))
                        // Own this bitmap independently of Glide's pool.
                        sampled.copy(Bitmap.Config.ARGB_8888, false) to document
                    } finally {
                        Glide.with(applicationContext).clear(target)
                    }
                }
                sourcePreview = loaded.first
                history = loaded.second
                render(loaded.second)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(R.string.invalid_image_path)
                finish()
            } catch (_: OutOfMemoryError) {
                toast(org.fossify.commons.R.string.out_of_memory_error)
                finish()
            }
        }
    }

    private fun readSourceSize(): EditSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Glide's additional decoders can handle formats BitmapFactory cannot read.
            val target = Glide.with(applicationContext).asBitmap().load(sourceUri).disallowHardwareConfig()
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            return try {
                target.get().let { EditSize(it.width, it.height) }
            } finally {
                Glide.with(applicationContext).clear(target)
            }
        }
        val orientation = readExif(sourceUri)?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val swapped = orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        return if (swapped) EditSize(bounds.outHeight, bounds.outWidth) else EditSize(bounds.outWidth, bounds.outHeight)
    }

    private fun changeHistory(change: (EditHistory) -> EditHistory) {
        val current = history ?: return
        if (binding.advancedEditorImage.tool == AdvancedEditorView.Tool.DRAW) {
            binding.advancedEditorImage.cancelGesture()
        } else {
            selectTool(AdvancedEditorView.Tool.NONE)
        }
        val next = change(current)
        if (next != current) render(next)
    }

    private fun commit(operation: EditOperation) {
        if (busy) return
        history?.let { render(it.add(operation)) }
    }

    private fun render(next: EditHistory) {
        val source = sourcePreview ?: return
        busy = true
        updateControls()
        lifecycleScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = withContext(Dispatchers.Default) {
                    val context = coroutineContext
                    EditRenderer.render(source, next, PREVIEW_EDGE) { context.ensureActive() }
                }
                val previous = preview
                preview = rendered
                history = next
                binding.advancedEditorImage.setImage(rendered, next.currentSize)
                previous?.recycle()
                updateBrushWidth()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(R.string.editor_render_failed)
            } catch (_: OutOfMemoryError) {
                toast(org.fossify.commons.R.string.out_of_memory_error)
            } finally {
                if (rendered !== preview) rendered?.recycle()
                busy = false
                updateControls()
            }
        }
    }

    private fun selectTool(tool: AdvancedEditorView.Tool) {
        if (cropping) closeCrop()
        if (tool != AdvancedEditorView.Tool.TEXT) pendingText = null
        binding.advancedEditorImage.tool = tool
        binding.advancedEditorInkSize.progress =
            if (tool == AdvancedEditorView.Tool.TEXT) textProgress else brushProgress
        updateBrushWidth()
        updateControls()
    }

    private fun updateBrushWidth() {
        history?.currentSize?.let {
            binding.advancedEditorImage.brushWidth =
                min(it.width, it.height) * (MIN_BRUSH_FRACTION + brushProgress * BRUSH_FRACTION_STEP)
        }
    }

    private fun textSize(size: EditSize) = size.width * (MIN_TEXT_FRACTION + textProgress * TEXT_FRACTION_STEP)

    private fun updateInkColor() {
        binding.advancedEditorColor.setTextColor(binding.advancedEditorImage.inkColor)
        // A swatch against the selected ink's inverse keeps white/black visible in either theme.
        binding.advancedEditorColor.setBackgroundColor(binding.advancedEditorImage.inkColor xor INVERT_RGB_MASK)
    }

    private fun updateControls() = with(binding) {
        val ready = !busy && history != null && preview != null
        advancedEditorProgress.isVisible = busy
        advancedEditorImage.isEnabled = ready && !cropping
        advancedEditorCrop.isEnabled = ready
        advancedEditorTools.isVisible = !cropping
        for (index in 0 until advancedEditorToolButtons.childCount) {
            advancedEditorToolButtons.getChildAt(index).isEnabled = ready
        }
        advancedEditorInkControls.isVisible = !cropping && advancedEditorImage.tool != AdvancedEditorView.Tool.NONE
        advancedEditorColor.isEnabled = ready
        advancedEditorInkSize.isEnabled = ready
        advancedEditorDone.isEnabled = ready
        advancedEditorCropControls.isVisible = cropping
        advancedEditorApplyCrop.isEnabled = ready
        updateMenu(ready && !cropping)
        advancedEditorStatus.text = when {
            cropping -> getString(R.string.editor_crop_hint)
            advancedEditorImage.tool == AdvancedEditorView.Tool.TEXT -> getString(R.string.editor_text_hint)
            advancedEditorImage.tool == AdvancedEditorView.Tool.DRAW -> getString(R.string.editor_draw_hint)
            else -> history?.currentSize?.let { getString(R.string.editor_dimensions, it.width, it.height) }.orEmpty()
        }
        advancedEditorStatus.setTextColor(getProperTextColor())
    }

    private fun updateMenu(ready: Boolean) {
        val menu = binding.advancedEditorToolbar.menu
        menu.findItem(R.id.editor_undo).isEnabled = ready && history?.canUndo == true
        menu.findItem(R.id.editor_redo).isEnabled = ready && history?.canRedo == true
        for (id in listOf(R.id.editor_undo, R.id.editor_redo)) {
            val item = menu.findItem(id)
            item.icon?.alpha = if (item.isEnabled) ICON_ENABLED_ALPHA else ICON_DISABLED_ALPHA
        }
        menu.findItem(R.id.save_as).isEnabled = ready
        menu.findItem(R.id.editor_fit_image).isEnabled = ready
        menu.findItem(R.id.overwrite_original).apply {
            isEnabled = ready
            isVisible = outputUri() == null
        }
    }

    private fun startCrop() {
        if (busy) return
        selectTool(AdvancedEditorView.Tool.NONE)
        cropping = true
        cropRatio = null
        binding.advancedEditorImage.isVisible = false
        binding.advancedEditorCrop.apply {
            isVisible = true
            setFixedAspectRatio(false)
            setImageBitmap(preview)
        }
        updateControls()
    }

    private fun closeCrop() {
        cropping = false
        binding.advancedEditorCrop.setImageBitmap(null)
        binding.advancedEditorCrop.isVisible = false
        binding.advancedEditorImage.isVisible = true
        updateControls()
    }

    private fun applyCrop() {
        val image = preview ?: return
        val size = history?.currentSize ?: return
        val points = binding.advancedEditorCrop.cropPoints.toList().chunked(2).map { EditPoint(it[0], it[1]) }
        val crop = CropGeometry.fromPreview(size, EditSize(image.width, image.height), points, cropRatio)
        closeCrop()
        if (crop != EditOperation.Crop(0, 0, size.width, size.height)) commit(crop)
    }

    private fun resize() {
        val size = history?.currentSize ?: return
        selectTool(AdvancedEditorView.Tool.NONE)
        EditorResizeDialog(this, size) {
            if (it != size) commit(EditOperation.Resize(it.width, it.height))
        }
    }

    private fun addText() {
        val input = AppCompatEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(MAX_TEXT_LENGTH))
            minLines = 2
            setTextColor(getProperTextColor())
        }
        getAlertDialogBuilder().setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null).apply {
                setupDialogStuff(input, this, R.string.editor_add_text) { dialog ->
                    dialog.showKeyboard(input)
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text.toString()
                        if (text.isNotBlank()) {
                            selectTool(AdvancedEditorView.Tool.TEXT)
                            pendingText = text
                            dialog.dismiss()
                        }
                    }
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun outputUri() = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
        ?.takeIf { it.scheme in listOf("content", "file") }

    private fun saveAs() {
        selectTool(AdvancedEditorView.Tool.NONE)
        outputUri()?.let {
            export(it)
            return
        }
        val (proposed, append) = proposeNewFilePath(sourceUri)
        // Animated and uncommon source formats are exported to a supported still-image format.
        val path = if (proposed.substringAfterLast('.', "").lowercase() in OUTPUT_EXTENSIONS) {
            proposed
        } else {
            "${proposed.substringBeforeLast('.')}.png"
        }
        SaveAsDialog(this, path, append) { destination ->
            if (destination.substringAfterLast('.', "").lowercase() !in OUTPUT_EXTENSIONS) {
                toast(R.string.editor_output_format)
            } else export(File(destination).toUri())
        }
    }

    private fun overwriteOriginal() {
        selectTool(AdvancedEditorView.Tool.NONE)
        if (getMimeTypeFromUri(sourceUri) !in OUTPUT_MIME_TYPES) {
            toast(R.string.editor_output_format)
            return
        }
        val path = if (sourceUri.scheme == "file") sourceUri.path else getRealPathFromURI(sourceUri)
        if (path != null) {
            if (path.substringAfterLast('.', "").lowercase() !in OUTPUT_EXTENSIONS) {
                toast(R.string.editor_output_format)
                return
            }
            ensureWritablePath(path, confirmOverwrite = true) { export(sourceUri) }
        } else {
            getAlertDialogBuilder().setTitle(R.string.overwrite_original)
                .setMessage(R.string.editor_overwrite_message)
                .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> export(sourceUri) }
                .setNegativeButton(org.fossify.commons.R.string.cancel, null).show()
        }
    }

    private fun export(destination: Uri) {
        val snapshot = history ?: return
        if (busy) return
        busy = true
        updateControls()
        binding.advancedEditorStatus.setText(R.string.editor_exporting)
        lifecycleScope.launch {
            var temporary: File? = null
            try {
                val format = getCompressionFormatFromUri(destination)
                // Keep the path outside withContext so cancellation can always remove the file.
                val encoded = File(cacheDir, "editor-export-${UUID.randomUUID()}.${formatExtension(format)}")
                temporary = encoded
                withContext(Dispatchers.IO) {
                    val target = Glide.with(applicationContext).asBitmap().load(sourceUri).disallowHardwareConfig()
                        .format(DecodeFormat.PREFER_ARGB_8888).diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true).submit(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    val context = coroutineContext
                    val output = try {
                        EditRenderer.render(target.get(), snapshot) { context.ensureActive() }
                    } finally {
                        Glide.with(applicationContext).clear(target)
                    }
                    try {
                        if (format == Bitmap.CompressFormat.JPEG) {
                            Canvas(output).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                        }
                        encoded.outputStream().use { check(output.compress(format, EXPORT_QUALITY, it)) }
                        // Some output formats do not support EXIF writes; the encoded image is still valid.
                        runCatching { writeExif(readExif(sourceUri), encoded.toUri()) }
                        context.ensureActive()
                    } finally {
                        output.recycle()
                    }
                }
                withContext(Dispatchers.IO) {
                    val stream = if (destination.scheme == "file") {
                        getFileOutputStreamSync(destination.path!!, destination.path!!.getMimeType())
                    } else {
                        contentResolver.openOutputStream(destination, "wt")
                    } ?: throw IOException("Cannot open output")
                    stream.use { out -> encoded.inputStream().use { it.copyTo(out) } }
                }
                finishExport(destination)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast(R.string.editor_export_failed)
            } catch (_: OutOfMemoryError) {
                toast(org.fossify.commons.R.string.out_of_memory_error)
            } finally {
                temporary?.delete()
                busy = false
                updateControls()
            }
        }
    }

    private fun formatExtension(format: Bitmap.CompressFormat) = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.JPEG -> "jpg"
        else -> "webp"
    }

    private fun finishExport(destination: Uri) {
        fun complete() {
            val resultUri = if (destination.scheme == "file") {
                getFinalUriFromPath(destination.path!!, BuildConfig.APPLICATION_ID) ?: destination
            } else destination
            setResult(RESULT_OK, Intent().setData(resultUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            toast(org.fossify.commons.R.string.file_saved)
            finish()
        }
        if (destination.scheme == "file") {
            val paths = arrayListOf(destination.path!!)
            rescanPaths(paths) {
                fixDateTaken(paths, false)
                runOnUiThread { complete() }
            }
        } else complete()
    }

    private fun confirmDiscard() {
        getAlertDialogBuilder().setTitle(R.string.editor_discard_title).setMessage(R.string.editor_discard_message)
            .setPositiveButton(R.string.editor_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.editor_keep_editing, null).show()
    }

    private fun sessionFile() = File(cacheDir, "advanced-editor-$sessionName.state")

    private fun restoreHistory(): EditHistory? = runCatching {
        ObjectInputStream(sessionFile().inputStream()).use {
            val source = it.readUTF()
            (it.readObject() as EditHistory).takeIf { source == sourceUri.toString() }
        }
    }.getOrNull()

    override fun onSaveInstanceState(outState: Bundle) {
        history?.let { document ->
            runCatching {
                ObjectOutputStream(sessionFile().outputStream()).use {
                    it.writeUTF(sourceUri.toString())
                    it.writeObject(document)
                }
                outState.putString(SESSION, sessionName)
            }
        }
        outState.putInt(INK_COLOR, binding.advancedEditorImage.inkColor)
        outState.putInt(BRUSH_SIZE, brushProgress)
        outState.putInt(TEXT_SIZE, textProgress)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.advancedEditorCrop.setImageBitmap(null)
        binding.advancedEditorImage.setImage(null)
        preview?.recycle()
        // sourcePreview may still be read by a cancelling render; allow it to be collected afterwards.
        if (isFinishing) sessionFile().delete()
        super.onDestroy()
    }

    companion object {
        private const val PREVIEW_EDGE = 2048
        private const val DEFAULT_INK_SIZE = 20
        private const val MIN_BRUSH_FRACTION = 0.001f
        private const val BRUSH_FRACTION_STEP = 0.0004f
        private const val MIN_TEXT_FRACTION = 0.015f
        private const val TEXT_FRACTION_STEP = 0.0015f
        private const val INVERT_RGB_MASK = 0x00FFFFFF
        private const val ICON_ENABLED_ALPHA = 255
        private const val ICON_DISABLED_ALPHA = 64
        private const val MAX_TEXT_LENGTH = 2000
        private const val EXPORT_QUALITY = 95
        private const val SESSION = "editor_session"
        private const val INK_COLOR = "editor_ink_color"
        private const val BRUSH_SIZE = "editor_brush_size"
        private const val TEXT_SIZE = "editor_text_size"
        private val OUTPUT_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val OUTPUT_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        @Suppress("MagicNumber") // These are the literal aspect ratios offered in the crop menu.
        private val CROP_RATIOS = arrayOf(null, 1 to 1, 4 to 3, 3 to 4, 16 to 9, 9 to 16)
    }
}
