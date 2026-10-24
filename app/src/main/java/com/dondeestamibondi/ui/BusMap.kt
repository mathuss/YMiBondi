package com.dondeestamibondi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dondeestamibondi.data.BusPosition
import com.dondeestamibondi.data.Parada
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** AMBA-ish default camera (Obelisco). */
private val DEFAULT_CENTER = GeoPoint(-34.6037, -58.3816)

/**
 * Mutes the stock OpenStreetMap tiles so the route, buses and stops are the only
 * color on screen. Done on-device with a ColorMatrix: no extra tile provider, no key.
 *
 * Dark:  grayscale, then inverted -> near-black land, light labels, gray road outlines.
 * Light: grayscale, then contrast stretched around white -> white roads on light gray land.
 */
private fun basemapFilter(dark: Boolean): ColorMatrixColorFilter {
    val m = ColorMatrix().apply { setSaturation(0f) }
    if (dark) {
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    } else {
        // L' = 1 + (L - 1) * k keeps white at white and pushes the beige land darker.
        val k = 1.7f
        val o = 255f * (1f - k)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    k, 0f, 0f, 0f, o,
                    0f, k, 0f, 0f, o,
                    0f, 0f, k, 0f, o,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    return ColorMatrixColorFilter(m)
}

/** One recorrido (OSM route variant) of the searched line, drawn in its own color. */
data class RouteLayer(
    val key: String,
    val label: String,
    val description: String,
    val color: Int,
    val paths: List<List<GeoPoint>>,
)

/** A live bus plus the real color of its line, resolved by the caller. */
data class BusPin(val bus: BusPosition, val colorHex: String)

@Composable
fun BusMap(
    buses: List<BusPin>,
    routes: List<RouteLayer>,
    stops: List<Parada>,
    fitKey: String,
    onStopClick: (Parada) -> Unit,
    modifier: Modifier = Modifier,
    trail: List<GeoPoint> = emptyList(),
    onBusClick: (BusPosition) -> Unit = {},
    showMyLocation: Boolean = false,
    centerOnMeRequest: Int = 0,
    onNoFix: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onStopClickState = rememberUpdatedState(onStopClick)
    val stopsState = rememberUpdatedState(stops)
    val onBusClickState = rememberUpdatedState(onBusClick)
    val showMyLocationState = rememberUpdatedState(showMyLocation)
    val onNoFixState = rememberUpdatedState(onNoFix)
    val dark = isSystemInDarkTheme()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(12.0)
            controller.setCenter(DEFAULT_CENTER)
            isTilesScaledToDpi = true
        }
    }

    val copyright = remember { CopyrightOverlay(context) }
    val tileFilter = remember(dark) { basemapFilter(dark) }
    val busIcons = remember { BusMarkerFactory(context) }

    // The user's own position: a plain blue dot (no direction arrow), drawn on top of
    // everything else (buses, routes, stops) by being added last to map.overlays.
    val myLocationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            setPersonIcon(myLocationDotBitmap(context))
            setPersonAnchor(0.5f, 0.5f)
            setDirectionIcon(myLocationDotBitmap(context))
            setDirectionAnchor(0.5f, 0.5f)
            setDrawAccuracyEnabled(true)
        }
    }

    // Taps that no marker consumed: open the nearest stop within a finger's reach.
    // Stop dots are only 8 dp, so requiring an exact hit made them hard to open.
    val tapOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val nearest = nearestStop(mapView, p, stopsState.value, 28f * context.resources.displayMetrics.density)
                    ?: return false
                onStopClickState.value(nearest)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
    }

    // Shared tiny drawable for all stop dots (cheap to reuse across hundreds).
    val stopIcon = remember(dark) { stopMarkerDrawable(context, dark) }

    // Rebuild route/stop overlays only when their inputs change, not every poll.
    val routeOverlays = remember(routes) {
        routes.flatMap { layer ->
            layer.paths.filter { it.size >= 2 }.map { pts ->
                Polyline(mapView).apply {
                    setPoints(pts)
                    outlinePaint.color = withAlpha(layer.color, 215)
                    outlinePaint.strokeWidth = 7f * context.resources.displayMetrics.density / 2f
                    // Without this, a tap near the line opens OSMDroid's empty info bubble.
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                }
            }
        }
    }
    val stopOverlays = remember(stops, stopIcon) {
        stops.map { p ->
            Marker(mapView).apply {
                position = GeoPoint(p.latitud, p.longitud)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = stopIcon
                title = p.nombre
                infoWindow = null // no OSMDroid bubble; we open our own sheet
                setOnMarkerClickListener { _, _ ->
                    onStopClickState.value(p)
                    true
                }
            }
        }
    }

    // Recent trail of the selected bus: a light line with a dark casing so it reads
    // on top of any route color, in both themes.
    val trailOverlays = remember(trail, dark) {
        if (trail.size < 2) emptyList() else {
            val dp = context.resources.displayMetrics.density
            listOf(
                Polyline(mapView).apply {
                    setPoints(trail)
                    outlinePaint.color = Color.parseColor(if (dark) "#CC000000" else "#CCFFFFFF")
                    outlinePaint.strokeWidth = 7f * dp
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                },
                Polyline(mapView).apply {
                    setPoints(trail)
                    outlinePaint.color = Color.parseColor(if (dark) "#FFFFFF" else "#212121")
                    outlinePaint.strokeWidth = 3.5f * dp
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * dp, 5f * dp), 0f)
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                },
            )
        }
    }

    val lastFit = remember { arrayOf("") }
    val lastTrail = remember { arrayOf<List<GeoPoint>?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (showMyLocationState.value) runCatching { myLocationOverlay.enableMyLocation() }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                    myLocationOverlay.disableMyLocation()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            myLocationOverlay.disableMyLocation()
            mapView.onDetach()
        }
    }

    // Turning the permission on/off (or the caller's showMyLocation flag) while the
    // map is already on screen should start/stop the GPS fix without a resume cycle.
    LaunchedEffect(showMyLocation) {
        if (showMyLocation) {
            runCatching { myLocationOverlay.enableMyLocation() }
        } else {
            myLocationOverlay.disableMyLocation()
        }
    }

    // Each tap on the "center on me" button bumps centerOnMeRequest; animate to the
    // last known fix, or wait for the first one if there isn't one yet.
    LaunchedEffect(centerOnMeRequest) {
        if (centerOnMeRequest == 0 || !showMyLocation) return@LaunchedEffect
        val loc = myLocationOverlay.myLocation
        if (loc != null) {
            mapView.controller.setZoom(16.0)
            mapView.controller.animateTo(loc)
        } else {
            onNoFixState.value()
            myLocationOverlay.runOnFirstFix {
                mapView.post {
                    val fix = myLocationOverlay.myLocation ?: return@post
                    mapView.controller.setZoom(16.0)
                    mapView.controller.animateTo(fix)
                }
            }
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.clipToBounds(),
        update = { map ->
            // Follow the system theme live.
            map.overlayManager.tilesOverlay.apply {
                setColorFilter(tileFilter)
                setLoadingBackgroundColor(Color.parseColor(if (dark) "#0E0E0E" else "#E6E6E6"))
                setLoadingLineColor(Color.parseColor(if (dark) "#1C1C1C" else "#D6D6D6"))
            }
            copyright.setTextColor(Color.parseColor(if (dark) "#9E9E9E" else "#616161"))

            map.overlays.clear()
            // Draw order: tap catcher (bottom) -> routes -> stops -> buses -> attribution.
            map.overlays.add(tapOverlay)
            map.overlays.addAll(routeOverlays)
            map.overlays.addAll(stopOverlays)
            map.overlays.addAll(trailOverlays)

            val busPoints = ArrayList<GeoPoint>(buses.size)
            for ((p, colorHex) in buses) {
                if (p.latitude == 0.0 && p.longitude == 0.0) continue
                val gp = GeoPoint(p.latitude, p.longitude)
                busPoints.add(gp)
                val marker = Marker(map).apply {
                    position = gp
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = busIcons.drawable(p, colorHex)
                    infoWindow = null // our own sheet instead of OSMDroid's bubble
                    setOnMarkerClickListener { _, _ ->
                        onBusClickState.value(p)
                        true
                    }
                }
                map.overlays.add(marker)
            }
            map.overlays.add(copyright)
            // Added last so the user's own position draws above routes, stops and buses.
            if (showMyLocation) map.overlays.add(myLocationOverlay)

            // Zoom to a freshly loaded trail once; don't fight the user afterwards.
            if (trail.size >= 2 && lastTrail[0] !== trail) {
                lastTrail[0] = trail
                val box = BoundingBox.fromGeoPointsSafe(trail)
                map.post { runCatching { map.zoomToBoundingBox(box.increaseByScale(1.4f), true) } }
            } else if (trail.isEmpty()) {
                lastTrail[0] = null
            }

            if (lastFit[0] != fitKey) {
                val fitPoints = ArrayList<GeoPoint>()
                routes.forEach { layer -> layer.paths.forEach { fitPoints.addAll(it) } }
                if (fitPoints.isEmpty()) fitPoints.addAll(busPoints)
                // Lines without live buses or route geometry (e.g. 723) still have stops.
                if (fitPoints.isEmpty()) stops.forEach { fitPoints.add(GeoPoint(it.latitud, it.longitud)) }
                if (fitPoints.isNotEmpty()) {
                    lastFit[0] = fitKey
                    if (fitPoints.size == 1) {
                        map.controller.setZoom(15.0)
                        map.controller.setCenter(fitPoints[0])
                    } else {
                        val box = BoundingBox.fromGeoPointsSafe(fitPoints)
                        map.post {
                            runCatching { map.zoomToBoundingBox(box.increaseByScale(1.2f), false) }
                        }
                    }
                }
            }
            map.invalidate()
        },
    )
}

