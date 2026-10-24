package com.dondeestamibondi.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Great-circle distance in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * (2 * atan2(sqrt(a), sqrt(1 - a)))
    }

    /**
     * Distance in meters from (lat, lon) to the segment (lat1, lon1)-(lat2, lon2).
     * Uses a local equirectangular projection around the segment's first point, which is
     * accurate enough at city scale (a few km) and much cheaper than great-circle math
     * for the many point-to-segment checks a route match needs.
     */
    fun distanceToSegmentMeters(lat: Double, lon: Double, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(lat1))
        val x0 = (lon - lon1) * metersPerDegLon
        val y0 = (lat - lat1) * metersPerDegLat
        val x2 = (lon2 - lon1) * metersPerDegLon
        val y2 = (lat2 - lat1) * metersPerDegLat
        val lenSq = x2 * x2 + y2 * y2
        val t = if (lenSq > 0) ((x0 * x2 + y0 * y2) / lenSq).coerceIn(0.0, 1.0) else 0.0
        val dx = x0 - t * x2
        val dy = y0 - t * y2
        return sqrt(dx * dx + dy * dy)
    }
}
