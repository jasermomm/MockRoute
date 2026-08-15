package com.jasermomm.mockroute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.jasermomm.mockroute.core.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PointChooser(
    title: String,
    point: GeoPoint?,
    selected: Boolean,
    onPick: () -> Unit,
    onSearch: () -> Unit,
    onCoordinates: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        point?.display() ?: "Not selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                trailing()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSearch, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Search")
                }
                TextButton(onClick = onCoordinates, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.EditLocationAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Coordinates")
                }
                TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Map")
                }
            }
        }
    }
}

@Composable
fun DurationEditor(durationMs: Long, onChange: (Int, Int, Int) -> Unit) {
    val totalSeconds = (durationMs / 1_000).toInt()
    var hours by remember(durationMs) { mutableStateOf((totalSeconds / 3_600).toString()) }
    var minutes by remember(durationMs) { mutableStateOf(((totalSeconds % 3_600) / 60).toString()) }
    var seconds by remember(durationMs) { mutableStateOf((totalSeconds % 60).toString()) }
    fun commit() = onChange(hours.toIntOrNull() ?: 0, minutes.toIntOrNull() ?: 0, seconds.toIntOrNull() ?: 0)
    Column {
        Text("Duration", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationField("Hours", hours, Modifier.weight(1f)) { hours = it; commit() }
            DurationField("Minutes", minutes, Modifier.weight(1f)) { minutes = it; commit() }
            DurationField("Seconds", seconds, Modifier.weight(1f)) { seconds = it; commit() }
        }
    }
}

