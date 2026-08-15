package com.jasermomm.mockroute.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GeoMathTest {
    @Test fun coordinateValidationAndParsing() {
        assertTrue(GeoPoint(90.0, -180.0).isValid)
        assertFalse(GeoPoint(90.1, 0.0).isValid)
        assertFalse(GeoPoint(0.0, 180.1).isValid)
        assertEquals(GeoPoint(29.9792, 31.1342), GeoMath.parseCoordinate("29.9792, 31.1342"))
        assertEquals(GeoPoint(29.9792, 31.1342), GeoMath.parseCoordinate("29.9792 31.1342"))
        assertNull(GeoMath.parseCoordinate("not a coordinate"))
    }

    @Test fun haversineAndBearingAreAccurate() {
        val cairo = GeoPoint(30.0444, 31.2357)
        val alex = GeoPoint(31.2001, 29.9187)
        assertEquals(178_700.0, GeoMath.haversineMeters(cairo, alex), 2_500.0)
        assertTrue(GeoMath.initialBearingDegrees(cairo, alex) in 300.0..340.0)
        assertEquals(0.0, GeoMath.haversineMeters(cairo, cairo), 1e-9)
    }

    @Test fun antimeridianUsesShortPath() {
        val a = GeoPoint(0.0, 179.0)
        val b = GeoPoint(0.0, -179.0)
        assertEquals(222_390.0, GeoMath.haversineMeters(a, b), 500.0)
        val halfway = GeoMath.interpolate(a, b, 0.5)
        assertTrue(abs(abs(halfway.longitude) - 180.0) < 1e-6)
    }

    @Test fun multiSegmentInterpolationUsesDistanceNotPointIndex() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.0, 0.11))
        val route = RouteGeometry(points)
        val midpoint = route.pointAt(0.5)
        assertEquals(0.055, midpoint.longitude, 0.001)
        assertEquals(route.segmentMeters.sum(), route.totalMeters, 1e-6)
        assertEquals(0.0, route.pointAt(0.0).longitude, 0.0)
        assertEquals(0.11, route.pointAt(1.0).longitude, 0.0)
    }

    @Test fun zeroLengthSegmentsDoNotBreakRoute() {
        val a = GeoPoint(10.0, 10.0)
        val b = GeoPoint(10.1, 10.1)
        val route = RouteGeometry(listOf(a, a, b))
        assertTrue(route.totalMeters > 0)
        assertTrue(route.pointAt(0.5).isValid)
        assertTrue(route.bearingAt(0.0).isFinite())
    }

    @Test fun longitudeNormalizationWorksForLargeValues() {
        assertEquals(-170.0, GeoMath.normalizeLongitude(190.0), 0.0)
        assertEquals(170.0, GeoMath.normalizeLongitude(-190.0), 0.0)
        assertEquals(10.0, GeoMath.normalizeLongitude(730.0), 0.0)
    }
}
