package com.jasermomm.mockroute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jasermomm.mockroute.core.GeoPoint
import com.jasermomm.mockroute.core.SimulationSnapshot
import kotlinx.coroutines.delay

internal const val MAP_RENDERED_TAG = "mockroute-web-map-tile-rendered"
private const val MAP_LOADING_TIMEOUT_MS = 25_000L

data class MapUiState(
    val controls: List<Pair<String, GeoPoint>> = emptyList(),
    val route: List<GeoPoint> = emptyList(),
    val active: GeoPoint? = null,
    val follow: Boolean = false,
    val accent: String = "#2458D3",
)

internal enum class MapLoadState { LOADING, READY, TILES_UNAVAILABLE, FAILED }

internal interface MapRuntime {
    fun render(state: MapUiState)
    fun center(point: GeoPoint, zoom: Int)
    fun fit(state: MapUiState)
    fun zoomIn()
    fun zoomOut()
    fun repaint()
}

class MapController {
    private var runtime: MapRuntime? = null
    private var pendingState = MapUiState()

    internal fun attach(value: MapRuntime) {
        runtime = value
        value.render(pendingState)
    }

    internal fun detach(value: MapRuntime) {
        if (runtime === value) runtime = null
    }

    fun push(state: MapUiState) {
        pendingState = state
        runtime?.render(state)
    }

    fun center(point: GeoPoint, zoom: Int = 16) = runtime?.center(point, zoom)
    fun fit() = runtime?.fit(pendingState)
    fun zoomIn() = runtime?.zoomIn()
    fun zoomOut() = runtime?.zoomOut()

    fun setFollow(value: Boolean) {
        pendingState = pendingState.copy(follow = value)
        runtime?.render(pendingState)
    }

    fun invalidate() = runtime?.repaint()
}

@Composable
fun MockRouteMap(
    state: MapUiState,
    controller: MapController,
    modifier: Modifier = Modifier,
    onMapTap: (GeoPoint) -> Unit,
    onMapLongPress: (GeoPoint) -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf(MapLoadState.LOADING) }
    val currentTap by rememberUpdatedState(onMapTap)
    val currentLongPress by rememberUpdatedState(onMapLongPress)
    val currentGeneration by rememberUpdatedState(reloadKey)
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(state) { controller.push(state) }
    LaunchedEffect(reloadKey) {
        loadState = MapLoadState.LOADING
        delay(MAP_LOADING_TIMEOUT_MS)
        if (currentGeneration == reloadKey && loadState == MapLoadState.LOADING) {
            loadState = MapLoadState.TILES_UNAVAILABLE
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        key(reloadKey) {
            val generation = reloadKey
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    OsmMapView(context).apply {
                        onPick = { if (currentGeneration == generation) currentTap(it) }
                        onLongPick = { if (currentGeneration == generation) currentLongPress(it) }
                        onTileRendered = {
                            if (currentGeneration == generation) loadState = MapLoadState.READY
                        }
                        onTilesUnavailable = {
                            if (currentGeneration == generation && loadState != MapLoadState.READY) {
                                loadState = MapLoadState.TILES_UNAVAILABLE
                            }
                        }
                        onMapError = {
                            if (currentGeneration == generation) loadState = MapLoadState.FAILED
                        }
                        controller.attach(this)
                    }
                },
                update = { it.render(state) },
                onRelease = {
                    controller.detach(it)
                    it.release()
                },
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 220.dp)
                .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
            color = MaterialTheme.colorScheme.surface.copy(alpha = .90f),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = "© OpenStreetMap",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        when (loadState) {
            MapLoadState.LOADING -> MapStatusCard(loading = true, text = "Loading map")
            MapLoadState.TILES_UNAVAILABLE -> MapStatusCard(
                text = "Map tiles unavailable",
                action = "Retry",
                onAction = { reloadKey += 1 },
            )
            MapLoadState.FAILED -> MapStatusCard(
                text = "Map could not load",
                action = "Retry",
                onAction = { reloadKey += 1 },
            )
            MapLoadState.READY -> Unit
        }
    }
}

@Composable
private fun MapStatusCard(
    text: String,
    loading: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.widthIn(max = 260.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
            }
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

fun SimulationSnapshot.asMapActive(): GeoPoint? = point
