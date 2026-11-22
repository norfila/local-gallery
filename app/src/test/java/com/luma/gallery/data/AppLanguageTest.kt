package com.luma.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun emptyTagsFollowTheSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
    }

    @Test fun regionalTagsMapToTheirLanguage() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTags("de-DE"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTags("de-AT,en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en-GB"))
    }

    @Test fun languagesNotOfferedFollowTheSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("fr-FR"))
        // "den" is not German; only a whole subtag counts.
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("den"))
    }
}
