package com.milelog.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.CardHigh
import com.milelog.ui.theme.Sky
import com.milelog.ui.theme.TextMid
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The route line for a trip, drawn on free open map tiles. Tiles need internet;
 * with no connection the line still draws over an empty background.
 *
 * The map is boxed and clipped to exactly the space it is given. Without explicit
 * layout parameters an osmdroid MapView inside an AndroidView sizes itself to its own
 * content, which made some cards render a postage-stamp world map and others a map
 * taller than the card it sat in.
 */
@Composable
fun MapPreview(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    showEndpoints: Boolean = true
) {
    if (points.isEmpty()) {
        MapPlaceholder(modifier, "No route recorded")
        return
    }

    val geo = remember(points) { points.map { GeoPoint(it.first, it.second) } }

    Box(modifier.clipToBounds().background(CardHigh)) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // Fill the box we were given, rather than measuring ourselves.
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(interactive)
                    setUseDataConnection(true)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                }
            },
            onRelease = { map -> runCatching { map.onDetach() } },
            update = { map ->
                // Rebuilt only when the route itself changes, so scrolling the list does
                // not re-run every overlay and zoom.
                if (map.tag == geo) return@AndroidView
                map.tag = geo
                map.overlays.clear()

                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(geo)
                        outlinePaint.color = Blue.toArgb()
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.isAntiAlias = true
                    }
                )
                if (showEndpoints && geo.size > 1) {
                    map.overlays.add(dot(map, geo.first(), Sky.toArgb()))
                    map.overlays.add(dot(map, geo.last(), Blue.toArgb()))
                }
                frameRoute(map, geo)
            }
        )
    }
}

/**
 * Frames the route once the view actually has a size. Asking osmdroid to fit a bounding
 * box before layout throws, and the failure used to be swallowed — leaving the map
 * sitting at its default view of the whole world.
 */
private fun frameRoute(map: MapView, geo: List<GeoPoint>, attempt: Int = 0) {
    if (attempt > MAX_FRAME_ATTEMPTS) return
    if (map.width == 0 || map.height == 0) {
        map.postDelayed({ frameRoute(map, geo, attempt + 1) }, FRAME_RETRY_MS)
        return
    }

    val box = BoundingBox.fromGeoPointsSafe(geo)
    val stoodStill = box.latitudeSpan < DEGENERATE_SPAN &&
        box.longitudeSpanWithDateLine < DEGENERATE_SPAN

    if (stoodStill) {
        // A trip that barely moved has no box to fit; drop to street level on the point.
        map.controller.setZoom(16.0)
        map.controller.setCenter(geo.first())
    } else {
        runCatching { map.zoomToBoundingBox(box.increaseByScale(1.25f), false, EDGE_PADDING_PX) }
            .onFailure { map.postDelayed({ frameRoute(map, geo, attempt + 1) }, FRAME_RETRY_MS) }
    }
    map.invalidate()
}

private const val MAX_FRAME_ATTEMPTS = 12
private const val FRAME_RETRY_MS = 60L
private const val DEGENERATE_SPAN = 0.0005
private const val EDGE_PADDING_PX = 24

private fun dot(map: MapView, point: GeoPoint, color: Int): Marker =
    Marker(map).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(4, android.graphics.Color.WHITE)
            setSize(28, 28)
        }
        setInfoWindow(null)
    }

@Composable
fun MapPlaceholder(modifier: Modifier = Modifier, label: String = "Map appears once you drive") {
    Box(modifier.clipToBounds().background(CardHigh), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMid)
    }
}
