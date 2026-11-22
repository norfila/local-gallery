package com.luma.gallery.data

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.luma.gallery.R

/**
 * The languages offered in Settings. An empty tag follows the phone's language. The choice is not kept in
 * [AppPreferences]: Android 13+ stores it itself (and shows it in its per-app language setting), and AppCompat
 * stores it on older versions. A new language also needs a values-xx folder, res/xml/locales_config.xml and
 * androidResources.localeFilters in app/build.gradle.kts.
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    SYSTEM("", R.string.language_system),
    ENGLISH("en", R.string.language_en),
    GERMAN("de", R.string.language_de);

    /** Switches the app to this language. Android rebuilds the open screens in it. */
    fun apply() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    companion object {
        /** The language for a stored tag list such as "de-DE"; anything not offered counts as [SYSTEM]. */
        fun fromTags(tags: String): AppLanguage {
            val first = tags.substringBefore(',').trim()
            return entries.firstOrNull { it.tag.isNotEmpty() && (first == it.tag || first.startsWith("${it.tag}-")) } ?: SYSTEM
        }

        /** The language chosen in Settings, or [SYSTEM] when none was chosen. */
        fun current(): AppLanguage = fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
}
