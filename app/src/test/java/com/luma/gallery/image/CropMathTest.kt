package com.luma.gallery.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private fun assertCrop(expected: CropRect, actual: CropRect) {
        assertEquals("left", expected.left, actual.left, .001f)
        assertEquals("top", expected.top, actual.top, .001f)
        assertEquals("right", expected.right, actual.right, .001f)
        assertEquals("bottom", expected.bottom, actual.bottom, .001f)
    }

    @Test fun centeredCropIsTheLargestShapeInTheMiddle() {
        assertCrop(CropRect(1f / 6f, 0f, 5f / 6f, 1f), centeredCrop(1f, 300f, 200f))
        assertCrop(CropRect(0f, .125f, 1f, .875f), centeredCrop(4f / 3f, 300f, 300f))
        assertCrop(CropRect(), centeredCrop(null, 300f, 200f))
        assertTrue(centeredCrop(1.5f, 300f, 200f).isFull)
    }

    @Test fun turningKeepsTheSamePartOfThePhoto() {
        // Clockwise: the old left edge becomes the new top, the old bottom becomes the new left.
        assertCrop(CropRect(.4f, .1f, .8f, .5f), CropRect(.1f, .2f, .5f, .6f).turnedClockwise())
        var crop = CropRect(.1f, .2f, .5f, .6f)
        repeat(4) { crop = crop.turnedClockwise() }
        assertCrop(CropRect(.1f, .2f, .5f, .6f), crop)
        assertEquals(CropRatio.THREE_FOUR, CropRatio.FOUR_THREE.turned)
        assertEquals(CropRatio.TALL, CropRatio.WIDE.turned)
        assertEquals(CropRatio.SQUARE, CropRatio.SQUARE.turned)
        assertEquals(1.5f, CropRatio.ORIGINAL.pixelRatio(300f, 200f)!!, .001f)
        assertNull(CropRatio.FREE.pixelRatio(300f, 200f))
    }

    @Test fun touchesPickCornersBeforeEdgesBeforeTheInside() {
        val crop = CropRect(.2f, .2f, .8f, .8f)
        assertEquals(CropHandle.TOP_LEFT, cropHandleAt(crop, .21f, .19f, .05f, .05f))
        assertEquals(CropHandle.BOTTOM_RIGHT, cropHandleAt(crop, .82f, .79f, .05f, .05f))
        assertEquals(CropHandle.TOP, cropHandleAt(crop, .5f, .22f, .05f, .05f))
        assertEquals(CropHandle.LEFT, cropHandleAt(crop, .18f, .5f, .05f, .05f))
        assertEquals(CropHandle.MOVE, cropHandleAt(crop, .5f, .5f, .05f, .05f))
        assertNull(cropHandleAt(crop, .05f, .5f, .05f, .05f))
        // A tiny crop: the nearer side wins instead of always the left one.
        assertEquals(CropHandle.RIGHT, cropHandleAt(CropRect(.5f, .2f, .52f, .8f), .525f, .5f, .05f, .05f))
    }

    @Test fun freeCropResizesEachSideAndStaysInsideTheImage() {
        assertCrop(CropRect(.1f, .2f, 1f, 1f), dragCrop(CropRect(), CropHandle.TOP_LEFT, .1f, .2f, null, 1000f, 500f, 50f))
        // Pulling outward cannot leave the image.
        assertCrop(CropRect(0f, .2f, 1f, 1f), dragCrop(CropRect(.1f, .2f, 1f, 1f), CropHandle.LEFT, -.5f, 0f, null, 1000f, 500f, 50f))
        // Pushing an edge past the other one stops at the smallest size (50 px of 1000 = .05).
        assertCrop(CropRect(.1f, 0f, .15f, 1f), dragCrop(CropRect(.1f, 0f, 1f, 1f), CropHandle.RIGHT, -2f, 0f, null, 1000f, 500f, 50f))
        assertCrop(CropRect(0f, 0f, 1f, .1f), dragCrop(CropRect(), CropHandle.BOTTOM, 0f, -2f, null, 1000f, 500f, 50f))
    }

    @Test fun movingKeepsTheSizeAndStopsAtTheEdges() {
        assertCrop(CropRect(.3f, .25f, .7f, .65f), dragCrop(CropRect(.2f, .2f, .6f, .6f), CropHandle.MOVE, .1f, .05f, null, 1000f, 500f, 50f))
        assertCrop(CropRect(.6f, 0f, 1f, .4f), dragCrop(CropRect(.2f, .2f, .6f, .6f), CropHandle.MOVE, .9f, -.9f, 1f, 1000f, 500f, 50f))
    }

    @Test fun lockedShapeKeepsItsRatioWhenResizedFromCornersAndEdges() {
        val square = centeredCrop(1f, 1000f, 500f)
        assertCrop(CropRect(.25f, 0f, .75f, 1f), square)
        // Moving the corner mostly sideways shrinks the square from the opposite corner.
        val smaller = dragCrop(square, CropHandle.BOTTOM_RIGHT, -.1f, 0f, 1f, 1000f, 500f, 50f)
        assertCrop(CropRect(.25f, 0f, .65f, .8f), smaller)
        assertEquals(1f, smaller.width * 1000f / (smaller.height * 500f), .001f)
        // Growing stops where the image ends.
        val grown = dragCrop(smaller, CropHandle.BOTTOM_RIGHT, .5f, .5f, 1f, 1000f, 500f, 50f)
        assertCrop(CropRect(.25f, 0f, .75f, 1f), grown)
        // An edge resizes around the middle of the opposite side and keeps the shape.
        val edge = dragCrop(CropRect(.3f, .2f, .5f, .6f), CropHandle.RIGHT, -.1f, 0f, 1f, 1000f, 500f, 50f)
        assertCrop(CropRect(.3f, .3f, .4f, .5f), edge)
        // Never smaller than the minimum on either side.
        val tiny = dragCrop(square, CropHandle.TOP_LEFT, 2f, 2f, 16f / 9f, 1000f, 500f, 50f)
        assertTrue(tiny.width * 1000f >= 50f * 16f / 9f - .5f && tiny.height * 500f >= 50f - .5f)
        assertEquals(16f / 9f, tiny.width * 1000f / (tiny.height * 500f), .01f)
    }
}
