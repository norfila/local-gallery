package com.luma.gallery.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Read-only rating interoperability. XMP wins when the image contains both formats. */
internal object MetadataRating {
    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/"
    const val MAX_METADATA_BYTES = 2 * 1024 * 1024
    private const val MAX_CONTAINER_SCAN = 128L * 1024 * 1024

    fun fromXmp(bytes: ByteArray?): Int? = bytes
        ?.takeIf { it.size <= MAX_METADATA_BYTES }
        ?.let { fromXmp(it.toString(Charsets.UTF_8).trimEnd('\u0000')) }

    fun fromXmp(xml: String): Int? {
        if (xml.length > MAX_METADATA_BYTES || xml.contains("<!DOCTYPE", ignoreCase = true)) return null
        return runCatching {
            var rating: Int? = null
            var readingRating = false
            val value = StringBuilder()
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if (rating == null) {
                        for (index in 0 until attributes.length) {
                            if (attributes.getURI(index) == XMP_NAMESPACE && attributes.getLocalName(index) == "Rating") {
                                rating = starValue(attributes.getValue(index))
                            }
                        }
                    }
                    if (uri == XMP_NAMESPACE && localName == "Rating") {
                        readingRating = true
                        value.setLength(0)
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (readingRating && value.length < 64) value.append(ch, start, minOf(length, 64 - value.length))
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (uri == XMP_NAMESPACE && localName == "Rating") {
                        if (rating == null) rating = starValue(value.toString())
                        readingRating = false
                    }
                }
            }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                // Android and desktop JVM XML parsers support different feature subsets.
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newSAXParser().xmlReader.apply {
                contentHandler = handler
                errorHandler = handler
                entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(xml.trimStart('\uFEFF'))))
            rating
        }.getOrNull()
    }

    private fun starValue(raw: String): Int? {
        val value = raw.trim().toDoubleOrNull() ?: return null
        // XMP -1 means rejected. The current gallery represents this as unrated.
        if (value == -1.0) return 0
        return value.takeIf { it.isFinite() && it in 0.0..5.0 && it == it.toInt().toDouble() }?.toInt()
    }

    /** Windows EXIF Rating/RatingPercent are not exposed by AndroidX ExifInterface. */
    fun fromContainer(source: InputStream): Int? = runCatching {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        input.mark(16)
        val header = input.readN(12) ?: return@runCatching null
        input.reset()
        when {
            header[0].u() == 0xff && header[1].u() == 0xd8 -> jpeg(input)
            header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> png(input)
            header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WEBP" -> webp(input)
            header.ascii(0, 2) in listOf("II", "MM") -> fromExifBlock(input.readBytesCapped(MAX_METADATA_BYTES))
            else -> null // HEIF EXIF is located separately by MediaMetadataRetriever.
        }
    }.getOrNull()

    /** Accepts TIFF, Exif\0\0 + TIFF, or the HEIF 4-byte TIFF-offset prefix. */
    fun fromExifBlock(bytes: ByteArray): Int? {
        val start = when {
            bytes.size >= 6 && bytes.ascii(0, 6) == "Exif\u0000\u0000" -> 6
            bytes.size >= 8 && bytes.ascii(0, 2) in listOf("II", "MM") -> 0
            bytes.size >= 12 -> {
                val offset = bytes.uint32(0, false) ?: return null
                (4L + offset).takeIf { it <= bytes.size - 8 }?.toInt() ?: return null
            }
            else -> return null
        }
        val little = when (bytes.ascii(start, 2)) { "II" -> true; "MM" -> false; else -> return null }
        if (bytes.uint16(start + 2, little) != 42) return null
        val relativeIfd = bytes.uint32(start + 4, little) ?: return null
        val ifd = (start.toLong() + relativeIfd).takeIf { it in 0..(bytes.size - 2).toLong() }?.toInt() ?: return null
        val count = bytes.uint16(ifd, little) ?: return null
        var stars: Int? = null
        var percent: Int? = null
        for (index in 0 until minOf(count, 4096)) {
            val entry = ifd + 2 + index * 12
            if (entry + 12 > bytes.size) break
            val tag = bytes.uint16(entry, little)
            if (tag != 0x4746 && tag != 0x4749) continue
            val type = bytes.uint16(entry + 2, little)
            if (bytes.uint32(entry + 4, little) != 1L) continue
            val number = when (type) {
                1 -> bytes[entry + 8].u().toLong()
                3 -> bytes.uint16(entry + 8, little)?.toLong()
                4 -> bytes.uint32(entry + 8, little)
                else -> null
            } ?: continue
            if (tag == 0x4746 && number in 0..5) stars = number.toInt()
            if (tag == 0x4749 && number in 0..100) percent = number.toInt()
        }
        return stars ?: percent?.let(::percentToStars)
    }

    internal fun percentToStars(percent: Int): Int? = when (percent) {
        0 -> 0
        in 1..12 -> 1
        in 13..37 -> 2
        in 38..62 -> 3
        in 63..87 -> 4
        in 88..100 -> 5
        else -> null
    }

    private fun jpeg(input: InputStream): Int? {
        if (!input.skipFully(2)) return null
        var scanned = 2L
        while (scanned < MAX_CONTAINER_SCAN) {
            if (input.read() != 0xff) return null
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker < 0 || marker == 0xda || marker == 0xd9) return null
            if (marker == 0x01 || marker in 0xd0..0xd8) continue
            val lengthBytes = input.readN(2) ?: return null
            val length = (lengthBytes[0].u() shl 8) + lengthBytes[1].u() - 2
            if (length < 0) return null
            scanned += length + 4
            if (marker == 0xe1) {
                val data = input.readN(length) ?: return null
                fromExifBlock(data)?.let { return it }
            } else if (!input.skipFully(length.toLong())) return null
        }
        return null
    }

    private fun png(input: InputStream): Int? {
        if (!input.skipFully(8)) return null
        var scanned = 8L
        while (scanned < MAX_CONTAINER_SCAN) {
            val chunk = input.readN(8) ?: return null
            val length = chunk.uint32(0, false) ?: return null
            val type = chunk.ascii(4, 4)
            if (type == "IEND") return null
            if (type == "eXIf" && length <= MAX_METADATA_BYTES) {
                val data = input.readN(length.toInt()) ?: return null
                fromExifBlock(data)?.let { return it }
            } else if (!input.skipFully(length)) return null
            if (!input.skipFully(4)) return null
            scanned += length + 12
        }
        return null
    }

    private fun webp(input: InputStream): Int? {
        val header = input.readN(12) ?: return null
        val declaredSize = header.uint32(4, true)?.plus(8) ?: return null
        var scanned = 12L
        while (scanned + 8 <= minOf(declaredSize, MAX_CONTAINER_SCAN)) {
            val chunk = input.readN(8) ?: return null
            val length = chunk.uint32(4, true) ?: return null
            if (scanned + 8 + length > declaredSize) return null
            if (chunk.ascii(0, 4) == "EXIF" && length <= MAX_METADATA_BYTES) {
                val data = input.readN(length.toInt()) ?: return null
                fromExifBlock(data)?.let { return it }
            } else if (!input.skipFully(length)) return null
            if (length % 2L != 0L && !input.skipFully(1)) return null
            scanned += 8 + length + length % 2
        }
        return null
    }

    private fun Byte.u(): Int = toInt() and 0xff
    private fun ByteArray.ascii(offset: Int, length: Int): String =
        if (offset >= 0 && offset + length <= size) String(this, offset, length, Charsets.ISO_8859_1) else ""
    private fun ByteArray.uint16(offset: Int, little: Boolean): Int? {
        if (offset < 0 || offset + 2 > size) return null
        return if (little) this[offset].u() or (this[offset + 1].u() shl 8)
        else (this[offset].u() shl 8) or this[offset + 1].u()
    }
    private fun ByteArray.uint32(offset: Int, little: Boolean): Long? {
        if (offset < 0 || offset + 4 > size) return null
        var value = 0L
        for (index in 0..3) value = value or (this[offset + index].u().toLong() shl (if (little) index * 8 else (3 - index) * 8))
        return value
    }
    private fun InputStream.readN(length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(bytes, offset, length - offset)
            if (count <= 0) return null
            offset += count
        }
        return bytes
    }
    private fun InputStream.readBytesCapped(limit: Int): ByteArray = take(limit).readBytes()
    private fun InputStream.take(limit: Int): InputStream = object : InputStream() {
        var remaining = limit
        override fun read(): Int = if (remaining-- > 0) this@take.read() else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val count = this@take.read(b, off, minOf(len, remaining))
            if (count > 0) remaining -= count
            return count
        }
    }
    internal fun InputStream.skipFully(length: Long): Boolean {
        var remaining = length
        if (length < 0) return false
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() >= 0) remaining-- else return false
        }
        return true
    }
}
