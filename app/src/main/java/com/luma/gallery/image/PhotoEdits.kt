package com.luma.gallery.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import com.luma.gallery.R
import com.luma.gallery.data.UserMessageException
import com.luma.gallery.data.Photo
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An editing failure with a message for the user, in the user's language. */
internal class EditException(@StringRes val messageRes: Int, cause: Throwable? = null) : IOException(null, cause)

/** Pen coordinates and width belong to the oriented source, so every edit stays aligned. */
internal data class InkPoint(val x: Float, val y: Float)
internal data class InkStroke(val points: List<InkPoint>, val color: Int, val width: Float)

/** The crop is a part of the image as turned by [quarterTurns]; [ratio] only locks its shape while it is adjusted. */
internal data class PhotoEdits(
    val quarterTurns: Int = 0,
    val crop: CropRect = CropRect(),
    val ratio: CropRatio = CropRatio.FREE,
    val strokes: List<InkStroke> = emptyList(),
) {
    /** Turns 90° clockwise; the crop follows the photo and a locked shape turns with it. */
    fun turned() = copy(quarterTurns = (quarterTurns + 1) % 4, crop = crop.turnedClockwise(), ratio = ratio.turned)
}

internal class EditGeometry(val width: Int, val height: Int, edits: PhotoEdits) {
    private val turns = ((edits.quarterTurns % 4) + 4) % 4
    val rotatedWidth = if (turns % 2 == 0) width.toFloat() else height.toFloat()
    val rotatedHeight = if (turns % 2 == 0) height.toFloat() else width.toFloat()
    val rotation = Matrix().apply {
        setValues(when (turns) {
            1 -> floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
            2 -> floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
            3 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        })
    }
    val crop = RectF(edits.crop.left * rotatedWidth, edits.crop.top * rotatedHeight,
        edits.crop.right * rotatedWidth, edits.crop.bottom * rotatedHeight)

    fun viewport(canvasWidth: Float, canvasHeight: Float): RectF {
        val scale = min(canvasWidth / crop.width(), canvasHeight / crop.height())
        val viewWidth = crop.width() * scale
        val viewHeight = crop.height() * scale
        return RectF((canvasWidth - viewWidth) / 2f, (canvasHeight - viewHeight) / 2f,
            (canvasWidth + viewWidth) / 2f, (canvasHeight + viewHeight) / 2f)
    }

    fun sourcePoint(x: Float, y: Float, viewport: RectF): InkPoint {
        val rotated = floatArrayOf(
            crop.left + (x - viewport.left) / viewport.width() * crop.width(),
            crop.top + (y - viewport.top) / viewport.height() * crop.height(),
        )
        Matrix().apply { rotation.invert(this) }.mapPoints(rotated)
        return InkPoint((rotated[0] / width).coerceIn(0f, 1f), (rotated[1] / height).coerceIn(0f, 1f))
    }
}

/** The preview and JPEG export use this same transform and renderer. */
internal fun drawPhotoEdits(canvas: Canvas, bitmap: Bitmap, edits: PhotoEdits, target: RectF) {
    val geometry = EditGeometry(bitmap.width, bitmap.height, edits)
    val save = canvas.save()
    canvas.clipRect(target)
    canvas.translate(target.left, target.top)
    canvas.scale(target.width() / geometry.crop.width(), target.height() / geometry.crop.height())
    canvas.translate(-geometry.crop.left, -geometry.crop.top)
    canvas.concat(geometry.rotation)
    canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    edits.strokes.forEach { stroke ->
        if (stroke.points.isEmpty()) return@forEach
        ink.color = stroke.color
        ink.strokeWidth = stroke.width * min(bitmap.width, bitmap.height)
        val first = stroke.points.first()
        if (stroke.points.size == 1) {
            ink.style = Paint.Style.FILL
            canvas.drawCircle(first.x * bitmap.width, first.y * bitmap.height, ink.strokeWidth / 2f, ink)
            ink.style = Paint.Style.STROKE
        } else {
            val path = Path().apply {
                moveTo(first.x * bitmap.width, first.y * bitmap.height)
                stroke.points.drop(1).forEach { lineTo(it.x * bitmap.width, it.y * bitmap.height) }
            }
            canvas.drawPath(path, ink)
        }
    }
    canvas.restoreToCount(save)
}

internal fun renderPhotoEdits(bitmap: Bitmap, edits: PhotoEdits): Bitmap {
    val crop = EditGeometry(bitmap.width, bitmap.height, edits).crop
    return Bitmap.createBitmap(max(1, crop.width().roundToInt()), max(1, crop.height().roundToInt()), Bitmap.Config.ARGB_8888).also { output ->
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        drawPhotoEdits(canvas, bitmap, edits, RectF(0f, 0f, output.width.toFloat(), output.height.toFloat()))
    }
}

