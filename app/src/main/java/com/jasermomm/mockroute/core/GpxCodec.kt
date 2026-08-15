package com.jasermomm.mockroute.core

object GpxCodec {
    fun encode(name: String, points: List<GeoPoint>): String {
        require(points.isNotEmpty() && points.all { it.isValid })
        val body = points.joinToString("\n") { p ->
            val elevation = p.altitude?.let { "<ele>${"%.3f".format(java.util.Locale.US, it)}</ele>" }.orEmpty()
            "      <trkpt lat=\"${"%.8f".format(java.util.Locale.US, p.latitude)}\" lon=\"${"%.8f".format(java.util.Locale.US, p.longitude)}\">$elevation</trkpt>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MockRoute/1.0.4" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>${escape(name)}</name><trkseg>
$body
  </trkseg></trk>
</gpx>
"""
    }

    fun decode(text: String): Result<List<GeoPoint>> = runCatching {
        require(text.length <= 20_000_000) { "GPX file is too large" }
        val trackPoint = Regex(
            "<trkpt\\b[^>]*\\blat\\s*=\\s*[\"']([^\"']+)[\"'][^>]*\\blon\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</trkpt>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val selfClosing = Regex(
            "<trkpt\\b[^>]*\\blat\\s*=\\s*[\"']([^\"']+)[\"'][^>]*\\blon\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/>",
            RegexOption.IGNORE_CASE,
        )
        val elevation = Regex("<ele>([^<]+)</ele>", RegexOption.IGNORE_CASE)
        val points = buildList {
            trackPoint.findAll(text).forEach { match ->
                val p = GeoPoint(
                    match.groupValues[1].toDouble(),
                    match.groupValues[2].toDouble(),
                    elevation.find(match.groupValues[3])?.groupValues?.get(1)?.toDoubleOrNull(),
                )
                require(p.isValid) { "GPX contains invalid coordinates" }
                add(p)
            }
            selfClosing.findAll(text).forEach { match ->
                val p = GeoPoint(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
                require(p.isValid) { "GPX contains invalid coordinates" }
                add(p)
            }
        }
        require(points.isNotEmpty()) { "No GPX track points found" }
        points
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
