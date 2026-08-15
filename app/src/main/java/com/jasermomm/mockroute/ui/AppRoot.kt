package com.jasermomm.mockroute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jasermomm.mockroute.BuildConfig
import com.jasermomm.mockroute.core.*
import com.jasermomm.mockroute.data.*
import com.jasermomm.mockroute.service.SimulationService
import com.jasermomm.mockroute.ui.theme.hex
import com.jasermomm.mockroute.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: AppViewModel,
    state: AppUiState,
    onStart: (SimulationConfig) -> Unit,
    onRealLocation: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportGpx: () -> Unit,
    onImportGpx: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.draft.message, state.active.error) {
        val message = state.active.error ?: state.draft.message
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            if (state.draft.message != null) viewModel.message(null)
        }
    }
    BackHandler(enabled = state.draft.searchOpen || state.draft.screen != AppScreen.MAIN) {
        if (state.draft.searchOpen) viewModel.closeSearch() else viewModel.setScreen(AppScreen.MAIN)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when (state.draft.screen) {
            AppScreen.MAIN -> MainScreen(
                viewModel, state, onStart, onRealLocation, onOpenDeveloperOptions,
                modifier = Modifier.padding(padding),
            )
            AppScreen.LIBRARY -> LibraryScreen(
                viewModel, state, onStart, onExportBackup, onImportBackup, onExportGpx, onImportGpx,
                modifier = Modifier.padding(padding),
            )
            AppScreen.SETTINGS -> SettingsScreen(viewModel, state, Modifier.padding(padding))
        }
    }
    if (state.draft.searchOpen) SearchDialog(state.draft, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: AppViewModel,
    state: AppUiState,
    onStart: (SimulationConfig) -> Unit,
    onRealLocation: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapController = remember { MapController() }
    val controls = remember(state.controlPoints, state.draft.mode) {
        state.controlPoints.mapIndexed { index, point ->
            val label = when {
                state.controlPoints.size == 1 -> "P"
                index == 0 -> "A"
                index == state.controlPoints.lastIndex -> "B"
                else -> index.toString()
            }
            label to point
        }
    }
    val mapState = MapUiState(
        controls = controls,
        route = state.displayGeometry,
        active = state.active.point,
        follow = state.draft.follow,
        accent = state.settings.accent.hex(),
    )
    var coordinateTarget by remember { mutableStateOf<Triple<String, SelectionTarget, Int>?>(null) }
    var saveKind by remember { mutableStateOf<String?>(null) }

    BottomSheetScaffold(
        modifier = modifier,
        sheetPeekHeight = if (state.active.active) 230.dp else 190.dp,
        sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetDragHandle = { BottomSheetDefaults.DragHandle() },
        sheetContent = {
            if (state.active.active) {
                ActivePanel(
                    snapshot = state.active,
                    onPauseResume = {
                        SimulationService.action(
                            viewModel.getApplication<android.app.Application>(),
                            if (state.active.paused) SimulationService.ACTION_RESUME else SimulationService.ACTION_PAUSE,
                        )
                    },
                    onStop = { SimulationService.action(viewModel.getApplication<android.app.Application>(), SimulationService.ACTION_STOP) },
                    onSeek = { SimulationService.seek(viewModel.getApplication<android.app.Application>(), it) },
                )
            } else {
                MainSheet(
                    state = state,
                    viewModel = viewModel,
                    onCoordinates = { title, target, index -> coordinateTarget = Triple(title, target, index) },
                    onStart = onStart,
                    onSavePlace = { saveKind = "place" },
                    onSaveRoute = { saveKind = "route" },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            MockRouteMap(
                state = mapState,
                controller = mapController,
                modifier = Modifier.fillMaxSize(),
                onMapTap = viewModel::choosePoint,
                onMapLongPress = viewModel::choosePoint,
            )
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                    shadowElevation = 5.dp,
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PinDrop, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("MockRoute", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.setScreen(AppScreen.LIBRARY) }) { Icon(Icons.Default.Bookmarks, "Library") }
                            IconButton(onClick = { viewModel.setScreen(AppScreen.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SimulationMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = state.draft.mode == mode,
                                    onClick = { viewModel.setMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, SimulationMode.entries.size),
                                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }
                    }
                }
                if (state.mockAuthorized == false) {
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Select MockRoute as the mock location app", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onOpenDeveloperOptions) { Text("Open") }
                            IconButton(onClick = viewModel::checkAuthorization) { Icon(Icons.Default.Refresh, "Check again") }
                        }
                    }
                }
            }

            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallFloatingActionButton(onClick = mapController::zoomIn) { Icon(Icons.Default.Add, "Zoom in") }
                SmallFloatingActionButton(onClick = mapController::zoomOut) { Icon(Icons.Default.Remove, "Zoom out") }
                AssistChip(onClick = onRealLocation, label = { Text("Me") }, leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)) })
                AssistChip(
                    onClick = { (state.active.point ?: state.controlPoints.lastOrNull())?.let(mapController::center) },
                    label = { Text("Center") },
                    leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(18.dp)) },
                )
                AssistChip(onClick = mapController::fit, label = { Text("Fit") }, leadingIcon = { Icon(Icons.Default.ZoomOutMap, null, Modifier.size(18.dp)) })
                if (state.active.active) {
                    FilterChip(
                        selected = state.draft.follow,
                        onClick = { viewModel.setFollow(!state.draft.follow); mapController.setFollow(!state.draft.follow) },
                        label = { Text("Follow") },
                    )
                }
            }
        }
    }

    coordinateTarget?.let { (title, target, index) ->
        val initial = when (target) {
            SelectionTarget.STATIC -> state.draft.staticPoint
            SelectionTarget.START -> state.draft.start
            SelectionTarget.DESTINATION -> state.draft.destination
            SelectionTarget.WAYPOINT -> state.draft.waypoints.getOrNull(index)
        }
        CoordinateDialog(title, initial, { coordinateTarget = null }) { viewModel.setCoordinates(target, it, index) }
    }
    saveKind?.let { kind ->
        NameDialog(
            title = if (kind == "place") "Save place" else "Save route",
            includeNote = kind == "place",
            onDismiss = { saveKind = null },
            onSave = { name, note -> if (kind == "place") viewModel.saveCurrentPlace(name, note) else viewModel.saveCurrentRoute(name) },
        )
    }
}

