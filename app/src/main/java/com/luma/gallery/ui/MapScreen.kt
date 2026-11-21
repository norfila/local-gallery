package com.luma.gallery.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.PrivacyTip
import com.luma.gallery.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.luma.gallery.data.Photo
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val PHOTO_SOURCE = "luma-photo-locations"
private const val PHOTO_POINTS = "luma-photo-pins"
private const val PHOTO_CLUSTERS = "luma-photo-clusters"
private const val PHOTO_COUNTS = "luma-photo-counts"
private const val PHOTO_URI = "photo_uri"

/**
 * Photo locations come exclusively from photo metadata; device location is never requested. The online map loads
 * only once the user agreed ([onlineMap] true). Until then a placeholder drawn on the device asks first, and no map
 * code runs, so nothing is downloaded.
 */
@Composable
fun PhotoMapScreen(
    photos: List<Photo>,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onlineMap: Boolean?,
    onOnlineMapChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (onlineMap == true) OnlineMap(photos, hasLocationPermission, onRequestLocationPermission, onPhotoClick,
        onTurnOff = { onOnlineMapChange(false) }, modifier = modifier)
    else MapConsent(asked = onlineMap == false, onAllow = { onOnlineMapChange(true) }, onDecline = { onOnlineMapChange(false) },
        modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineMap(
    photos: List<Photo>,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = LocalGalleryDarkTheme.current
    val density = LocalDensity.current.density
    val mapPreferences = remember(context) {
        context.applicationContext.getSharedPreferences("luma-map", Context.MODE_PRIVATE)
    }
    var selectedMapStyle by remember(mapPreferences) {
        mutableStateOf(GalleryMapStyle.entries.firstOrNull {
            it.name == mapPreferences.getString("basemap", null)
        } ?: GalleryMapStyle.SIMPLE)
    }
    val locatedPhotos = remember(photos) {
        photos.filter { photo ->
            val latitude = photo.latitude
            val longitude = photo.longitude
            latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
    }
    val features = remember(locatedPhotos) { photoFeatures(locatedPhotos) }
    val currentPhotos by rememberUpdatedState(locatedPhotos.associateBy { it.uri })
    val currentOnPhotoClick by rememberUpdatedState(onPhotoClick)
    var selectedUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sourceReady by remember { mutableStateOf(false) }
    var styleRequest by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var hasFramedPhotos by remember { mutableStateOf(false) }
    var maximized by remember { mutableStateOf(false) }

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        // A TextureView composes with AndroidView clipping and captures with the rest of the UI.
        MapView(context, MapLibreMapOptions().textureMode(true)).apply { onCreate(Bundle()) }
    }

    // Compose can enter a screen after ON_RESUME, so synchronize current state as well as events.
    // Disposal also covers switching tabs while the enclosing activity remains resumed.
    DisposableEffect(mapView, lifecycleOwner) {
        var started = false
        var resumed = false
        var destroyed = false
        fun synchronize(state: Lifecycle.State) {
            if (destroyed) return
            if (!state.isAtLeast(Lifecycle.State.RESUMED) && resumed) {
                mapView.onPause()
                resumed = false
            }
            if (!state.isAtLeast(Lifecycle.State.STARTED) && started) {
                mapView.onStop()
                started = false
            }
            if (state.isAtLeast(Lifecycle.State.STARTED) && !started) {
                mapView.onStart()
                started = true
            }
            if (state.isAtLeast(Lifecycle.State.RESUMED) && !resumed) {
                mapView.onResume()
                resumed = true
            }
        }
        val observer = LifecycleEventObserver { _, _ ->
            synchronize(lifecycleOwner.lifecycle.currentState)
        }
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { if (!destroyed) mapView.onLowMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !destroyed) mapView.onLowMemory()
            }
        }
        val failureListener = MapView.OnDidFailLoadingMapListener {
            if (!destroyed) {
                error = resources.getString(R.string.map_error_load)
                loading = false
            }
        }
        val renderListener = MapView.OnDidFinishRenderingMapListener { fully ->
            if (fully && !destroyed) mapView.post {
                val activeMap = map
                if (!destroyed && sourceReady && style != null && activeMap != null) {
                    // Style JSON can load well before tiles and the asynchronous GeoJSON source.
                    // Only report ready once a complete native frame contains our photo pins.
                    val visibleBounds = activeMap.projection.visibleRegion.latLngBounds
                    val hasVisiblePhotos = currentPhotos.values.any { photo ->
                        visibleBounds.contains(LatLng(photo.latitude!!, photo.longitude!!))
                    }
                    val pinsRendered = !hasVisiblePhotos || activeMap.queryRenderedFeatures(
                        RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat()),
                        PHOTO_POINTS, PHOTO_CLUSTERS,
                    ).isNotEmpty()
                    if (pinsRendered) {
                        loading = false
                        error = null
                    }
                }
            }
        }
        var clickListener: MapLibreMap.OnMapClickListener? = null
        mapView.addOnDidFailLoadingMapListener(failureListener)
        mapView.addOnDidFinishRenderingMapListener(renderListener)
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        lifecycleOwner.lifecycle.addObserver(observer)
        synchronize(lifecycleOwner.lifecycle.currentState)
        mapView.getMapAsync { readyMap ->
            if (!destroyed) {
                readyMap.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = false // Clickable attribution is below the map controls.
                }
                val firstPhoto = currentPhotos.values.firstOrNull()
                val initialTarget = firstPhoto?.let { LatLng(it.latitude!!, it.longitude!!) } ?: LatLng(0.0, 0.0)
                // Avoid downloading a world of labels before framing a local photo collection.
                readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialTarget, if (firstPhoto == null) 1.0 else 12.0))
                clickListener = MapLibreMap.OnMapClickListener { location ->
                    val activeStyle = readyMap.style
                    if (activeStyle?.getSource(PHOTO_SOURCE) == null) {
                        false
                    } else {
                        val screen = readyMap.projection.toScreenLocation(location)
                        val radius = 20 * density
                        val hits = readyMap.queryRenderedFeatures(
                            RectF(screen.x - radius, screen.y - radius, screen.x + radius, screen.y + radius),
                            PHOTO_POINTS, PHOTO_CLUSTERS,
                        )
                        val cluster = hits.firstOrNull { it.hasProperty("cluster_id") }
                        val tappedUris = if (cluster != null) {
                            // At the maximum zoom, coincident photos still open as a browsable group.
                            val source = activeStyle.getSourceAs<GeoJsonSource>(PHOTO_SOURCE)
                            val count = cluster.getNumberProperty("point_count").toLong()
                            source?.getClusterLeaves(cluster, count, 0)?.features().orEmpty()
                                .mapNotNull { it.getStringProperty(PHOTO_URI) }
                        } else {
                            hits.mapNotNull { if (it.hasProperty(PHOTO_URI)) it.getStringProperty(PHOTO_URI) else null }
                        }.distinct().filter { it in currentPhotos }
                        when (tappedUris.size) {
                            0 -> false
                            1 -> {
                                currentPhotos[tappedUris.first()]?.let(currentOnPhotoClick)
                                true
                            }
                            else -> {
                                selectedUris = tappedUris
                                true
                            }
                        }
                    }
                }
                readyMap.addOnMapClickListener(clickListener!!)
                map = readyMap
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            mapView.removeOnDidFailLoadingMapListener(failureListener)
            mapView.removeOnDidFinishRenderingMapListener(renderListener)
            clickListener?.let { map?.removeOnMapClickListener(it) }
            synchronize(Lifecycle.State.CREATED)
            destroyed = true
            mapView.onDestroy()
            map = null
            style = null
            sourceReady = false
            styleRequest++
        }
    }

    LaunchedEffect(map, dark, retry, selectedMapStyle) {
        val activeMap = map ?: return@LaunchedEffect
        error = null
        loading = true
        style = null
        sourceReady = false
        val request = ++styleRequest
        // Changing the style on the same map preserves its camera and gesture state.
        activeMap.setStyle(galleryMapStyle(selectedMapStyle, dark)) { loadedStyle ->
            if (!mapView.isDestroyed && request == styleRequest) {
                addPhotoLayers(loadedStyle, dark)
                style = loadedStyle
                error = null
            }
        }
        delay(30_000)
        if (request == styleRequest && loading) {
            loading = false
            error = resources.getString(R.string.map_error_slow)
        }
    }

    LaunchedEffect(style, features) {
        style?.getSourceAs<GeoJsonSource>(PHOTO_SOURCE)?.let { source ->
            source.setGeoJson(features)
            sourceReady = true
        }
        if (style != null && !hasFramedPhotos && locatedPhotos.isNotEmpty()) {
            map?.let { fitPhotoLocations(it, locatedPhotos, density, animate = false) }
            hasFramedPhotos = true
        }
    }

    // Keep the native view and its lifecycle above both hosts. Moving it into a dialog
    // expands the viewport without reloading the style, resetting the camera, or
    // creating a second map behind the full-screen window.
    // Embedded, the map is a card inside the already inset library; only full screen reaches under the system bars.
    val safeDrawing = WindowInsets.safeDrawing
    val composeDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insetTop = if (maximized) safeDrawing.getTop(composeDensity) else 0
    val insetLeft = if (maximized) safeDrawing.getLeft(composeDensity, layoutDirection) else 0
    val insetRight = if (maximized) safeDrawing.getRight(composeDensity, layoutDirection) else 0
    // The compass (shown once the map is turned) sits below the full-screen button instead of under it.
    LaunchedEffect(map, insetTop, insetLeft, insetRight) {
        val margin = (16 * density).toInt()
        map?.uiSettings?.setCompassMargins(insetLeft + margin, insetTop + ((14 + 44 + 8) * density).toInt(), insetRight + margin, 0)
    }
    val edgeInsets = if (maximized) Modifier.windowInsetsPadding(safeDrawing) else Modifier
    // The question before loading already explained the map; the shield button reopens it and turns the map off.
    var privacyNoteVisible by remember { mutableStateOf(false) }
    val mapDescription = stringResource(R.string.map_description)
    val mapState = stringResource(when {
        error != null -> R.string.map_unavailable
        loading -> R.string.map_loading_state
        else -> R.string.map_ready
    })
    val mapContent: @Composable () -> Unit = {
        // Embedded, the map is a rounded card like the other tiles; full screen it reaches the edges.
        Box((if (maximized) Modifier.fillMaxSize() else modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)))
            .background(MaterialTheme.colorScheme.background).testTag("photo-map").semantics {
            contentDescription = mapDescription
            stateDescription = mapState
        }) {
            AndroidView(factory = {
                (mapView.parent as? ViewGroup)?.removeView(mapView)
                mapView
            }, modifier = Modifier.fillMaxSize())
    
            FilledTonalIconButton(
                onClick = { maximized = !maximized },
                modifier = Modifier.align(Alignment.TopEnd).then(edgeInsets)
                    .padding(14.dp).size(44.dp).testTag("map-maximize"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    if (maximized) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    stringResource(if (maximized) R.string.map_restore else R.string.map_maximize),
                )
            }
    
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .then(edgeInsets).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (privacyNoteVisible) {
                    MapMessageCard(
                        title = stringResource(R.string.map_privacy_title),
                        message = stringResource(R.string.map_privacy_body),
                        action = stringResource(R.string.action_got_it),
                        onAction = { privacyNoteVisible = false },
                        secondaryAction = stringResource(R.string.map_turn_off),
                        onSecondaryAction = onTurnOff,
                        icon = Icons.Outlined.PrivacyTip,
                        modifier = Modifier.testTag("map-privacy-note"),
                    )
                }
                if (error != null) {
                    MapMessageCard(
                        title = stringResource(R.string.map_unavailable),
                        message = error!!,
                        action = stringResource(R.string.action_retry),
                        onAction = { retry++ },
                        secondaryAction = if (selectedMapStyle == GalleryMapStyle.SATELLITE) stringResource(R.string.map_use_simple) else null,
                        onSecondaryAction = {
                            selectedMapStyle = GalleryMapStyle.SIMPLE
                            mapPreferences.edit().putString("basemap", GalleryMapStyle.SIMPLE.name).apply()
                        },
                    )
                }
                if (!hasLocationPermission) {
                    MapMessageCard(
                        title = stringResource(R.string.map_unlock_title),
                        message = stringResource(R.string.map_unlock_message),
                        action = stringResource(R.string.map_unlock_action),
                        onAction = onRequestLocationPermission,
                    )
                } else if (locatedPhotos.isEmpty()) {
                    MapMessageCard(
                        title = stringResource(R.string.map_no_locations_title),
                        message = stringResource(if (photos.isEmpty()) R.string.map_no_photos_message else R.string.map_no_gps_message),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    shadowElevation = 3.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GalleryMapStyle.entries.forEach { option ->
                                FilterChip(
                                    selected = selectedMapStyle == option,
                                    onClick = {
                                        selectedMapStyle = option
                                        mapPreferences.edit().putString("basemap", option.name).apply()
                                    },
                                    label = { Text(stringResource(option.labelRes), style = MaterialTheme.typography.bodySmall) },
                                    leadingIcon = {
                                        Icon(if (option == GalleryMapStyle.SIMPLE) Icons.Outlined.Map else Icons.Outlined.SatelliteAlt,
                                            null, Modifier.size(17.dp))
                                    },
                                    modifier = Modifier.weight(1f).testTag("map-style-${option.name.lowercase()}"),
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { map?.let { fitPhotoLocations(it, locatedPhotos, density) } },
                                enabled = map != null && locatedPhotos.isNotEmpty(),
                                modifier = Modifier.size(42.dp), shape = RoundedCornerShape(13.dp),
                            ) {
                                Icon(Icons.Outlined.MyLocation, stringResource(R.string.map_fit), Modifier.size(21.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { privacyNoteVisible = !privacyNoteVisible },
                                modifier = Modifier.size(42.dp).testTag("map-privacy-info"), shape = RoundedCornerShape(13.dp),
                            ) {
                                Icon(Icons.Outlined.PrivacyTip, stringResource(R.string.map_privacy_show), Modifier.size(20.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pluralStringResource(R.plurals.map_photo_count, locatedPhotos.size, locatedPhotos.size),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (loading) {
                                Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(7.dp))
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        }
                        MapAttribution(selectedMapStyle)
                    }
                }
            }
        }
    }
    if (maximized) {
        Dialog(
            onDismissRequest = { maximized = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().testTag("full-screen-map")) { mapContent() }
        }
    } else {
        mapContent()
    }

    val selectedPhotos = selectedUris.mapNotNull { currentPhotos[it] }
    if (selectedPhotos.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { selectedUris = emptyList() }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_place_title), style = MaterialTheme.typography.titleLarge)
                    Text(pluralStringResource(R.plurals.map_place_count, selectedPhotos.size, selectedPhotos.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { selectedUris = emptyList() }) { Icon(Icons.Outlined.Close, stringResource(R.string.map_place_close)) }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(selectedPhotos, key = { it.uri }) { photo ->
                    Box(
                        modifier = Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { selectedUris = emptyList(); currentOnPhotoClick(photo) },
                    ) {
                        AsyncImage(model = photo.uri, contentDescription = photo.name, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize())
                        if (photo.rating > 0) {
                            Text("★ ${photo.rating}", color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f))
                                    .padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapMessageCard(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                // Two longer labels (for example in German) wrap onto a second line instead of being cut off.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onAction) { Text(action) }
                    if (secondaryAction != null) {
                        TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapAttribution(mapStyle: GalleryMapStyle) {
    val uriHandler = LocalUriHandler.current
    if (mapStyle == GalleryMapStyle.SATELLITE) {
        Column {
            Text(SATELLITE_CREDIT, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { uriHandler.openUri("https://cloudless.eox.at/") }.padding(vertical = 2.dp))
            Text(SATELLITE_DATA_CREDIT, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CC BY-NC-SA 4.0", fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { uriHandler.openUri(SATELLITE_LICENSE_URL) }.padding(vertical = 3.dp))
                Text(stringResource(R.string.map_fonts_credit), fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { uriHandler.openUri("https://openfreemap.org/") }.padding(vertical = 3.dp))
            }
        }
        return
    }
    val sources = listOf(
        "OpenFreeMap" to "https://openfreemap.org/",
        "© OpenMapTiles" to "https://openmaptiles.org/",
        "© OpenStreetMap" to "https://www.openstreetmap.org/copyright",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        sources.forEach { (label, url) ->
            Text(label, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { uriHandler.openUri(url) }.padding(vertical = 4.dp))
        }
    }
}

private fun photoFeatures(photos: List<Photo>): FeatureCollection = FeatureCollection.fromFeatures(
    photos.map { photo ->
        Feature.fromGeometry(Point.fromLngLat(photo.longitude!!, photo.latitude!!)).apply {
            addStringProperty(PHOTO_URI, photo.uri)
        }
    },
)

private fun addPhotoLayers(style: Style, dark: Boolean) {
    val sage = android.graphics.Color.parseColor(if (dark) "#BDCEA5" else "#476747")
    val ink = android.graphics.Color.parseColor(if (dark) "#17261E" else "#FFFDF4")
    style.addSource(
        GeoJsonSource(PHOTO_SOURCE, FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(true).withClusterMaxZoom(18).withClusterRadius(56)),
    )
    style.addLayer(CircleLayer(PHOTO_CLUSTERS, PHOTO_SOURCE).apply {
        setFilter(Expression.has("point_count"))
        setProperties(
            PropertyFactory.circleColor(sage), PropertyFactory.circleRadius(23f),
            PropertyFactory.circleStrokeColor(ink), PropertyFactory.circleStrokeWidth(3f),
        )
    })
    style.addLayer(CircleLayer(PHOTO_POINTS, PHOTO_SOURCE).apply {
        setFilter(Expression.not(Expression.has("point_count")))
        setProperties(
            PropertyFactory.circleColor(sage), PropertyFactory.circleRadius(10f),
            PropertyFactory.circleStrokeColor(ink), PropertyFactory.circleStrokeWidth(3f),
        )
    })
    style.addLayer(SymbolLayer(PHOTO_COUNTS, PHOTO_SOURCE).apply {
        setFilter(Expression.has("point_count"))
        setProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f), PropertyFactory.textColor(ink),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        )
    })
}

private fun fitPhotoLocations(map: MapLibreMap, photos: List<Photo>, density: Float, animate: Boolean = true) {
    if (photos.isEmpty()) return
    val coordinates = photos.map { LatLng(it.latitude!!, it.longitude!!) }.distinctBy { it.latitude to it.longitude }
    val camera = if (coordinates.size == 1) {
        CameraUpdateFactory.newLatLngZoom(coordinates.first(), 12.0)
    } else {
        val bounds = LatLngBounds.Builder().includes(coordinates).build()
        val padding = minOf(100 * density, map.width * 0.15f, map.height * 0.18f).toInt().coerceAtLeast(0)
        CameraUpdateFactory.newLatLngBounds(bounds, padding)
    }
    if (animate) map.animateCamera(camera) else map.moveCamera(camera)
}
