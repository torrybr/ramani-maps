package org.maplibre.compose

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.graphics.minus
import androidx.core.graphics.plus
import com.mapbox.mapboxsdk.geometry.LatLng

@Composable
private fun VerticeDragger(
    draggedCenter: LatLng,
    vertices: MutableList<LatLng>,
    onCenterAndVerticesChanged: (LatLng, MutableList<LatLng>) -> Unit
) {

    if (vertices.size <= 0) {
        return
    }

    var mapApplier = currentComposer.applier as? MapApplier
    val projection = mapApplier?.map!!.projection

    var currentCenter = PointF()

    for (coord in vertices) {
        currentCenter += projection.toScreenLocation(coord)
    }

    currentCenter.x = currentCenter.x / vertices.size
    currentCenter.y = currentCenter.y / vertices.size

    val newCenter = projection.toScreenLocation(draggedCenter)
    val draggedPixels: PointF = newCenter - currentCenter

    val draggedVertices = vertices.map { latlng ->
        projection.fromScreenLocation(projection.toScreenLocation(latlng) + draggedPixels)
    }.toMutableList()

    onCenterAndVerticesChanged(projection.fromScreenLocation(currentCenter), draggedVertices)
}

@Composable
private fun PolygonDragHandle(
    vertices: MutableList<LatLng>,
    onCenterChanged: (LatLng) -> Unit = {},
    onVerticesChanged: (MutableList<LatLng>) -> Unit = {}
) {

    val polygonDragHandleCoord = remember {
        mutableStateOf(LatLng())
    }

    val inputDragCoord = remember {
        mutableStateOf(LatLng())
    }

    val dragActive = remember {
        mutableStateOf(false)
    }

    VerticeDragger(
        draggedCenter = inputDragCoord.value,
        vertices = vertices,
        onCenterAndVerticesChanged = { center, vertices ->
            polygonDragHandleCoord.value = center
            if (dragActive.value) {
                onVerticesChanged(vertices)
            }
            onCenterChanged(center)
        })

    Circle(
        center = polygonDragHandleCoord.value,
        radius = 30.0f,
        draggable = true,
        color = "Transparent",
        onCenterDragged = {
            dragActive.value = true
            inputDragCoord.value = it
        },
        onDragFinished = {
            dragActive.value = false
        })
}

@MapLibreComposable
@Composable
fun Polygon(
    vertices: MutableList<MutableList<LatLng>>,
    fillColor: String = "Transparent",
    opacity: Float = 1.0f,
    draggable: Boolean = false,
    onVerticesChanged: (MutableList<MutableList<LatLng>>) -> Unit,
) {

    Fill(
        points = vertices,
        fillColor = fillColor,
        opacity = opacity,
        draggable = false,
        onVericesChanged = onVerticesChanged
    )
    if (draggable) {
        PolygonDragHandle(
            vertices = vertices.first(),
            onVerticesChanged = {
                onVerticesChanged(mutableListOf(it))
            })
    }
}