@Composable
private fun DurationField(label: String, value: String, modifier: Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(3)) },
        modifier = modifier,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedPanel(draft: DraftState, viewModel: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalDivider()
        Text("Update rate", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(2_000L to "0.5 Hz", 1_000L to "1 Hz", 500L to "2 Hz", 200L to "5 Hz").forEach { (ms, label) ->
                FilterChip(selected = draft.updateIntervalMs == ms, onClick = { viewModel.setUpdateInterval(ms) }, label = { Text(label) })
            }
        }

        Text("Realism ${draft.realismPercent}%", style = MaterialTheme.typography.labelLarge)
        Slider(value = draft.realismPercent.toFloat(), onValueChange = { viewModel.setRealism(it.roundToInt()) }, valueRange = 0f..100f)

        Text("Accuracy ${draft.accuracyMeters.roundToInt()} m", style = MaterialTheme.typography.labelLarge)
        Slider(value = draft.accuracyMeters, onValueChange = viewModel::setAccuracy, valueRange = 1f..100f)

        Text("Start delay", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0L to "Now", 5_000L to "5s", 10_000L to "10s", 30_000L to "30s", 60_000L to "1m").forEach { (ms, label) ->
                FilterChip(selected = draft.startDelayMs == ms, onClick = { viewModel.setStartDelay(ms) }, label = { Text(label) })
            }
        }

        if (draft.mode != SimulationMode.STATIC) {
            Text("Completion", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompletionBehavior.entries.forEach { behavior ->
                    FilterChip(
                        selected = draft.completion == behavior,
                        onClick = { viewModel.setCompletion(behavior) },
                        label = { Text(behavior.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }

            Text("Speed profile", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SpeedPreset.CONSTANT, SpeedPreset.WALKING, SpeedPreset.JOGGING, SpeedPreset.CYCLING, SpeedPreset.CITY, SpeedPreset.HIGHWAY, SpeedPreset.SMOOTH).forEach { preset ->
                    FilterChip(
                        selected = draft.profile.preset == preset,
                        onClick = { viewModel.setProfile(preset) },
                        label = { Text(preset.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            SpeedGraphEditor(draft.profile, viewModel::setProfilePoints)
            Text("Drag the graph to shape relative speed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SpeedGraphEditor(profile: SpeedProfile, onChange: (List<SpeedPoint>) -> Unit) {
    val samples = remember(profile) {
        MutableList(7) { index -> SpeedPoint(index / 6.0, SpeedProfiles.valueAt(profile, index / 6.0).coerceIn(0.0, 1.5)) }
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(profile) {
                var activeIndex = -1
                detectDragGestures(
                    onDragStart = { offset -> activeIndex = ((offset.x / size.width) * 6).roundToInt().coerceIn(0, 6) },
                    onDragEnd = { activeIndex = -1 },
                    onDragCancel = { activeIndex = -1 },
                ) { change, _ ->
                    if (activeIndex >= 0) {
                        change.consume()
                        val value = ((size.height - change.position.y) / size.height * 1.5f).coerceIn(0f, 1.5f).toDouble()
                        samples[activeIndex] = samples[activeIndex].copy(value = value)
                        onChange(samples.toList())
                    }
                }
            },
    ) {
        drawRoundRect(color = gridColor.copy(alpha = .25f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f))
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val path = Path()
        samples.forEachIndexed { index, p ->
            val x = size.width * p.timeFraction.toFloat()
            val y = size.height * (1f - (p.value / 1.5).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
        samples.forEach { p ->
            drawCircle(lineColor, radius = 9f, center = Offset(size.width * p.timeFraction.toFloat(), size.height * (1f - (p.value / 1.5).toFloat())))
        }
    }
}

@Composable
fun ActivePanel(snapshot: SimulationSnapshot, onPauseResume: () -> Unit, onStop: () -> Unit, onSeek: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp)) {
                Text("ACTIVE", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(snapshot.mode?.name?.lowercase()?.replaceFirstChar(Char::uppercase).orEmpty(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${(snapshot.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        snapshot.point?.let { Text(it.display(), style = MaterialTheme.typography.bodyMedium) }
        if (snapshot.mode != SimulationMode.STATIC) {
            LinearProgressIndicator(progress = { snapshot.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Slider(value = snapshot.progress.toFloat(), onValueChange = { onSeek(it.toDouble()) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Speed", "%.1f km/h".format(snapshot.speedMps * 3.6))
                Metric("Remaining", formatDistance(snapshot.remainingMeters))
                Metric("ETA", snapshot.remainingMs.formatDuration())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Average", "%.1f km/h".format(snapshot.averageSpeedMps * 3.6))
                Metric("Maximum", "%.1f km/h".format(snapshot.maxSpeedMps * 3.6))
                Metric("Elapsed", snapshot.elapsedMs.formatDuration())
            }
        } else {
            Metric("Elapsed", snapshot.elapsedMs.formatDuration())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (snapshot.mode != SimulationMode.STATIC) {
                OutlinedButton(onClick = onPauseResume, modifier = Modifier.weight(1f)) {
                    Icon(if (snapshot.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(6.dp)); Text(if (snapshot.paused) "Resume" else "Pause")
                }
            }
            Button(onClick = onStop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDistance(meters: Double): String = if (meters >= 1_000) "%.1f km".format(meters / 1_000) else "${meters.roundToInt()} m"

@Composable
fun SearchDialog(draft: DraftState, viewModel: AppViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeSearch,
        title = { Text("Search places") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Place or address") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                    )
                    FilledIconButton(onClick = viewModel::search, enabled = !draft.searching) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Search") }
                }
                if (draft.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                draft.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.searchResults.forEach { result ->
                        ListItem(
                            headlineContent = { Text(result.displayName, maxLines = 2) },
                            supportingContent = { Text(result.point.display(), maxLines = 1) },
                            leadingContent = { Icon(Icons.Default.Place, null) },
                            modifier = Modifier.clickable { viewModel.chooseSearchResult(result) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeSearch) { Text("Close") } },
    )
}

@Composable
fun CoordinateDialog(
    title: String,
    initial: GeoPoint?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
) {
    var value by remember(initial) { mutableStateOf(initial?.display().orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; invalid = false },
                label = { Text("Latitude, longitude") },
                supportingText = { Text(if (invalid) "Enter valid coordinates" else "Example: 29.9792, 31.1342") },
                isError = invalid,
                singleLine = true,
            )
        },
        confirmButton = { Button(onClick = { if (onConfirm(value)) onDismiss() else invalid = true }) { Text("Use point") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NameDialog(
    title: String,
    initialName: String = "",
    includeNote: Boolean = false,
    initialNote: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true)
                if (includeNote) OutlinedTextField(note, { note = it.take(300) }, label = { Text("Note (optional)") }, maxLines = 3)
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) { onSave(name, note); onDismiss() } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
