package com.nila.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.data.Severity
import com.nila.ui.theme.SeverityAttention
import com.nila.ui.theme.SeverityAttentionBg
import com.nila.ui.theme.SeverityCalm
import com.nila.ui.theme.SeverityCalmBg
import com.nila.ui.theme.SeverityUrgent
import com.nila.ui.theme.SeverityUrgentBg

/**
 * Severity as a chip.
 *
 * Colour is never the only carrier -- each chip also has a distinct label, so it
 * survives colour-blindness and a glance from across a dark room. That is the
 * same reason the layout changes with severity rather than only the hue.
 */
@Composable
fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    val (fg, bg) = when (severity) {
        Severity.NOTE -> MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.surfaceContainer
        Severity.ATTENTION -> SeverityAttention to SeverityAttentionBg
        Severity.URGENT, Severity.CRITICAL -> SeverityUrgent to SeverityUrgentBg
    }
    Surface(color = bg, shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            text = severity.label.uppercase(),
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun CalmChip(text: String, modifier: Modifier = Modifier) {
    Surface(color = SeverityCalmBg, shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            text = text,
            color = SeverityCalm,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The cry intensity sparkline.
 *
 * This is the honest visual in the app. Unlike a cause label, the shape of the
 * envelope is something we genuinely measured, and it answers the question a
 * parent in the next room actually has: is this building or dying down.
 */
@Composable
fun IntensityCurve(
    points: List<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height

        drawLine(
            color = track,
            start = Offset(0f, h),
            end = Offset(w, h),
            strokeWidth = 1.5f,
        )
        if (points.size < 2) return@Canvas

        val step = w / (points.size - 1).toFloat()
        val path = Path()
        points.forEachIndexed { i, value ->
            val x = i * step
            val y = h - (value / 100f).coerceIn(0f, 1f) * (h - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // Emphasised endpoint: where the cry is *now* is the part being read.
        val lastX = (points.size - 1) * step
        val lastY = h - (points.last() / 100f).coerceIn(0f, 1f) * (h - 4f)
        drawCircle(color, radius = 4f, center = Offset(lastX, lastY))
    }
}

/** A labelled fact. Used wherever a number needs its unit and meaning attached. */
@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The slow pulse that says the guardian is awake. */
@Composable
fun PulseDot(active: Boolean, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val color = if (active) SeverityCalm else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** An inline caveat. Present whenever a claim has a limit, never empty. */
@Composable
fun HedgeNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("i", style = MaterialTheme.typography.labelMedium,
             fontWeight = FontWeight.Bold,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
fun OutlinedBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) { content() }
}

/**
 * A live microphone level.
 *
 * This exists because "Listening" is a claim, not evidence. An emulator with no
 * audio input, a phone whose microphone another app has grabbed, and a genuinely
 * quiet nursery all produce identical text -- and the first two are failures a
 * parent needs to find out about before the night, not after it. A bar that
 * moves when you clap settles the question in one second.
 */
@Composable
fun InputLevelMeter(
    level: Float,
    dbfs: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "MICROPHONE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (dbfs <= -79f) "silent" else "${dbfs.toInt()} dB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** An amber caution box. Used where the app is working but something is wrong. */
@Composable
fun WarningBox(title: String, body: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(com.nila.ui.theme.SeverityAttentionBg)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = com.nila.ui.theme.SeverityAttention,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
