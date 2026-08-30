package com.milelog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.CardHigh
import com.milelog.ui.theme.Sky
import com.milelog.ui.theme.TextLow
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * The route line for a trip, drawn on free open map tiles. Tiles need internet;
 * with no connection the line still draws over an empty background.
 */
@Composable
fun MapPreview(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    showEndpoints: Boolean = true
) {
    if (points.isEmpty()) {
        Box(modifier.background(CardHigh), contentAlignment = Alignment.Center) {
            Text("No route recorded", style = MaterialTheme.typography.bodyMedium, color = TextLow)
        }
        return
    }

    val geo = points.map { GeoPoint(it.first, it.second) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                setUseDataConnection(true)
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                )
            }
        },
        update = { map ->
            map.overlays.clear()

            val line = Polyline(map).apply {
                setPoints(geo)
                outlinePaint.color = Blue.toArgb()
                outlinePaint.strokeWidth = 10f
                outlinePaint.isAntiAlias = true
            }
            map.overlays.add(line)

            if (showEndpoints && geo.size > 1) {
                map.overlays.add(dot(map, geo.first(), Sky.toArgb()))
                map.overlays.add(dot(map, geo.last(), Blue.toArgb()))
            }

            val box = BoundingBox.fromGeoPointsSafe(geo)
            map.post {
                runCatching { map.zoomToBoundingBox(box.increaseByScale(1.35f), false) }
            }
            map.invalidate()
        }
    )

    DisposableEffect(Unit) { onDispose { } }
}

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
    Box(modifier.background(CardHigh), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextLow)
    }
}
