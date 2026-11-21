package com.luma.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.exifinterface.media.ExifInterface
import java.io.File

internal val metadataFixtureUri: Uri get() = Uri.parse("content://com.luma.gallery.test.metadata/photo")

/** Prepare the JPEG in the instrumented app, where Kotlin and AndroidX dependencies are available. */
internal fun seedMetadataFixture(context: Context) {
    val image = File.createTempFile("metadata-fixture-", ".jpg", context.cacheDir)
    try {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { bitmap.recycle() }
        ExifInterface(image.absolutePath).apply {
            setLatLong(48.137, 11.575)
            setAttribute(ExifInterface.TAG_MAKE, "Gallery fixture")
            setAttribute(ExifInterface.TAG_XMP, """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="4"/></rdf:RDF></x:xmpmeta>""")
            saveAttributes()
        }
        context.contentResolver.call(metadataFixtureUri, "seed", null, Bundle().apply { putByteArray("bytes", image.readBytes()) })
    } finally { image.delete() }
}
