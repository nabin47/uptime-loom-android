package io.github.nabin47.uptimeloom.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.geometry.LatLng as MlLatLng

/** Open map data from OpenFreeMap (OpenStreetMap): no API key or account needed. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private const val SOURCE = "area-circle"

/** A request to move the map (search result, my location, link...). [id] makes repeats count. */
data class CameraRequest(val lat: Double, val lng: Double, val zoom: Double, val id: Long = System.nanoTime())

/**
 * A map you pan under a fixed centre pin. Reports the centre as it moves and
 * draws the area's radius around it.
 */
@Composable
fun MapPicker(
    modifier: Modifier,
    request: CameraRequest?,
    radiusM: Int,
    accent: Color,
    onCenter: (lat: Double, lng: Double, zoom: Double) -> Unit,
    onLoadFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    val currentOnCenter by rememberUpdatedState(onCenter)
    val currentOnFail by rememberUpdatedState(onLoadFailed)
    val currentRadius by rememberUpdatedState(radiusM)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { view ->
            view.onCreate(null)
            view.addOnDidFailLoadingMapListener { currentOnFail() }
            view.getMapAsync { map ->
                holder.map = map
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    style.addSource(GeoJsonSource(SOURCE, circle(map.cameraPosition.target, currentRadius)))
                    style.addLayer(FillLayer("area-fill", SOURCE).withProperties(
                        PropertyFactory.fillColor(accent.toArgb()), PropertyFactory.fillOpacity(0.15f)))
                    style.addLayer(LineLayer("area-line", SOURCE).withProperties(
                        PropertyFactory.lineColor(accent.toArgb()), PropertyFactory.lineWidth(2f)))
                    holder.styleReady = true
                    holder.redraw(currentRadius)
                }
                map.addOnCameraMoveListener { holder.redraw(currentRadius) }
                map.addOnCameraIdleListener {
                    map.cameraPosition.target?.let { currentOnCenter(it.latitude, it.longitude, map.cameraPosition.zoom) }
                }
                holder.pending?.let { holder.move(it, animate = false) }
            }
        }
    }

    // Keep the map in step with the screen's lifecycle.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(request) { request?.let { holder.move(it, animate = holder.map != null) } }
    LaunchedEffect(radiusM) { holder.redraw(radiusM) }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private class MapHolder {
    var map: MapLibreMap? = null
    var styleReady = false
    var pending: CameraRequest? = null

    fun move(r: CameraRequest, animate: Boolean) {
        val m = map ?: run { pending = r; return }
        pending = null
        val update = CameraUpdateFactory.newLatLngZoom(MlLatLng(r.lat, r.lng), r.zoom)
        if (animate) m.animateCamera(update, 600) else m.moveCamera(update)
    }

    fun redraw(radiusM: Int) {
        val m = map ?: return
        if (!styleReady) return
        val target = m.cameraPosition.target ?: return
        m.style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(circle(target, radiusM))
    }
}

/** GeoJSON polygon approximating a circle of [radiusM] metres. */
private fun circle(center: MlLatLng?, radiusM: Int): String {
    val c = center ?: MlLatLng(0.0, 0.0)
    val points = 64
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * cos(Math.toRadians(c.latitude)).coerceAtLeast(0.01))
    val coords = (0..points).joinToString(",") { i ->
        val a = 2 * Math.PI * i / points
        "[${c.longitude + dLng * cos(a)},${c.latitude + dLat * sin(a)}]"
    }
    return """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]},"properties":{}}"""
}

