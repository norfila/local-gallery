@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.luma.gallery.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import com.luma.gallery.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.luma.gallery.GalleryViewModel
import com.luma.gallery.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.luma.gallery.widget.AlbumWidgetProvider
import com.luma.gallery.widget.AlbumWidgetSize

private enum class Destination { LIBRARY, ALBUMS, PLACES }

private data class PhotoListing(val base: List<Photo> = emptyList(), val filtered: List<Photo> = emptyList())

@Composable
fun GalleryApp(
    model: GalleryViewModel,
    hasPhotoAccess: Boolean,
    hasFullPhotoAccess: Boolean,
    hasLocationAccess: Boolean,
    onRequestPhotos: () -> Unit,
    onRequestLocation: () -> Unit,
    requestedAlbumId: Long? = null,
    onAlbumRequestHandled: () -> Unit = {},
    requestedPhoto: Photo? = null,
    onPhotoRequestHandled: () -> Unit = {},
    hasAllFilesAccess: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(when (model.startPage) { StartPage.ALBUMS -> Destination.ALBUMS; StartPage.PLACES -> Destination.PLACES; else -> Destination.LIBRARY }) }
    var countryView by rememberSaveable { mutableStateOf(model.startPage == StartPage.COUNTRIES) }
    var hiddenPageOpen by rememberSaveable { mutableStateOf(false) }
    val hiddenView = hiddenPageOpen && tab == Destination.LIBRARY
    var selectedAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    var libraryShortcut by rememberSaveable { mutableStateOf(when (model.startPage) {
        StartPage.PHOTOS -> LibraryShortcut.PHOTOS
        StartPage.VIDEOS -> LibraryShortcut.VIDEOS
        StartPage.FOLDERS -> LibraryShortcut.FOLDERS
        else -> LibraryShortcut.ALL
    }) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var selectedFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable(stateSaver = PhotoQuerySaver) { mutableStateOf(PhotoQuery()) }
    var order by rememberSaveable { mutableStateOf(PhotoOrder.NEWEST) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var fullFiltersInitially by rememberSaveable { mutableStateOf(false) }
    // Saved, so Settings stays open when choosing a language rebuilds the screen.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var albumOptions by remember { mutableStateOf<Album?>(null) }
    var widgetAlbum by remember { mutableStateOf<Album?>(null) }
    var newAlbum by remember { mutableStateOf(false) }
    var renameAlbum by remember { mutableStateOf<Album?>(null) }
    var deleteAlbum by remember { mutableStateOf<Album?>(null) }
    var folderAlbum by remember { mutableStateOf<Album?>(null) }
    var coverAlbum by remember { mutableStateOf<Album?>(null) }
    var pickingForAlbum by remember { mutableStateOf<Long?>(null) }
    var addingUris by remember { mutableStateOf(setOf<String>()) }
    // The open photo survives a rebuilt activity (language change, process death); the list beside it is
    // rebuilt from the page instead, since thousands of URIs could exceed Android's saved-state limit.
    var viewerUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editingPhoto by remember { mutableStateOf<Photo?>(null) }
    var movingPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var moveNote by remember { mutableStateOf<MoveNote?>(null) }
    var editedPhoto by remember { mutableStateOf<Photo?>(null) }
    var viewerUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var externalAlbumNotice by remember { mutableStateOf(false) }
    var binOpen by rememberSaveable { mutableStateOf(false) }
    var folderLayoutOpen by remember { mutableStateOf(false) }
    var folderInfoOpen by remember { mutableStateOf(false) }
    var hiddenAccessOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val hiddenActions = rememberHiddenAccessActions(model, snackbar)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current
    val dark = LocalGalleryDarkTheme.current
    val resources = LocalResources.current
    // Android's approval window before a move or delete can be switched off (Android 12+), but only in Android's
    // settings. Gallery says so once, right after that window has interrupted the user; later it is in Settings.
    val preferences = remember(context) { AppPreferences(context) }
    var offerMediaManagement by remember { mutableStateOf(false) }
    fun androidAsked() {
        if (MediaManagement.supported && !MediaManagement.granted(context) && !preferences.mediaManagementOffered()) offerMediaManagement = true
    }
    val deletePhotos = rememberDeleteAction(
        onDeleted = { uris ->
            if (uris.isNotEmpty()) {
                // Library items go to the bin through Android's window; hidden files are deleted without it.
                if (MediaBin.supported && model.photos.any { it.uri in uris && !it.hidden }) androidAsked()
                model.removeFromLibrary(uris)
                selected = selected - uris
                val current = viewerUri
                if (current != null) {
                    val remaining = viewerUris.filterNot { it in uris }
                    if (remaining.isEmpty()) {
                        viewerUri = null
                        if (requestedPhoto != null) onPhotoRequestHandled()
                    } else if (current in uris) {
                        val index = viewerUris.indexOf(current)
                        viewerUri = viewerUris.drop(index + 1).firstOrNull { it !in uris } ?: viewerUris.take(index).lastOrNull { it !in uris }
                    }
                    viewerUris = remaining
                }
                scope.launch {
                    snackbar.showSnackbar(resources.getQuantityString(if (MediaBin.supported) R.plurals.moved_to_bin else R.plurals.deleted_count, uris.size, uris.size))
                }
            }
        },
        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )
    val album = model.albums.firstOrNull { it.id == selectedAlbumId }
    val library = model.photos
    val destination = tab
    val shortcut = libraryShortcut
    val folderScope = selectedFolderKey
    val activeQuery = query
    val activeOrder = order
    // Metadata arrives while the user scrolls. Scope, filter and sort snapshots on a worker,
    // rather than repeatedly doing whole-library work in the frame that draws the grid.
    val hiddenPage = hiddenView
    val listing by produceState(initialValue = PhotoListing(), library, destination, album, shortcut, folderScope, activeQuery, activeOrder, hiddenPage) {
        value = withContext(Dispatchers.Default) {
            // Hidden media appears only on the Hidden page, and in albums it was added to on purpose.
            val visible = library.filterNot { it.hidden }
            val pageLibrary = if (hiddenPage) library.filter { it.hidden } else when (shortcut) {
                LibraryShortcut.PHOTOS -> visible.filterNot { it.isVideo }
                LibraryShortcut.VIDEOS -> visible.filter { it.isVideo }
                LibraryShortcut.ALL, LibraryShortcut.FOLDERS -> visible
            }
            val scoped = when {
                destination == Destination.ALBUMS && album != null -> album.photos(library)
                destination == Destination.LIBRARY && folderScope != null -> pageLibrary.filter { it.folderKey == folderScope }
                destination == Destination.LIBRARY -> pageLibrary
                else -> visible
            }
            val matching = activeQuery.apply(scoped)
            val ordered = when (activeOrder) {
                PhotoOrder.NEWEST -> matching.sortedByDescending { it.dateTaken }
                PhotoOrder.OLDEST -> matching.sortedBy { it.dateTaken }
                PhotoOrder.RATING -> matching.sortedWith(compareByDescending<Photo> { it.rating }.thenByDescending { it.dateTaken })
                PhotoOrder.NAME -> matching.sortedBy { it.name.lowercase(Locale.ROOT) }
            }
            PhotoListing(scoped, ordered)
        }
    }
    val base = listing.base
    val filtered = listing.filtered
    val showingFolders = !countryView && tab == Destination.LIBRARY && (hiddenView || libraryShortcut == LibraryShortcut.FOLDERS) && selectedFolderKey == null
    val needsHiddenSetup = hiddenView && !hasAllFilesAccess && model.hiddenMediaFolders.isEmpty()
    fun showPhoto(photo: Photo) { libraryMenuOpen = false; focus.clearFocus(); keyboard?.hide(); viewerUris = filtered.map { it.uri }; viewerUri = photo.uri }
    BackHandler(!drawer.isOpen && (selected.isNotEmpty() || album != null || selectedFolderKey != null)) {
        if (selected.isNotEmpty()) selected = emptySet() else { selectedAlbumId = null; selectedFolderKey = null; query = PhotoQuery() }
    }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) libraryMenuOpen = false }
    LaunchedEffect(requestedAlbumId, model.libraryReady) {
        if (requestedAlbumId != null && model.libraryReady) {
            libraryMenuOpen = false
            tab = Destination.ALBUMS
            countryView = false
            selectedFolderKey = null
            query = PhotoQuery()
            selected = emptySet()
            viewerUri = null
            selectedAlbumId = model.albums.firstOrNull { it.id == requestedAlbumId }?.id
            onAlbumRequestHandled()
            if (selectedAlbumId == null && requestedAlbumId > 0) snackbar.showSnackbar(resources.getString(R.string.album_missing))
        }
    }
    LaunchedEffect(requestedPhoto?.uri) {
        requestedPhoto?.let { photo ->
            libraryMenuOpen = false
            focus.clearFocus(); keyboard?.hide()
            viewerUris = listOf(photo.uri)
            viewerUri = photo.uri
        }
    }
    // "Rotate only when viewing photos" (phones): the library stays upright; an open photo or the editor
    // follows the phone and Android's auto-rotate setting again. Tablets always turn freely.
    val activity = LocalActivity.current
    val phone = LocalConfiguration.current.smallestScreenWidthDp < 600
    val holdUpright = phone && model.rotateOnlyInViewer && viewerUri == null && editingPhoto == null
    LaunchedEffect(activity, holdUpright) {
        activity?.requestedOrientation = if (holdUpright) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Short screens (a phone turned sideways, split screen): the header and the floating navigation step aside
    // while the photos scroll on, and return with any scroll back. Tall screens keep them in place.
    val bars = rememberScrollAwayBars()
    val shortScreen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
    val touchExploration = rememberTouchExplorationEnabled()
    val barsMayHide = shortScreen && !touchExploration && selected.isEmpty() && !libraryMenuOpen && tab != Destination.PLACES
    SideEffect { bars.enabled = barsMayHide }
    LaunchedEffect(barsMayHide, tab, countryView, hiddenView, libraryShortcut, selectedAlbumId, selectedFolderKey, query) { bars.show() }
    val barsShown = !barsMayHide || !bars.hidden
    LaunchedEffect(model.error) {
        model.error?.let { snackbar.showSnackbar(it); model.clearError() }
    }
    LaunchedEffect(model.photos) {
        if (selected.isNotEmpty()) {
            val available = withContext(Dispatchers.Default) { library.mapTo(hashSetOf()) { it.uri } }
            selected = selected.intersect(available)
        }
    }

    fun navigate(destination: Destination, countries: Boolean = false, library: LibraryShortcut = LibraryShortcut.ALL, clearQuery: Boolean = true, hidden: Boolean = false) {
        focus.clearFocus(); keyboard?.hide()
        libraryMenuOpen = false
        tab = destination; countryView = countries; hiddenPageOpen = hidden
        libraryShortcut = library
        selectedAlbumId = null; selectedFolderKey = null; selected = emptySet()
        if (clearQuery) query = PhotoQuery()
        scope.launch { drawer.close() }
    }
    // The Hidden page is a side trip: Back returns to the library, as does switching the page off.
    BackHandler(!drawer.isOpen && hiddenView && selected.isEmpty() && selectedFolderKey == null) { navigate(Destination.LIBRARY) }
    LaunchedEffect(model.showHiddenMedia) { if (!model.showHiddenMedia && hiddenPageOpen) navigate(Destination.LIBRARY) }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = viewerUri == null && (drawer.isOpen || tab != Destination.PLACES),
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp).testTag("navigation-drawer")
                .then(if (drawer.isClosed) Modifier.clearAndSetSemantics {} else Modifier)) {
              Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(20.dp))
                DrawerLink(stringResource(R.string.nav_library), Icons.Outlined.GridView, tab == Destination.LIBRARY && !countryView && !hiddenView && libraryShortcut == LibraryShortcut.ALL) { navigate(Destination.LIBRARY) }
                DrawerLink(stringResource(R.string.view_photos), Icons.Outlined.PhotoLibrary, tab == Destination.LIBRARY && !countryView && !hiddenView && libraryShortcut == LibraryShortcut.PHOTOS) { navigate(Destination.LIBRARY, library = LibraryShortcut.PHOTOS) }
                DrawerLink(stringResource(R.string.view_videos), Icons.Outlined.Videocam, tab == Destination.LIBRARY && !countryView && !hiddenView && libraryShortcut == LibraryShortcut.VIDEOS) { navigate(Destination.LIBRARY, library = LibraryShortcut.VIDEOS) }
                DrawerLink(stringResource(R.string.view_folders), Icons.Outlined.FolderOpen, tab == Destination.LIBRARY && !countryView && !hiddenView && libraryShortcut == LibraryShortcut.FOLDERS) { navigate(Destination.LIBRARY, library = LibraryShortcut.FOLDERS) }
                // Library views above the line, collections and places below.
                HorizontalDivider(Modifier.padding(16.dp).testTag("drawer-divider-library"))
                DrawerLink(stringResource(R.string.nav_albums), Icons.Outlined.CollectionsBookmark, tab == Destination.ALBUMS) { navigate(Destination.ALBUMS) }
                DrawerLink(stringResource(R.string.nav_places), Icons.Outlined.Map, tab == Destination.PLACES) { navigate(Destination.PLACES) }
                DrawerLink(stringResource(R.string.nav_countries), Icons.Outlined.Public, countryView) { navigate(Destination.LIBRARY, countries = true) }
                if (model.showHiddenMedia) DrawerLink(stringResource(R.string.hidden_title), Icons.Outlined.VisibilityOff, hiddenView) {
                    navigate(Destination.LIBRARY, hidden = true)
                }
                if (MediaBin.supported) DrawerLink(stringResource(R.string.bin_title), Icons.Outlined.DeleteOutline, false) {
                    libraryMenuOpen = false
                    scope.launch { drawer.close(); binOpen = true }
                }
                HorizontalDivider(Modifier.padding(16.dp))
                DrawerLink(stringResource(R.string.nav_settings), Icons.Outlined.Settings, false) {
                    libraryMenuOpen = false
                    scope.launch { drawer.close(); settingsOpen = true }
                }
              }
            }
        },
    ) {
      val appearanceState = stringResource(if (dark) R.string.appearance_dark_state else R.string.appearance_light_state)
      Box(Modifier.fillMaxSize()) {
      Scaffold(
        modifier = Modifier.testTag("gallery-shell").semantics { stateDescription = appearanceState }
            .then(if (libraryMenuOpen) Modifier.clearAndSetSemantics {} else Modifier),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 96.dp)) },
    ) { padding ->
        // Photos, folders and albums run to the bottom edge, under Android's navigation strip, instead of stopping
        // at an empty band above it. Each list adds the strip's height to its own bottom space, so its last row
        // still clears the floating navigation.
        val layoutDirection = LocalLayoutDirection.current
        val systemBottom = padding.calculateBottomPadding()
        Column(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(layoutDirection), top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection)).nestedScroll(bars.connection)) {
            if (model.indexing) {
                LinearProgressIndicator(
                    progress = { if (model.photos.isEmpty()) 0f else model.loaded.toFloat() / model.photos.size },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }

            AnimatedVisibility(barsShown) { Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 2.dp)) {
                if (selected.isEmpty()) CompactSearchAndFilter(query, onQuery = { query = it },
                    onFilter = { libraryMenuOpen = false; focus.clearFocus(); keyboard?.hide(); fullFiltersInitially = true; searchOpen = true },
                    onMenu = { libraryMenuOpen = false; focus.clearFocus(); keyboard?.hide(); scope.launch { drawer.open() } },
                    showFilter = tab != Destination.ALBUMS || album != null,
                    placeholder = stringResource(if (tab == Destination.ALBUMS && album == null) R.string.search_albums else R.string.search_placeholder),
                    onSearchOpen = if (tab == Destination.ALBUMS && album == null) null else ({
                        libraryMenuOpen = false; focus.clearFocus(); keyboard?.hide(); fullFiltersInitially = false; searchOpen = true
                    }),
                )
                // The 48 dp button centres a 24 dp icon whose cross starts 5 dp further in; shifting the row by both
                // lines the cross itself up with the text below it.
                else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).offset(x = (-17).dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, stringResource(R.string.selection_cancel)) }
                    // What to do with the selection sits in the bar at the bottom, in the navigation's place.
                    Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
            } }

            if (!hasPhotoAccess && !hiddenView && tab != Destination.ALBUMS) {
                WelcomeScreen(onRequestPhotos, Modifier.padding(bottom = systemBottom))
            } else if (tab == Destination.ALBUMS && album == null) {
                AlbumList(model.albums.filter { it.name.contains(query.search, ignoreCase = true) }, model.photos,
                    onOpen = { selectedAlbumId = it.id; query = PhotoQuery() },
                    onNew = { newAlbum = true }, bottomInset = systemBottom,
                )
            } else {
                AnimatedVisibility(barsShown) { Column(Modifier.padding(horizontal = 22.dp)) {
                    // Back, name and (for folders) an info button share one row, spaced away from the search field.
                    // The shift (12 dp of button, 4 dp inside the icon) starts the arrow itself on the text's line.
                    if (album != null || selectedFolderKey != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).offset(x = (-16).dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            when {
                                selected.isNotEmpty() -> selected = emptySet()
                                album != null -> { selectedAlbumId = null; query = PhotoQuery() }
                                else -> selectedFolderKey = null
                            }
                        }, modifier = Modifier.testTag("collection-back")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(if (album != null) R.string.back_to_albums else R.string.back_to_folders))
                        }
                        Text(album?.name ?: base.firstOrNull()?.folder ?: selectedFolderKey!!.substringAfter(':').trimEnd('/').substringAfterLast('/'),
                            Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (album == null) IconButton(onClick = { folderInfoOpen = true }, modifier = Modifier.offset(x = 28.dp).testTag("folder-info")) {
                            Icon(Icons.Outlined.Info, stringResource(R.string.folder_info))
                        }
                    }
                    if (album != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = { pickingForAlbum = album.id }, label = { Text(stringResource(R.string.album_add_photos)) }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { folderAlbum = album }, label = { Text(stringResource(R.string.album_folders_chip, album.folderKeys.size)) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)) })
                            IconButton(onClick = { albumOptions = album }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.album_options)) }
                        }
                    }
                    if (!countryView && tab != Destination.PLACES && !needsHiddenSetup) {
                        val folderCount = remember(showingFolders, filtered) { if (showingFolders) filtered.map { it.folderKey }.distinct().size else 0 }
                        CompactLibraryContext(
                            view = if (tab == Destination.LIBRARY && selectedFolderKey == null) stringResource(if (hiddenView) R.string.hidden_title else libraryShortcut.labelRes) else null,
                            count = (if (model.indexing) stringResource(R.string.reading_metadata, model.loaded, model.photos.size)
                                else if (showingFolders) pluralStringResource(R.plurals.folder_count, folderCount, folderCount)
                                else pluralStringResource(if (filtered.any { it.isVideo }) R.plurals.item_count else R.plurals.photo_count, filtered.size, filtered.size))
                                .uppercase(Locale.getDefault()),
                            order = order, onOrderChange = { order = it },
                            onLayout = { if (showingFolders) folderLayoutOpen = true else layoutOpen = true },
                            showSort = !showingFolders,
                            onManageHidden = if (hiddenView && selectedFolderKey == null) ({ hiddenAccessOpen = true }) else null,
                        )
                    }
                    ActiveFilterChips(query, onQuery = { query = it }, modifier = Modifier.testTag("active-filter-chips"))
                    if (!hasFullPhotoAccess && !hiddenView) {
                        TextButton(onClick = onRequestPhotos, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.manage_media_access), fontSize = 12.sp)
                        }
                    }
                } }
                if (countryView) {
                    CountriesScreen(filtered, hasLocationAccess, onRequestLocation, ::showPhoto, Modifier.weight(1f),
                        onPhotoGroupClick = { photo, group -> if (selected.isNotEmpty()) selected = selected.toggle(photo.uri) else { showPhoto(photo); viewerUris = group.map { it.uri } } },
                        backHandlingEnabled = !drawer.isOpen && selected.isEmpty(), columns = model.photoColumns, layout = model.photoLayout,
                        selectedUris = selected, onSelectionChange = { selected = it },
                        onColumnsChange = model::changePhotoColumns, bottomContentPadding = 114.dp + systemBottom)
                } else if (hiddenView && selectedFolderKey == null && (needsHiddenSetup || base.isEmpty())) {
                    if (needsHiddenSetup) HiddenSetup(hiddenActions, onStorageAccess = onRequestPhotos, modifier = Modifier.weight(1f), bottomInset = systemBottom)
                    else HiddenEmpty(model.scanningHidden, onManage = { hiddenAccessOpen = true })
                } else if (showingFolders && filtered.isNotEmpty()) {
                    FoldersScreen(filtered, onOpen = { selectedFolderKey = it; selected = emptySet() }, modifier = Modifier.weight(1f), columns = model.folderColumns,
                        layout = model.folderLayout, roundedCorners = model.roundedFolderCorners, bottomContentPadding = 114.dp + systemBottom)
                } else if (tab == Destination.PLACES) {
                    PhotoMapScreen(filtered, hasLocationAccess, onRequestLocation, ::showPhoto, model.onlineMap, model::changeOnlineMap,
                        Modifier.weight(1f).padding(start = 22.dp, top = 14.dp, end = 22.dp, bottom = 102.dp + systemBottom))
                } else {
                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = if (base.isEmpty()) Icons.Outlined.PhotoLibrary else Icons.Outlined.FilterAltOff,
                            title = stringResource(if (base.isEmpty()) if (album != null) R.string.empty_album_title else R.string.empty_library_title else R.string.empty_filter_title),
                            message = stringResource(if (album != null && base.isEmpty()) R.string.empty_album_message
                                else if (base.isEmpty()) R.string.empty_library_message
                                else R.string.empty_filter_message),
                            action = stringResource(if (base.isNotEmpty()) R.string.filters_clear else if (album != null) R.string.album_add_photos else R.string.library_refresh),
                            onAction = { if (base.isNotEmpty()) query = PhotoQuery() else if (album != null) pickingForAlbum = album.id else model.refresh() },
                        )
                    } else PhotoGrid(filtered, selected,
                        onClick = { photo -> if (selected.isNotEmpty()) selected = selected.toggle(photo.uri) else showPhoto(photo) },
                        onLongClick = { selected = selected.toggle(it.uri) },
                        modifier = Modifier.weight(1f), groupByMonth = order == PhotoOrder.NEWEST || order == PhotoOrder.OLDEST,
                        columns = model.photoColumns, layout = model.photoLayout, onSelectionChange = { selected = it },
                        onColumnsChange = model::changePhotoColumns, bottomContentPadding = 114.dp + systemBottom,
                    )
                }
            }
        }
      }
      val closeLibraryMenu = stringResource(R.string.nav_close_library_menu)
      if (libraryMenuOpen) Box(Modifier.fillMaxSize().testTag("dismiss-library-shortcuts").semantics { contentDescription = closeLibraryMenu }
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
              onClickLabel = closeLibraryMenu, onClick = { libraryMenuOpen = false }))
      AnimatedVisibility(barsShown, Modifier.align(Alignment.BottomCenter),
          enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
      if (selected.isNotEmpty()) SelectionActionBar(buildList {
          fun chosen() = model.photos.filter { it.uri in selected }
          add(SelectionAction(stringResource(R.string.selection_bar_share), stringResource(R.string.selection_share), Icons.Outlined.Share, "selection-share") {
              sharePhotos(context, chosen())
          })
          // One album action, by where you are: inside an album its photos can leave it, everywhere else they can
          // join one. (An opened photo keeps "add to album", also inside an album.)
          if (album != null) add(SelectionAction(stringResource(R.string.selection_bar_remove), stringResource(R.string.album_remove_links),
              Icons.Outlined.BookmarkRemove, "selection-remove") {
              val folderLinks = album.copy(photoUris = emptySet())
              val includesLinkedFolderPhotos = model.photos.any { it.uri in selected && folderLinks.contains(it) }
              model.removePhotos(album.id, selected)
              selected = emptySet()
              if (includesLinkedFolderPhotos) scope.launch {
                  val result = snackbar.showSnackbar(
                      message = resources.getString(R.string.album_linked_photos_stay),
                      actionLabel = resources.getString(R.string.album_linked_folders), duration = SnackbarDuration.Long,
                  )
                  if (result == SnackbarResult.ActionPerformed) folderAlbum = album
              }
          })
          else add(SelectionAction(stringResource(R.string.selection_bar_album), stringResource(R.string.selection_add_to_album),
              Icons.Outlined.BookmarkAdd, "selection-album") { addingUris = selected })
          // Hidden files have no MediaStore entry to move; the Hidden page offers the other actions only.
          if (!hiddenView) add(SelectionAction(stringResource(R.string.selection_bar_move), stringResource(R.string.selection_move),
              Icons.Outlined.DriveFileMove, "selection-move") { movingPhotos = chosen() })
          add(SelectionAction(stringResource(R.string.action_delete), stringResource(R.string.action_delete), Icons.Outlined.DeleteOutline, "selection-delete") {
              deletePhotos(chosen())
          })
      })
      else FloatingGalleryNavigation(tab.ordinal, onSelect = { index ->
          val destination = Destination.entries[index]
          focus.clearFocus(); keyboard?.hide()
          if (destination == Destination.LIBRARY && tab == Destination.LIBRARY && !countryView && !hiddenView) libraryMenuOpen = !libraryMenuOpen
          else navigate(destination, clearQuery = destination == Destination.LIBRARY || tab == Destination.ALBUMS || destination == Destination.ALBUMS)
      }, libraryMenuExpanded = libraryMenuOpen, libraryMenuAvailable = tab == Destination.LIBRARY && !countryView && !hiddenView,
          currentLibraryView = libraryShortcut, onLibraryMenuDismiss = { libraryMenuOpen = false },
          onLibraryViewSelect = { shortcut ->
              libraryShortcut = shortcut; libraryMenuOpen = false
              selectedFolderKey = null; selected = emptySet()
          })
      }
      // Keep ordinary map panning free; the left edge still opens the navigation drawer.
      if (tab == Destination.PLACES && viewerUri == null) Box(Modifier.width(18.dp).fillMaxHeight().pointerInput(drawer) {
          var dragged = 0f
          detectHorizontalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = {
              if (dragged > 40.dp.toPx()) scope.launch { drawer.open() }
          }, onDragCancel = { dragged = 0f }) { change, amount -> change.consume(); dragged += amount }
      })
      }
    }

    if (searchOpen) GallerySearchWorkspace(query, base, onQuery = { query = it }, favorites = model.favoriteFilters,
        onSaveFavorite = model::saveFavoriteFilter, onRenameFavorite = model::renameFavoriteFilter,
        onRemoveFavorite = model::removeFavoriteFilter, onDismiss = { searchOpen = false }, initialAllFilters = fullFiltersInitially)
    if (binOpen) Dialog(onDismissRequest = { binOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            BinScreen(model.photoColumns, model::changePhotoColumns, onChanged = model::refresh, onBack = { binOpen = false },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        }
    }
    if (settingsOpen) GallerySettingsPage(model, hasFullPhotoAccess, hasPhotoAccess, hasLocationAccess, hasAllFilesAccess, snackbar,
        onScan = model::rescan,
        onPhotos = onRequestPhotos, onLocation = onRequestLocation, onDismiss = { settingsOpen = false })
    if (folderLayoutOpen) FolderLayoutDialog(model.folderColumns, model.folderLayout, model.roundedFolderCorners, model::changeFolderColumns,
        model::changeFolderLayout, model::changeRoundedFolderCorners) { folderLayoutOpen = false }
    if (hiddenAccessOpen) HiddenAccessSheet(model, hasAllFilesAccess) { hiddenAccessOpen = false }
    if (folderInfoOpen) selectedFolderKey?.let { key -> FolderInfoDialog(key, base) { folderInfoOpen = false } }
    if (layoutOpen) PhotoLayoutDialog(model.photoColumns, model.photoLayout, model.roundedPhotoCorners, model::changePhotoColumns,
        model::changePhotoLayout, model::changeRoundedPhotoCorners) { layoutOpen = false }
    albumOptions?.let { changing -> AlbumOptionsSheet(changing, onDismiss = { albumOptions = null },
        onRename = { albumOptions = null; renameAlbum = changing }, onCover = { albumOptions = null; coverAlbum = changing },
        onFolders = { albumOptions = null; folderAlbum = changing }, onWidget = { albumOptions = null; widgetAlbum = changing },
        onDelete = { albumOptions = null; deleteAlbum = changing }) }
    widgetAlbum?.let { target -> WidgetSizeDialog(onDismiss = { widgetAlbum = null }, onChoose = { size ->
        widgetAlbum = null
        if (!AlbumWidgetProvider.pinAlbum(context, target.id, size)) scope.launch { snackbar.showSnackbar(resources.getString(R.string.widget_pin_manually, size.label)) }
    }) }
    if (newAlbum) AlbumNameDialog(stringResource(R.string.album_new_title), stringResource(R.string.album_create), onDismiss = { newAlbum = false }, onSave = { name ->
        model.createAlbum(name, onCreated = { newAlbum = false; tab = Destination.ALBUMS; selectedAlbumId = it; query = PhotoQuery() })
    })
    renameAlbum?.let { changing -> AlbumNameDialog(stringResource(R.string.album_rename_title), stringResource(R.string.action_save), changing.name, onDismiss = { renameAlbum = null }, onSave = { name ->
        model.renameAlbum(changing.id, name); renameAlbum = null
    }) }
    deleteAlbum?.let { deleting ->
        AlertDialog(onDismissRequest = { deleteAlbum = null }, title = { Text(stringResource(R.string.album_delete_title, deleting.name)) },
            text = { Text(stringResource(R.string.album_delete_message)) },
            confirmButton = { TextButton(onClick = { model.deleteAlbum(deleting.id); selectedAlbumId = null; deleteAlbum = null }) { Text(stringResource(R.string.album_delete)) } },
            dismissButton = { TextButton(onClick = { deleteAlbum = null }) { Text(stringResource(R.string.album_keep)) } },
        )
    }
    folderAlbum?.let { linking -> FolderDialog(model.albums.firstOrNull { it.id == linking.id } ?: linking, remember(model.photos) { model.photos.filterNot(Photo::hidden) },
        onSave = { keys -> model.updateFolders(linking.id, keys); folderAlbum = null },
        onDismiss = { folderAlbum = null },
    ) }
    pickingForAlbum?.let { id -> PhotoPickerDialog(remember(model.photos) { model.photos.filterNot(Photo::hidden) }, model.photoColumns, model.photoLayout, model::changePhotoColumns, onDismiss = { pickingForAlbum = null }, onAdd = { uris -> model.addPhotos(id, uris); pickingForAlbum = null }) }
    coverAlbum?.let { choosing -> AlbumCoverDialog(model.albums.firstOrNull { it.id == choosing.id } ?: choosing, model.photos,
        columns = model.photoColumns, layout = model.photoLayout, onColumnsChange = model::changePhotoColumns,
        onDismiss = { coverAlbum = null }, onChoose = { uri -> model.setAlbumCover(choosing.id, uri); coverAlbum = null }) }
    LaunchedEffect(viewerUri, filtered, model.indexing, model.libraryReady) {
        val open = viewerUri ?: return@LaunchedEffect
        if (viewerUris.isNotEmpty()) return@LaunchedEffect
        when {
            filtered.any { it.uri == open } -> viewerUris = filtered.map { it.uri }
            model.indexing -> Unit // The library is still loading after the rebuild.
            model.photos.isNotEmpty() -> viewerUris = listOf(open)
            model.libraryReady -> viewerUri = null
        }
    }
    viewerUri?.takeIf { viewerUris.isNotEmpty() }?.let { uri ->
        val lookup = model.photos.associateBy { it.uri }.toMutableMap()
        editedPhoto?.let { copy -> if (copy.uri !in lookup) lookup[copy.uri] = copy }
        requestedPhoto?.let { external ->
            val member = external.libraryUri?.let { lookup[it] } ?: lookup[external.uri]
            lookup[external.uri] = member ?: external
        }
        val viewing = viewerUris.mapNotNull { lookup[it] }
        // The shown items can disappear (deleted elsewhere, access revoked); never keep an invisible viewer open.
        if (viewing.isEmpty()) LaunchedEffect(Unit) { viewerUri = null; if (requestedPhoto != null) onPhotoRequestHandled() }
        else PhotoViewer(viewing, uri, { photo ->
            if (model.photos.any { it.uri == photo.uri }) addingUris = setOf(photo.uri)
            else externalAlbumNotice = true
        }, { viewerUri = null; if (requestedPhoto != null) onPhotoRequestHandled() },
            onEdit = { editingPhoto = it }, onMove = { movingPhotos = listOf(it) }, onDelete = { deletePhotos(listOf(it)) })
    }
    editingPhoto?.let { original ->
        PhotoEditor(original, onDismiss = { editingPhoto = null }, onSaved = { savedUri ->
            editingPhoto = null
            model.refresh()
            scope.launch(Dispatchers.Main.immediate) {
                val saved = ExternalPhotoReader(context).read(ExternalPhotoRequest(savedUri, "image/jpeg"))
                if (saved != null) {
                    editedPhoto = saved
                    viewerUris = listOf(saved.uri)
                    viewerUri = saved.uri
                }
                android.widget.Toast.makeText(context, R.string.editor_copy_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
    // One tap on a folder moves the files, so the note that follows offers to put them back.
    val undoMove = rememberMoveUndo { _, failed ->
        model.refresh()
        android.widget.Toast.makeText(context, if (failed == 0) resources.getString(R.string.move_undone)
            else resources.getQuantityString(R.plurals.move_undo_failed, failed, failed), android.widget.Toast.LENGTH_SHORT).show()
    }
    if (movingPhotos.isNotEmpty()) MoveDestinationPage(movingPhotos, model.photos, onDismiss = { movingPhotos = emptyList() }, onMoved = { moved, failed, asked ->
        if (asked) androidAsked()
        val one = movingPhotos.size == 1
        val origins = moveOrigins(movingPhotos)
        movingPhotos = emptyList()
        selected = emptySet()
        model.refresh()
        moveNote = MoveNote(when {
            failed > 0 -> resources.getString(R.string.move_partial, moved, failed)
            one -> resources.getString(R.string.move_done)
            else -> resources.getQuantityString(R.plurals.move_done_count, moved, moved)
        }, origins)
    })
    moveNote?.let { note ->
        ActionNote(note.message, stringResource(R.string.move_undo), onAction = { moveNote = null; undoMove(note.origins) }, onDismiss = { moveNote = null })
    }
    // After the Undo note, so the two do not compete.
    if (offerMediaManagement && moveNote == null) {
        val close = { preferences.setMediaManagementOffered(true); offerMediaManagement = false }
        AlertDialog(onDismissRequest = close,
            title = { Text(stringResource(R.string.media_management_offer_title)) },
            text = { Text(stringResource(R.string.media_management_offer_message)) },
            confirmButton = { TextButton(onClick = {
                close()
                MediaManagement.settingsIntents(context).firstOrNull { intent -> runCatching { context.startActivity(intent) }.isSuccess }
            }, modifier = Modifier.testTag("media-management-offer-open")) { Text(stringResource(R.string.media_management_offer_open)) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.media_management_offer_later)) } },
            modifier = Modifier.testTag("media-management-offer"))
    }
    if (externalAlbumNotice) AlertDialog(onDismissRequest = { externalAlbumNotice = false },
        title = { Text(stringResource(R.string.external_album_title)) },
        text = { Text(stringResource(R.string.external_album_message)) },
        confirmButton = { TextButton(onClick = { externalAlbumNotice = false }) { Text(stringResource(R.string.action_done)) } })
    if (addingUris.isNotEmpty()) {
        // The page closes on the tap that chooses an album, so a short note says where the photos went.
        fun added(album: String) {
            addingUris = emptySet(); selected = emptySet()
            android.widget.Toast.makeText(context, resources.getString(R.string.album_added, album), android.widget.Toast.LENGTH_SHORT).show()
        }
        AlbumDestinationPage(model.albums, model.photos, addingUris.size,
            onChoose = { model.addPhotos(it.id, addingUris); added(it.name) },
            onCreate = { name -> model.createAlbum(name, addingUris, onCreated = { added(name) }) },
            onDismiss = { addingUris = emptySet() })
    }
}

private fun Set<String>.toggle(uri: String) = if (uri in this) this - uri else this + uri

/** Where a folder lives and what it holds; kept out of the header so the name stays clear. */
@Composable
private fun FolderInfoDialog(key: String, photos: List<Photo>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val volume = key.substringBefore(':')
    val path = key.substringAfter(':').trimEnd('/')
    val location = if (volume == "external_primary") stringResource(R.string.storage_internal_path, path) else "$volume · $path"
    val videos = photos.count { it.isVideo }
    val totalSize = remember(photos) { photos.sumOf { it.size } }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.FolderOpen, null) },
        title = { Text(photos.firstOrNull()?.folder ?: path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FolderInfoLine(stringResource(R.string.folder_info_location), location) {
                    IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(path)) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.details_copy, stringResource(R.string.folder_info_location)), Modifier.size(16.dp))
                    }
                }
                val photoText = pluralStringResource(R.plurals.photo_count, photos.size - videos, photos.size - videos)
                val videoText = pluralStringResource(R.plurals.video_count, videos, videos)
                FolderInfoLine(stringResource(R.string.folder_info_contents), when {
                    videos == 0 -> photoText
                    videos == photos.size -> videoText
                    else -> stringResource(R.string.album_summary, photoText, videoText)
                })
                if (totalSize > 0) FolderInfoLine(stringResource(R.string.meta_size), android.text.format.Formatter.formatShortFileSize(context, totalSize))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        modifier = Modifier.testTag("folder-info-dialog"),
    )
}

