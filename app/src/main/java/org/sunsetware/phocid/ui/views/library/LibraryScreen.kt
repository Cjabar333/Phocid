@file:OptIn(ExperimentalMaterial3Api::class)

package org.sunsetware.phocid.ui.views.library

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.Strings
import org.sunsetware.phocid.data.*
import org.sunsetware.phocid.ui.components.*
import org.sunsetware.phocid.ui.theme.EnterFromBottom
import org.sunsetware.phocid.ui.theme.ExitToBottom
import org.sunsetware.phocid.ui.theme.LocalThemeAccent
import org.sunsetware.phocid.ui.theme.Typography
import org.sunsetware.phocid.ui.theme.contentColor
import org.sunsetware.phocid.ui.theme.emphasizedEnter
import org.sunsetware.phocid.ui.theme.emphasizedExit
import org.sunsetware.phocid.ui.views.playlist.NewPlaylistDialog
import org.sunsetware.phocid.ui.views.playlist.PlaylistIoScreen
import org.sunsetware.phocid.ui.views.preferences.PreferencesScreen
import org.sunsetware.phocid.utils.*

@Immutable
interface LibraryScreenItem<T : LibraryScreenItem<T>> {
    @Stable
    fun getMultiSelectMenuItems(
        others: List<T>,
        viewModel: MainViewModel,
        continuation: () -> Unit,
    ): List<MenuItem.Button>
}

