package com.luma.gallery.widget

import android.content.ComponentName
import android.content.Context

/** Separate picker entries let the launcher place the desired shape immediately. */
enum class AlbumWidgetSize(
    val columns: Int,
    val rows: Int,
    internal val providerClass: Class<out AlbumWidgetProvider>,
) {
    ONE_BY_ONE(1, 1, AlbumWidgetProvider::class.java),
    TWO_BY_ONE(2, 1, WideAlbumWidgetProvider::class.java),
    ONE_BY_TWO(1, 2, TallAlbumWidgetProvider::class.java),
    TWO_BY_TWO(2, 2, LargeAlbumWidgetProvider::class.java);

    val label: String get() = "$columns × $rows"
    internal fun component(context: Context): ComponentName = ComponentName(context, providerClass)

    companion object {
        internal fun recognizes(context: Context, component: ComponentName?): Boolean =
            entries.any { it.component(context) == component }
    }
}

// Keep the existing provider identity for 1×1 widgets so installed instances survive upgrades.
class WideAlbumWidgetProvider : AlbumWidgetProvider()
class TallAlbumWidgetProvider : AlbumWidgetProvider()
class LargeAlbumWidgetProvider : AlbumWidgetProvider()
