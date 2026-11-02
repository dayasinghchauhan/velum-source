package com.daya.velum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daya.velum.ui.theme.V
import kotlin.math.cos
import kotlin.math.sin

enum class G { FLASH_OFF, FLASH_AUTO, FLASH_ON, TORCH, TIMER, GRID, GEAR, FLIP, LOOKS, RECIPES, CLOSE, PLAY, PAUSE, BACK, TRASH, INFO, LEVEL, HIST, PLUS, STAR, STAR_FILLED, CHECK, CHEVRON }

/** Original line glyphs drawn on a 24-unit grid, 1.6 dp stroke. */
@Composable
fun Glyph(g: G, modifier: Modifier = Modifier, tint: Color = V.Bone, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            if (close) close()
        }
        fun bolt() = drawPath(p(13f, 2f, 5f, 13.5f, 11f, 13.5f, 10f, 22f, 18f, 10f, 12f, 10f, close = true), tint, style = st)
        when (g) {
            G.FLASH_ON -> bolt()
            G.FLASH_OFF -> { bolt(); drawLine(tint, Offset(3 * u, 3 * u), Offset(21 * u, 21 * u), st.width, StrokeCap.Round) }
            G.FLASH_AUTO -> { bolt(); drawCircle(tint, 2.2f * u, Offset(20 * u, 19 * u)) }
            G.TORCH -> {
                drawPath(p(7f, 3f, 17f, 3f, 17f, 7f, 14f, 11f, 14f, 21f, 10f, 21f, 10f, 11f, 7f, 7f, close = true), tint, style = st)
                drawLine(tint, Offset(12 * u, 13 * u), Offset(12 * u, 15.5f * u), st.width, StrokeCap.Round)
            }
            G.TIMER -> {
                drawCircle(tint, 8f * u, Offset(12 * u, 13.5f * u), style = st)
                drawLine(tint, Offset(12 * u, 13.5f * u), Offset(12 * u, 9 * u), st.width, StrokeCap.Round)
                drawLine(tint, Offset(9.5f * u, 2.5f * u), Offset(14.5f * u, 2.5f * u), st.width, StrokeCap.Round)
            }
            G.GRID -> {
                drawRect(tint, Offset(3 * u, 3 * u), Size(18 * u, 18 * u), style = st)
                for (k in listOf(9f, 15f)) {
                    drawLine(tint, Offset(k * u, 3 * u), Offset(k * u, 21 * u), st.width)
                    drawLine(tint, Offset(3 * u, k * u), Offset(21 * u, k * u), st.width)
                }
            }
            G.GEAR -> {
                drawCircle(tint, 3.2f * u, Offset(12 * u, 12 * u), style = st)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    drawLine(tint, Offset((12 + 6.2 * cos(a)).toFloat() * u, (12 + 6.2 * sin(a)).toFloat() * u),
                        Offset((12 + 9.2 * cos(a)).toFloat() * u, (12 + 9.2 * sin(a)).toFloat() * u), st.width * 1.6f, StrokeCap.Round)
                }
                drawCircle(tint, 6.6f * u, Offset(12 * u, 12 * u), style = st)
            }
            G.FLIP -> {
                // Two opposed arcs with arrowheads around a lens dot: front/back swap.
                drawArc(tint, 200f, 140f, false, Offset(4 * u, 4 * u), Size(16 * u, 16 * u), style = st)
                drawArc(tint, 20f, 140f, false, Offset(4 * u, 4 * u), Size(16 * u, 16 * u), style = st)
                drawPath(p(16.2f, 2.8f, 18.6f, 5.6f, 15.0f, 6.6f), tint, style = st)
                drawPath(p(7.8f, 21.2f, 5.4f, 18.4f, 9.0f, 17.4f), tint, style = st)
                drawCircle(tint, 2.4f * u, Offset(12 * u, 12 * u), style = st)
            }
            G.LOOKS -> {
                drawCircle(tint, 5.5f * u, Offset(9 * u, 9.5f * u), style = st)
                drawCircle(tint, 5.5f * u, Offset(15 * u, 9.5f * u), style = st)
                drawCircle(tint, 5.5f * u, Offset(12 * u, 15 * u), style = st)
            }
            G.RECIPES -> {
                drawRect(tint, Offset(4 * u, 3 * u), Size(16 * u, 18 * u), style = st)
                for (y in listOf(8f, 12f, 16f)) drawLine(tint, Offset(8 * u, y * u), Offset(16 * u, y * u), st.width, StrokeCap.Round)
            }
            G.CLOSE -> {
                drawLine(tint, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), st.width, StrokeCap.Round)
                drawLine(tint, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), st.width, StrokeCap.Round)
            }
            G.PLAY -> drawPath(p(8f, 5f, 19f, 12f, 8f, 19f, close = true), tint)
            G.PAUSE -> {
                drawRect(tint, Offset(7 * u, 5 * u), Size(3.5f * u, 14 * u))
                drawRect(tint, Offset(13.5f * u, 5 * u), Size(3.5f * u, 14 * u))
            }
            G.BACK -> drawPath(p(15f, 5f, 8f, 12f, 15f, 19f), tint, style = st)
            G.CHEVRON -> drawPath(p(9f, 5f, 16f, 12f, 9f, 19f), tint, style = st)
            G.TRASH -> {
                drawPath(p(5f, 7f, 19f, 7f), tint, style = st)
                drawPath(p(7f, 7f, 8f, 21f, 16f, 21f, 17f, 7f), tint, style = st)
                drawPath(p(10f, 7f, 10f, 4f, 14f, 4f, 14f, 7f), tint, style = st)
            }
            G.INFO -> {
                drawCircle(tint, 9f * u, Offset(12 * u, 12 * u), style = st)
                drawLine(tint, Offset(12 * u, 11 * u), Offset(12 * u, 17 * u), st.width, StrokeCap.Round)
                drawCircle(tint, 1.1f * u, Offset(12 * u, 7.5f * u))
            }
            G.LEVEL -> {
                drawLine(tint, Offset(2 * u, 12 * u), Offset(8 * u, 12 * u), st.width, StrokeCap.Round)
                drawLine(tint, Offset(16 * u, 12 * u), Offset(22 * u, 12 * u), st.width, StrokeCap.Round)
                drawCircle(tint, 3f * u, Offset(12 * u, 12 * u), style = st)
            }
            G.HIST -> {
                val hs = listOf(4f, 7f, 12f, 16f, 11f, 6f, 9f)
                hs.forEachIndexed { i, h -> drawLine(tint, Offset((4 + i * 2.7f) * u, 20 * u), Offset((4 + i * 2.7f) * u, (20 - h) * u), st.width * 1.2f, StrokeCap.Round) }
            }
            G.PLUS -> {
                drawLine(tint, Offset(12 * u, 5 * u), Offset(12 * u, 19 * u), st.width, StrokeCap.Round)
                drawLine(tint, Offset(5 * u, 12 * u), Offset(19 * u, 12 * u), st.width, StrokeCap.Round)
            }
            G.STAR, G.STAR_FILLED -> {
                val path = Path()
                for (k in 0 until 10) {
                    val r = if (k % 2 == 0) 9f else 4f
                    val a = Math.toRadians(-90.0 + k * 36)
                    val x = (12 + r * cos(a)).toFloat() * u; val y = (12.5f + r * sin(a)).toFloat() * u
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                if (g == G.STAR_FILLED) drawPath(path, V.Amber) else drawPath(path, tint, style = st)
            }
            G.CHECK -> drawPath(p(5f, 12.5f, 10f, 17.5f, 19f, 7f), tint, style = st)
        }
    }
}

