/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.ramani.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMap.OnMoveListener
import com.mapbox.mapboxsdk.maps.MapboxMap.OnScaleListener
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.plugins.annotation.AnnotationManager
import com.mapbox.mapboxsdk.plugins.annotation.Circle
import com.mapbox.mapboxsdk.plugins.annotation.CircleManager
import com.mapbox.mapboxsdk.plugins.annotation.Fill
import com.mapbox.mapboxsdk.plugins.annotation.FillManager
import com.mapbox.mapboxsdk.plugins.annotation.Line
import com.mapbox.mapboxsdk.plugins.annotation.LineManager
import com.mapbox.mapboxsdk.plugins.annotation.OnCircleDragListener
import com.mapbox.mapboxsdk.plugins.annotation.Symbol
import com.mapbox.mapboxsdk.plugins.annotation.SymbolManager
import kotlinx.coroutines.awaitCancellation
import java.lang.Math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal suspend inline fun disposingComposition(factory: () -> Composition) {
    val composition = factory()
    try {
        awaitCancellation()
    } finally {
        composition.dispose()
    }
}

internal suspend fun MapView.newComposition(
    parent: CompositionContext,
    style: Style,
    content: @Composable () -> Unit,
): Composition {
    val map = awaitMap()
    return Composition(
        MapApplier(map, this, style), parent
    ).apply {
        setContent(content)
    }
}

internal suspend fun MapboxMap.awaitStyle(apiKey: String) = suspendCoroutine { continuation ->
    Helper.validateKey(apiKey)
    val styleUrl = "https://api.maptiler.com/maps/satellite/style.json?key=$apiKey";
    setStyle(styleUrl) { style ->
        continuation.resume(style)
    }
}

internal interface MapNode {
    fun onAttached() {}
    fun onRemoved() {}
    fun onCleared() {}
}

private object MapNodeRoot : MapNode

