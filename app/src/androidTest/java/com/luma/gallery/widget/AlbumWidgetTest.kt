package com.luma.gallery.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.luma.gallery.MainActivity
import com.luma.gallery.R
import com.luma.gallery.data.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AlbumWidgetTest {
    @Test fun bitmapCornersAreTransparentWithoutDependingOnViewOutlineClipping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        listOf(48 to 48, 160 to 96, 96 to 160).forEach { (width, height) ->
            val cover = roundedWidgetCover(original, width, height, 20f)
            assertEquals(width, cover.width)
            assertEquals(height, cover.height)
            listOf(0 to 0, (width - 1) to 0, 0 to (height - 1), (width - 1) to (height - 1)).forEach { (x, y) ->
                assertEquals("The bitmap itself must have transparent corners on every Android version", 0, Color.alpha(cover.getPixel(x, y)))
            }
            assertEquals(Color.RED, cover.getPixel(width / 2, height / 2))
            val views = RemoteViews(context.packageName, R.layout.widget_album)
            views.setImageViewBitmap(R.id.widget_album_cover, cover)
            instrumentation.runOnMainSync {
                val rendered = views.apply(context, null).findViewById<ImageView>(R.id.widget_album_cover)
                assertEquals("Scaling must retain the rounded pixel mask instead of cropping it away", ImageView.ScaleType.FIT_XY, rendered.scaleType)
            }
            cover.recycle()
        }
        original.recycle()
    }

    @Test fun independentWidgetsPersistAndDeletingOneDoesNotAffectAnother() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "widget-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = app.getSharedPreferences(name, mode)
        }
        try {
            AlbumWidgetStore(context).apply { save(101, 12); save(102, 34) }
            val reopened = AlbumWidgetStore(context)
            assertEquals(12L, reopened.albumId(101))
            assertEquals(34L, reopened.albumId(102))
            reopened.move(101, 103)
            assertNull(reopened.albumId(101))
            assertEquals(12L, reopened.albumId(103))
            AlbumWidgetProvider().onDeleted(context, intArrayOf(103))
            assertNull(reopened.albumId(103))
            assertEquals(34L, reopened.albumId(102))
            val token = reopened.newPin(56)
            assertEquals(56L, AlbumWidgetStore(context).consumePin(token))
            assertNull(reopened.consumePin(token))
        } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun launcherPickerOffersFourSizesAndEachProviderRendersItsAlbum() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, UUID.randomUUID().hashCode())
        val storage = AlbumWidgetStore(context)
        val library = LibraryStore(context)
        val album = library.createAlbum("Widget sizes ${UUID.randomUUID()}")
        val ids = mutableListOf<Int>()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            host.startListening()
            AlbumWidgetSize.entries.forEach { size ->
                val id = host.allocateAppWidgetId().also(ids::add)
                assertTrue("Launcher must recognize ${size.label}", manager.bindAppWidgetIdIfAllowed(id, size.component(context)))
                val info = manager.getAppWidgetInfo(id)
                assertTrue(AlbumWidgetSize.recognizes(context, info.provider))
                assertEquals(ComponentName(context, AlbumWidgetConfigureActivity::class.java), info.configure)
                if (Build.VERSION.SDK_INT >= 31) {
                    assertEquals(size.columns, info.targetCellWidth)
                    assertEquals(size.rows, info.targetCellHeight)
                }
                assertEquals(3, info.resizeMode)
                assertTrue(info.loadLabel(context.packageManager).contains(size.label))
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 72 * size.columns)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 72 * size.columns)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72 * size.rows)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 72 * size.rows)
                })
                storage.save(id, album.id)
                AlbumWidgetProvider.updateWidgets(context, intArrayOf(id))
                instrumentation.runOnMainSync {
                    val view = host.createView(context, id, info)
                    val density = context.resources.displayMetrics.density
                    val width = (72 * size.columns * density).toInt()
                    val height = (72 * size.rows * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    assertEquals(album.name, view.findViewById<TextView>(R.id.widget_album_title).text.toString())
                    val image = view.findViewById<View>(R.id.widget_album_image)
                    assertTrue("${size.label} must fill its chosen shape", image.width > 0 && image.height > 0)
                    assertTrue(view.findViewById<View>(R.id.widget_album_root).hasOnClickListeners())
                }
            }
            assertTrue(!AlbumWidgetSize.recognizes(context, ComponentName(context, MainActivity::class.java)))
        } finally {
            ids.forEach { host.deleteAppWidgetId(it); storage.remove(it) }
            host.stopListening()
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            library.deleteAlbum(album.id)
            library.close()
        }
    }

    /** Uses Android's real host/service path, including inflation and album PendingIntent routing. */
    @Test fun realWidgetHostRendersSeparateAlbumsAndReflectsRenameAndDeletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, AlbumWidgetProvider::class.java)
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, UUID.randomUUID().hashCode())
        val storage = AlbumWidgetStore(context)
        val library = LibraryStore(context)
        val first = library.createAlbum("Widget test A ${UUID.randomUUID()}")
        val second = library.createAlbum("Widget test B ${UUID.randomUUID()}")
        val widgetIds = mutableListOf<Int>()
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
            val idA = host.allocateAppWidgetId().also { widgetIds += it }
            val idB = host.allocateAppWidgetId().also { widgetIds += it }
            storage.save(idA, first.id)
            storage.save(idB, second.id)
            assertTrue("The emulator must allow the test host to bind widgets", manager.bindAppWidgetIdIfAllowed(idA, component))
            assertTrue(manager.bindAppWidgetIdIfAllowed(idB, AlbumWidgetSize.TWO_BY_ONE.component(context)))
            assertNotNull(manager.getAppWidgetInfo(idA))
            val widgetInfo = manager.getAppWidgetInfo(idA)
            val density = context.resources.displayMetrics.density
            assertTrue("The default minimum must fit a single launcher cell", widgetInfo.minWidth / density <= 48 && widgetInfo.minHeight / density <= 48)
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals(1, widgetInfo.targetCellWidth)
                assertEquals(1, widgetInfo.targetCellHeight)
            }
            manager.updateAppWidgetOptions(idA, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 48)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 48)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 48)
            })
            host.startListening()
            AlbumWidgetProvider.updateWidgets(context, intArrayOf(idA, idB))
            fun hostView(id: Int): AppWidgetHostView {
                var view: AppWidgetHostView? = null
                instrumentation.runOnMainSync { view = host.createView(context, id, manager.getAppWidgetInfo(id)) }
                return view!!
            }
            fun title(id: Int): String = hostView(id).findViewById<TextView>(R.id.widget_album_title).text.toString()
            val firstView = hostView(idA)
            val secondView = hostView(idB)
            assertEquals(first.name, firstView.findViewById<TextView>(R.id.widget_album_title).text.toString())
            assertEquals(second.name, secondView.findViewById<TextView>(R.id.widget_album_title).text.toString())
            instrumentation.runOnMainSync {
                val smallSize = (48 * density).toInt()
                firstView.measure(View.MeasureSpec.makeMeasureSpec(smallSize, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(smallSize, View.MeasureSpec.EXACTLY))
                firstView.layout(0, 0, smallSize, smallSize)
                // View assigns a background's drawable bounds during drawing. Layout alone
                // leaves an unattached host's GradientDrawable outline empty (radius -1).
                val raster = Bitmap.createBitmap(smallSize, smallSize, Bitmap.Config.ARGB_8888)
                firstView.draw(Canvas(raster))
                raster.recycle()
                val root = firstView.findViewById<View>(R.id.widget_album_root)
                val cover = firstView.findViewById<View>(R.id.widget_album_cover)
                val image = firstView.findViewById<View>(R.id.widget_album_image)
                val title = firstView.findViewById<TextView>(R.id.widget_album_title)
                assertTrue("One-cell title must have real space", title.width > 0 && title.height > 0)
                assertEquals(View.GONE, firstView.findViewById<View>(R.id.widget_album_count).visibility)
                if (Build.VERSION.SDK_INT >= 31) {
                    assertTrue("Both a cover and its placeholder must be inside rounded clipping", root.clipToOutline && image.clipToOutline && cover.clipToOutline)
                }
                listOf(root, image, cover).forEach { rounded ->
                    val name = context.resources.getResourceEntryName(rounded.id)
                    val outline = Outline()
                    rounded.background.getOutline(outline)
                    assertTrue("$name must have nonempty drawable bounds after its first draw: ${rounded.background.bounds}", !rounded.background.bounds.isEmpty)
                    assertTrue("$name needs a rounded drawable outline; radius=${outline.radius}, bounds=${rounded.background.bounds}", outline.radius > 0)
                }
            }
            val foreground = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            instrumentation.waitForIdleSync()
            fun accessibleTexts(root: AccessibilityNodeInfo?): List<String> {
                if (root == null) return emptyList()
                val texts = mutableListOf<String>()
                fun collect(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 40) return
                    node.text?.toString()?.let(texts::add)
                    node.contentDescription?.toString()?.let(texts::add)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, depth + 1) }
                }
                collect(root, 0)
                return texts
            }
            fun clickAndAwaitAlbum(view: AppWidgetHostView, albumName: String) {
                instrumentation.runOnMainSync {
                    // Real launcher widgets are attached to a foreground window. Reproduce that
                    // context without introducing an ActivityScenario that owns Gallery's task.
                    val windowRoot = foreground.window.decorView as ViewGroup
                    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    windowRoot.addView(view, ViewGroup.LayoutParams(1, 1))
                    try {
                        assertNotNull("A widget click must have a real display", view.display)
                        assertTrue("The widget root must expose its album PendingIntent", view.findViewById<View>(R.id.widget_album_root).performClick())
                    } finally { windowRoot.removeView(view) }
                }
                val deadline = SystemClock.uptimeMillis() + 15_000
                var albumVisible = false
                var texts: List<String> = emptyList()
                while (SystemClock.uptimeMillis() < deadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    // Some Compose accessibility providers expose children but do not implement
                    // findAccessibilityNodeInfosByText; traverse those virtual descendants too.
                    texts = accessibleTexts(root)
                    albumVisible = root != null && root.packageName?.toString() == context.packageName &&
                        (root.findAccessibilityNodeInfosByText(albumName).any { it.text?.toString() == albumName } || albumName in texts) &&
                        "Add photos" in texts
                    if (albumVisible) break
                    SystemClock.sleep(100)
                }
                assertTrue("Tapping the widget must open its own album: $albumName. Visible accessibility text: $texts", albumVisible)
            }
            clickAndAwaitAlbum(firstView, first.name)
            // Reuse the running MainActivity to verify the second widget has its own identity.
            clickAndAwaitAlbum(secondView, second.name)
            library.renameAlbum(first.id, "Renamed widget ${UUID.randomUUID()}")
            library.deleteAlbum(second.id)
            AlbumWidgetProvider.updateWidgets(context, intArrayOf(idA, idB))
            assertTrue(title(idA).startsWith("Renamed widget"))
            assertEquals("Album removed", title(idB))
        } finally {
            // Widget clicks start real activities without an ActivityScenario owner.
            instrumentation.runOnMainSync {
                Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            widgetIds.forEach { id -> host.deleteAppWidgetId(id); storage.remove(id) }
            host.stopListening()
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            library.deleteAlbum(first.id)
            library.deleteAlbum(second.id)
            library.close()
        }
    }
}