@Composable
fun LibraryScreen(
    playerScreenDragLock: DragLock,
    isObscured: Boolean,
    viewModel: MainViewModel = viewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val playerManager = viewModel.playerManager
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val libraryIndex by viewModel.libraryIndex.collectAsStateWithLifecycle()
    val uiManager = viewModel.uiManager
    val homeViewState = uiManager.libraryScreenHomeViewState
    val collectionViewStack by
        uiManager.libraryScreenCollectionViewStack.collectAsStateWithLifecycle()
    val collectionInfos by
        uiManager.libraryScreenCollectionViewStack
            .flatMapLatest(coroutineScope) { states ->
                states.map { it.info }.combine(coroutineScope)
            }
            .runningReduce(coroutineScope) { last, current ->
                current.mapIndexed { index, info ->
                    if (info != null) info else last.getOrNull(index) ?: InvalidCollectionViewInfo
                }
            }
            .map(coroutineScope) { infos ->
                infos.map { if (it != null) it else InvalidCollectionViewInfo }
            }
            .collectAsStateWithLifecycle()
    val currentCollection = collectionViewStack.lastOrNull()
    val currentCollectionType = collectionInfos.lastOrNull()?.type
    val currentHomeTabIndex =
        homeViewState.pagerState.targetPage.coerceIn(0, preferences.tabs.size - 1)
    val currentHomeTab = preferences.tabs[currentHomeTabIndex]
    val activeHomeViewMultiSelectState by
        homeViewState.activeMultiSelectState.collectAsStateWithLifecycle()
    val currentMultiSelectState =
        currentCollection?.multiSelectState ?: activeHomeViewMultiSelectState
    val currentMultiSelectItems = currentMultiSelectState?.items?.collectAsStateWithLifecycle()
    val currentSelectedCount =
        remember(currentMultiSelectItems?.value) {
            currentMultiSelectItems?.value?.count { it.selected } ?: 0
        }
    var searchQueryBuffer by remember {
        mutableStateOf(viewModel.uiManager.libraryScreenSearchQuery.value)
    }
    var viewSettingsVisibility by remember { mutableStateOf(false) }
    var maxGridSize by remember { mutableIntStateOf(4) }
    val overflowMenuItems =
        remember(currentCollection, currentHomeTab) {
            when {
                currentCollection != null -> {
                    collectionMenuItemsWithoutPlay(
                        {
                            currentCollection.multiSelectState.items.value.flatMap {
                                it.value.multiSelectTracks
                            }
                        },
                        playerManager,
                        uiManager,
                    ) +
                        (currentCollection.info.value?.extraCollectionMenuItems(viewModel)
                            ?: emptyList<MenuItem>()) +
                        MenuItem.Divider
                }
                currentHomeTab.type == LibraryScreenTabType.PLAYLISTS ->
                    listOf(
                        MenuItem.Button(Strings[R.string.playlist_new], Icons.Filled.AddBox) {
                            uiManager.openDialog(NewPlaylistDialog())
                        },
                        MenuItem.Button(
                            Strings[R.string.playlist_import_export],
                            Icons.Filled.ImportExport,
                        ) {
                            uiManager.openTopLevelScreen(PlaylistIoScreen.import())
                        },
                        MenuItem.Button(Strings[R.string.playlist_io_sync], Icons.Filled.Sync) {
                            uiManager.openTopLevelScreen(PlaylistIoScreen.sync())
                        },
                        MenuItem.Divider,
                    )
                else -> emptyList()
            } +
                listOf(
                    MenuItem.Button(
                        Strings[R.string.view_settings],
                        Icons.AutoMirrored.Filled.ViewList,
                    ) {
                        viewSettingsVisibility = true
                    },
                    MenuItem.Button(Strings[R.string.library_rescan], Icons.Filled.Refresh) {
                        viewModel.scanLibrary(true)
                    },
                    MenuItem.Button(Strings[R.string.preferences], Icons.Filled.Settings) {
                        uiManager.openTopLevelScreen(PreferencesScreen)
                    },
                )
        }
    val floatingToolbarDataSource =
        remember(currentMultiSelectItems?.value) {
            currentMultiSelectItems?.value?.selection ?: emptyList()
        }
    val floatingToolbarItems =
        rememberFloatingToolbarItems(floatingToolbarDataSource, currentMultiSelectState)
    val isScanningLibrary by viewModel.isScanningLibrary.collectAsStateWithLifecycle()
    var scanSnackbarVisibility by remember { mutableStateOf(false) }

    LaunchedEffect(isScanningLibrary) {
        when (isScanningLibrary) {
            false -> {
                delay(1.seconds)
                if (isActive) scanSnackbarVisibility = true
            }
            true -> {
                scanSnackbarVisibility = true
            }
            null -> {
                scanSnackbarVisibility = false
            }
        }
    }

    LaunchedEffect(searchQueryBuffer) {
        uiManager.libraryScreenSearchQuery.update { searchQueryBuffer }
    }

    Scaffold(
        modifier =
            Modifier.imePadding().onSizeChanged {
                with(density) { maxGridSize = (it.width / 72.dp.toPx()).toInt().coerceAtLeast(4) }
            },
        topBar = {
            TopBar(
                collectionTitles = collectionInfos.map { it.title },
                onBack = { uiManager.back() },
                searchQuery = searchQueryBuffer,
                onSearchQueryChange = { query -> searchQueryBuffer = query },
                onPlayAll = {
                    playerManager.setTracks(
                        currentCollection?.multiSelectState?.items?.value?.flatMap {
                            it.value.playTracks
                        }
                            ?: libraryIndex.tracks.values.let { tracks ->
                                val tracksTab =
                                    preferences.tabSettings[LibraryScreenTabType.TRACKS]!!
                                tracks.sorted(
                                    preferences.sortCollator,
                                    tracksTab.sortingKeys,
                                    tracksTab.sortAscending,
                                )
                            },
                        null,
                    )
                },
                selectedCount = currentSelectedCount,
                menuItems = overflowMenuItems,
            )
        },
        bottomBar = {
            BottomBar(
                playerManager,
                libraryIndex,
                preferences.artworkColorPreference,
                preferences.shapePreference.artworkShape,
                uiManager.playerScreenDragState,
                playerScreenDragLock,
                isObscured,
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .padding(scaffoldPadding)
                    .consumeWindowInsets(scaffoldPadding)
                    .systemBarsPadding()
        ) {
            AnimatedForwardBackwardTransition(collectionViewStack) { animatedCollectionViewState ->
                if (animatedCollectionViewState == null) {
                    LibraryScreenHomeView(homeViewState)
                } else {
                    LibraryScreenCollectionView(animatedCollectionViewState)
                }
            }

            Column(
                modifier =
                    Modifier.padding(bottom = 16.dp).align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AnimatedVisibility(
                    visible = scanSnackbarVisibility,
                    enter = EnterFromBottom,
                    exit = ExitToBottom,
                ) {
                    val libraryScanProgress by
                        viewModel.libraryScanProgress.collectAsStateWithLifecycle()

                    IndefiniteSnackbar(
                        Strings[R.string.snackbar_scanning_library].icuFormat(
                            libraryScanProgress?.first ?: 0,
                            libraryScanProgress?.second ?: "?",
                        )
                    )
                }

                AnimatedVisibility(
                    visible =
                        floatingToolbarDataSource.isNotEmpty() && floatingToolbarItems.isNotEmpty(),
                    enter = EnterFromBottom,
                    exit = ExitToBottom,
                ) {
                    FloatingToolbar(floatingToolbarItems)
                }
            }
        }
    }

    ViewSettings(
        visibility = viewSettingsVisibility,
        onDismissRequest = { viewSettingsVisibility = false },
        sortingOptions =
            currentCollectionType?.sortingOptions ?: currentHomeTab.type.sortingOptions,
        activeSortingOptionId =
            currentCollectionType?.let { preferences.collectionViewSorting[it]!!.first }
                ?: currentHomeTab.sortingOptionId,
        onSetSortingOption = { sortingOptionId ->
            if (currentCollectionType != null) {
                viewModel.updatePreferences {
                    it.copy(
                        collectionViewSorting =
                            it.collectionViewSorting +
                                (currentCollectionType to
                                    Pair(
                                        sortingOptionId,
                                        it.collectionViewSorting[currentCollectionType]!!.second,
                                    ))
                    )
                }
            } else {
                viewModel.updateTabInfo(currentHomeTabIndex) {
                    it.copy(sortingOptionId = sortingOptionId)
                }
            }
        },
        sortAscending =
            currentCollectionType?.let { preferences.collectionViewSorting[it]!!.second }
                ?: currentHomeTab.sortAscending,
        onSetSortAscending = { sortAscending ->
            if (currentCollectionType != null) {
                viewModel.updatePreferences {
                    it.copy(
                        collectionViewSorting =
                            it.collectionViewSorting +
                                (currentCollectionType to
                                    Pair(
                                        it.collectionViewSorting[currentCollectionType]!!.first,
                                        sortAscending,
                                    ))
                    )
                }
            } else {
                viewModel.updateTabInfo(currentHomeTabIndex) {
                    it.copy(sortAscending = sortAscending)
                }
            }
        },
        gridSize = if (currentCollection == null) currentHomeTab.gridSize else null,
        maxGridSize = maxGridSize,
        onSetGridSize = { gridSize ->
            viewModel.updateTabInfo(currentHomeTabIndex) { it.copy(gridSize = gridSize) }
        },
    )
}

@Composable
private fun TopBar(
    collectionTitles: List<String>,
    onBack: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onPlayAll: () -> Unit,
    selectedCount: Int,
    menuItems: List<MenuItem>,
) {
    val titles =
        collectionTitles +
            listOfNotNull(
                if (selectedCount > 0)
                    Strings[R.string.list_multi_select_title].icuFormat(selectedCount)
                else null
            )
    TopAppBar(
        title = {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.negativePadding(start = 16.dp),
            ) {
                AnimatedForwardBackwardTransition(
                    if (titles.isEmpty()) emptyList() else listOf(Unit),
                    slide = false,
                    modifier = Modifier.padding(horizontal = 4.dp).height(48.dp),
                ) { animatedCollectionTitle ->
                    if (animatedCollectionTitle != null) {
                        IconButton(
                            onClick = onBack,
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = Strings[R.string.commons_back],
                            )
                        }
                    } else {
                        Box(modifier = Modifier.width(0.dp).height(48.dp))
                    }
                }
                AnimatedForwardBackwardTransition(
                    titles,
                    slide = false,
                    modifier = Modifier.padding(start = 16.dp).fillMaxWidth().height(48.dp),
                    keepRoot = false,
                ) { animatedTitle ->
                    if (animatedTitle == null) {
                        SearchBar(searchQuery, onSearchQueryChange)
                    } else {
                        Box(modifier = Modifier.fillMaxHeight())
                    }
                }
                AnimatedForwardBackwardTransition(
                    titles,
                    slide = false,
                    modifier =
                        Modifier.padding(start = (48 + 4 * 2).dp).fillMaxWidth().height(48.dp),
                ) { animatedTitle ->
                    if (animatedTitle == null) {
                        Box(modifier = Modifier.fillMaxHeight())
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            SingleLineText(animatedTitle, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        actions = {
            Row {
                IconButton(onClick = onPlayAll) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = Strings[R.string.track_play_all],
                    )
                }
                OverflowMenu(menuItems)
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focus by rememberSaveable { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions { focusManager.clearFocus() },
        textStyle = Typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.onFocusChanged { focus = it.isFocused }.height(48.dp),
    ) { innerTextField ->
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(end = 16.dp).fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && !focus) {
                        Text(
                            text = Strings[R.string.search],
                            style = Typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        innerTextField()
                    }
                }
                if (value.isNotEmpty()) {
                    IconButton({
                        onValueChange("")
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.Filled.Clear, Strings[R.string.commons_clear])
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    playerManager: PlayerManager,
    libraryIndex: LibraryIndex,
    artworkColorPreference: ArtworkColorPreference,
    artworkShape: Shape,
    playerScreenDragState: BinaryDragState,
    playerScreenDragLock: DragLock,
    isObscured: Boolean,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    var progress by remember { mutableFloatStateOf(0f) }

    val playerState by playerManager.state.collectAsStateWithLifecycle()
    val currentTrack by
        playerManager.state
            .map(coroutineScope) { state ->
                val id = state.actualPlayQueue.getOrNull(state.currentIndex)
                if (id != null) libraryIndex.tracks[id] ?: InvalidTrack else null
            }
            .runningReduce(coroutineScope) { last, current -> current ?: last }
            .collectAsStateWithLifecycle()
    val isPlaying by
        playerManager.transientState
            .map(coroutineScope) { it.isPlaying }
            .collectAsStateWithLifecycle()
    val playerTransientStateVersion by
        playerManager.transientState
            .map(coroutineScope) { it.version }
            .collectAsStateWithLifecycle()

    val animatedThemeAccent = animateColorAsState(LocalThemeAccent.current)

    // Update progress
    LaunchedEffect(currentTrack, isObscured) {
        if (isObscured) return@LaunchedEffect

        val frameTime = (1f / context.display.refreshRate).toDouble().milliseconds

        while (isActive) {
            progress =
                if (currentTrack == null) 0f
                else
                    playerManager.currentPosition.toFloat() /
                        currentTrack!!.duration.inWholeMilliseconds
            delay(frameTime)
        }
    }

    AnimatedContent(
        targetState = playerState.actualPlayQueue.isNotEmpty(),
        transitionSpec = { slideInVertically { it } togetherWith slideOutVertically { it } },
    ) { animatedVisibility ->
        if (animatedVisibility) {
            Column {
                LinearProgressIndicator(
                    progress = { progress.takeIf { it.isFinite() } ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    drawStopIndicator = {},
                )
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 4.dp),
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { playerManager.togglePlay() },
                            containerColor = animatedThemeAccent.value,
                            contentColor = animatedThemeAccent.value.contentColor(),
                        ) {
                            AnimatedContent(targetState = isPlaying) { animatedIsPlaying ->
                                if (animatedIsPlaying) {
                                    Icon(
                                        Icons.Filled.Pause,
                                        contentDescription = Strings[R.string.player_pause],
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = Strings[R.string.player_play],
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        TrackCarousel(
                            state = playerState,
                            key = playerTransientStateVersion,
                            countSelector = { it.actualPlayQueue.size },
                            indexSelector = { it.currentIndex },
                            repeatSelector = { it.repeat != Player.REPEAT_MODE_OFF },
                            indexEqualitySelector = { playerState, index ->
                                if (playerState.shuffle)
                                    playerState.unshuffledPlayQueueMapping!!.indexOf(index)
                                else index
                            },
                            tapKey = Unit,
                            onTap = { playerScreenDragState.animateTo(1f) },
                            onVerticalDrag = {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        playerScreenDragState.onDragStart(playerScreenDragLock)
                                    },
                                    onDragCancel = {
                                        playerScreenDragState.onDragEnd(
                                            playerScreenDragLock,
                                            density,
                                        )
                                    },
                                    onDragEnd = {
                                        playerScreenDragState.onDragEnd(
                                            playerScreenDragLock,
                                            density,
                                        )
                                    },
                                ) { _, dragAmount ->
                                    playerScreenDragState.onDrag(playerScreenDragLock, dragAmount)
                                }
                            },
                            onPrevious = { playerManager.seekToPrevious() },
                            onNext = { playerManager.seekToNext() },
                        ) { state, index ->
                            val id = state.actualPlayQueue.getOrNull(index)
                            val track =
                                (if (id != null) libraryIndex.tracks[id] else null) ?: InvalidTrack
                            LibraryListItemHorizontal(
                                title = track.displayTitle,
                                subtitle = track.displayArtistWithAlbum,
                                lead = {
                                    ArtworkImage(
                                        artwork = Artwork.Track(track),
                                        artworkColorPreference = artworkColorPreference,
                                        shape = artworkShape,
                                        async = false,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                },
                                actions = {},
                                modifier = Modifier.fillMaxHeight(),
                                marquee = true,
                            )
                        }
                    },
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {}
        }
    }
}

@Composable
private fun <T : LibraryScreenItem<T>> rememberFloatingToolbarItems(
    dataSource: List<LibraryScreenItem<T>>,
    multiSelectManager: MultiSelectManager?,
): List<MenuItem.Button> {
    val viewModel = viewModel<MainViewModel>()
    val selectionItems =
        remember(multiSelectManager) {
            listOf(
                MenuItem.Button(
                    Strings[R.string.list_multi_select_select_all],
                    Icons.Default.SelectAll,
                ) {
                    multiSelectManager?.selectAll()
                },
                MenuItem.Button(
                    Strings[R.string.list_multi_select_select_inverse],
                    Icons.Default.BorderStyle,
                ) {
                    multiSelectManager?.selectInverse()
                },
            )
        }
    var actionItems by remember { mutableStateOf(emptyList<MenuItem.Button>()) }

    LaunchedEffect(dataSource) {
        if (dataSource.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            actionItems =
                dataSource
                    .first()
                    .getMultiSelectMenuItems(
                        others = dataSource.drop(1) as List<T>,
                        viewModel = viewModel,
                        continuation = { multiSelectManager?.clearSelection() },
                    )
        }
    }
    val items =
        remember(selectionItems, actionItems) {
            if (actionItems.isNotEmpty()) selectionItems + actionItems else emptyList()
        }
    return items
}

@Composable
private inline fun ViewSettings(
    visibility: Boolean,
    noinline onDismissRequest: () -> Unit,
    sortingOptions: Map<String, SortingOption>,
    activeSortingOptionId: String,
    crossinline onSetSortingOption: (String) -> Unit,
    sortAscending: Boolean,
    crossinline onSetSortAscending: (Boolean) -> Unit,
    gridSize: Int? = null,
    maxGridSize: Int = 0,
    crossinline onSetGridSize: (Int) -> Unit = {},
) {
    AnimatedVisibility(
        visibility,
        enter = slideInVertically(emphasizedEnter()) { it },
        exit = slideOutVertically(emphasizedExit()) { it },
    ) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (gridSize != null) {
                    Text(
                        text = Strings[R.string.view_settings_grid_size],
                        style = Typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = gridSize.toFloat(),
                        valueRange = 0f..maxGridSize.toFloat(),
                        steps = (maxGridSize - 1).coerceAtLeast(0),
                        onValueChange = { onSetGridSize(it.roundToInt()) },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(text = Strings[R.string.view_settings_sort_by], style = Typography.labelLarge)
                Spacer(modifier = Modifier.height(16.dp))
                SortingOptionPicker(
                    sortingOptions = sortingOptions,
                    activeSortingOptionId = activeSortingOptionId,
                    sortAscending = sortAscending,
                    onSetSortingOption = onSetSortingOption,
                    onSetSortAscending = onSetSortAscending,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
