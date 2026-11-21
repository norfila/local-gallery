package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMetadataDetailsTest {
    @Test fun groupsExifAndXmpIntoMeaningfulCategories() {
        val expected = mapOf(
            "DateTimeOriginal" to MetadataSection.CAPTURE,
            "XMP · dc:date" to MetadataSection.CAPTURE,
            "LensModel" to MetadataSection.CAMERA,
            "Make" to MetadataSection.CAMERA,
            "PhotographicSensitivity" to MetadataSection.EXPOSURE,
            "ExposureTime" to MetadataSection.EXPOSURE,
            "GPSLatitude" to MetadataSection.LOCATION,
            "XMP · photoshop:City" to MetadataSection.LOCATION,
            "ImageWidth" to MetadataSection.IMAGE,
            "SubjectLocation" to MetadataSection.IMAGE,
            "SubjectArea" to MetadataSection.IMAGE,
            "XMP · xmp:Rating" to MetadataSection.RATINGS,
            "XMP · xmpMM:PreservedFileName" to MetadataSection.FILE,
            "MakerNote" to MetadataSection.OTHER,
            "Copyright" to MetadataSection.OTHER,
        )
        expected.forEach { (tag, category) -> assertEquals(tag, category, metadataSectionForTag(tag)) }
    }

    @Test fun hidesGeographicFieldsWithoutHidingImageSubjectPosition() {
        val result = categorizeMetadataAttributes(mapOf(
            "GPSLatitude" to "48/1, 8/1, 12/1",
            "GPSLongitude" to "11/1, 30/1, 0/1",
            "XMP · exif:GPSLatitude" to "48,8.2N",
            "XMP · iptc:LocationCreated / iptc:Name" to "Munich",
            "XMP · iptc:LocationShown / custom:Value" to "48.137,11.575",
            "XMP · photoshop:State" to "Bavaria",
            "SubjectLocation" to "500,300",
            "LensModel" to "Example lens",
        ), includeLocation = false)
        assertFalse(result.containsKey(MetadataSection.LOCATION))
        assertEquals(listOf(MetadataDetail("Subject Location", "500,300")), result[MetadataSection.IMAGE])
        assertTrue(result.getValue(MetadataSection.CAMERA).any { it.value == "Example lens" })
    }

    @Test fun readsXmpAttributesElementsListsAndResources() {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmp:Rating="4">
                <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Mountain &amp; lake</rdf:li></rdf:Alt></dc:title>
                <dc:subject><rdf:Bag><rdf:li>Travel</rdf:li><rdf:li>Nature</rdf:li></rdf:Bag></dc:subject>
                <xmp:CreatorTool>Editor</xmp:CreatorTool>
                <xmp:WebStatement rdf:resource="https://example.com/license"/>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        val properties = PhotoMetadataDetailsReader.extractXmpProperties(xml)
        assertEquals("4", properties["xmp:Rating"])
        assertEquals("Mountain & lake", properties["dc:title"])
        assertEquals("Travel; Nature", properties["dc:subject"])
        assertEquals("Editor", properties["xmp:CreatorTool"])
        assertEquals("https://example.com/license", properties["xmp:WebStatement"])
        assertFalse(properties.keys.any { it.startsWith("rdf:") || it.startsWith("x:") })
    }

    @Test fun retainsGpsContextInNestedXmpAndFiltersItBeforeDisplay() {
        val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:iptc="http://iptc.org/std/Iptc4xmpExt/2008-02-29/" xmlns:custom="https://example.com">
            <rdf:Description><iptc:LocationCreated rdf:parseType="Resource"><custom:Value>48.137,11.575</custom:Value></iptc:LocationCreated></rdf:Description>
        </rdf:RDF>"""
        val parsed = PhotoMetadataDetailsReader.extractXmpProperties(xml)
        assertEquals("48.137,11.575", parsed["iptc:LocationCreated / custom:Value"])
        val hidden = categorizeMetadataAttributes(parsed.mapKeys { "XMP · ${it.key}" }, false)
        assertTrue(hidden.isEmpty())
    }

    @Test fun doesNotFetchEntitiesAndRejectsMalformedOrExcessiveXmp() {
        assertTrue(PhotoMetadataDetailsReader.extractXmpProperties("""<!DOCTYPE r [<!ENTITY data SYSTEM "file:///private">]><r>&data;</r>""").isEmpty())
        assertTrue(PhotoMetadataDetailsReader.extractXmpProperties("<broken").isEmpty())
        assertTrue(PhotoMetadataDetailsReader.extractXmpProperties(" ".repeat(1024 * 1024 + 1)).isEmpty())
        assertTrue(PhotoMetadataDetailsReader.extractXmpProperties("<r>".repeat(65) + "value" + "</r>".repeat(65)).isEmpty())
    }

    @Test fun boundsXmpPropertyCountAndValueLength() {
        val xml = "<r xmlns:p=\"https://example.com\">" + (1..300).joinToString("") { "<p:Field$it>${"x".repeat(9000)}</p:Field$it>" } + "</r>"
        // Keep the packet below the packet bound while checking independent field bounds.
        val shortValues = xml.replace("x".repeat(9000), "value")
        assertEquals(256, PhotoMetadataDetailsReader.extractXmpProperties(shortValues).size)
        val oneLongValue = "<p:Field xmlns:p=\"https://example.com\">${"x".repeat(9000)}</p:Field>"
        assertEquals(8192, PhotoMetadataDetailsReader.extractXmpProperties(oneLongValue).getValue("p:Field").length)
    }
}
