package com.daya.velum.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.daya.velum.imaging.FrameStats
import com.daya.velum.settings.GridType
import com.daya.velum.ui.theme.V
import kotlin.math.abs
import kotlin.math.min

/** Rectangle of a [ratio]:1 frame whose long side follows the box's long side (sensor orientation). */
fun cineFrame(box: Size, ratio: Float): Rect {
    val portrait = box.height > box.width
    val long = if (portrait) box.height else box.width
    val short = if (portrait) box.width else box.height
    var fLong = long; var fShort = long / ratio
    if (fShort > short) { fShort = short; fLong = short * ratio }
    val w = if (portrait) fShort else fLong
    val h = if (portrait) fLong else fShort
    return Rect(Offset((box.width - w) / 2, (box.height - h) / 2), Size(w, h))
}

@Composable
fun GuidesOverlay(grid: GridType, center: Boolean, safe: Boolean, cineRatio: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val line = V.Bone.copy(alpha = 0.45f)
        val sw = 1.dp.toPx()
        val area = cineRatio?.let { cineFrame(size, it) } ?: Rect(Offset.Zero, size)
        if (cineRatio != null) {
            // Matte outside the frame, then crisp frame lines.
            val matte = Color.Black.copy(alpha = 0.62f)
            drawRect(matte, Offset.Zero, Size(size.width, area.top))
            drawRect(matte, Offset(0f, area.bottom), Size(size.width, size.height - area.bottom))
            drawRect(matte, Offset(0f, area.top), Size(area.left, area.height))
            drawRect(matte, Offset(area.right, area.top), Size(size.width - area.right, area.height))
            drawRect(V.Bone.copy(alpha = 0.8f), area.topLeft, area.size, style = Stroke(sw))
        }
        fun lines(fr: List<Float>) {
            for (f in fr) {
                drawLine(line, Offset(area.left + area.width * f, area.top), Offset(area.left + area.width * f, area.bottom), sw)
                drawLine(line, Offset(area.left, area.top + area.height * f), Offset(area.right, area.top + area.height * f), sw)
            }
        }
        when (grid) {
            GridType.THIRDS -> lines(listOf(1f / 3, 2f / 3))
            GridType.GOLDEN -> lines(listOf(0.382f, 0.618f))
            GridType.CENTER -> lines(listOf(0.5f))
            GridType.NONE -> {}
        }
        if (center) {
            val c = area.center; val r = 9.dp.toPx()
            drawLine(V.Bone, Offset(c.x - r, c.y), Offset(c.x + r, c.y), sw * 1.4f)
            drawLine(V.Bone, Offset(c.x, c.y - r), Offset(c.x, c.y + r), sw * 1.4f)
        }
        if (safe) {
            val inset = Rect(area.left + area.width * 0.05f, area.top + area.height * 0.05f, area.right - area.width * 0.05f, area.bottom - area.height * 0.05f)
            drawRect(V.Amber.copy(alpha = 0.55f), inset.topLeft, inset.size, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
    }
}

/** Horizon level. [rollDeg] is device roll; the indicator snaps to the nearest held orientation. */
@Composable
fun LevelOverlay(rollDeg: Float, modifier: Modifier = Modifier) {
    val nearest = (Math.round(rollDeg / 90f) * 90f)
    val dev = rollDeg - nearest
    val level = abs(dev) < 1f
    Canvas(modifier.fillMaxSize()) {
        val c = center
        val half = min(size.width, size.height) * 0.22f
        val col = if (level) V.Amber else V.Bone.copy(alpha = 0.85f)
        rotate(-nearest, c) {
            drawLine(V.Bone.copy(alpha = 0.35f), Offset(c.x - half - 18.dp.toPx(), c.y), Offset(c.x - half, c.y), 1.5.dp.toPx())
            drawLine(V.Bone.copy(alpha = 0.35f), Offset(c.x + half, c.y), Offset(c.x + half + 18.dp.toPx(), c.y), 1.5.dp.toPx())
        }
        rotate(-rollDeg, c) {
            drawLine(col, Offset(c.x - half, c.y), Offset(c.x + half, c.y), if (level) 2.dp.toPx() else 1.5.dp.toPx())
        }
    }
}

@Composable
fun FocusRing(at: Offset?, stamp: Long, success: Boolean?, modifier: Modifier = Modifier) {
    if (at == null) return
    val anim = remember { Animatable(1.4f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(stamp) {
        alpha.snapTo(1f); anim.snapTo(1.4f)
        anim.animateTo(1f, tween(180))
        kotlinx.coroutines.delay(1400)
        alpha.animateTo(0f, tween(300))
    }
    Canvas(modifier.fillMaxSize()) {
        val r = 34.dp.toPx() * anim.value
        val col = (if (success == false) V.Ash else if (success == true) V.Amber else V.Bone).copy(alpha = alpha.value)
        drawCircle(col, r, at, style = Stroke(1.5.dp.toPx()))
        drawCircle(col, 2.dp.toPx(), at)
    }
}

@Composable
fun Histogram(stats: FrameStats?, modifier: Modifier = Modifier) {
    val s = stats ?: return
    Canvas(modifier.size(120.dp, 56.dp).background(V.Scrim, RoundedCornerShape(6.dp)).padding(6.dp)) {
        val maxV = (s.histogram.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / s.histogram.size
        s.histogram.forEachIndexed { i, v ->
            val h = (v / maxV) * size.height
            val col = when {
                i >= 62 -> V.Tally
                i <= 1 -> V.Ash
                else -> V.Bone.copy(alpha = 0.8f)
            }
            drawRect(col, Offset(i * bw, size.height - h), Size(bw * 0.9f, h))
        }
        if (s.clippedFraction > 0.01f) drawRect(V.Tally, Offset(size.width - 3.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
    }
}
