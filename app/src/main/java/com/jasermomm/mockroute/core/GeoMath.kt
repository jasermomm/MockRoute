package com.jasermomm.mockroute.core

import kotlin.math.*

object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    fun normalizeLongitude(longitude: Double): Double {
        if (!longitude.isFinite()) return longitude
        val normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return if (normalized == -180.0 && longitude > 0) 180.0 else normalized
    }

    fun coordinate(latitude: Double, longitude: Double, altitude: Double? = null): GeoPoint =
        GeoPoint(latitude.coerceIn(-90.0, 90.0), normalizeLongitude(longitude), altitude)

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        require(a.isValid && b.isValid)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(normalizeLongitude(b.longitude - a.longitude))
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun initialBearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        if (haversineMeters(a, b) < 0.001) return 0.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(normalizeLongitude(b.longitude - a.longitude))
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        require(a.isValid && b.isValid)
        val f = fraction.coerceIn(0.0, 1.0)
        if (f == 0.0) return a
        if (f == 1.0) return b

        val lat1 = Math.toRadians(a.latitude)
        val lon1 = Math.toRadians(a.longitude)
        val lat2 = Math.toRadians(b.latitude)
        val lon2 = lon1 + Math.toRadians(normalizeLongitude(b.longitude - a.longitude))
        val dot = (sin(lat1) * sin(lat2) + cos(lat1) * cos(lat2) * cos(lon2 - lon1))
            .coerceIn(-1.0, 1.0)
        val angular = acos(dot)
        if (angular < 1e-12 || abs(sin(angular)) < 1e-12) {
            val lon = normalizeLongitude(a.longitude + normalizeLongitude(b.longitude - a.longitude) * f)
            val altitude = interpolateAltitude(a.altitude, b.altitude, f)
            return coordinate(a.latitude + (b.latitude - a.latitude) * f, lon, altitude)
        }
        val scaleA = sin((1 - f) * angular) / sin(angular)
        val scaleB = sin(f * angular) / sin(angular)
        val x = scaleA * cos(lat1) * cos(lon1) + scaleB * cos(lat2) * cos(lon2)
        val y = scaleA * cos(lat1) * sin(lon1) + scaleB * cos(lat2) * sin(lon2)
        val z = scaleA * sin(lat1) + scaleB * sin(lat2)
        val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        val lon = normalizeLongitude(Math.toDegrees(atan2(y, x)))
        return coordinate(lat, lon, interpolateAltitude(a.altitude, b.altitude, f))
    }

    private fun interpolateAltitude(a: Double?, b: Double?, f: Double): Double? =
        if (a != null && b != null) a + (b - a) * f else a ?: b

    fun offsetMeters(point: GeoPoint, eastMeters: Double, northMeters: Double): GeoPoint {
        val dLat = northMeters / EARTH_RADIUS_METERS
        val cosLat = cos(Math.toRadians(point.latitude)).coerceAtLeast(1e-9)
        val dLon = eastMeters / (EARTH_RADIUS_METERS * cosLat)
        return coordinate(
            point.latitude + Math.toDegrees(dLat),
            point.longitude + Math.toDegrees(dLon),
            point.altitude,
        )
    }

    fun parseCoordinate(input: String): GeoPoint? {
        val cleaned = input.trim().replace(';', ',')
        val pieces = if (',' in cleaned) cleaned.split(',') else cleaned.split(Regex("\\s+"))
        if (pieces.size != 2) return null
        val lat = pieces[0].trim().toDoubleOrNull() ?: return null
        val lon = pieces[1].trim().toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon).takeIf { it.isValid }
    }
}

class RouteGeometry(val points: List<GeoPoint>) {
    val segmentMeters: List<Double>
    val cumulativeMeters: List<Double>
    val totalMeters: Double

    init {
        require(points.isNotEmpty()) { "Route needs at least one point" }
        require(points.all { it.isValid }) { "Route contains an invalid point" }
        segmentMeters = points.zipWithNext(GeoMath::haversineMeters)
        cumulativeMeters = buildList {
            var sum = 0.0
            add(sum)
            segmentMeters.forEach {
                sum += it
                add(sum)
            }
        }
        totalMeters = cumulativeMeters.last()
    }

    fun pointAt(progress: Double): GeoPoint {
        if (points.size == 1 || totalMeters <= 1e-9) return points.last()
        val p = progress.coerceIn(0.0, 1.0)
        if (p <= 0.0) return points.first()
        if (p >= 1.0) return points.last()
        val target = totalMeters * p
        val segment = segmentForDistance(target)
        val localDistance = target - cumulativeMeters[segment]
        val fraction = if (segmentMeters[segment] <= 1e-9) 0.0 else localDistance / segmentMeters[segment]
        return GeoMath.interpolate(points[segment], points[segment + 1], fraction)
    }

    fun bearingAt(progress: Double): Double {
        if (points.size < 2 || totalMeters <= 1e-9) return 0.0
        val target = totalMeters * progress.coerceIn(0.0, 0.999999999)
        var segment = segmentForDistance(target)
        while (segment < segmentMeters.lastIndex && segmentMeters[segment] <= 1e-9) segment++
        return GeoMath.initialBearingDegrees(points[segment], points[segment + 1])
    }

    private fun segmentForDistance(distance: Double): Int {
        var low = 0
        var high = segmentMeters.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulativeMeters[mid + 1] < distance) low = mid + 1 else high = mid
        }
        return low
    }
}
