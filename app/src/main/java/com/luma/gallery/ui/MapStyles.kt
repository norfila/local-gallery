package com.luma.gallery.ui

import android.content.Context
import androidx.annotation.StringRes
import com.luma.gallery.R
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager

/**
 * Deletes the map images MapLibre keeps on the device (its ambient cache) once the online map is switched off.
 * Gallery stores no offline regions, so nothing else lives in that database. Call on the main thread.
 */
internal fun clearMapTileCache(context: Context, onDone: (Boolean) -> Unit = {}) {
    runCatching {
        MapLibre.getInstance(context.applicationContext)
        OfflineManager.getInstance(context.applicationContext).clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = onDone(true)
            override fun onError(message: String) = onDone(false)
        })
    }.onFailure { onDone(false) }
}

internal enum class GalleryMapStyle(@StringRes val labelRes: Int) {
    SIMPLE(R.string.map_style_simple),
    SATELLITE(R.string.map_style_satellite),
}

internal const val SATELLITE_CREDIT = "EOxCloudless by EOX IT Services GmbH"
internal const val SATELLITE_DATA_CREDIT = "Contains modified Copernicus Sentinel data 2025"
internal const val SATELLITE_LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/4.0/"

/**
 * Provider publishes this layer for personal/noncommercial use under CC BY-NC-SA 4.0:
 * https://cloudless.eox.at/documentation/license
 * Tile template and credits: https://tiles.maps.eox.at/wmts?SERVICE=WMTS&REQUEST=GetCapabilities
 * This app streams tiles; it does not offer satellite downloads or redistribute imagery.
 */
internal fun galleryMapStyle(style: GalleryMapStyle, dark: Boolean): Style.Builder = when (style) {
    GalleryMapStyle.SIMPLE -> Style.Builder().fromUri(
        "https://tiles.openfreemap.org/styles/${if (dark) "dark" else "liberty"}",
    )
    GalleryMapStyle.SATELLITE -> Style.Builder().fromJson(
        """
        {
          "version": 8,
          "name": "EOxCloudless Sentinel-2 2025",
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "sources": {
            "sentinel-satellite": {
              "type": "raster",
              "tiles": ["https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg"],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": 14,
              "attribution": "$SATELLITE_CREDIT ($SATELLITE_DATA_CREDIT), CC BY-NC-SA 4.0"
            }
          },
          "layers": [
            {"id": "satellite-background", "type": "background", "paint": {"background-color": "#111713"}},
            {"id": "satellite-imagery", "type": "raster", "source": "sentinel-satellite", "paint": {"raster-fade-duration": 150}}
          ]
        }
        """.trimIndent(),
    )
}