@Composable
private fun FolderInfoLine(label: String, value: String, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}

@Composable
private fun DrawerLink(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(label = { Text(label) }, selected = selected, onClick = onClick,
        icon = { Icon(icon, null) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
}

@Composable
private fun WelcomeScreen(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 30.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(156.dp).clip(RoundedCornerShape(48.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FilterHdr, null, modifier = Modifier.size(86.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Box(Modifier.size(17.dp).offset(x = 31.dp, y = (-34).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer))
        }
        Spacer(Modifier.height(34.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_message), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        Button(onClick = onRequest, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 15.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(19.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.welcome_action))
        }
        Spacer(Modifier.height(15.dp))
        Text(stringResource(R.string.welcome_footer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(12.dp)); TextButton(onClick = onAction) { Text(action) } }
    }
}

@Composable
private fun AlbumList(albums: List<Album>, photos: List<Photo>, onOpen: (Album) -> Unit, onNew: () -> Unit, bottomInset: Dp = 0.dp) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.album_count, albums.size, albums.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalIconButton(onClick = onNew) { Icon(Icons.Outlined.Add, stringResource(R.string.album_create)) }
        }
        if (albums.isEmpty()) {
            EmptyState(Icons.Outlined.CollectionsBookmark, stringResource(R.string.albums_empty_title), stringResource(R.string.albums_empty_message), stringResource(R.string.albums_empty_action), onNew)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 114.dp + bottomInset), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(albums, key = { it.id }) { album -> AlbumTile(album, photos) { onOpen(album) } }
        }
    }
}

