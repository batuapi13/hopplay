package com.hopplay.ui.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hopplay.player.DragAxis
import com.hopplay.player.resolveDragAxis

/** Callbacks only; no playback logic lives here. */
class GestureActions(
    val onTap: () -> Unit,
    val onDoubleTap: (left: Boolean) -> Unit,
    val onSeekStart: () -> Unit,
    val onSeekDrag: (totalDx: Float, width: Float) -> Unit,
    val onSeekEnd: () -> Unit,
    val onLevelStart: (DragAxis) -> Unit,
    val onLevelDrag: (DragAxis, totalDy: Float, height: Float) -> Unit,
    val onLevelEnd: () -> Unit,
    val onLongPress: (Boolean) -> Unit,
)

@Composable
fun GestureLayer(actions: GestureActions, modifier: Modifier = Modifier) {
    val a by rememberUpdatedState(actions)
    val threshold = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { a.onTap() },
                    onDoubleTap = { o -> a.onDoubleTap(o.x < this.size.width / 2f) },
                    onLongPress = { a.onLongPress(true) },
                    onPress = {
                        tryAwaitRelease()
                        a.onLongPress(false)
                    },
                )
            }
            .pointerInput(Unit) {
                var accX = 0f
                var accY = 0f
                var axis = DragAxis.NONE
                var left = true
                detectDragGestures(
                    onDragStart = { o: Offset ->
                        accX = 0f; accY = 0f; axis = DragAxis.NONE
                        left = o.x < this.size.width / 2f
                    },
                    onDragEnd = { finish(axis, a); axis = DragAxis.NONE },
                    onDragCancel = { finish(axis, a); axis = DragAxis.NONE },
                ) { change, drag ->
                    change.consume()
                    accX += drag.x
                    accY += drag.y
                    if (axis == DragAxis.NONE) {
                        axis = resolveDragAxis(accX, accY, left, threshold)
                        when (axis) {
                            DragAxis.SEEK -> a.onSeekStart()
                            DragAxis.NONE -> {}
                            else -> a.onLevelStart(axis)
                        }
                    }
                    when (axis) {
                        DragAxis.SEEK -> a.onSeekDrag(accX, this.size.width.toFloat())
                        DragAxis.NONE -> {}
                        else -> a.onLevelDrag(axis, accY, this.size.height.toFloat())
                    }
                }
            }
    )
}

private fun finish(axis: DragAxis, a: GestureActions) {
    when (axis) {
        DragAxis.SEEK -> a.onSeekEnd()
        DragAxis.NONE -> {}
        else -> a.onLevelEnd()
    }
}
