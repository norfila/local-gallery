package com.luma.gallery.data

import androidx.annotation.StringRes
import com.luma.gallery.R

enum class PhotoLayout(@StringRes val labelRes: Int) {
    GRID(R.string.layout_grid),
    MASONRY(R.string.layout_masonry),
    /** One item per row with its name and details, like a table. */
    LIST(R.string.layout_list),
    ;

    companion object {
        /** Folders have no proportions to preserve, so they offer grid and list only. */
        val folderChoices = listOf(GRID, LIST)
    }
}
