package ru.oasispay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp), enabled = enabled,
        shape = MaterialTheme.shapes.medium, contentPadding = ButtonDefaults.ContentPadding) { Text(label) }
}

@Composable
internal fun SecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp), enabled = enabled,
        shape = MaterialTheme.shapes.medium) { Text(label) }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Notice(text: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 13.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun StatusTag(label: String, positive: Boolean = false, negative: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when { negative -> colors.errorContainer
                        positive -> colors.primaryContainer
                        else -> colors.secondaryContainer },
        contentColor = when { negative -> colors.onErrorContainer
                        positive -> colors.onPrimaryContainer
                        else -> colors.onSecondaryContainer },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = when { negative -> colors.onErrorContainer
                        positive -> colors.onPrimaryContainer
                        else -> colors.onSecondaryContainer }
            Canvas(Modifier.size(6.dp)) { drawCircle(ink) }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal enum class Glyph { BRAND, TERMINAL, PHONE, RADIO }

@Composable
internal fun Symbol(glyph: Glyph, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.size(48.dp), color = colors.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(25.dp)) {
                val ink = colors.onPrimaryContainer
                val unit = size.width / 24
                fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
                fun line(x: Float, y: Float, x2: Float, y2: Float) =
                    drawLine(ink, point(x, y), point(x2, y2), 1.65f * unit, StrokeCap.Round)
                fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(
                    ink, point(x, y), Size(w * unit, h * unit), CornerRadius(2 * unit), style = Stroke(1.65f * unit))
                when (glyph) {
                    Glyph.BRAND -> {
                        val shape = Path().apply {
                            moveTo(12 * unit, 2 * unit)
                            cubicTo(16 * unit, 8 * unit, 21 * unit, 10 * unit, 21 * unit, 15 * unit)
                            cubicTo(21 * unit, 20 * unit, 17 * unit, 22 * unit, 12 * unit, 22 * unit)
                            cubicTo(7 * unit, 22 * unit, 3 * unit, 20 * unit, 3 * unit, 15 * unit)
                            cubicTo(3 * unit, 10 * unit, 8 * unit, 8 * unit, 12 * unit, 2 * unit)
                            close()
                        }
                        drawPath(shape, ink, style = Stroke(1.6f * unit))
                        line(8f, 15f, 11f, 18f)
                        line(11f, 18f, 16f, 13f)
                    }
                    Glyph.TERMINAL -> {
                        box(3f, 4f, 18f, 13f)
                        line(7f, 12f, 9f, 10f)
                        line(9f, 10f, 11f, 12f)
                        line(11f, 12f, 15f, 8f)
                        line(8f, 21f, 16f, 21f)
                        line(12f, 17f, 12f, 21f)
                    }
                    Glyph.PHONE -> {
                        box(6f, 2f, 12f, 20f)
                        line(10f, 18f, 14f, 18f)
                        line(9.5f, 10f, 11.5f, 12f)
                        line(11.5f, 12f, 15f, 8.5f)
                    }
                    Glyph.RADIO -> {
                        line(12f, 3f, 12f, 21f)
                        line(12f, 3f, 17f, 7f)
                        line(17f, 7f, 7f, 16f)
                        line(12f, 21f, 17f, 17f)
                        line(17f, 17f, 7f, 8f)
                    }
                }
            }
        }
    }
}