/** Decode once, apply EXIF orientation, and bound working memory for large camera JPEGs. */
internal suspend fun loadEditingBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val scale = min(1f, 4096f / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
                ?: throw EditException(R.string.edit_error_open_file)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw EditException(R.string.edit_error_format)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw EditException(R.string.edit_error_format)
            val orientation = resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also { if (it !== bitmap) bitmap.recycle() }
        }
    } catch (memory: OutOfMemoryError) {
        throw EditException(R.string.edit_error_memory_open, memory)
    }
}

internal fun editedCopyName(photo: Photo): String {
    val stem = photo.name.substringBeforeLast('.').take(70).map { if (it.isLetterOrDigit() || it in "-_ ") it else '_' }.joinToString("").ifBlank { "Photo" }
    return "${stem}_edited_${System.currentTimeMillis()}.jpg"
}

/** A new MediaStore item (or an ACTION_CREATE_DOCUMENT result on Android 8/9) is the only write target. */
internal suspend fun saveEditedCopy(context: Context, photo: Photo, bitmap: Bitmap, edits: PhotoEdits, document: Uri? = null): Uri = withContext(Dispatchers.IO) {
    if (document != null && document == Uri.parse(photo.uri)) throw UserMessageException(R.string.edit_error_overwrite)
    val resolver = context.contentResolver
    if (document != null) rejectOriginalDestination(context, Uri.parse(photo.uri), document)
    val output = try { renderPhotoEdits(bitmap, edits) } catch (memory: OutOfMemoryError) {
        throw EditException(R.string.edit_error_memory_save, memory)
    }
    var created: Uri? = null
    try {
        currentCoroutineContext().ensureActive()
        val uri = document ?: if (Build.VERSION.SDK_INT >= 29) {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, editedCopyName(photo))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Gallery")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                if (photo.dateTaken > 0) put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTaken)
            }) ?: throw EditException(R.string.edit_error_create)
        } else throw EditException(R.string.edit_error_location)
        created = uri
        resolver.openOutputStream(uri, "w")?.use { stream ->
            if (!output.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw EditException(R.string.edit_error_encode)
        } ?: throw EditException(R.string.edit_error_write)
        currentCoroutineContext().ensureActive()
        copyPhotoExif(context, Uri.parse(photo.uri), uri, output.width, output.height)
        if (document == null && Build.VERSION.SDK_INT >= 29) {
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) <= 0)
                throw EditException(R.string.edit_error_publish)
        }
        uri
    } catch (failure: Throwable) {
        created?.let { runCatching { resolver.delete(it, null, null) } }
        throw failure
    } finally { output.recycle() }
}

private fun rejectOriginalDestination(context: Context, original: Uri, destination: Uri) {
    // URI aliases can refer to the same file. Open without truncation and compare the
    // underlying files before any write or cleanup is allowed to touch the destination.
    val resolver = context.contentResolver
    val source = resolver.openFileDescriptor(original, "r") ?: throw EditException(R.string.edit_error_verify)
    source.use { sourceDescriptor ->
        val target = resolver.openFileDescriptor(destination, "rw") ?: throw EditException(R.string.edit_error_verify)
        target.use { targetDescriptor ->
            val sourceStat = Os.fstat(sourceDescriptor.fileDescriptor)
            val targetStat = Os.fstat(targetDescriptor.fileDescriptor)
            val sameFile = OsConstants.S_ISREG(sourceStat.st_mode) && OsConstants.S_ISREG(targetStat.st_mode) &&
                sourceStat.st_dev == targetStat.st_dev && sourceStat.st_ino == targetStat.st_ino
            if (sameFile) throw UserMessageException(R.string.edit_error_overwrite)
        }
    }
}

private fun copyPhotoExif(context: Context, original: Uri, copy: Uri, width: Int, height: Int) {
    // JPEG export bakes orientation into pixels; only copy capture data, not old dimensions,
    // thumbnails, or XMP regions that could describe the unedited image.
    runCatching {
        val tags = arrayOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF)
        val attributes = context.contentResolver.openInputStream(original)?.use { stream ->
            val exif = ExifInterface(stream)
            tags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }
        }.orEmpty()
        context.contentResolver.openFileDescriptor(copy, "rw")?.use { descriptor ->
            ExifInterface(descriptor.fileDescriptor).apply {
                attributes.forEach { (tag, value) -> setAttribute(tag, value) }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                saveAttributes()
            }
        }
    }
}
