package com.jasermomm.mockroute.network

import com.jasermomm.mockroute.core.GeoPoint
import com.jasermomm.mockroute.core.RouteResult
import org.junit.Assert.*
import org.junit.Test

class NetworkClientsTest {
    @Test fun routingUrlUsesLongitudeLatitudeOrderAndFullGeometry() {
        val url = OsrmClient.buildUrl("https://router.example/", listOf(GeoPoint(30.0, 31.0), GeoPoint(40.0, 41.0)))
        assertTrue(url.contains("31.0000000,30.0000000;41.0000000,40.0000000"))
        assertTrue(url.contains("overview=full"))
        assertTrue(url.contains("geometries=geojson"))
    }

    @Test fun searchQueryIsEncodedAndNotAutocomplete() {
        val url = NominatimClient.buildUrl("https://search.example", "الجامعة الأمريكية بالقاهرة & Cairo", "ar-EG")
        assertFalse(url.contains(" "))
        assertTrue(url.contains("%26"))
        assertTrue(url.contains("limit=8"))
        assertTrue(url.startsWith("https://search.example/search?"))
    }

    @Test fun searchParsingHandlesNativeScriptAndSkipsMalformedItems() {
        val json = """[
          {"display_name":"مطار القاهرة الدولي","lat":"30.1219","lon":"31.4056","type":"aerodrome"},
          {"display_name":"broken","lat":"x","lon":"31"}
        ]"""
        val results = NominatimClient.parse(json)
        assertEquals(1, results.size)
        assertEquals("مطار القاهرة الدولي", results.single().displayName)
    }

    @Test fun malformedSearchResponseThrows() {
        assertThrows(Exception::class.java) { NominatimClient.parse("not json") }
    }

    @Test fun routeParserUsesCompleteReturnedGeometry() {
        val body = """{
          "code":"Ok",
          "waypoints":[{"location":[31.0,30.0]},{"location":[31.2,30.2]}],
          "routes":[{"distance":1234.5,"duration":321.0,"geometry":{"type":"LineString","coordinates":[[31.0,30.0],[31.05,30.1],[31.2,30.2]]}}]
        }"""
        val result = OsrmClient.parse(body) as RouteResult.Success
        assertEquals(3, result.route.geometry.size)
        assertEquals(GeoPoint(30.1, 31.05), result.route.geometry[1])
        assertEquals(1234.5, result.route.distanceMeters, 0.0)
    }

    @Test fun driveNoRouteAndBadGeometryAreNotFakeFallbacks() {
        assertTrue(OsrmClient.parse("{\"code\":\"NoRoute\"}") is RouteResult.Failure)
        assertTrue(OsrmClient.parse("{\"code\":\"Ok\",\"routes\":[]}") is RouteResult.Failure)
    }
}
