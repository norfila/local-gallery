package com.luma.gallery.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import com.luma.gallery.R
import java.io.StringReader
import java.lang.reflect.Modifier
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

enum class MetadataSection(@StringRes val titleRes: Int) {
    FILE(R.string.section_file), CAPTURE(R.string.section_capture), CAMERA(R.string.section_camera),
    EXPOSURE(R.string.section_exposure), LOCATION(R.string.section_location), IMAGE(R.string.section_image),
    RATINGS(R.string.section_ratings), OTHER(R.string.section_other),
}

data class MetadataDetail(val label: String, val value: String)
data class PhotoMetadataCategory(val section: MetadataSection, val entries: List<MetadataDetail>)
data class PhotoMetadataDetails(val categories: List<PhotoMetadataCategory>, val notice: String? = null)

/** Reads details only when requested; originals are never modified. */
object PhotoMetadataDetailsReader {
    private const val MAX_VALUE_BYTES = 16 * 1024
    private const val MAX_VALUE_CHARS = 8 * 1024
    private const val MAX_XMP_BYTES = 1024 * 1024
    private const val MAX_RAW_XMP_CHARS = 4096
    private val binaryTags = setOf(
        "MakerNote", "DNGPrivateData", "OECF", "SpatialFrequencyResponse", "CFAPattern",
        "DeviceSettingDescription", "JPEGInterchangeFormat", "JPEGInterchangeFormatLength",
    )

