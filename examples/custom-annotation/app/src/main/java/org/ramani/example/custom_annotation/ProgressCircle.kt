package org.ramani.example.custom_annotation

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.plus
import org.maplibre.android.geometry.LatLng
import org.ramani.compose.Circle
import org.ramani.compose.Fill
import org.ramani.compose.MapApplier
import org.ramani.compose.MapObserver
import org.ramani.compose.Polyline
import org.ramani.compose.Symbol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ProgressCircle(
    center: LatLng,
    radius: Float,
    progress: ProgressPercent,
    fillColor: String = "Green",
    borderColor: String = "Red",
    borderWidth: Float = 1.0f,
    indicatorTextSize: Float = 25.0f,
    indicatorTextColor: String = "Black",
) {
    val mapApplier = currentComposer.applier as MapApplier
    val proj = mapApplier.map.projection
    val centerLocal = proj.toScreenLocation(center)
    val dpToPixel = with(LocalDensity.current) { 1.dp.toPx() }

    val recomposeState = remember { mutableStateOf(true) }
    MapObserver(onMapScaled = { recomposeState.value = !recomposeState.value })

    Symbol(
        center = proj.fromScreenLocation(
            centerLocal + PointF(
                0.0f,
                -(radius + indicatorTextSize) * dpToPixel
            )
        ),
        color = indicatorTextColor,
        isDraggable = false,
        text = progress.value.toString(),
        size = indicatorTextSize,
    )

    Circle(
        center = center,
        radius = radius,
        color = "Transparent",
        borderColor = borderColor,
        borderWidth = borderWidth
    )

    key(recomposeState.value) {
        if (progress.value == 100) {
            Circle(
                center = center,
                radius = radius,
                color = fillColor,
                borderColor = borderColor,
                borderWidth = borderWidth
            )
        } else {
            val angle = 2 * PI * progress.decimalMultiplier

            val arcPoints = List(100) { index ->
                angle / 100 * index
            }.map {
                val pointLocal = centerLocal + PointF(
                    -radius * sin(it).toFloat() * dpToPixel,
                    -radius * cos(it).toFloat() * dpToPixel
                )
                proj.fromScreenLocation(pointLocal)
            }

            Polyline(
                points = listOf(arcPoints.first(), center, arcPoints.last()),
                color = borderColor,
                lineWidth = borderWidth,
            )

            Fill(
                points = listOf(center) + arcPoints + listOf(center),
                fillColor = fillColor,
            )
        }
    }
}

class ProgressPercent(private val percent: Int) {
    val value: Int
        get() = percent.coerceIn(0, 100)
    val decimalMultiplier: Float
        get() = value.toFloat() / 100.0f
}
