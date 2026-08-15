package com.jasermomm.mockroute.network

import android.content.Context
import com.jasermomm.mockroute.core.*
import com.jasermomm.mockroute.data.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val USER_AGENT = "MockRoute/1.0.4 (Android; com.jasermomm.mockroute)"

class RequestGate(private val minimumIntervalMs: Long = 1_050L) {
    private val mutex = Mutex()
    private var lastStartMs = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = minimumIntervalMs - (System.currentTimeMillis() - lastStartMs)
        if (wait > 0) delay(wait)
        lastStartMs = System.currentTimeMillis()
        block()
    }
}

object SimpleHttp {
    suspend fun get(url: String, language: String? = null): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            language?.let { connection.setRequestProperty("Accept-Language", it) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

data class HttpResponse(val status: Int, val body: String)

class NominatimClient(context: Context) {
    private val gate = RequestGate()
    private val cache = SearchCache(context)

    suspend fun search(baseUrl: String, rawQuery: String): Result<List<SearchResult>> {
        val query = rawQuery.trim()
        if (query.length !in 2..200) return Result.failure(IllegalArgumentException("Enter a place or address"))
        val language = Locale.getDefault().toLanguageTag()
        cache.get(query, language)?.let { return Result.success(it) }
        return runCatching {
            gate.run {
                val response = SimpleHttp.get(buildUrl(baseUrl, query, language), language)
                if (response.status !in 200..299) throw IOException("Search service returned ${response.status}")
                parse(response.body).also { cache.put(query, language, it) }
            }
        }.recoverCatching { error ->
            when (error) {
                is UnknownHostException -> throw IOException("No internet connection")
                is SocketTimeoutException -> throw IOException("Search timed out")
                is IOException -> throw error
                else -> throw IOException("Search service returned an invalid response")
            }
        }
    }

    companion object {
        fun buildUrl(baseUrl: String, query: String, language: String): String {
            val q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            val lang = URLEncoder.encode(language, StandardCharsets.UTF_8.name())
            return "${baseUrl.trimEnd('/')}/search?format=jsonv2&q=$q&limit=8&addressdetails=1&accept-language=$lang"
        }

        fun parse(body: String): List<SearchResult> {
            val array = JSONArray(body)
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val point = GeoPoint(
                    item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                    item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null,
                )
                if (!point.isValid) return@mapNotNull null
                SearchResult(
                    displayName = item.optString("display_name").ifBlank { "Unnamed place" },
                    point = point,
                    type = item.optString("type"),
                )
            }
        }
    }
}

class OsrmClient {
    private val gate = RequestGate()

    suspend fun route(baseUrl: String, controlPoints: List<GeoPoint>): RouteResult {
        if (controlPoints.size < 2 || controlPoints.any { !it.isValid }) {
            return RouteResult.Failure(RouteFailure.MalformedResponse)
        }
        return try {
            gate.run {
                val response = SimpleHttp.get(buildUrl(baseUrl, controlPoints))
                when {
                    response.status == 400 || response.status == 404 -> parseFailure(response.body)
                    response.status !in 200..299 -> RouteResult.Failure(RouteFailure.ServerUnavailable)
                    else -> parse(response.body)
                }
            }
        } catch (_: UnknownHostException) {
            RouteResult.Failure(RouteFailure.Offline)
        } catch (_: SocketTimeoutException) {
            RouteResult.Failure(RouteFailure.ServerUnavailable)
        } catch (_: IOException) {
            RouteResult.Failure(RouteFailure.Offline)
        } catch (_: Exception) {
            RouteResult.Failure(RouteFailure.MalformedResponse)
        }
    }

    companion object {
        fun buildUrl(baseUrl: String, points: List<GeoPoint>): String {
            require(points.size >= 2 && points.all { it.isValid })
            val coordinates = points.joinToString(";") {
                "%.7f,%.7f".format(Locale.US, it.longitude, it.latitude)
            }
            return "${baseUrl.trimEnd('/')}/route/v1/driving/$coordinates?overview=full&geometries=geojson&steps=false&alternatives=false"
        }

        fun parse(body: String): RouteResult {
            val root = JSONObject(body)
            val code = root.optString("code")
            if (code != "Ok") return failureForCode(code)
            val route = root.optJSONArray("routes")?.optJSONObject(0)
                ?: return RouteResult.Failure(RouteFailure.NoRoute)
            val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return RouteResult.Failure(RouteFailure.MalformedResponse)
            val geometry = (0 until coordinates.length()).mapNotNull { index ->
                val pair = coordinates.optJSONArray(index) ?: return@mapNotNull null
                if (pair.length() < 2) return@mapNotNull null
                GeoPoint(pair.optDouble(1, Double.NaN), pair.optDouble(0, Double.NaN)).takeIf { it.isValid }
            }
            if (geometry.size < 2) return RouteResult.Failure(RouteFailure.MalformedResponse)
            val snapped = root.optJSONArray("waypoints")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val pair = array.optJSONObject(index)?.optJSONArray("location") ?: return@mapNotNull null
                    GeoPoint(pair.optDouble(1, Double.NaN), pair.optDouble(0, Double.NaN)).takeIf { it.isValid }
                }
            }.orEmpty()
            val distance = route.optDouble("distance", Double.NaN)
            val duration = route.optDouble("duration", Double.NaN)
            if (!distance.isFinite() || distance <= 0 || !duration.isFinite() || duration <= 0) {
                return RouteResult.Failure(RouteFailure.MalformedResponse)
            }
            return RouteResult.Success(RoadRoute(geometry, distance, duration, snapped))
        }

        private fun parseFailure(body: String): RouteResult = runCatching {
            failureForCode(JSONObject(body).optString("code"))
        }.getOrDefault(RouteResult.Failure(RouteFailure.ServerUnavailable))

        private fun failureForCode(code: String): RouteResult = RouteResult.Failure(
            when (code) {
                "NoRoute" -> RouteFailure.NoRoute
                "NoSegment", "InvalidValue" -> RouteFailure.CannotSnap
                else -> RouteFailure.MalformedResponse
            },
        )
    }
}

private class SearchCache(context: Context) {
    private val file = File(context.cacheDir, "search_cache.json")
    private val mutex = Mutex()
    private val maxAgeMs = 30L * 24 * 60 * 60 * 1_000

    suspend fun get(query: String, language: String): List<SearchResult>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = load()
            val entry = root.optJSONObject(key(query, language)) ?: return@withContext null
            if (System.currentTimeMillis() - entry.optLong("time") > maxAgeMs) return@withContext null
            entry.optJSONArray("results")?.let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    SearchResult(item.getString("name"), JsonCodec.point(item.getJSONObject("point")), item.optString("type"))
                }
            }
        }
    }

    suspend fun put(query: String, language: String, results: List<SearchResult>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = load()
            root.put(key(query, language), JSONObject()
                .put("time", System.currentTimeMillis())
                .put("results", JSONArray().apply {
                    results.forEach { put(JSONObject().put("name", it.displayName).put("point", JsonCodec.point(it.point)).put("type", it.type)) }
                }))
            val keys = root.keys().asSequence().toList()
            if (keys.size > 50) keys.take(keys.size - 50).forEach(root::remove)
            file.writeText(root.toString())
        }
    }

    private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private fun key(query: String, language: String) =
        "${language.lowercase(Locale.ROOT)}|${query.trim().lowercase(Locale.ROOT)}"
}
