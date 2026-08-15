package com.jasermomm.mockroute.data

import com.jasermomm.mockroute.core.*
import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {
    fun point(point: GeoPoint): JSONObject = JSONObject()
        .put("lat", point.latitude)
        .put("lon", point.longitude)
        .apply { point.altitude?.let { put("alt", it) } }

    fun point(json: JSONObject): GeoPoint = GeoPoint(
        json.getDouble("lat"),
        json.getDouble("lon"),
        json.optDoubleOrNull("alt"),
    ).requireValid()

    fun control(value: ControlPoint): JSONObject = JSONObject()
        .put("id", value.id)
        .put("label", value.label)
        .put("point", point(value.point))

    fun control(json: JSONObject): ControlPoint = ControlPoint(
        json.getString("id"),
        point(json.getJSONObject("point")),
        json.optString("label"),
    )

    fun profile(value: SpeedProfile): JSONObject = JSONObject()
        .put("preset", value.preset.name)
        .put("mode", value.mode.name)
        .put("points", JSONArray().apply {
            value.points.forEach { put(JSONObject().put("t", it.timeFraction).put("v", it.value)) }
        })

    fun profile(json: JSONObject): SpeedProfile = SpeedProfile(
        preset = enumValueOr(json.optString("preset"), SpeedPreset.CONSTANT),
        mode = enumValueOr(json.optString("mode"), ProfileMode.DURATION_LOCKED),
        points = json.optJSONArray("points")?.objects()?.map {
            SpeedPoint(it.getDouble("t"), it.getDouble("v"))
        }.orEmpty().ifEmpty { SpeedProfiles.preset(SpeedPreset.CONSTANT).points },
    ).let(SpeedProfiles::normalize)

    fun config(value: SimulationConfig): JSONObject = JSONObject()
        .put("mode", value.mode.name)
        .put("controlPoints", JSONArray().apply { value.controlPoints.forEach { put(control(it)) } })
        .put("geometry", JSONArray().apply { value.geometry.forEach { put(point(it)) } })
        .put("durationMs", value.durationMs)
        .put("updateIntervalMs", value.updateIntervalMs)
        .put("accuracyMeters", value.accuracyMeters.toDouble())
        .put("includeAltitude", value.includeAltitude)
        .put("realismPercent", value.realismPercent)
        .put("completion", value.completion.name)
        .put("startDelayMs", value.startDelayMs)
        .put("speedProfile", profile(value.speedProfile))
        .put("name", value.name)
        .put("driveRouteValidated", value.driveRouteValidated)

    fun config(json: JSONObject): SimulationConfig = SimulationConfig(
        mode = enumValueOr(json.optString("mode"), SimulationMode.STATIC),
        controlPoints = json.getJSONArray("controlPoints").objects().map(::control),
        geometry = json.getJSONArray("geometry").objects().map(::point),
        durationMs = json.optLong("durationMs", 60_000L),
        updateIntervalMs = json.optLong("updateIntervalMs", 1_000L),
        accuracyMeters = json.optDouble("accuracyMeters", 5.0).toFloat(),
        includeAltitude = json.optBoolean("includeAltitude", false),
        realismPercent = json.optInt("realismPercent", 0),
        completion = enumValueOr(json.optString("completion"), CompletionBehavior.STOP),
        startDelayMs = json.optLong("startDelayMs", 0L),
        speedProfile = json.optJSONObject("speedProfile")?.let(::profile) ?: SpeedProfile(),
        name = json.optString("name"),
        driveRouteValidated = json.optBoolean("driveRouteValidated", false),
    )

    fun savedPlace(value: SavedPlace): JSONObject = JSONObject()
        .put("id", value.id).put("name", value.name).put("point", point(value.point))
        .put("note", value.note).put("updatedAt", value.updatedAt)

    fun savedPlace(json: JSONObject): SavedPlace = SavedPlace(
        json.getString("id"), json.getString("name"), point(json.getJSONObject("point")),
        json.optString("note"), json.optLong("updatedAt", 0L),
    )

    fun savedRoute(value: SavedRoute): JSONObject = JSONObject()
        .put("id", value.id).put("name", value.name).put("config", config(value.config))
        .put("updatedAt", value.updatedAt)

    fun savedRoute(json: JSONObject): SavedRoute = SavedRoute(
        json.getString("id"), json.getString("name"), config(json.getJSONObject("config")),
        json.optLong("updatedAt", 0L),
    )

    fun stored(value: StoredData): JSONObject = JSONObject()
        .put("schema", 1)
        .put("savedPlaces", JSONArray().apply { value.savedPlaces.forEach { put(savedPlace(it)) } })
        .put("savedRoutes", JSONArray().apply { value.savedRoutes.forEach { put(savedRoute(it)) } })
        .put("recentPlaces", JSONArray().apply { value.recentPlaces.forEach { put(savedPlace(it)) } })
        .put("recentRoutes", JSONArray().apply { value.recentRoutes.forEach { put(savedRoute(it)) } })

    fun stored(json: JSONObject): StoredData = StoredData(
        savedPlaces = json.optJSONArray("savedPlaces")?.objects()?.map(::savedPlace).orEmpty(),
        savedRoutes = json.optJSONArray("savedRoutes")?.objects()?.map(::savedRoute).orEmpty(),
        recentPlaces = json.optJSONArray("recentPlaces")?.objects()?.map(::savedPlace).orEmpty(),
        recentRoutes = json.optJSONArray("recentRoutes")?.objects()?.map(::savedRoute).orEmpty(),
    )

    inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) getDouble(name) else null

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }
}