internal class MapApplier(
    val map: MapboxMap,
    val mapView: MapView,
    val style: Style
) : AbstractApplier<MapNode>(MapNodeRoot) {
    private val decorations = mutableListOf<MapNode>()

    private val circleManagerMap = mutableMapOf<Int, CircleManager>()
    private val fillManagerMap = mutableMapOf<Int, FillManager>()
    private val symbolManagerMap = mutableMapOf<Int, SymbolManager>()
    private val lineManagerMap = mutableMapOf<Int, LineManager>()

    private val zIndexReferenceAnnotationManagerMap =
        mutableMapOf<Int, AnnotationManager<*, *, *, *, *, *>>()


    init {
        attachMapListeners()
    }

    private fun attachMapListeners() {

        map.addOnScaleListener(object : OnScaleListener {
            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                decorations
                    .filterIsInstance<MapObserverNode>()
                    .forEach { it.onMapScaled.invoke() }
            }

            override fun onScale(detector: StandardScaleGestureDetector) {
                decorations
                    .filterIsInstance<MapObserverNode>()
                    .forEach { it.onMapScaled.invoke() }
            }

            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
            }

        })


        map.addOnMoveListener(
            object : OnMoveListener {

                override fun onMoveBegin(detector: MoveGestureDetector) {
                    decorations.forEach {
                        if (it is MapObserverNode) {
                            it.onMapMoved.invoke()
                        }
                    }
                }

                override fun onMove(detector: MoveGestureDetector) {
                    decorations.forEach {
                        if (it is MapObserverNode) {
                            it.onMapMoved.invoke()
                        }
                    }
                }

                override fun onMoveEnd(detector: MoveGestureDetector) {
                }

            })
    }

    fun getOrCreateCircleManagerForZIndex(zIndex: Int): CircleManager {

        circleManagerMap[zIndex]?.let { return it }

        val layerInsertInfo = getLayerInsertInfoForZIndex(zIndex)

        val circleManager = layerInsertInfo?.let {
            CircleManager(
                mapView,
                map,
                style,
                if (it.insertPosition == LayerInsertMethod.INSERT_BELOW) it.referenceLayerId else null,
                if (it.insertPosition == LayerInsertMethod.INSERT_ABOVE) it.referenceLayerId else null
            )
        } ?: run {
            CircleManager(mapView, map, style)
        }

        circleManagerMap.put(zIndex, circleManager)

        if (!zIndexReferenceAnnotationManagerMap.containsKey(zIndex)) {
            zIndexReferenceAnnotationManagerMap[zIndex] = circleManager
        }

        circleManager.addDragListener(object : OnCircleDragListener {
            override fun onAnnotationDragStarted(annotation: Circle?) {
                decorations.findInputCallback<CircleNode, Circle, Unit>(
                    nodeMatchPredicate = { it.circle.id == annotation?.id && it.circleManager.layerId == circleManager.layerId },
                    nodeInputCallback = { onCircleDragged }
                )?.invoke(annotation!!)
            }

            override fun onAnnotationDrag(annotation: Circle?) {
                decorations.findInputCallback<CircleNode, Circle, Unit>(
                    nodeMatchPredicate = { it.circle.id == annotation?.id && it.circleManager.layerId == circleManager.layerId },
                    nodeInputCallback = { onCircleDragged }
                )?.invoke(annotation!!)
            }

            override fun onAnnotationDragFinished(annotation: Circle?) {
                decorations.findInputCallback<CircleNode, Circle, Unit>(
                    nodeMatchPredicate = { it.circle.id == annotation?.id && it.circleManager.layerId == circleManager.layerId },
                    nodeInputCallback = { onCircleDragStopped }
                )?.invoke(annotation!!)
            }
        })


        return circleManagerMap[zIndex]!!
    }

    private fun getLayerInsertInfoForZIndex(zIndex: Int): LayerInsertInfo? {
        val keys = zIndexReferenceAnnotationManagerMap.keys.sorted()

        if (keys.isEmpty()) {
            return null
        }

        val closestLayerIndex = keys.map {
            abs(it - zIndex)
        }.withIndex().minBy { it.value }.index

        return LayerInsertInfo(
            zIndexReferenceAnnotationManagerMap[keys[closestLayerIndex]]?.getLayerId()!!,
            if (zIndex > keys[closestLayerIndex]) LayerInsertMethod.INSERT_ABOVE else LayerInsertMethod.INSERT_BELOW
        )

    }

    fun getOrCreateSymbolManagerForZIndex(zIndex: Int): SymbolManager {

        symbolManagerMap[zIndex]?.let { return it }
        val layerInsertInfo = getLayerInsertInfoForZIndex(zIndex)

        val symbolManager = layerInsertInfo?.let {
            SymbolManager(
                mapView,
                map,
                style,
                if (it.insertPosition == LayerInsertMethod.INSERT_BELOW) it.referenceLayerId else null,
                if (it.insertPosition == LayerInsertMethod.INSERT_ABOVE) it.referenceLayerId else null,
                null
            )
        } ?: run {
            SymbolManager(mapView, map, style)
        }

        if (!zIndexReferenceAnnotationManagerMap.containsKey(zIndex)) {
            zIndexReferenceAnnotationManagerMap[zIndex] = symbolManager
        }
        symbolManagerMap.put(zIndex, symbolManager)
        return symbolManager
    }

    fun getOrCreateFillManagerForZIndex(zIndex: Int): FillManager {
        fillManagerMap[zIndex]?.let { return it }
        val layerInsertInfo = getLayerInsertInfoForZIndex(zIndex)
        val fillManager = layerInsertInfo?.let {
            FillManager(
                mapView,
                map,
                style,
                if (it.insertPosition == LayerInsertMethod.INSERT_BELOW) it.referenceLayerId else null,
                if (it.insertPosition == LayerInsertMethod.INSERT_ABOVE) it.referenceLayerId else null,
                null
            )
        } ?: run {
            FillManager(mapView, map, style)
        }

        if (!zIndexReferenceAnnotationManagerMap.containsKey(zIndex)) {
            zIndexReferenceAnnotationManagerMap[zIndex] = fillManager
        }
        fillManagerMap.put(zIndex, fillManager)
        return fillManager
    }

    fun getOrCreateLineManagerForZIndex(zIndex: Int): LineManager {
        lineManagerMap[zIndex]?.let { return it }
        val layerInsertInfo = getLayerInsertInfoForZIndex(zIndex)
        val lineManager = layerInsertInfo?.let {
            LineManager(
                mapView,
                map,
                style,
                if (it.insertPosition == LayerInsertMethod.INSERT_BELOW) it.referenceLayerId else null,
                if (it.insertPosition == LayerInsertMethod.INSERT_ABOVE) it.referenceLayerId else null,
                null
            )
        } ?: run {
            LineManager(mapView, map, style)
        }

        if (!zIndexReferenceAnnotationManagerMap.containsKey(zIndex)) {
            zIndexReferenceAnnotationManagerMap[zIndex] = lineManager
        }
        lineManagerMap.put(zIndex, lineManager)
        return lineManager
    }

    override fun insertBottomUp(index: Int, instance: MapNode) {
        // TODO: implement properly
    }

    override fun insertTopDown(index: Int, instance: MapNode) {
        decorations.add(index, instance)
        instance.onAttached()
    }

    override fun move(from: Int, to: Int, count: Int) {
    }

    override fun onClear() {
        decorations.forEach { it.onCleared() }
        decorations.clear()
    }

    override fun remove(index: Int, count: Int) {
        val toRemove = decorations.subList(index, index + count)
        toRemove.forEach { it.onRemoved() }
        toRemove.clear()
    }
}

