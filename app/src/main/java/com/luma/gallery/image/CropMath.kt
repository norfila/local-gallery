package com.luma.gallery.image

import androidx.annotation.StringRes
import com.luma.gallery.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A crop of the (rotated) image as fractions of its width and height, 0 to 1. */
internal data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** The same area after the image turns 90° clockwise. */
    fun turnedClockwise() = CropRect(1f - bottom, left, 1f - top, right)
}

/** Shapes the crop can be locked to; FREE resizes each side on its own. */
internal enum class CropRatio(@StringRes val labelRes: Int, private val value: Float?) {
    FREE(R.string.crop_free, null),
    ORIGINAL(R.string.crop_original, null),
    SQUARE(R.string.crop_square, 1f),
    FOUR_THREE(R.string.crop_four_three, 4f / 3f),
    THREE_FOUR(R.string.crop_three_four, 3f / 4f),
    WIDE(R.string.crop_wide, 16f / 9f),
    TALL(R.string.crop_tall, 9f / 16f),
    ;

    /** Width ÷ height in pixels, or null when free. ORIGINAL follows the image as currently turned. */
    fun pixelRatio(imageWidth: Float, imageHeight: Float): Float? = when (this) {
        FREE -> null
        ORIGINAL -> imageWidth / imageHeight
        else -> value
    }

    /** The same shape after a 90° turn, so a locked 4:3 crop stays on the same part of the photo as 3:4. */
    val turned: CropRatio get() = when (this) {
        FOUR_THREE -> THREE_FOUR
        THREE_FOUR -> FOUR_THREE
        WIDE -> TALL
        TALL -> WIDE
        else -> this
    }
}

internal enum class CropHandle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, MOVE }

/** The largest centred crop with this pixel ratio; the whole image when the ratio is null. */
internal fun centeredCrop(ratio: Float?, imageWidth: Float, imageHeight: Float): CropRect {
    if (ratio == null || imageWidth <= 0f || imageHeight <= 0f) return CropRect()
    return if (imageWidth / imageHeight > ratio) {
        val width = imageHeight * ratio / imageWidth
        CropRect((1f - width) / 2f, 0f, (1f + width) / 2f, 1f)
    } else {
        val height = imageWidth / ratio / imageHeight
        CropRect(0f, (1f - height) / 2f, 1f, (1f + height) / 2f)
    }
}

/**
 * Which handle a touch at (x, y), in crop fractions, picks up. Corners win over edges, edges over the
 * inside; slopX and slopY are the touch tolerance as fractions of the image. Null: the touch misses the crop.
 */
internal fun cropHandleAt(crop: CropRect, x: Float, y: Float, slopX: Float, slopY: Float): CropHandle? {
    val nearLeft = abs(x - crop.left) <= slopX
    val nearRight = abs(x - crop.right) <= slopX
    val nearTop = abs(y - crop.top) <= slopY
    val nearBottom = abs(y - crop.bottom) <= slopY
    val withinX = x in crop.left - slopX..crop.right + slopX
    val withinY = y in crop.top - slopY..crop.bottom + slopY
    // On a very small crop both sides can be in reach; the closer one wins.
    val left = nearLeft && (!nearRight || abs(x - crop.left) <= abs(x - crop.right))
    val right = nearRight && !left
    val top = nearTop && (!nearBottom || abs(y - crop.top) <= abs(y - crop.bottom))
    val bottom = nearBottom && !top
    return when {
        top && left -> CropHandle.TOP_LEFT
        top && right -> CropHandle.TOP_RIGHT
        bottom && left -> CropHandle.BOTTOM_LEFT
        bottom && right -> CropHandle.BOTTOM_RIGHT
        left && withinY -> CropHandle.LEFT
        right && withinY -> CropHandle.RIGHT
        top && withinX -> CropHandle.TOP
        bottom && withinX -> CropHandle.BOTTOM
        x in crop.left..crop.right && y in crop.top..crop.bottom -> CropHandle.MOVE
        else -> null
    }
}

/**
 * Applies a drag of (dx, dy), in fractions of the image, to [start]. The result stays inside the image,
 * keeps both sides at least [minPixels] long, and keeps [ratio] (width ÷ height in pixels) when locked.
 */