private fun nearestStop(map: MapView, tap: GeoPoint, stops: List<Parada>, maxPx: Float): Parada? {
    if (stops.isEmpty()) return null
    val proj = map.projection
    val t = proj.toPixels(tap, null)
    val pt = android.graphics.Point()
    var best: Parada? = null
    var bestD2 = maxPx * maxPx
    for (s in stops) {
        proj.toPixels(GeoPoint(s.latitud, s.longitud), pt)
        val dx = (pt.x - t.x).toFloat()
        val dy = (pt.y - t.y).toFloat()
        val d2 = dx * dx + dy * dy
        if (d2 <= bestD2) {
            bestD2 = d2
            best = s
        }
    }
    return best
}

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** The classic "blue dot": solid blue circle with a white ring, legible on both basemaps. */
private fun myLocationDotBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (18 * density).toInt()
    val stroke = 3f * density
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val center = size / 2f
    val outerRadius = center - stroke / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE
    c.drawCircle(center, center, outerRadius + stroke / 2f, p)
    p.color = Color.parseColor("#1A73E8")
    c.drawCircle(center, center, outerRadius, p)
    return bmp
}

private fun stopMarkerDrawable(context: Context, dark: Boolean): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (9 * density).toInt()
    val stroke = (1.5f * density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(if (dark) "#ECEFF1" else "#37474F"))
        setStroke(stroke, if (dark) Color.parseColor("#121212") else Color.WHITE)
        setSize(size, size)
        setBounds(0, 0, size, size)
    }
}