/** An album as its cover, name and size: on the Albums page, and where a destination album is chosen. */
@Composable
internal fun AlbumTile(album: Album, photos: List<Photo>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val contents = remember(album, photos) { album.photos(photos) }
    val cover = remember(album, photos) { album.coverPhoto(photos) }
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.03f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (cover != null) {
                AsyncImage(cover.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            } else Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(45.dp))
            Surface(Modifier.align(Alignment.BottomEnd).padding(10.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Icon(Icons.Outlined.Link, stringResource(R.string.album_virtual), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(7.dp).size(16.dp))
            }
        }
        Text(album.name, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val photoCount = pluralStringResource(R.plurals.photo_count, contents.size, contents.size)
        Text(if (album.folderKeys.isEmpty()) photoCount else stringResource(R.string.album_summary, photoCount,
            pluralStringResource(R.plurals.folder_count, album.folderKeys.size, album.folderKeys.size)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AlbumNameDialog(title: String, action: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.CollectionsBookmark, null) }, title = { Text(title, fontFamily = FontFamily.Serif) },
        text = { Column {
            Text(stringResource(R.string.album_name_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text(stringResource(R.string.album_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun FolderDialog(album: Album, photos: List<Photo>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var keys by remember(album.id) { mutableStateOf(album.folderKeys) }
    val folders = remember(photos) { photos.groupBy { it.folderKey } }
    val options = remember(folders, album.folderKeys) {
        (folders.keys.flatMap(::folderAndParents) + album.folderKeys)
            .filter { it.substringAfter(':').trim('/').isNotBlank() }
            .distinct().sorted()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.album_linked_folders), fontFamily = FontFamily.Serif) },
        text = { Column {
            Text(stringResource(R.string.album_linked_folders_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(options, key = { it }) { key ->
                    Row(Modifier.fillMaxWidth().clickable { keys = keys.toggle(key) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(key in keys, { keys = keys.toggle(key) })
                        Column(Modifier.weight(1f)) {
                            Text(folders[key]?.firstOrNull()?.folder ?: key.substringAfter(':').trim('/').substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                            val volume = key.substringBefore(':')
                            val path = key.substringAfter(':')
                            Text(if (volume == "external_primary") stringResource(R.string.storage_internal_path, path) else "$volume · $path", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (options.isEmpty()) item { Text(stringResource(R.string.album_no_folders), Modifier.padding(vertical = 20.dp)) }
            }
        } },
        confirmButton = { TextButton(onClick = { onSave(keys) }) { Text(stringResource(R.string.album_save_links)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Include useful ancestors without offering a storage volume or a legacy mount root. */
private fun folderAndParents(key: String): List<String> {
    val separator = key.indexOf(':')
    if (separator <= 0 || !key.endsWith('/')) return listOf(key)
    val volume = key.substring(0, separator)
    val path = key.substring(separator + 1).trimEnd('/')
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return emptyList()
    if (segments.first() == "bucket") return listOf(key)
    val legacy = path.startsWith('/')
    val storageRootSize = if (!legacy) 0 else when {
        segments.take(2) == listOf("storage", "emulated") -> 3
        segments.first() == "storage" -> 2
        segments.first() == "sdcard" -> 1
        else -> segments.indexOfFirst { it.equals("DCIM", true) || it.equals("Pictures", true) }
            .takeIf { it >= 0 } ?: (segments.size - 1)
    }
    if (legacy && segments.size <= storageRootSize) return emptyList()
    val firstFolderSize = (storageRootSize + 1).coerceAtMost(segments.size)
    return (firstFolderSize..segments.size).map { count ->
        "$volume:${if (legacy) "/" else ""}${segments.take(count).joinToString("/")}/"
    }
}

@Composable
private fun PhotoPickerDialog(photos: List<Photo>, columns: Int, layout: PhotoLayout, onColumnsChange: (Int) -> Unit, onDismiss: () -> Unit, onAdd: (Set<String>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<String>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) }
                    Text(stringResource(R.string.picker_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { onAdd(picked) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.picker_add, picked.size)) }
                }
                if (photos.isEmpty()) EmptyState(Icons.Outlined.PhotoLibrary, stringResource(R.string.picker_empty_title), stringResource(R.string.picker_empty_message))
                else PhotoGrid(photos, picked, { picked = picked.toggle(it.uri) }, { picked = picked.toggle(it.uri) }, Modifier.weight(1f),
                    columns = columns, layout = layout, onSelectionChange = { picked = it }, onColumnsChange = onColumnsChange)
            }
        }
    }
}

@Composable
private fun AlbumCoverDialog(album: Album, photos: List<Photo>, columns: Int, layout: PhotoLayout, onColumnsChange: (Int) -> Unit, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    val contents = remember(album, photos) { album.photos(photos) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.cover_cancel)) }
                    Text(stringResource(R.string.cover_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.cover_message, album.name), Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onChoose(null) }, modifier = Modifier.padding(horizontal = 14.dp)) { Text(stringResource(R.string.cover_reset)) }
                if (contents.isEmpty()) EmptyState(Icons.Outlined.PhotoLibrary, stringResource(R.string.cover_empty_title), stringResource(R.string.cover_empty_message))
                else PhotoGrid(contents, setOfNotNull(album.coverUri), { onChoose(it.uri) }, { onChoose(it.uri) }, Modifier.weight(1f),
                    columns = columns, layout = layout, onColumnsChange = onColumnsChange)
            }
        }
    }
}

