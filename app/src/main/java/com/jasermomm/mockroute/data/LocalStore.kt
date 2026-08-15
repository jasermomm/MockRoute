package com.jasermomm.mockroute.data

import android.content.Context
import com.jasermomm.mockroute.core.SavedPlace
import com.jasermomm.mockroute.core.SavedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class StoredData(
    val savedPlaces: List<SavedPlace> = emptyList(),
    val savedRoutes: List<SavedRoute> = emptyList(),
    val recentPlaces: List<SavedPlace> = emptyList(),
    val recentRoutes: List<SavedRoute> = emptyList(),
)

class LocalStore(context: Context) {
    private val file = File(context.filesDir, "mockroute_store.json")
    private val temp = File(context.filesDir, "mockroute_store.tmp")
    private val mutex = Mutex()
    private val _data = MutableStateFlow(loadSafely())
    val data: StateFlow<StoredData> = _data.asStateFlow()

    suspend fun savePlace(place: SavedPlace) = update { value ->
        value.copy(savedPlaces = value.savedPlaces.upsert(place) { it.id }.sortedBy { it.name.lowercase() })
    }

    suspend fun deletePlace(id: String) = update { it.copy(savedPlaces = it.savedPlaces.filterNot { p -> p.id == id }) }

    suspend fun saveRoute(route: SavedRoute) = update { value ->
        value.copy(savedRoutes = value.savedRoutes.upsert(route) { it.id }.sortedBy { it.name.lowercase() })
    }

    suspend fun deleteRoute(id: String) = update { it.copy(savedRoutes = it.savedRoutes.filterNot { r -> r.id == id }) }

    suspend fun addRecentPlace(place: SavedPlace) = update { value ->
        value.copy(recentPlaces = (listOf(place) + value.recentPlaces.filterNot { it.id == place.id }).take(30))
    }

    suspend fun addRecentRoute(route: SavedRoute) = update { value ->
        value.copy(recentRoutes = (listOf(route) + value.recentRoutes.filterNot { it.id == route.id }).take(20))
    }

    suspend fun clearRecents() = update { it.copy(recentPlaces = emptyList(), recentRoutes = emptyList()) }

    suspend fun importBackup(text: String) {
        val parsed = runCatching { JsonCodec.stored(JSONObject(text)) }
            .getOrElse { throw IllegalArgumentException("This backup is not valid MockRoute JSON") }
        update { parsed }
    }

    fun exportBackup(): String = JsonCodec.stored(_data.value).toString(2)

    private suspend fun update(block: (StoredData) -> StoredData) = mutex.withLock {
        val updated = block(_data.value)
        persist(updated)
        _data.value = updated
    }

    private suspend fun persist(value: StoredData) = withContext(Dispatchers.IO) {
        temp.writeText(JsonCodec.stored(value).toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun loadSafely(): StoredData = runCatching {
        if (!file.exists()) StoredData() else JsonCodec.stored(JSONObject(file.readText()))
    }.getOrDefault(StoredData())

    private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> {
        val index = indexOfFirst { id(it) == id(value) }
        return if (index < 0) this + value else toMutableList().apply { this[index] = value }
    }
}
