package org.osmutah.utahbusstop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val here = LatLon(40.7608, -111.8910)

    @Test fun bearingPointsTowardTheStop() {
        assertEquals("north", directionWords(bearingDegrees(here, LatLon(40.77, -111.8910))))
        assertEquals("east", directionWords(bearingDegrees(here, LatLon(40.7608, -111.88))))
        assertEquals("south-west", directionWords(bearingDegrees(here, LatLon(40.75, -111.90))))
    }

    @Test fun distanceIsRoughlyRightAndShownInUsUnits() {
        val oneBlockNorth = LatLon(40.7608 + 0.0009, -111.8910)
        assertEquals(100.0, distanceMeters(here, oneBlockNorth), 5.0)
        assertEquals("330 ft", friendlyDistance(100.0))
        assertEquals("0.5 mi", friendlyDistance(800.0))
        assertEquals("12 mi", friendlyDistance(19_500.0))
    }

    @Test fun searchAreaContainsItsCenter() {
        val (west, south, east, north) = boundsAround(here, 2000.0).toList()
        assertTrue(west < here.lon && here.lon < east && south < here.lat && here.lat < north)
    }

    @Test fun readsGeoJsonPoints() {
        val point = Json.parseToJsonElement("""{"type":"Point","coordinates":[-111.77,40.57]}""").jsonObject
        assertEquals(LatLon(40.57, -111.77), pointOf(point))
        val marker = Json.parseToJsonElement("""{"lat":40.57,"lng":-111.77}""").jsonObject
        assertEquals(LatLon(40.57, -111.77), pointOf(marker))
        assertNull(pointOf(null))
    }
}
