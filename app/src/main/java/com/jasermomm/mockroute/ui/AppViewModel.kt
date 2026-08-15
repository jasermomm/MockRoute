package com.jasermomm.mockroute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jasermomm.mockroute.MockRouteApp
import com.jasermomm.mockroute.core.*
import com.jasermomm.mockroute.data.*
import com.jasermomm.mockroute.location.MockLocationController
import com.jasermomm.mockroute.location.RealLocationSource
import com.jasermomm.mockroute.service.SimulationBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AppScreen { MAIN, LIBRARY, SETTINGS }
enum class SelectionTarget { STATIC, START, DESTINATION, WAYPOINT }

data class DraftState(
    val screen: AppScreen = AppScreen.MAIN,
    val mode: SimulationMode = SimulationMode.STATIC,
    val staticPoint: GeoPoint? = null,
    val start: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val waypoints: List<GeoPoint> = emptyList(),
    val selectionTarget: SelectionTarget = SelectionTarget.STATIC,
    val selectionWaypointIndex: Int = -1,
    val importedGeometry: List<GeoPoint>? = null,
    val driveRoute: RoadRoute? = null,
    val routing: Boolean = false,
    val durationMs: Long = 300_000L,
    val updateIntervalMs: Long = 1_000L,
    val accuracyMeters: Float = 5f,
    val realismPercent: Int = 0,
    val startDelayMs: Long = 0L,
    val completion: CompletionBehavior = CompletionBehavior.STOP,
    val profile: SpeedProfile = SpeedProfiles.preset(SpeedPreset.CONSTANT),
    val advanced: Boolean = false,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val follow: Boolean = false,
    val message: String? = null,
)

