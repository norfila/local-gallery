package com.luma.gallery.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import java.util.UUID

/** Each home-screen instance keeps its own album, including after process restarts. */
internal class AlbumWidgetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("album_widgets", Context.MODE_PRIVATE)

    fun albumId(widgetId: Int): Long? = preferences.getLong("widget_$widgetId", -1).takeIf { it > 0 }

    fun save(widgetId: Int, albumId: Long) {
        require(widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && albumId > 0)
        preferences.edit().putLong("widget_$widgetId", albumId).commit()
    }

    fun remove(widgetId: Int) { preferences.edit().remove("widget_$widgetId").commit() }

    fun move(oldId: Int, newId: Int) {
        val album = albumId(oldId) ?: return
        save(newId, album)
        if (oldId != newId) remove(oldId)
    }

    fun newPin(albumId: Long): String {
        prunePins()
        val token = UUID.randomUUID().toString()
        preferences.edit().putLong("pin_$token", albumId).putLong("time_$token", System.currentTimeMillis()).commit()
        return token
    }

    /** A launcher callback may consume a pin request only once. */
    fun consumePin(token: String): Long? = synchronized(lock) {
        val albumId = preferences.getLong("pin_$token", -1).takeIf { it > 0 }
        preferences.edit().remove("pin_$token").remove("time_$token").commit()
        albumId
    }

    fun prunePins() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val edit = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("time_") && value is Long && value < cutoff) {
                val token = key.removePrefix("time_")
                edit.remove(key).remove("pin_$token")
            }
        }
        edit.apply()
    }

    private companion object { val lock = Any() }
}
