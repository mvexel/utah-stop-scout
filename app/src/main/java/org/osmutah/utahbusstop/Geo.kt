package org.osmutah.utahbusstop

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A place on Earth in degrees. */
data class LatLon(val lat: Double, val lon: Double)

/** A bus stop with a location, ready to show in a list or on a map. */
data class NearbyStop(
    val taskId: Long, val challengeId: Long, val title: String,
    val position: LatLon, val distanceMeters: Double, val bearingDegrees: Double,
)

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val METERS_PER_FOOT = 0.3048
private const val METERS_PER_MILE = 1609.344

fun distanceMeters(from: LatLon, to: LatLon): Double {
    val dLat = Math.toRadians(to.lat - from.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
}

/** Compass bearing from one place to another: 0 is north, 90 is east. */
fun bearingDegrees(from: LatLon, to: LatLon): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

private val DIRECTION_WORDS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

fun directionWords(bearing: Double): String = DIRECTION_WORDS[(((bearing % 360 + 360) % 360) / 45).roundToInt() % 8]

/** US-friendly distance: feet up to a tenth of a mile, then miles. */
fun friendlyDistance(meters: Double): String {
    val feet = meters / METERS_PER_FOOT
    if (feet < 50) return "${max(feet.roundToInt(), 1)} ft"
    if (feet < 528) return "${(feet / 10).roundToInt() * 10} ft"
    val miles = meters / METERS_PER_MILE
    return if (miles < 10) "%.1f mi".format(miles) else "${miles.roundToInt()} mi"
}

/** A square search area around a place, roughly [radiusMeters] from the center to each edge. */
fun boundsAround(center: LatLon, radiusMeters: Double): DoubleArray {
    val dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS)
    val dLon = dLat / max(cos(Math.toRadians(center.lat)), 0.01)
    return doubleArrayOf(
        max(center.lon - dLon, -180.0), max(center.lat - dLat, -90.0),
        min(center.lon + dLon, 180.0), min(center.lat + dLat, 90.0),
    )
}

/** Reads a GeoJSON `{"coordinates":[lon,lat]}` point or a MapRoulette `{"lat":..,"lng":..}` point. */
fun pointOf(json: JsonObject?): LatLon? {
    if (json == null) return null
    val coordinates = json["coordinates"] as? JsonArray
    if (coordinates != null) {
        val lon = (coordinates.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (coordinates.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        return LatLon(lat, lon)
    }
    val lat = (json["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val lon = ((json["lng"] ?: json["lon"]) as? JsonPrimitive)?.doubleOrNull ?: return null
    return LatLon(lat, lon)
}