internal fun dragCrop(
    start: CropRect, handle: CropHandle, dx: Float, dy: Float,
    ratio: Float?, imageWidth: Float, imageHeight: Float, minPixels: Float,
): CropRect {
    val w = imageWidth
    val h = imageHeight
    var left = start.left * w
    var top = start.top * h
    var right = start.right * w
    var bottom = start.bottom * h
    val moveX = dx * w
    val moveY = dy * h
    val minimum = min(minPixels, min(w, h))

    if (handle == CropHandle.MOVE) {
        val shiftX = moveX.coerceIn(-left, w - right)
        val shiftY = moveY.coerceIn(-top, h - bottom)
        return CropRect((left + shiftX) / w, (top + shiftY) / h, (right + shiftX) / w, (bottom + shiftY) / h)
    }

    if (ratio == null) {
        if (handle in setOf(CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT)) left = (left + moveX).coerceIn(0f, right - minimum)
        if (handle in setOf(CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT)) right = (right + moveX).coerceIn(left + minimum, w)
        if (handle in setOf(CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT)) top = (top + moveY).coerceIn(0f, bottom - minimum)
        if (handle in setOf(CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT)) bottom = (bottom + moveY).coerceIn(top + minimum, h)
        return CropRect(left / w, top / h, right / w, bottom / h)
    }

    // A locked shape grows from the opposite corner (or, for an edge, from the opposite edge's middle).
    val smallestWidth = max(minimum, minimum * ratio)
    fun fitWidth(wanted: Float, roomX: Float, roomY: Float): Float = wanted.coerceAtMost(min(roomX, roomY * ratio)).coerceAtLeast(min(smallestWidth, min(roomX, roomY * ratio)))
    when (handle) {
        CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT -> {
            val growsRight = handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT
            val growsDown = handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT
            val anchorX = if (growsRight) left else right
            val anchorY = if (growsDown) top else bottom
            val cornerX = (if (growsRight) right else left) + moveX
            val cornerY = (if (growsDown) bottom else top) + moveY
            // Follow the direction the finger mostly moves, so a corner can both grow and shrink the shape.
            val byX = (cornerX - anchorX) * (if (growsRight) 1f else -1f)
            val byY = (cornerY - anchorY) * (if (growsDown) 1f else -1f) * ratio
            val wantedWidth = if (abs(moveX) >= abs(moveY) * ratio) byX else byY
            val width = fitWidth(wantedWidth, if (growsRight) w - anchorX else anchorX, if (growsDown) h - anchorY else anchorY)
            val height = width / ratio
            left = if (growsRight) anchorX else anchorX - width
            right = left + width
            top = if (growsDown) anchorY else anchorY - height
            bottom = top + height
        }
        CropHandle.LEFT, CropHandle.RIGHT -> {
            val growsRight = handle == CropHandle.RIGHT
            val anchorX = if (growsRight) left else right
            val centerY = (top + bottom) / 2f
            val wanted = (if (growsRight) right + moveX - anchorX else anchorX - (left + moveX))
            val width = fitWidth(wanted, if (growsRight) w - anchorX else anchorX, h)
            val height = width / ratio
            left = if (growsRight) anchorX else anchorX - width
            right = left + width
            top = (centerY - height / 2f).coerceIn(0f, h - height)
            bottom = top + height
        }
        CropHandle.TOP, CropHandle.BOTTOM -> {
            val growsDown = handle == CropHandle.BOTTOM
            val anchorY = if (growsDown) top else bottom
            val centerX = (left + right) / 2f
            val wantedHeight = (if (growsDown) bottom + moveY - anchorY else anchorY - (top + moveY))
            val width = fitWidth(wantedHeight * ratio, w, if (growsDown) h - anchorY else anchorY)
            val height = width / ratio
            top = if (growsDown) anchorY else anchorY - height
            bottom = top + height
            left = (centerX - width / 2f).coerceIn(0f, w - width)
            right = left + width
        }
        CropHandle.MOVE -> Unit
    }
    return CropRect(left / w, top / h, right / w, bottom / h)
}