    suspend fun load(context: Context, photo: Photo): PhotoMetadataDetails = withContext(Dispatchers.IO) {
        try {
            readDetails(context.applicationContext, photo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val app = context.applicationContext
            // A revoked permission or an unavailable provider must not close the viewer.
            PhotoMetadataDetails(
                categories = MetadataSection.entries.map { section ->
                    PhotoMetadataCategory(section, when (section) {
                        MetadataSection.FILE -> listOf(
                            MetadataDetail(app.getString(R.string.meta_name), photo.name),
                            MetadataDetail(app.getString(R.string.meta_type), photo.mimeType),
                            MetadataDetail(app.getString(R.string.meta_folder), photo.folderKey),
                        )
                        MetadataSection.RATINGS -> listOf(MetadataDetail(app.getString(R.string.meta_embedded_rating), app.getString(R.string.meta_rating_value, photo.embeddedRating)))
                        else -> emptyList()
                    })
                },
                notice = app.getString(R.string.meta_notice_unavailable),
            )
        }
    }

    private fun readDetails(app: Context, photo: Photo): PhotoMetadataDetails {
        val sourceUri = Uri.parse(photo.uri)
        val mediaStoreSource = sourceUri.authority == MediaStore.AUTHORITY
        // ACCESS_MEDIA_LOCATION and requireOriginal belong to MediaStore. Another provider
        // decides what metadata its explicitly shared stream contains; preserve its exact URI.
        val locationAllowed = !mediaStoreSource || Build.VERSION.SDK_INT < 29 ||
            app.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        val original = if (mediaStoreSource && Build.VERSION.SDK_INT >= 29 && locationAllowed) MediaStore.setRequireOriginal(sourceUri) else sourceUri
        var unredacted = locationAllowed
        val video = if (photo.isVideo) VideoMetadataReader.read(app, sourceUri, locationAllowed) else null
        val exif = if (photo.isVideo) null else {
            readExif(app, original) ?: if (original != sourceUri) {
                unredacted = false
                readExif(app, sourceUri)
            } else null
        }
        val sections = MetadataSection.entries.associateWith { mutableListOf<MetadataDetail>() }
        fun add(section: MetadataSection, label: String, value: String?) {
            value?.trim()?.takeIf(String::isNotEmpty)?.let { sections.getValue(section).add(MetadataDetail(label, it)) }
        }
        fun text(id: Int, vararg args: Any) = app.getString(id, *args)
        add(MetadataSection.FILE, text(R.string.meta_name), photo.name)
        add(MetadataSection.FILE, text(R.string.meta_type), photo.mimeType)
        add(MetadataSection.FILE, text(R.string.meta_size), text(R.string.meta_size_value, Formatter.formatShortFileSize(app, photo.size), photo.size))
        add(MetadataSection.FILE, text(R.string.meta_folder), photo.folder)
        add(MetadataSection.FILE, text(R.string.meta_storage_path), photo.folderKey)
        add(MetadataSection.FILE, text(R.string.meta_content_uri), photo.uri)
        if (photo.dateModified > 0) add(MetadataSection.FILE, text(R.string.meta_modified), date(photo.dateModified * 1000L))
        if (photo.dateTaken > 0) add(MetadataSection.CAPTURE, text(R.string.meta_library_capture_date), date(photo.dateTaken))
        add(MetadataSection.CAMERA, text(R.string.meta_camera), photo.camera)
        add(MetadataSection.CAMERA, text(R.string.meta_lens), photo.lens)
        photo.iso?.let { add(MetadataSection.EXPOSURE, text(R.string.meta_iso), it.toString()) }
        photo.aperture?.let { add(MetadataSection.EXPOSURE, text(R.string.meta_aperture), "f/${decimal(it)}") }
        photo.focalLength?.let { add(MetadataSection.EXPOSURE, text(R.string.meta_focal_length), text(R.string.meta_focal_length_value, decimal(it))) }
        photo.exposureSeconds?.let { add(MetadataSection.EXPOSURE, text(R.string.meta_exposure_time), text(R.string.meta_exposure_time_value, it.toString())) }
        if (photo.width > 0 && photo.height > 0) {
            add(MetadataSection.IMAGE, text(R.string.meta_dimensions), text(R.string.meta_dimensions_value, photo.width, photo.height))
            if (!photo.isVideo) add(MetadataSection.IMAGE, text(R.string.meta_resolution), text(R.string.meta_resolution_value, decimal(photo.megapixels)))
        }
        if (photo.isVideo) {
            val duration = video?.durationMillis?.takeIf { it > 0 } ?: photo.durationMillis
            if (duration > 0) add(MetadataSection.IMAGE, text(R.string.meta_duration), text(R.string.meta_duration_value, decimal(duration / 1000.0)))
            video?.let {
                add(MetadataSection.IMAGE, text(R.string.meta_rotation), "${it.rotation}°")
                it.frameRate?.let { rate -> add(MetadataSection.IMAGE, text(R.string.meta_frame_rate), text(R.string.meta_frame_rate_value, decimal(rate))) }
                it.bitrate?.let { bitrate -> add(MetadataSection.FILE, text(R.string.meta_bit_rate), text(R.string.meta_bit_rate_value, bitrate)) }
                add(MetadataSection.IMAGE, text(R.string.meta_audio_track), text(if (it.hasAudio) R.string.meta_audio_present else R.string.meta_audio_none))
                it.dateTaken?.let { taken -> add(MetadataSection.CAPTURE, text(R.string.meta_container_date), date(taken)) }
                it.coordinates?.let { coordinates -> add(MetadataSection.LOCATION, text(R.string.meta_coordinates), String.format(Locale.getDefault(), "%.6f, %.6f", coordinates.first, coordinates.second)) }
            }
        }
        add(MetadataSection.RATINGS, text(R.string.meta_embedded_rating), text(R.string.meta_rating_value, photo.embeddedRating))

        val attributes = linkedMapOf<String, String>()
        if (exif != null) {
            if (unredacted) {
                runCatching { exif.latLong }.getOrNull()?.let { coordinates ->
                    add(MetadataSection.LOCATION, text(R.string.meta_coordinates), String.format(Locale.getDefault(), "%.6f, %.6f", coordinates[0], coordinates[1]))
                }
            }
            // Only public, known tag constants are queried. Unsupported tags simply return null.
            val tags = ExifInterface::class.java.fields.asSequence()
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
                .filterNot { it.isAnnotationPresent(java.lang.Deprecated::class.java) }
                .mapNotNull { field -> runCatching { field.get(null) as? String }.getOrNull() }
                .filterNot { it == ExifInterface.TAG_XMP }
                .distinct().sorted().toList()
            tags.forEach { tag ->
                if (!unredacted && metadataSectionForTag(tag) == MetadataSection.LOCATION) return@forEach
                runCatching {
                    val bytes = exif.getAttributeBytes(tag) ?: return@runCatching
                    val value = when {
                        tag in binaryTags -> "${bytes.size} bytes (binary metadata)"
                        bytes.size > MAX_VALUE_BYTES -> "${bytes.size} bytes (value exceeds display limit)"
                        else -> exif.getAttribute(tag)?.trim('\u0000', ' ', '\n', '\r', '\t')?.let(::boundedValue)
                    }
                    if (!value.isNullOrBlank()) attributes[tag] = value
                }
            }
            val xmp = runCatching { exif.getAttributeBytes(ExifInterface.TAG_XMP) }.getOrNull()
            if (xmp != null) {
                if (xmp.size <= MAX_XMP_BYTES) {
                    val xml = decodeXmp(xmp)
                    extractXmpProperties(xml).forEach { (key, value) -> attributes["XMP · $key"] = value }
                    // A raw packet may include GPS not exposed as a normal EXIF property.
                    if (unredacted) {
                        val suffix = if (xml.length > MAX_RAW_XMP_CHARS) "\n… Packet truncated for display (${xmp.size} bytes)." else ""
                        attributes["Raw XMP"] = xml.take(MAX_RAW_XMP_CHARS) + suffix
                    } else {
                        attributes["XMP packet"] = "${xmp.size} bytes · GPS fields and raw packet hidden without photo location access"
                    }
                } else attributes["XMP packet"] = "${xmp.size} bytes (packet exceeds display limit)"
            }
        }
        categorizeMetadataAttributes(attributes, unredacted).forEach { (section, entries) ->
            val list = sections.getValue(section)
            entries.forEach { entry ->
                if (list.none { it.label.equals(entry.label, true) && it.value == entry.value }) list += entry
            }
        }
        if (!locationAllowed) {
            add(MetadataSection.LOCATION, text(R.string.meta_location_access), text(R.string.meta_location_access_value))
        } else if (!unredacted) {
            add(MetadataSection.LOCATION, text(R.string.meta_gps), text(R.string.meta_gps_redacted))
        } else if (sections.getValue(MetadataSection.LOCATION).isEmpty()) {
            add(MetadataSection.LOCATION, text(R.string.meta_gps), text(if (photo.isVideo) R.string.meta_gps_none_video else R.string.meta_gps_none_photo))
        }
        return PhotoMetadataDetails(
            categories = MetadataSection.entries.map { section ->
                PhotoMetadataCategory(section, sections.getValue(section).distinct().sortedBy { it.label.lowercase(Locale.ROOT) })
            },
            notice = when {
                photo.isVideo && video == null -> text(R.string.meta_notice_video_unreadable)
                !photo.isVideo && exif == null -> text(R.string.meta_notice_image_unreadable)
                else -> null
            },
        )
    }

    private fun readExif(context: Context, uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.getOrNull() ?: runCatching {
        context.contentResolver.openInputStream(uri)?.use(::ExifInterface)
    }.getOrNull()

    private fun date(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG).format(Date(value))
    private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')
    private fun boundedValue(value: String): String = if (value.length <= MAX_VALUE_CHARS) value else value.take(MAX_VALUE_CHARS) + "\n… Value truncated for display."

    private fun decodeXmp(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        return bytes.toString(charset).trimStart('\uFEFF').trimEnd('\u0000', ' ', '\n', '\r')
    }

    /** Leaf text covers RDF lists/alternatives as well as ordinary XMP element properties. */
    internal fun extractXmpProperties(xml: String): Map<String, String> {
        if (xml.length > MAX_XMP_BYTES || xml.contains("<!DOCTYPE", ignoreCase = true)) return emptyMap()
        return runCatching {
            val result = linkedMapOf<String, String>()
            data class Element(val name: String, val namespace: String, val text: StringBuilder = StringBuilder(), var hasChildren: Boolean = false)
            val stack = mutableListOf<Element>()
            val rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            fun propertyName(name: String): String {
                // Keep the enclosing property for RDF structs and list items. Besides making
                // the display meaningful, this preserves LocationCreated/GPS context even
                // when a nested property is named only "Value" or "Name".
                val parents = stack.filter { it.namespace != rdf && it.namespace != "adobe:ns:meta/" }
                    .map { it.name }.filter { it != name }
                return (parents + name).filter(String::isNotBlank).joinToString(" / ")
            }
            fun record(key: String, value: String) {
                val clean = value.trim().take(MAX_VALUE_CHARS)
                if (key.isBlank() || clean.isBlank() || result.size >= 256) return
                val previous = result[key]
                result[key] = if (previous == null || previous == clean) clean else boundedValue("$previous; $clean")
            }
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                    if (stack.size >= 64) throw org.xml.sax.SAXException("XMP nesting exceeds display limit")
                    stack.lastOrNull()?.hasChildren = true
                    stack += Element(qName.orEmpty().ifBlank { localName.orEmpty() }, uri.orEmpty())
                    for (index in 0 until attrs.length) {
                        val namespace = attrs.getURI(index)
                        if (namespace.isNotEmpty() && namespace != rdf && namespace != "http://www.w3.org/XML/1998/namespace") {
                            record(propertyName(attrs.getQName(index).ifBlank { attrs.getLocalName(index) }), attrs.getValue(index))
                        }
                    }
                    attrs.getValue(rdf, "resource")?.let { record(propertyName(stack.last().name), it) }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    stack.lastOrNull()?.text?.let { if (it.length < MAX_VALUE_CHARS) it.append(ch, start, minOf(length, MAX_VALUE_CHARS - it.length)) }
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val element = stack.removeLastOrNull() ?: return
                    val name = if (element.namespace == rdf) stack.lastOrNull { it.namespace != rdf }?.name.orEmpty() else element.name
                    if (!element.hasChildren && element.namespace != "adobe:ns:meta/") record(propertyName(name), element.text.toString())
                }
            }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newSAXParser().xmlReader.apply {
                contentHandler = handler
                errorHandler = handler
                entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(InputSource(StringReader(xml)))
            result
        }.getOrDefault(emptyMap())
    }
}

internal fun categorizeMetadataAttributes(attributes: Map<String, String>, includeLocation: Boolean): Map<MetadataSection, List<MetadataDetail>> =
    attributes.entries.filter { it.value.isNotBlank() }.filter { includeLocation || metadataSectionForTag(it.key) != MetadataSection.LOCATION }
        .groupBy({ metadataSectionForTag(it.key) }, { MetadataDetail(metadataLabel(it.key), it.value) })

internal fun metadataSectionForTag(tag: String): MetadataSection {
    val normalized = tag.lowercase(Locale.ROOT).replace(Regex("[\\s_\\-]"), "")
    val property = normalized.substringAfterLast('/').substringAfterLast(':').trim()
    val subjectPosition = property in setOf("subjectlocation", "subjectarea")
    val geographicContext = if (subjectPosition) normalized.substringBeforeLast('/', "") else normalized
    return when {
        // EXIF SubjectLocation is a pixel position in the image, not a GPS location.
        property == "state" || listOf("gps", "latitude", "longitude", "location", "altitude", "city", "country", "province", "coordinate").any(geographicContext::contains) -> MetadataSection.LOCATION
        subjectPosition -> MetadataSection.IMAGE
        normalized.contains("rating") -> MetadataSection.RATINGS
        property in setOf("filename", "filesize", "filetype", "mimetype", "directory", "preservedfilename") -> MetadataSection.FILE
        property == "date" || listOf("datetime", "subsec", "offsettime", "createdate", "modifydate", "datecreated", "datestamp", "timestamp").any(normalized::contains) -> MetadataSection.CAPTURE
        listOf("exposure", "shutter", "aperture", "fnumber", "focallength", "flash", "iso", "sensitivity", "metering", "brightness", "lightsource", "whitebalance", "digitalzoom", "subjectdistance").any(normalized::contains) -> MetadataSection.EXPOSURE
        property == "make" || listOf("model", "lens", "camera", "serialnumber", "bodyserial", "ownername", "imageuniqueid").any(normalized::contains) -> MetadataSection.CAMERA
        listOf("width", "length", "height", "orientation", "colorspace", "resolution", "compression", "bitsper", "samplesper", "photometric", "chromatic", "ycbcr", "imageprocessing", "componentsconfiguration", "gaincontrol", "contrast", "saturation", "sharpness", "scenetype", "scenecapturetype", "subjectarea", "subjectlocation", "focalplane", "interop").any(normalized::contains) -> MetadataSection.IMAGE
        else -> MetadataSection.OTHER
    }
}

private fun metadataLabel(tag: String): String {
    if (tag.startsWith("XMP · ") || tag in setOf("Raw XMP", "XMP packet")) return tag
    return tag.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .replace('_', ' ')
}