data class AppUiState(
    val draft: DraftState = DraftState(),
    val settings: AppSettings = AppSettings(),
    val stored: StoredData = StoredData(),
    val active: SimulationSnapshot = SimulationSnapshot(),
    val mockAuthorized: Boolean? = null,
) {
    val controlPoints: List<GeoPoint>
        get() = when (draft.mode) {
            SimulationMode.STATIC -> listOfNotNull(draft.staticPoint)
            else -> listOfNotNull(draft.start) + draft.waypoints + listOfNotNull(draft.destination)
        }

    val displayGeometry: List<GeoPoint>
        get() = when (draft.mode) {
            SimulationMode.STATIC -> emptyList()
            SimulationMode.TRAVEL -> draft.importedGeometry ?: controlPoints
            SimulationMode.DRIVE -> draft.driveRoute?.geometry.orEmpty()
        }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MockRouteApp
    private val store = app.localStore
    private val settingsRepository = app.settingsRepository
    private val realLocationSource = RealLocationSource(application)
    private val _draft = MutableStateFlow(DraftState())
    private val _authorized = MutableStateFlow<Boolean?>(null)
    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val uiState: StateFlow<AppUiState> = combine(
        _draft, settings, store.data, SimulationBus.state, _authorized,
    ) { draft, appSettings, stored, active, authorized ->
        AppUiState(draft, appSettings, stored, active, authorized)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    fun setScreen(screen: AppScreen) = mutate { it.copy(screen = screen, searchOpen = false) }

    fun setMode(mode: SimulationMode) = mutate {
        it.copy(
            mode = mode,
            selectionTarget = when (mode) {
                SimulationMode.STATIC -> SelectionTarget.STATIC
                else -> if (it.start == null) SelectionTarget.START else SelectionTarget.DESTINATION
            },
            searchOpen = false,
            message = null,
        )
    }

    fun selectTarget(target: SelectionTarget, waypointIndex: Int = -1) = mutate {
        it.copy(selectionTarget = target, selectionWaypointIndex = waypointIndex, message = "Tap the map or search")
    }

    fun choosePoint(point: GeoPoint) {
        if (!point.isValid) return
        mutate { state ->
            when (state.selectionTarget) {
                SelectionTarget.STATIC -> state.copy(staticPoint = point, message = null)
                SelectionTarget.START -> state.copy(start = point, driveRoute = null, importedGeometry = null, message = null)
                SelectionTarget.DESTINATION -> state.copy(destination = point, driveRoute = null, importedGeometry = null, message = null)
                SelectionTarget.WAYPOINT -> {
                    val list = state.waypoints.toMutableList()
                    if (state.selectionWaypointIndex in list.indices) list[state.selectionWaypointIndex] = point else list += point
                    state.copy(waypoints = list, driveRoute = null, importedGeometry = null, message = null)
                }
            }
        }
        viewModelScope.launch { addRecentPoint(point) }
    }

    fun setCoordinates(target: SelectionTarget, input: String, waypointIndex: Int = -1): Boolean {
        val point = GeoMath.parseCoordinate(input) ?: run { message("Enter latitude, longitude"); return false }
        selectTarget(target, waypointIndex)
        choosePoint(point)
        return true
    }

    fun swapRoute() {
        val current = _draft.value
        if (current.start == null || current.destination == null) return
        mutate {
            it.copy(
                start = current.destination,
                destination = current.start,
                waypoints = current.waypoints.reversed(),
                driveRoute = null,
                importedGeometry = current.importedGeometry?.reversed(),
            )
        }
        if (current.mode == SimulationMode.DRIVE) calculateDrive()
    }

    fun addWaypoint() = mutate {
        it.copy(selectionTarget = SelectionTarget.WAYPOINT, selectionWaypointIndex = it.waypoints.size, message = "Tap the map or search")
    }

    fun removeWaypoint(index: Int) = mutate {
        it.copy(waypoints = it.waypoints.filterIndexed { i, _ -> i != index }, driveRoute = null, importedGeometry = null)
    }

    fun moveWaypoint(index: Int, direction: Int) = mutate { state ->
        val target = index + direction
        if (index !in state.waypoints.indices || target !in state.waypoints.indices) return@mutate state
        val list = state.waypoints.toMutableList()
        val value = list.removeAt(index)
        list.add(target, value)
        state.copy(waypoints = list, driveRoute = null, importedGeometry = null)
    }

    fun setDuration(hours: Int, minutes: Int, seconds: Int) {
        val total = ((hours.coerceIn(0, 168) * 3_600L) + (minutes.coerceIn(0, 59) * 60L) + seconds.coerceIn(0, 59)) * 1_000L
        mutate { it.copy(durationMs = total) }
    }

    fun setUpdateInterval(ms: Long) = mutate { it.copy(updateIntervalMs = ms.coerceIn(200L, 60_000L)) }
    fun setAccuracy(value: Float) = mutate { it.copy(accuracyMeters = value.coerceIn(0.5f, 1000f)) }
    fun setRealism(value: Int) = mutate { it.copy(realismPercent = value.coerceIn(0, 100)) }
    fun setStartDelay(ms: Long) = mutate { it.copy(startDelayMs = ms.coerceIn(0L, 3_600_000L)) }
    fun setCompletion(value: CompletionBehavior) = mutate { it.copy(completion = value) }
    fun setAdvanced(value: Boolean) = mutate { it.copy(advanced = value) }
    fun setFollow(value: Boolean) = mutate { it.copy(follow = value) }
    fun setProfile(preset: SpeedPreset) = mutate { it.copy(profile = SpeedProfiles.preset(preset)) }
    fun setProfilePoints(points: List<SpeedPoint>) = mutate {
        it.copy(profile = SpeedProfiles.normalize(it.profile.copy(preset = SpeedPreset.CUSTOM, points = points)))
    }

    fun openSearch(target: SelectionTarget = _draft.value.selectionTarget, waypointIndex: Int = _draft.value.selectionWaypointIndex) = mutate {
        it.copy(searchOpen = true, selectionTarget = target, selectionWaypointIndex = waypointIndex, searchResults = emptyList(), message = null)
    }
    fun closeSearch() = mutate { it.copy(searchOpen = false, searching = false) }
    fun setSearchQuery(value: String) = mutate { it.copy(searchQuery = value.take(200)) }

    fun search() {
        val query = _draft.value.searchQuery
        val base = settings.value.nominatimBaseUrl
        if (query.trim().length < 2) { message("Enter a place or address"); return }
        viewModelScope.launch {
            mutate { it.copy(searching = true, searchResults = emptyList(), message = null) }
            val result = app.searchClient.search(base, query)
            result.onSuccess { values ->
                mutate { it.copy(searching = false, searchResults = values, message = if (values.isEmpty()) "No places found" else null) }
            }.onFailure { error ->
                mutate { it.copy(searching = false, message = error.message ?: "Search unavailable") }
            }
        }
    }

    fun chooseSearchResult(result: SearchResult) {
        choosePoint(result.point)
        mutate { it.copy(searchOpen = false, searchResults = emptyList(), searchQuery = "") }
    }

    fun calculateDrive() {
        val state = _draft.value
        val points = listOfNotNull(state.start) + state.waypoints + listOfNotNull(state.destination)
        if (state.start == null || state.destination == null) { message("Choose start and destination"); return }
        viewModelScope.launch {
            mutate { it.copy(routing = true, driveRoute = null, message = null) }
            when (val result = app.routeClient.route(settings.value.osrmBaseUrl, points)) {
                is RouteResult.Success -> mutate {
                    it.copy(
                        routing = false,
                        driveRoute = result.route,
                        durationMs = (result.route.durationSeconds * 1_000).toLong().coerceAtLeast(1_000L),
                        message = null,
                    )
                }
                is RouteResult.Failure -> mutate { it.copy(routing = false, message = result.reason.userMessage()) }
            }
        }
    }

    fun checkAuthorization() {
        viewModelScope.launch(Dispatchers.IO) {
            val allowed = MockLocationController(getApplication()).probeAuthorization().isSuccess
            _authorized.value = allowed
        }
    }

    fun fetchRealLocation() {
        viewModelScope.launch {
            mutate { it.copy(message = "Finding current location…") }
            realLocationSource.current().onSuccess { point ->
                choosePoint(point)
            }.onFailure { error -> message(error.message ?: "Real location unavailable") }
        }
    }

    fun hasRealLocationPermission(): Boolean = realLocationSource.hasPermission()

    fun buildConfig(): Result<SimulationConfig> = runCatching {
        val state = _draft.value
        val control = when (state.mode) {
            SimulationMode.STATIC -> listOfNotNull(state.staticPoint)
            else -> listOfNotNull(state.start) + state.waypoints + listOfNotNull(state.destination)
        }
        val geometry = when (state.mode) {
            SimulationMode.STATIC -> control
            SimulationMode.TRAVEL -> state.importedGeometry ?: control
            SimulationMode.DRIVE -> state.driveRoute?.geometry.orEmpty()
        }
        val config = SimulationConfig(
            mode = state.mode,
            controlPoints = control.mapIndexed { index, point -> ControlPoint("p$index", point, controlLabel(index, control.size)) },
            geometry = geometry,
            durationMs = state.durationMs,
            updateIntervalMs = state.updateIntervalMs,
            accuracyMeters = state.accuracyMeters,
            includeAltitude = geometry.any { it.altitude != null },
            realismPercent = state.realismPercent,
            completion = state.completion,
            startDelayMs = state.startDelayMs,
            speedProfile = state.profile,
            name = "${state.mode.name.lowercase().replaceFirstChar { it.uppercase() }} route",
            driveRouteValidated = state.mode != SimulationMode.DRIVE || state.driveRoute != null,
        )
        ConfigValidator.error(config)?.let { throw IllegalArgumentException(it) }
        config
    }

    fun noteStarted(config: SimulationConfig) {
        viewModelScope.launch {
            if (config.mode == SimulationMode.STATIC) config.geometry.firstOrNull()?.let { addRecentPoint(it) }
            else store.addRecentRoute(SavedRoute(UUID.randomUUID().toString(), config.name, config))
        }
    }

    fun saveCurrentPlace(name: String, note: String = "", existingId: String? = null) {
        val point = when (_draft.value.mode) {
            SimulationMode.STATIC -> _draft.value.staticPoint
            else -> _draft.value.start
        } ?: run { message("Choose a location first"); return }
        if (name.isBlank()) { message("Enter a name"); return }
        viewModelScope.launch {
            store.savePlace(SavedPlace(existingId ?: UUID.randomUUID().toString(), name.trim(), point, note.trim()))
            message("Place saved")
        }
    }

    fun saveCurrentRoute(name: String, existingId: String? = null) {
        buildConfig().onSuccess { config ->
            if (name.isBlank()) { message("Enter a name"); return@onSuccess }
            viewModelScope.launch {
                store.saveRoute(SavedRoute(existingId ?: UUID.randomUUID().toString(), name.trim(), config.copy(name = name.trim())))
                message("Route saved")
            }
        }.onFailure { message(it.message ?: "Route is incomplete") }
    }

    fun usePlace(place: SavedPlace, target: SelectionTarget = _draft.value.selectionTarget) {
        setScreen(AppScreen.MAIN)
        selectTarget(target)
        choosePoint(place.point)
    }

    fun deletePlace(id: String) = viewModelScope.launch { store.deletePlace(id) }
    fun deleteRoute(id: String) = viewModelScope.launch { store.deleteRoute(id) }
    fun clearRecents() = viewModelScope.launch { store.clearRecents() }

    fun updateSavedPlace(place: SavedPlace, name: String, note: String) = viewModelScope.launch {
        if (name.isBlank()) { message("Enter a name"); return@launch }
        store.savePlace(place.copy(name = name.trim(), note = note.trim(), updatedAt = System.currentTimeMillis()))
        message("Place updated")
    }

    fun loadRoute(route: SavedRoute, runAfter: ((SimulationConfig) -> Unit)? = null) {
        val config = route.config
        val controls = config.controlPoints.map { it.point }
        mutate {
            it.copy(
                screen = AppScreen.MAIN,
                mode = config.mode,
                staticPoint = controls.firstOrNull().takeIf { config.mode == SimulationMode.STATIC },
                start = controls.firstOrNull().takeIf { config.mode != SimulationMode.STATIC },
                destination = controls.lastOrNull().takeIf { config.mode != SimulationMode.STATIC },
                waypoints = if (controls.size > 2) controls.subList(1, controls.lastIndex) else emptyList(),
                importedGeometry = config.geometry.takeIf { config.mode == SimulationMode.TRAVEL },
                driveRoute = if (config.mode == SimulationMode.DRIVE) RoadRoute(config.geometry, config.route.totalMeters, config.durationMs / 1_000.0, controls) else null,
                durationMs = config.durationMs,
                updateIntervalMs = config.updateIntervalMs,
                accuracyMeters = config.accuracyMeters,
                realismPercent = config.realismPercent,
                completion = config.completion,
                startDelayMs = config.startDelayMs,
                profile = config.speedProfile,
                message = null,
            )
        }
        runAfter?.invoke(config)
    }

    fun duplicateRoute(route: SavedRoute) = viewModelScope.launch {
        store.saveRoute(route.copy(id = UUID.randomUUID().toString(), name = route.name + " copy", updatedAt = System.currentTimeMillis()))
    }

    fun renameRoute(route: SavedRoute, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) store.saveRoute(route.copy(name = name.trim(), config = route.config.copy(name = name.trim()), updatedAt = System.currentTimeMillis()))
    }

    fun reverseSavedRoute(route: SavedRoute) {
        val controls = route.config.controlPoints.map { it.point }.reversed()
        if (controls.size < 2) { message("This route cannot be reversed"); return }
        if (route.config.mode == SimulationMode.TRAVEL) {
            val reversedControl = controls.mapIndexed { index, point -> ControlPoint("p$index", point, controlLabel(index, controls.size)) }
            val reversed = route.config.copy(
                controlPoints = reversedControl,
                geometry = route.config.geometry.reversed(),
                name = route.name,
            )
            viewModelScope.launch {
                store.saveRoute(route.copy(config = reversed, updatedAt = System.currentTimeMillis()))
                message("Route reversed")
            }
        } else if (route.config.mode == SimulationMode.DRIVE) {
            viewModelScope.launch {
                message("Rerouting reverse direction…")
                when (val result = app.routeClient.route(settings.value.osrmBaseUrl, controls)) {
                    is RouteResult.Success -> {
                        val reversedControl = controls.mapIndexed { index, point -> ControlPoint("p$index", point, controlLabel(index, controls.size)) }
                        val config = route.config.copy(
                            controlPoints = reversedControl,
                            geometry = result.route.geometry,
                            durationMs = (result.route.durationSeconds * 1_000).toLong(),
                            driveRouteValidated = true,
                            name = route.name,
                        )
                        store.saveRoute(route.copy(config = config, updatedAt = System.currentTimeMillis()))
                        message("Reverse route recalculated")
                    }
                    is RouteResult.Failure -> message(result.reason.userMessage())
                }
            }
        } else message("Static locations do not have a direction")
    }

    fun exportBackup(): String = store.exportBackup()
    fun importBackup(text: String) = viewModelScope.launch {
        runCatching { store.importBackup(text) }
            .onSuccess { message("Backup imported") }
            .onFailure { message(it.message ?: "Backup could not be imported") }
    }

    fun exportGpx(): Result<String> = buildConfig().map { GpxCodec.encode(it.name, it.geometry) }

    fun importGpx(text: String) {
        GpxCodec.decode(text).onSuccess { points ->
            mutate {
                it.copy(
                    screen = AppScreen.MAIN,
                    mode = SimulationMode.TRAVEL,
                    start = points.first(),
                    destination = points.last(),
                    waypoints = emptyList(),
                    importedGeometry = points,
                    driveRoute = null,
                    message = "GPX track imported",
                )
            }
        }.onFailure { message(it.message ?: "GPX could not be imported") }
    }

    fun setTheme(value: ThemeMode) = viewModelScope.launch { settingsRepository.setTheme(value) }
    fun setAccent(value: Accent) = viewModelScope.launch { settingsRepository.setAccent(value) }
    fun setDynamic(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamic(value) }
    fun setSearchEndpoint(value: String) = endpointUpdate { settingsRepository.setSearchUrl(value) }
    fun setRouteEndpoint(value: String) = endpointUpdate { settingsRepository.setRouteUrl(value) }

    fun message(value: String?) = mutate { it.copy(message = value) }

    private fun endpointUpdate(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onSuccess { message("Endpoint saved") }.onFailure { message(it.message ?: "Use a valid HTTPS URL") }
    }

    private suspend fun addRecentPoint(point: GeoPoint) {
        val id = "${"%.5f".format(java.util.Locale.US, point.latitude)},${"%.5f".format(java.util.Locale.US, point.longitude)}"
        store.addRecentPlace(SavedPlace(id, point.display(), point))
    }

    private fun mutate(block: (DraftState) -> DraftState) { _draft.value = block(_draft.value) }

    private fun controlLabel(index: Int, count: Int): String = when {
        count == 1 -> "Point"
        index == 0 -> "Start"
        index == count - 1 -> "Destination"
        else -> "Waypoint $index"
    }

    private fun RouteFailure.userMessage(): String = when (this) {
        RouteFailure.NoRoute -> "No driving route found"
        RouteFailure.CannotSnap -> "A point could not snap to a road"
        RouteFailure.Offline -> "No internet connection"
        RouteFailure.ServerUnavailable -> "Routing server unavailable"
        RouteFailure.MalformedResponse -> "Routing server returned an invalid route"
        is RouteFailure.Other -> message
    }
}
