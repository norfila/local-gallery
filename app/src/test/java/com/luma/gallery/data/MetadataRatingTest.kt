package com.luma.gallery.data

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataRatingTest {
    @Test fun readsAdobeRatingAttributeRegardlessOfPrefix() {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:adobe="http://ns.adobe.com/xap/1.0/" adobe:Rating="4"/></rdf:RDF></x:xmpmeta>"""
        assertEquals(4, MetadataRating.fromXmp(xml))
    }

    @Test fun readsDigikamStyleRatingElementWithWhitespace() {
        val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><rdf:Description><xmp:Rating>
             5
        </xmp:Rating></rdf:Description></rdf:RDF>"""
        assertEquals(5, MetadataRating.fromXmp(xml.toByteArray()))
    }

    @Test fun acceptsDefaultNamespaceAndExplicitUnrated() {
        assertEquals(0, MetadataRating.fromXmp("""<Rating xmlns="http://ns.adobe.com/xap/1.0/">0</Rating>"""))
    }

    @Test fun ignoresMatchingNamesInDifferentNamespace() {
        assertNull(MetadataRating.fromXmp("""<r xmlns:custom="https://example.com/metadata" custom:Rating="5"><Rating>4</Rating></r>"""))
    }

    @Test fun ignoresMissingMalformedAndOutOfRangeRatings() {
        for (value in listOf("6", "-2", "4.5", "NaN", "Infinity", "four", "")) {
            assertNull(MetadataRating.fromXmp("""<x:Rating xmlns:x="http://ns.adobe.com/xap/1.0/">$value</x:Rating>"""))
        }
        assertNull(MetadataRating.fromXmp("<broken"))
    }

    @Test fun rejectsExternalEntitiesAndOversizedMetadata() {
        assertNull(MetadataRating.fromXmp("""<!DOCTYPE r [<!ENTITY data SYSTEM "file:///etc/passwd">]><r xmlns:x="http://ns.adobe.com/xap/1.0/" x:Rating="4">&data;</r>"""))
        assertNull(MetadataRating.fromXmp(" ".repeat(MetadataRating.MAX_METADATA_BYTES + 1)))
    }

    @Test fun readsLittleAndBigEndianWindowsExifRating() {
        assertEquals(4, MetadataRating.fromExifBlock(tiff(0x4746, 4, ByteOrder.LITTLE_ENDIAN)))
        assertEquals(5, MetadataRating.fromExifBlock(tiff(0x4746, 5, ByteOrder.BIG_ENDIAN)))
        assertNull(MetadataRating.fromExifBlock(tiff(0x4746, 9)))
    }

    @Test fun mapsWindowsPercentScale() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(0, 1, 25, 50, 75, 99).map { MetadataRating.fromExifBlock(tiff(0x4749, it)) })
        assertNull(MetadataRating.percentToStars(101))
    }

    @Test fun readsJpegPngAndWebpExifContainers() {
        val exif = "Exif\u0000\u0000".toByteArray() + tiff(0x4746, 4)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), ((exif.size + 2) shr 8).toByte(), (exif.size + 2).toByte()) + exif + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) + integer(tiff(0x4746, 4).size, ByteOrder.BIG_ENDIAN) + "eXIf".toByteArray() + tiff(0x4746, 4) + ByteArray(4)
        val chunk = "EXIF".toByteArray() + integer(exif.size, ByteOrder.LITTLE_ENDIAN) + exif
        val webp = "RIFF".toByteArray() + integer(chunk.size + 4, ByteOrder.LITTLE_ENDIAN) + "WEBP".toByteArray() + chunk
        for (container in listOf(jpeg, png, webp)) assertEquals(4, MetadataRating.fromContainer(ByteArrayInputStream(container)))
    }

    @Test fun readsHeifOffsetPrefixWithoutTrustingInvalidOffsets() {
        assertEquals(3, MetadataRating.fromExifBlock(ByteArray(4) + tiff(0x4746, 3)))
        assertNull(MetadataRating.fromExifBlock(integer(Int.MAX_VALUE, ByteOrder.BIG_ENDIAN) + tiff(0x4746, 3)))
        assertNull(MetadataRating.fromExifBlock(byteArrayOf(1, 2, 3)))
        assertNull(MetadataRating.fromExifBlock(tiff(0x4746, 3).copyOf(12)))
    }

    @Test fun truncatedContainersReturnNoRating() {
        assertNull(MetadataRating.fromContainer(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xd8.toByte()))))
        assertNull(MetadataRating.fromContainer(ByteArrayInputStream(ByteArray(200))))
    }

    private fun tiff(tag: Int, value: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray =
        ByteBuffer.allocate(26).order(order).apply {
            put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
            putShort(42); putInt(8); putShort(1)
            putShort(tag.toShort()); putShort(3); putInt(1); putShort(value.toShort()); putShort(0)
            putInt(0)
        }.array()

    private fun integer(value: Int, order: ByteOrder): ByteArray = ByteBuffer.allocate(4).order(order).putInt(value).array()
}
