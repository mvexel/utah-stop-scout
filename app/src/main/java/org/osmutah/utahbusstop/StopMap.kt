package org.osmutah.utahbusstop

import android.content.Context
import android.graphics.RectF
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** OpenFreeMap's light "Positron" basemap, tinted toward the app's green, with the nearby stops drawn on top. */
internal class StopMap(
    private val context: Context,
    mapView: MapView,
    private val you: LatLon,
    private val onSelect: (Long) -> Unit,
) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var stops: List<NearbyStop> = emptyList()
    private var selectedId: Long? = null
    private var framed = false

    init {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setMaxZoomPreference(18.0)
            m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(you.lat, you.lon)).zoom(15.0).build()))
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                style = loaded
                tint(loaded)
                install(loaded)
                redraw()
                frame()
            }
            m.addOnMapClickListener { latLng ->
                val center = m.projection.toScreenLocation(latLng)
                val slop = context.dpf(28f)
                val hit = m.queryRenderedFeatures(RectF(center.x - slop, center.y - slop, center.x + slop, center.y + slop), STOPS_LAYER).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toLong()
                if (id != null) onSelect(id)
                id != null
            }
        }
    }

    fun update(newStops: List<NearbyStop>, selected: Long?) {
        stops = newStops
        selectedId = selected
        redraw()
        frame()
    }

    fun select(id: Long?) {
        selectedId = id
        redraw()
        val stop = stops.firstOrNull { it.taskId == id } ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(stop.position.lat, stop.position.lon)))
    }

    /** Shows you and the closest few stops, once, when the stops first arrive. */
    private fun frame() {
        val m = map ?: return
        if (framed || stops.isEmpty() || style == null) return
        framed = true
        val box = LatLngBounds.Builder().include(LatLng(you.lat, you.lon))
        stops.take(4).forEach { box.include(LatLng(it.position.lat, it.position.lon)) }
        val d = context.dp(1)
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(box.build(), 60 * d, 250 * d, 60 * d, 230 * d))
    }

    private fun tint(style: Style) {
        style.getLayerAs<BackgroundLayer>("background")?.setProperties(PropertyFactory.backgroundColor(0xFFEFF3EC.toInt()))
        mapOf(
            "park" to 0xFFDDEBDD, "landcover_wood" to 0xFFDDEBDD,
            "water" to 0xFFD3E4E1, "landuse_residential" to 0xFFEAF0E7,
        ).forEach { (id, color) -> style.getLayerAs<FillLayer>(id)?.setProperties(PropertyFactory.fillColor(color.toInt())) }
    }

    private fun install(style: Style) {
        style.addImage("stop", stopMarkerBitmap(context, 0xFFC98A0B.toInt(), 38))
        style.addImage("stop-selected", stopMarkerBitmap(context, Palette.GREEN, 54))
        style.addSource(GeoJsonSource(YOU_SOURCE, Point.fromLngLat(you.lon, you.lat)))
        style.addLayer(CircleLayer("you-halo", YOU_SOURCE).withProperties(
            PropertyFactory.circleRadius(18f), PropertyFactory.circleColor(0xFF2F6BD8.toInt()), PropertyFactory.circleOpacity(0.2f),
        ))
        style.addLayer(CircleLayer("you-dot", YOU_SOURCE).withProperties(
            PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(0xFF2F6BD8.toInt()),
            PropertyFactory.circleStrokeColor(Palette.WHITE), PropertyFactory.circleStrokeWidth(3f),
        ))
        style.addSource(GeoJsonSource(STOPS_SOURCE, FeatureCollection.fromFeatures(emptyList())))
        val isSelected = Expression.eq(Expression.get("selected"), Expression.literal(true))
        style.addLayer(SymbolLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.switchCase(isSelected, Expression.literal("stop-selected"), Expression.literal("stop"))),
            PropertyFactory.iconAnchor("bottom"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.switchCase(isSelected, Expression.literal(1.0), Expression.literal(0.0))),
        ))
    }

    private fun redraw() {
        val source = style?.getSourceAs<GeoJsonSource>(STOPS_SOURCE) ?: return
        val features = stops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.position.lon, stop.position.lat)).also {
                it.addNumberProperty("id", stop.taskId)
                it.addBooleanProperty("selected", stop.taskId == selectedId)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val STOPS_SOURCE = "stops-src"
        const val STOPS_LAYER = "stops-layer"
        const val YOU_SOURCE = "you-src"
    }
}
