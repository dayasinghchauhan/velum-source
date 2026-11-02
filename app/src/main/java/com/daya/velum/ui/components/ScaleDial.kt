package com.daya.velum.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The lens-barrel scale: engraved ticks slide beneath a fixed amber index. Discrete [steps]; every
 * [majorEvery]-th tick is long and labelled with [label]. Drag or fling to change, tap a side to nudge one step.
 * The value is committed on every step crossed (so the camera follows the finger), with a light haptic tick.
 */
@Composable
fun ScaleDial(
    stepCount: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    majorEvery: Int = 3,
    stepWidthDp: Float = 14f,
    enabled: Boolean = true,
    contentDescription: String = "",
) {
    if (stepCount <= 0) return
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stepPx = with(density) { stepWidthDp.dp.toPx() }
    val pos = remember { Animatable(index.toFloat()) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val currentIndex by rememberUpdatedState(index)
    LaunchedEffect(index) { if (!dragging && abs(pos.value - index) > 0.01f) pos.animateTo(index.toFloat(), spring(stiffness = 600f)) }
    val measurer = rememberTextMeasurer()
    val tickColor = if (enabled) V.Bone else V.Dim

    fun commit(f: Float) {
        val i = f.roundToInt().coerceIn(0, stepCount - 1)
        if (i != currentIndex) { onIndex(i); haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = label(index)
                setProgress { t -> commit(t * (stepCount - 1)); true }
            }
            .pointerInput(enabled, stepCount) {
                if (!enabled) return@pointerInput
                val vt = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; vt.resetTracking() },
                    onDragEnd = {
                        val v = vt.calculateVelocity().x
                        val target = (pos.value - v / stepPx * 0.25f).roundToInt().coerceIn(0, stepCount - 1).toFloat()
                        scope.launch {
                            pos.animateTo(target, spring(stiffness = 400f)) { commit(value) }
                            commit(target); dragging = false
                        }
                    },
                    onDragCancel = { dragging = false },
                ) { change, dx ->
                    vt.addPosition(change.uptimeMillis, change.position)
                    val next = (pos.value - dx / stepPx).coerceIn(0f, (stepCount - 1).toFloat())
                    scope.launch { pos.snapTo(next) }
                    commit(next)
                }
            }
            .pointerInput(enabled, stepCount) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    val dir = if (o.x > size.width / 2) 1 else -1
                    val i = (currentIndex + dir).coerceIn(0, stepCount - 1)
                    if (i != currentIndex) { onIndex(i); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                }
            },
    ) {
        val cx = size.width / 2
        val baseY = size.height - 6.dp.toPx()
        val first = (pos.value - cx / stepPx).toInt() - 1
        val last = (pos.value + cx / stepPx).toInt() + 1
        for (i in first..last) {
            if (i < 0 || i >= stepCount) continue
            val x = cx + (i - pos.value) * stepPx
            val major = i % majorEvery == 0
            val fade = 1f - (abs(x - cx) / cx).coerceIn(0f, 1f) * 0.85f
            val h = if (major) 16.dp.toPx() else 8.dp.toPx()
            drawLine(tickColor.copy(alpha = fade), Offset(x, baseY - h), Offset(x, baseY), strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx())
            if (major) {
                val t = measurer.measure(label(i), VType.micro.copy(color = tickColor.copy(alpha = fade)))
                drawText(t, topLeft = Offset(x - t.size.width / 2f, baseY - h - t.size.height - 3.dp.toPx()))
            }
        }
        // Fixed index: an amber needle and a small notch.
        drawLine(V.Amber, Offset(cx, 2.dp.toPx()), Offset(cx, baseY + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
        drawCircle(V.Amber, radius = 3.dp.toPx(), center = Offset(cx, 2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    }
}

/** A value readout above the dial: label left in ash, value in bone tabular figures. */
@Composable
fun DialHeader(name: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = VType.small.copy(color = V.Ash))
        Spacer(Modifier.width(8.dp))
        Text(value, style = VType.value)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