@Composable
private fun MainSheet(
    state: AppUiState,
    viewModel: AppViewModel,
    onCoordinates: (String, SelectionTarget, Int) -> Unit,
    onStart: (SimulationConfig) -> Unit,
    onSavePlace: () -> Unit,
    onSaveRoute: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 670.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.draft.mode) {
                    SimulationMode.STATIC -> "Choose location"
                    SimulationMode.TRAVEL -> "Direct travel"
                    SimulationMode.DRIVE -> "Road route"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.openSearch() }) { Icon(Icons.Default.Search, "Search places") }
            IconButton(onClick = if (state.draft.mode == SimulationMode.STATIC) onSavePlace else onSaveRoute) { Icon(Icons.Default.BookmarkAdd, "Save") }
        }

        when (state.draft.mode) {
            SimulationMode.STATIC -> PointChooser(
                "Location", state.draft.staticPoint, state.draft.selectionTarget == SelectionTarget.STATIC,
                { viewModel.selectTarget(SelectionTarget.STATIC) },
                { viewModel.openSearch(SelectionTarget.STATIC) },
                { onCoordinates("Coordinates", SelectionTarget.STATIC, -1) },
            )
            SimulationMode.TRAVEL, SimulationMode.DRIVE -> RouteChoosers(state, viewModel, onCoordinates)
        }

        if (state.draft.mode != SimulationMode.STATIC) DurationEditor(state.draft.durationMs, viewModel::setDuration)

        if (state.draft.mode == SimulationMode.DRIVE) {
            state.draft.driveRoute?.let { route ->
                AssistChip(
                    onClick = {},
                    label = { Text("${formatDistance(route.distanceMeters)} • ${((route.durationSeconds * 1_000).toLong()).formatDuration()}") },
                    leadingIcon = { Icon(Icons.Default.Route, null, Modifier.size(18.dp)) },
                )
            }
            if (state.draft.routing) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = viewModel::calculateDrive,
                enabled = state.draft.start != null && state.draft.destination != null && !state.draft.routing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Route, null); Spacer(Modifier.width(8.dp)); Text(if (state.draft.driveRoute == null) "Calculate road route" else "Recalculate")
            }
        }

        TextButton(onClick = { viewModel.setAdvanced(!state.draft.advanced) }) {
            Icon(if (state.draft.advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            Spacer(Modifier.width(6.dp)); Text("Advanced")
        }
        if (state.draft.advanced) AdvancedPanel(state.draft, viewModel)

        Button(
            onClick = {
                viewModel.buildConfig().onSuccess(onStart).onFailure { viewModel.message(it.message ?: "Complete the route") }
            },
            enabled = state.mockAuthorized == true && !state.draft.routing &&
                (state.draft.mode != SimulationMode.DRIVE || state.draft.driveRoute != null),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Icon(if (state.draft.mode == SimulationMode.DRIVE) Icons.Default.DirectionsCar else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(
                when (state.draft.mode) {
                    SimulationMode.STATIC -> "Start mock location"
                    SimulationMode.TRAVEL -> "Start travel"
                    SimulationMode.DRIVE -> "Start driving"
                },
            )
        }
    }
}

@Composable
private fun RouteChoosers(
    state: AppUiState,
    viewModel: AppViewModel,
    onCoordinates: (String, SelectionTarget, Int) -> Unit,
) {
    PointChooser(
        "Start", state.draft.start, state.draft.selectionTarget == SelectionTarget.START,
        { viewModel.selectTarget(SelectionTarget.START) },
        { viewModel.openSearch(SelectionTarget.START) },
        { onCoordinates("Start coordinates", SelectionTarget.START, -1) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        FilledTonalButton(onClick = viewModel::swapRoute, enabled = state.draft.start != null && state.draft.destination != null) {
            Icon(Icons.Default.SwapVert, null); Spacer(Modifier.width(6.dp)); Text("Swap")
        }
    }
    state.draft.waypoints.forEachIndexed { index, point ->
        PointChooser(
            "Waypoint ${index + 1}", point,
            state.draft.selectionTarget == SelectionTarget.WAYPOINT && state.draft.selectionWaypointIndex == index,
            { viewModel.selectTarget(SelectionTarget.WAYPOINT, index) },
            { viewModel.openSearch(SelectionTarget.WAYPOINT, index) },
            { onCoordinates("Waypoint coordinates", SelectionTarget.WAYPOINT, index) },
            trailing = {
                IconButton(onClick = { viewModel.moveWaypoint(index, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move up") }
                IconButton(onClick = { viewModel.moveWaypoint(index, 1) }, enabled = index < state.draft.waypoints.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down") }
                IconButton(onClick = { viewModel.removeWaypoint(index) }) { Icon(Icons.Default.Close, "Remove") }
            },
        )
    }
    OutlinedButton(onClick = viewModel::addWaypoint, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Add waypoint")
    }
    PointChooser(
        "Destination", state.draft.destination, state.draft.selectionTarget == SelectionTarget.DESTINATION,
        { viewModel.selectTarget(SelectionTarget.DESTINATION) },
        { viewModel.openSearch(SelectionTarget.DESTINATION) },
        { onCoordinates("Destination coordinates", SelectionTarget.DESTINATION, -1) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    viewModel: AppViewModel,
    state: AppUiState,
    onStart: (SimulationConfig) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportGpx: () -> Unit,
    onImportGpx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    var renameRoute by remember { mutableStateOf<SavedRoute?>(null) }
    var editPlace by remember { mutableStateOf<SavedPlace?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = { IconButton(onClick = { viewModel.setScreen(AppScreen.MAIN) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(4.dp)); Text("Import") }
                FilledTonalButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("Backup") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImportGpx, modifier = Modifier.weight(1f)) { Text("Import GPX") }
                TextButton(onClick = onExportGpx, modifier = Modifier.weight(1f)) { Text("Export GPX") }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Places", "Routes", "Recents").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> SavedPlacesList(state.stored.savedPlaces, viewModel, onStart, { editPlace = it })
                1 -> SavedRoutesList(state.stored.savedRoutes, viewModel, onStart, { renameRoute = it })
                else -> RecentsList(state, viewModel)
            }
        }
    }
    renameRoute?.let { route ->
        NameDialog("Rename route", route.name, onDismiss = { renameRoute = null }) { name, _ -> viewModel.renameRoute(route, name) }
    }
    editPlace?.let { place ->
        NameDialog("Edit place", place.name, true, place.note, { editPlace = null }) { name, note -> viewModel.updateSavedPlace(place, name, note) }
    }
}

@Composable
private fun SavedPlacesList(
    places: List<SavedPlace>,
    viewModel: AppViewModel,
    onStart: (SimulationConfig) -> Unit,
    onEdit: (SavedPlace) -> Unit,
) {
    if (places.isEmpty()) EmptyState("No saved places", "Save a selected location from the map")
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(places, key = { it.id }) { place ->
            ListItem(
                headlineContent = { Text(place.name) },
                supportingContent = { Text(place.point.display(), maxLines = 1) },
                leadingContent = { Icon(Icons.Default.Place, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { viewModel.usePlace(place) }) { Icon(Icons.Default.EditLocationAlt, "Use") }
                        IconButton(onClick = {
                            onStart(SimulationConfig(SimulationMode.STATIC, listOf(ControlPoint("point", place.point, place.name)), listOf(place.point), name = place.name))
                        }) { Icon(Icons.Default.PlayArrow, "Run") }
                        PlaceMenu(place, viewModel, onEdit)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PlaceMenu(place: SavedPlace, viewModel: AppViewModel, onEdit: (SavedPlace) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Edit") }, { open = false; onEdit(place) }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem({ Text("Delete") }, { open = false; viewModel.deletePlace(place.id) }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

@Composable
private fun SavedRoutesList(
    routes: List<SavedRoute>,
    viewModel: AppViewModel,
    onStart: (SimulationConfig) -> Unit,
    onRename: (SavedRoute) -> Unit,
) {
    if (routes.isEmpty()) EmptyState("No saved routes", "Build a Travel or Drive route, then save it")
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(routes, key = { it.id }) { route ->
            ListItem(
                headlineContent = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${route.config.mode.name.lowercase().replaceFirstChar { it.uppercase() }} • ${formatDistance(route.config.route.totalMeters)}") },
                leadingContent = { Icon(if (route.config.mode == SimulationMode.DRIVE) Icons.Default.DirectionsCar else Icons.Default.Route, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { viewModel.loadRoute(route) }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { onStart(route.config) }) { Icon(Icons.Default.PlayArrow, "Run") }
                        RouteMenu(route, viewModel, onRename)
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RouteMenu(route: SavedRoute, viewModel: AppViewModel, onRename: (SavedRoute) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Rename") }, { open = false; onRename(route) }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) })
            DropdownMenuItem({ Text("Duplicate") }, { open = false; viewModel.duplicateRoute(route) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
            DropdownMenuItem({ Text("Reverse") }, { open = false; viewModel.reverseSavedRoute(route) }, leadingIcon = { Icon(Icons.Default.SwapVert, null) })
            DropdownMenuItem({ Text("Delete") }, { open = false; viewModel.deleteRoute(route.id) }, leadingIcon = { Icon(Icons.Default.Delete, null) })
        }
    }
}

@Composable
private fun RecentsList(state: AppUiState, viewModel: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent locations and routes", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = viewModel::clearRecents) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.stored.recentPlaces, key = { "p${it.id}" }) { place ->
                ListItem(
                    headlineContent = { Text(place.name, maxLines = 1) },
                    supportingContent = { Text("Location") },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            items(state.stored.recentRoutes, key = { "r${it.id}" }) { route ->
                ListItem(
                    headlineContent = { Text(route.name, maxLines = 1) },
                    supportingContent = { Text(route.config.mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingContent = { Icon(Icons.Default.Route, null) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(viewModel: AppViewModel, state: AppUiState, modifier: Modifier = Modifier) {
    var searchUrl by remember(state.settings.nominatimBaseUrl) { mutableStateOf(state.settings.nominatimBaseUrl) }
    var routeUrl by remember(state.settings.osrmBaseUrl) { mutableStateOf(state.settings.osrmBaseUrl) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { viewModel.setScreen(AppScreen.MAIN) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.settings.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dynamic color", Modifier.weight(1f))
                Switch(state.settings.dynamicColor, viewModel::setDynamic)
            }
            Text("Accent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Accent.entries.forEach { accent ->
                    FilterChip(
                        selected = state.settings.accent == accent,
                        onClick = { viewModel.setAccent(accent) },
                        label = { Text(accent.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        leadingIcon = { Icon(Icons.Default.Circle, null, tint = accent.colorForUi(), modifier = Modifier.size(16.dp)) },
                    )
                }
            }
            HorizontalDivider()
            Text("Service endpoints", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Change providers without updating the app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EndpointField("Place search", searchUrl, { searchUrl = it }, { viewModel.setSearchEndpoint(searchUrl) })
            EndpointField("Road routing", routeUrl, { routeUrl = it }, { viewModel.setRouteEndpoint(routeUrl) })
            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Saved content stays on this device. The map loads Leaflet from unpkg or jsDelivr; tiles, submitted searches, and Drive routes contact OpenStreetMap services. No analytics or accounts.")
            Text("Mock transparency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Android marks test-provider locations as mock. MockRoute never hides or bypasses that marker.")
            Text(
                "MockRoute ${BuildConfig.VERSION_NAME} • build ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EndpointField(label: String, value: String, onValue: (String) -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
        OutlinedButton(onClick = onSave, modifier = Modifier.align(Alignment.End)) { Text("Save") }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Inbox, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Accent.colorForUi() = color()
