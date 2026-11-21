package com.luma.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.luma.gallery.R

enum class LibraryShortcut(@StringRes val labelRes: Int, val icon: ImageVector) {
    ALL(R.string.view_all_items, Icons.Outlined.GridView),
    PHOTOS(R.string.view_photos, Icons.Outlined.PhotoLibrary),
    VIDEOS(R.string.view_videos, Icons.Outlined.Videocam),
    FOLDERS(R.string.view_folders, Icons.Outlined.FolderOpen),
}

/** The leading edge moves first and the trailing edge catches up, stretching the shared bubble. */
@Composable
fun FloatingGalleryNavigation(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    libraryMenuExpanded: Boolean = false,
    currentLibraryView: LibraryShortcut = LibraryShortcut.ALL,
    onLibraryViewSelect: (LibraryShortcut) -> Unit = {},
    onLibraryMenuDismiss: () -> Unit = {},
    /** Whether another tap on the selected Library tab opens its views; a small chevron after the label says so. */
    libraryMenuAvailable: Boolean = false,
) {
    val labels = listOf(stringResource(R.string.nav_library), stringResource(R.string.nav_albums), stringResource(R.string.nav_places))
    val libraryState = stringResource(if (libraryMenuExpanded) R.string.nav_library_menu_expanded else R.string.nav_library_menu_collapsed,
        stringResource(currentLibraryView.labelRes))
    val icons = listOf(Icons.Outlined.GridView, Icons.Outlined.CollectionsBookmark, Icons.Outlined.Map)
    val showLibraryMenu = selectedIndex == 0 && libraryMenuExpanded
    BackHandler(enabled = showLibraryMenu, onBack = onLibraryMenuDismiss)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
            AnimatedVisibility(visible = showLibraryMenu,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
                Column {
                    LibraryShortcutStrip(currentLibraryView, onLibraryViewSelect, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(76.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("floating-navigation").padding(6.dp).selectableGroup()) {
                val cell = maxWidth / 3
                val transition = updateTransition(selectedIndex, label = "navigation morph")
                val left by transition.animateDp(transitionSpec = {
                    spring(dampingRatio = 0.82f, stiffness = if (targetState < initialState) 600f else 240f)
                }, label = "trailing and leading left edge") { cell * it + 2.dp }
                val right by transition.animateDp(transitionSpec = {
                    spring(dampingRatio = 0.82f, stiffness = if (targetState > initialState) 600f else 240f)
                }, label = "trailing and leading right edge") { cell * (it + 1) - 2.dp }
                Box(Modifier.offset(x = left).width((right - left).coerceAtLeast(40.dp)).fillMaxHeight()
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).testTag("navigation-selection"))
                Row(Modifier.fillMaxSize()) {
                    labels.forEachIndexed { index, label ->
                        val chosen = index == selectedIndex
                        val tint by animateColorAsState(if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "navigation tint")
                        val scale by animateFloatAsState(if (chosen) 1.06f else 1f, spring(stiffness = 400f), label = "navigation icon scale")
                        Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .then(if (index == 0 && chosen) Modifier.semantics {
                                stateDescription = libraryState
                            } else Modifier)
                            .selectable(chosen, role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onSelect(index) }),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icons[index], null, tint = tint, modifier = Modifier.size(23.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                            Spacer(Modifier.height(3.dp))
                            if (index == 0 && chosen && libraryMenuAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                                // The spacer mirrors the chevron, so the word stays centred under its icon.
                                Spacer(Modifier.width(12.dp))
                                Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
                                Icon(if (libraryMenuExpanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, null,
                                    tint = tint, modifier = Modifier.size(12.dp).testTag("library-menu-chevron"))
                            } else Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryShortcutStrip(current: LibraryShortcut, onSelect: (LibraryShortcut) -> Unit, modifier: Modifier = Modifier) {
    val pane = stringResource(R.string.nav_library_views)
    Surface(modifier.testTag("library-shortcut-strip").semantics { paneTitle = pane }, shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LibraryShortcut.entries.forEach { shortcut ->
                val chosen = shortcut == current
                val tint = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .testTag("library-shortcut-${shortcut.name.lowercase()}")
                    .selectable(chosen, role = Role.RadioButton, interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = { onSelect(shortcut) })
                    .padding(horizontal = 1.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(shortcut.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(shortcut.labelRes), color = tint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
                }
            }
        }
    }
}
