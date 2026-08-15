package com.jasermomm.mockroute

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jasermomm.mockroute.core.SimulationConfig
import com.jasermomm.mockroute.service.SimulationService
import com.jasermomm.mockroute.ui.AppRoot
import com.jasermomm.mockroute.ui.AppViewModel
import com.jasermomm.mockroute.ui.theme.MockRouteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private var pendingStart: SimulationConfig? = null
    private var findLocationAfterPermission = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val config = pendingStart.also { pendingStart = null } ?: return@registerForActivityResult
        if (!granted) viewModel.message("Notifications are off; background controls may be hidden")
        beginSimulation(config)
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.any { it }
        if (findLocationAfterPermission && granted) viewModel.fetchRealLocation()
        else if (findLocationAfterPermission) viewModel.message("Location permission denied")
        findLocationAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsState()
            MockRouteTheme(state.settings) {
                val scope = rememberCoroutineScope()
                var pendingExportText by remember { mutableStateOf<String?>(null) }

                val createJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    val text = pendingExportText.also { pendingExportText = null }
                    if (uri != null && text != null) scope.launch { writeUri(uri, text) }
                }
                val createGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
                    val text = pendingExportText.also { pendingExportText = null }
                    if (uri != null && text != null) scope.launch { writeUri(uri, text) }
                }
                val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch { readUri(uri)?.let(viewModel::importBackup) }
                }
                val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch { readUri(uri)?.let(viewModel::importGpx) }
                }

                AppRoot(
                    viewModel = viewModel,
                    state = state,
                    onStart = ::requestSimulation,
                    onRealLocation = ::requestRealLocation,
                    onOpenDeveloperOptions = ::openDeveloperOptions,
                    onExportBackup = {
                        pendingExportText = viewModel.exportBackup()
                        createJson.launch("MockRoute-backup.json")
                    },
                    onImportBackup = { openJson.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    onExportGpx = {
                        viewModel.exportGpx().onSuccess {
                            pendingExportText = it
                            createGpx.launch("MockRoute-route.gpx")
                        }.onFailure { viewModel.message(it.message ?: "Route is incomplete") }
                    },
                    onImportGpx = { openGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "text/plain")) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAuthorization()
    }

    private fun requestSimulation(config: SimulationConfig) {
        if (viewModel.uiState.value.mockAuthorized != true) {
            viewModel.message("Select MockRoute as the mock location app")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = config
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginSimulation(config)
    }

    private fun beginSimulation(config: SimulationConfig) {
        runCatching { SimulationService.start(this, config) }
            .onSuccess { viewModel.noteStarted(config) }
            .onFailure { viewModel.message("Simulation could not start") }
    }

    private fun requestRealLocation() {
        if (viewModel.hasRealLocationPermission()) viewModel.fetchRealLocation()
        else {
            findLocationAfterPermission = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private suspend fun writeUri(uri: android.net.Uri, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "File could not be opened" }
                .bufferedWriter()
                .use { it.write(text) }
        }
            .onSuccess { viewModel.message("File saved") }
            .onFailure { viewModel.message("File could not be saved") }
    }

    private suspend fun readUri(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(contentResolver.openInputStream(uri)) { "File could not be opened" }
                .bufferedReader()
                .use { reader ->
                val text = reader.readText()
                require(text.length <= 20_000_000) { "File is too large" }
                text
            }
        }.onFailure { viewModel.message(it.message ?: "File could not be read") }.getOrNull()
    }
}