data class LayerInsertInfo(val referenceLayerId: String, val insertPosition: LayerInsertMethod)

enum class LayerInsertMethod {
    INSERT_BELOW,
    INSERT_ABOVE
}

internal class CircleNode(
    val circleManager: CircleManager,
    val circle: Circle,
    var onCircleDragged: (Circle) -> Unit,
    var onCircleDragStopped: (Circle) -> Unit
) : MapNode {
    override fun onRemoved() {
        circleManager.delete(circle)
    }

    override fun onCleared() {
        circleManager.delete(circle)
    }
}

internal class SymbolNode(
    val symbolManager: SymbolManager,
    val symbol: Symbol,
) : MapNode {
    override fun onRemoved() {
        symbolManager.delete(symbol)
    }

    override fun onCleared() {
        symbolManager.delete(symbol)
    }
}

internal class PolyLineNode(
    val lineManager: LineManager,
    val polyLine: Line,
) : MapNode {
    override fun onRemoved() {
        lineManager.delete(polyLine)
    }

    override fun onCleared() {
        lineManager.delete(polyLine)
    }
}

internal class FillNode(
    val fillManager: FillManager,
    val fill: Fill,
) : MapNode {
    override fun onRemoved() {
        fillManager.delete(fill)
    }

    override fun onCleared() {
        fillManager.delete(fill)
    }
}

internal class MapObserverNode(
    var onMapMoved: () -> Unit,
    var onMapScaled: () -> Unit,
) : MapNode {
    override fun onRemoved() {
    }
}

private inline fun <reified NodeT : MapNode, I, O> Iterable<MapNode>.findInputCallback(
    nodeMatchPredicate: (NodeT) -> Boolean,
    nodeInputCallback: NodeT.() -> ((I) -> O)?,
): ((I) -> O)? {
    val callback: ((I) -> O)? = null
    for (item in this) {
        if (item is NodeT && nodeMatchPredicate(item)) {
            // Found a matching node
            return nodeInputCallback(item)
        }
    }
    return callback
}

