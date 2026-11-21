package com.luma.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.core.content.ContextCompat
import com.luma.gallery.ui.GalleryApp
import com.luma.gallery.ui.GalleryTheme
import com.luma.gallery.ui.LocalGalleryDarkTheme
import com.luma.gallery.widget.WidgetContract
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.luma.gallery.data.AllFilesAccess
import com.luma.gallery.data.ExternalPhotoContract
import com.luma.gallery.data.ExternalPhotoReader
import com.luma.gallery.data.Photo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<GalleryViewModel>()
    private var permissionVersion by mutableIntStateOf(0)
    private var requestedAlbumId by mutableStateOf<Long?>(null)
    private var requestedPhoto by mutableStateOf<Photo?>(null)
    private var externalPhotoJob: Job? = null
    private val access = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
        model.refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveIntent(intent)
        enableEdgeToEdge()
        setContent {
            @Suppress("UNUSED_EXPRESSION")
            permissionVersion
            GalleryTheme(model.themeMode, roundedPhotoCorners = model.roundedPhotoCorners) {
                val dark = LocalGalleryDarkTheme.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                GalleryApp(
                    model = model,
                    requestedAlbumId = requestedAlbumId,
                    onAlbumRequestHandled = { requestedAlbumId = null },
                    requestedPhoto = requestedPhoto,
                    onPhotoRequestHandled = {
                        requestedPhoto = null
                        consumePhotoIntent()
                    },
                    hasPhotoAccess = canReadPhotos(),
                    hasFullPhotoAccess = if (Build.VERSION.SDK_INT >= 33) {
                        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
                    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE),
                    hasLocationAccess = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_MEDIA_LOCATION),
                    onRequestPhotos = ::requestPhotos,
                    onRequestLocation = {
                        if (Build.VERSION.SDK_INT >= 29) access.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
                    },
                    hasAllFilesAccess = AllFilesAccess.granted(this),
                )
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIntent(intent)
    }
    private fun receiveIntent(intent: Intent?) {
        externalPhotoJob?.cancel()
        requestedPhoto = null
        if (intent?.hasExtra(WidgetContract.EXTRA_ALBUM_ID) == true) {
            requestedAlbumId = intent.getLongExtra(WidgetContract.EXTRA_ALBUM_ID, -1L).takeIf { it > 0 }
            intent.removeExtra(WidgetContract.EXTRA_ALBUM_ID)
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            return
        }
        if (intent?.data?.scheme == "gallery-widget" && intent.data?.host == "album") {
            // A removed/unconfigured album widget opens the album list without an image request.
            requestedAlbumId = -1L
            intent.data = null
            intent.action = Intent.ACTION_MAIN
            return
        }
        val request = ExternalPhotoContract.request(intent)
        if (request == null) {
            if (intent?.action == Intent.ACTION_VIEW || intent?.action == ExternalPhotoContract.REVIEW_ACTION) {
                Toast.makeText(this, R.string.external_unsupported, Toast.LENGTH_LONG).show()
                consumePhotoIntent()
            }
            return
        }
        requestedAlbumId = null
        externalPhotoJob = lifecycleScope.launch {
            val photo = ExternalPhotoReader(this@MainActivity).read(request)
            if (photo != null) requestedPhoto = photo else {
                Toast.makeText(this@MainActivity, R.string.external_unreadable, Toast.LENGTH_LONG).show()
                consumePhotoIntent()
            }
        }
    }
    private fun consumePhotoIntent() {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == ExternalPhotoContract.REVIEW_ACTION) {
            intent?.data = null
            intent?.action = Intent.ACTION_MAIN
        }
    }
    override fun onResume() {
        super.onResume()
        permissionVersion++
        model.refresh()
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun canReadPhotos(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    /** Photo locations (EXIF GPS) are requested together with photo access; Android shows them as one decision. */
    private fun requestPhotos() {
        val media = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        access.launch(if (Build.VERSION.SDK_INT >= 29) media + Manifest.permission.ACCESS_MEDIA_LOCATION else media)
    }
}
