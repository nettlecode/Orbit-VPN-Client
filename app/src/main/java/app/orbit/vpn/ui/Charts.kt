package app.orbit.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Area chart of download (filled) and upload (line) over time. [points] are (up, down) pairs,
 * oldest first; [axisLabel] formats a y-axis value.
 */
@Composable
fun SpeedChart(
    points: List<Pair<Long, Long>>,
    capacity: Int,
    axisLabel: (Long) -> String,
    bottomLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val down = MaterialTheme.colorScheme.primary
    val up = Upload
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = niceMax(points.maxOfOrNull { maxOf(it.first, it.second) } ?: 0)
    ChartFrame(max, axisLabel, bottomLabels, modifier) {
        drawGrid(grid)
        if (points.size < 2) return@ChartFrame
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        val offset = (capacity - points.size) * step
        fun y(value: Long) = size.height * (1 - value.toFloat() / max)

        val downPath = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(offset, y(p.second)) else lineTo(offset + i * step, y(p.second)) }
        }
        val fill = Path().apply {
            addPath(downPath)
            lineTo(offset + (points.size - 1) * step, size.height)
            lineTo(offset, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(down.copy(alpha = 0.35f), down.copy(alpha = 0.02f))))
        drawPath(downPath, down, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val upPath = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(offset, y(p.first)) else lineTo(offset + i * step, y(p.first)) }
        }
        drawPath(upPath, up, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Stacked bars: download (bottom, green) and upload (top, blue) per period. */
@Composable
fun TrafficBars(
    points: List<Pair<Long, Long>>,
    axisLabel: (Long) -> String,
    bottomLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val down = MaterialTheme.colorScheme.primary
    val up = Upload
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = niceMax(points.maxOfOrNull { it.first + it.second } ?: 0)
    ChartFrame(max, axisLabel, bottomLabels, modifier) {
        drawGrid(grid)
        if (points.isEmpty()) return@ChartFrame
        val slot = size.width / points.size
        val barWidth = (slot * 0.6f).coerceAtMost(18.dp.toPx())
        val radius = CornerRadius(barWidth / 3)
        points.forEachIndexed { i, (u, d) ->
            if (u + d <= 0) return@forEachIndexed
            val x = i * slot + (slot - barWidth) / 2
            val downHeight = size.height * d / max
            val totalHeight = size.height * (u + d) / max
            // One rounded bar in the upload colour, with the download part painted over its bottom.
            drawRoundRect(up, Offset(x, size.height - totalHeight), Size(barWidth, totalHeight), radius)
            if (d > 0) {
                drawRoundRect(down, Offset(x, size.height - downHeight), Size(barWidth, downHeight), radius)
                if (u > 0) {
                    // Square off the seam so the two colours meet flush.
                    val seam = minOf(radius.y, downHeight)
                    drawRect(down, Offset(x, size.height - downHeight), Size(barWidth, seam))
                }
            }
        }
    }
}

@Composable
private fun ChartFrame(
    max: Long,
    axisLabel: (Long) -> String,
    bottomLabels: List<String>,
    modifier: Modifier,
    draw: DrawScope.() -> Unit,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row {
            Column(Modifier.height(170.dp).padding(end = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                listOf(max, max / 2, 0L).forEach { Text(axisLabel(it), style = labelStyle, color = labelColor) }
            }
            Canvas(Modifier.weight(1f).height(170.dp).padding(vertical = 7.dp), onDraw = draw)
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp)) {
            bottomLabels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = labelStyle,
                    color = labelColor,
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        bottomLabels.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    listOf(0f, 0.5f, 1f).forEach { f ->
        drawLine(color, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx(), pathEffect = dash)
    }
}

/** Rounds the axis maximum up to 1/2/5 × 10^n so labels stay readable. */
private fun niceMax(value: Long): Long {
    if (value <= 0) return 1024
    var magnitude = 1L
    while (magnitude * 10 <= value) magnitude *= 10
    return listOf(1, 2, 5, 10).map { it * magnitude }.first { it >= value * 1.1 || it == 10 * magnitude }
}
