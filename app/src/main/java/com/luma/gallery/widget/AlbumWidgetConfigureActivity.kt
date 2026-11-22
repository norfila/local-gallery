package com.luma.gallery.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.luma.gallery.MainActivity
import com.luma.gallery.R
import com.luma.gallery.data.Album
import com.luma.gallery.data.AppPreferences
import com.luma.gallery.data.LibraryStore
import com.luma.gallery.data.MediaRepository
import com.luma.gallery.data.Photo
import com.luma.gallery.image.rememberThumbnailRequest
import com.luma.gallery.ui.GalleryTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Used by the Android widget picker, by "reconfigure" on the home screen and when an empty
 * widget is tapped. Cancelling never leaves a half-configured instance.
 */
class AlbumWidgetConfigureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            !AlbumWidgetSize.recognizes(this, AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider)) {
            finish(); return
        }
        enableEdgeToEdge()
        setContent {
            GalleryTheme(AppPreferences(this).themeMode(), AppPreferences(this).colorPalette(), AppPreferences(this).pureBlack()) {
                var albums by remember { mutableStateOf<List<Album>?>(null) }
                var library by remember { mutableStateOf<List<Photo>>(emptyList()) }
                var failed by remember { mutableStateOf(false) }
                var saving by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    albums = withContext(Dispatchers.IO) { runCatching { LibraryStore(this@AlbumWidgetConfigureActivity).use { it.albums() } }.getOrNull() }
                    failed = albums == null
                    library = withContext(Dispatchers.IO) {
                        runCatching { MediaRepository(this@AlbumWidgetConfigureActivity).use { it.loadPhotos().filterNot(Photo::hidden) } }.getOrDefault(emptyList())
                    }
                }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.widget_configure_heading), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp))
                        Text(stringResource(R.string.widget_configure_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when {
                            failed -> Text(stringResource(R.string.widget_configure_failed))
                            albums == null || saving -> CircularProgressIndicator()
                            albums!!.isEmpty() -> {
                                Text(stringResource(R.string.widget_configure_empty))
                                Button(onClick = { startActivity(Intent(this@AlbumWidgetConfigureActivity, MainActivity::class.java)); finish() }) {
                                    Text(stringResource(R.string.widget_configure_open_gallery))
                                }
                            }
                            else -> LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(albums!!, key = { it.id }) { album ->
                                    val members = remember(album, library) { album.photos(library) }
                                    val cover = remember(album, members) { album.coverPhoto(members) }
                                    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable(enabled = !saving) {
                                        saving = true
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    AlbumWidgetStore(this@AlbumWidgetConfigureActivity).save(widgetId, album.id)
                                                    AlbumWidgetProvider.updateWidgets(this@AlbumWidgetConfigureActivity, intArrayOf(widgetId))
                                                }
                                            }
                                            if (result.isSuccess) {
                                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                                finish()
                                            } else {
                                                saving = false
                                                failed = true
                                            }
                                        }
                                    }) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                            if (cover != null) AsyncImage(rememberThumbnailRequest(cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            else Icon(Icons.Outlined.PhotoAlbum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                        }
                                        Text(album.name, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(pluralStringResource(R.plurals.item_count, members.size, members.size), Modifier.padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { finish() }, modifier = Modifier.padding(bottom = 12.dp)) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}
