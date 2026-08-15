package com.jasermomm.mockroute.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jasermomm.mockroute.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("mockroute_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Accent { BLUE, PURPLE, GREEN, ORANGE, RED, TEAL, PINK, NEUTRAL }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.BLUE,
    val dynamicColor: Boolean = false,
    val nominatimBaseUrl: String = BuildConfig.NOMINATIM_BASE_URL,
    val osrmBaseUrl: String = BuildConfig.OSRM_BASE_URL,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val dynamic = booleanPreferencesKey("dynamic")
        val searchUrl = stringPreferencesKey("nominatim_url")
        val routeUrl = stringPreferencesKey("osrm_url")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = JsonCodec.enumValueOr(prefs[Keys.theme].orEmpty(), ThemeMode.SYSTEM),
            accent = JsonCodec.enumValueOr(prefs[Keys.accent].orEmpty(), Accent.BLUE),
            dynamicColor = prefs[Keys.dynamic] ?: false,
            nominatimBaseUrl = prefs[Keys.searchUrl]?.validBaseUrl() ?: BuildConfig.NOMINATIM_BASE_URL,
            osrmBaseUrl = prefs[Keys.routeUrl]?.validBaseUrl() ?: BuildConfig.OSRM_BASE_URL,
        )
    }

    suspend fun setTheme(value: ThemeMode) = context.settingsDataStore.edit { it[Keys.theme] = value.name }
    suspend fun setAccent(value: Accent) = context.settingsDataStore.edit { it[Keys.accent] = value.name }
    suspend fun setDynamic(value: Boolean) = context.settingsDataStore.edit { it[Keys.dynamic] = value }
    suspend fun setSearchUrl(value: String) = context.settingsDataStore.edit {
        it[Keys.searchUrl] = value.validBaseUrl() ?: throw IllegalArgumentException("Use a valid HTTPS URL")
    }
    suspend fun setRouteUrl(value: String) = context.settingsDataStore.edit {
        it[Keys.routeUrl] = value.validBaseUrl() ?: throw IllegalArgumentException("Use a valid HTTPS URL")
    }

    private fun String.validBaseUrl(): String? {
        val cleaned = trim().trimEnd('/')
        return cleaned.takeIf { it.startsWith("https://") && it.length in 9..500 && !it.contains(Regex("[\\s?#]")) }
    }
}
