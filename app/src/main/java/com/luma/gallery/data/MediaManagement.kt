package com.luma.gallery.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * Android 12+ "Media management apps" access. Android asks before an app changes, bins or deletes a file it did
 * not create; with this access, granted in Android's settings, it approves those requests without showing its
 * window. Moving and deleting still go through the same MediaStore requests, so nothing else changes.
 */
object MediaManagement {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    /** This app's switch first; the list of all apps if a phone does not open that directly. */
    fun settingsIntents(context: Context): List<Intent> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA),
    ) else emptyList()
}
