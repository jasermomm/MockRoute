package com.jasermomm.mockroute.core

data class RealLocationCandidate(
    val provider: String,
    val point: GeoPoint,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val accuracyMeters: Float,
    val isMock: Boolean,
)

object RealLocationSelection {
    fun choose(
        candidates: List<RealLocationCandidate>,
        nowWallTimeMs: Long,
        maxAgeMs: Long = 120_000L,
    ): RealLocationCandidate? = candidates
        .asSequence()
        .filter { it.point.isValid && !it.isMock }
        .filter { it.wallTimeMs in (nowWallTimeMs - maxAgeMs)..(nowWallTimeMs + 10_000L) }
        .sortedWith(
            compareBy<RealLocationCandidate> { providerRank(it.provider) }
                .thenBy { it.accuracyMeters }
                .thenByDescending { it.wallTimeMs },
        )
        .firstOrNull()

    private fun providerRank(provider: String): Int = when (provider.lowercase()) {
        "fused" -> 0
        "network" -> 1
        "gps" -> 2
        else -> 3
    }
}
