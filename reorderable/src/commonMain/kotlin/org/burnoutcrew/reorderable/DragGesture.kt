/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.burnoutcrew.reorderable

import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import timber.log.Timber

// Copied from DragGestureDetector , as long the pointer api isn`t ready.

internal suspend fun AwaitPointerEventScope.awaitPointerSlopOrCancellation(
    pointerId: PointerId,
    pointerType: PointerType,
    onPointerSlopReached: (change: PointerInputChange, overSlop: Offset) -> Unit
): PointerInputChange? {
    if (currentEvent.isPointerUp(pointerId)) {
        return null // The pointer has already been lifted, so the gesture is canceled
    }
    var offset = Offset.Zero
    val touchSlop = viewConfiguration.pointerSlop(pointerType)

    var pointer = pointerId

    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.isConsumed) {
            return null
        } else if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                // This is the last "up"
                return null
            } else {
                pointer = otherDown.id
            }
        } else {
            offset += dragEvent.positionChange()
            val distance = offset.getDistance()
            var acceptedDrag = false
            if (distance >= touchSlop) {
                val touchSlopOffset = offset / distance * touchSlop
                onPointerSlopReached(dragEvent, offset - touchSlopOffset)
                if (dragEvent.isConsumed) {
                    acceptedDrag = true
                } else {
                    offset = Offset.Zero
                }
            }

            if (acceptedDrag) {
                return dragEvent
            } else {
                awaitPointerEvent(PointerEventPass.Final)
                if (dragEvent.isConsumed) {
                    return null
                }
            }
        }
    }
}

internal suspend fun PointerInputScope.awaitLongPressOrCancellation(
    initialDown: PointerInputChange
): PointerInputChange? {
    val longPress: PointerInputChange? = null
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    return try {
        // wait for first tap up or long press
        withTimeout(longPressTimeout) {
            awaitPointerEventScope {
                var finished = false
                while (!finished) {
                    val change = awaitDragOrCancellation(initialDown.id)
                    change?.let {
                        if (!change.pressed) {
                            Timber.tag("DragGesture").d("DragGesture after awaitDragOrCancellation and pressed is false")
                            finished = true
                        }
                    } ?: run {
                        finished = true
                        Timber.tag("DragGesture").d("DragGesture after awaitDragOrCancellation change is null")
                    }
                }
            }
        }
        null
    } catch (_: TimeoutCancellationException) {
        longPress ?: initialDown
    }
}

private fun PointerEvent.isPointerUp(pointerId: PointerId): Boolean =
    changes.fastFirstOrNull { it.id == pointerId }?.pressed != true

// This value was determined using experiments and common sense.
// We can't use zero slop, because some hypothetical desktop/mobile devices can send
// pointer events with a very high precision (but I haven't encountered any that send
// events with less than 1px precision)
private val mouseSlop = 0.125.dp
private val defaultTouchSlop = 18.dp // The default touch slop on Android devices
private val mouseToTouchSlopRatio = mouseSlop / defaultTouchSlop

// TODO(demin): consider this as part of ViewConfiguration class after we make *PointerSlop*
//  functions public (see the comment at the top of the file).
//  After it will be a public API, we should get rid of `touchSlop / 144` and return absolute
//  value 0.125.dp.toPx(). It is not possible right now, because we can't access density.
private fun ViewConfiguration.pointerSlop(pointerType: PointerType): Float {
    return when (pointerType) {
        PointerType.Mouse -> touchSlop * mouseToTouchSlopRatio
        else -> touchSlop
    }
}