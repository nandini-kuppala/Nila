package com.nila.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.data.Severity
import com.nila.monitor.EpisodePipeline
import com.nila.ui.theme.SeverityAttention
import com.nila.ui.theme.SeverityUrgent

/**
 * The escalation ladder, drawn.
 *
 * Two things this has to do that a list of sentences could not. It shows the
 * rungs that have *not* fired yet, so a parent watching at forty seconds can
 * see that a verification step and a decision point are still ahead -- the
 * difference between an app working through a plan and an app that has stopped.
 * And it puts each rung against the second it is due, so the wait between them
 * reads as designed rather than as lag.
 *
 * Deliberately the same component for the live episode and the summary. A
 * summary that renders differently from the thing it summarises makes the
 * reader do the reconciliation.
 */
@Composable
fun PipelineChart(
    steps: List<EpisodePipeline.Step>,
    seconds: Int,
    modifier: Modifier = Modifier,
    showRail: Boolean = true,
) {
    if (steps.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (showRail) {
            ProgressRail(steps = steps, seconds = seconds)
            Spacer(Modifier.height(18.dp))
        }
        steps.forEachIndexed { index, step ->
            PipelineRow(
                step = step,
                isLast = index == steps.lastIndex,
            )
        }
    }
}

/**
 * Episode position against the rungs, as one horizontal bar.
 *
 * The glanceable half of the chart: how far into the ladder this cry is, and
 * how much of it is left, without reading a single row.
 */
@Composable
private fun ProgressRail(
    steps: List<EpisodePipeline.Step>,
    seconds: Int,
) {
    val total = EpisodePipeline.Stage.CLOSED.atSeconds.toFloat()
    val progress by animateFloatAsState(
        targetValue = (seconds / total).coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "episode-progress",
    )
    val done = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tickPending = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val h = size.height
            val y = h / 2f
            val r = h / 2f
            drawLine(
                color = track, start = Offset(r, y), end = Offset(size.width - r, y),
                strokeWidth = h * 0.55f,
            )
            drawLine(
                color = done, start = Offset(r, y),
                end = Offset(r + (size.width - 2 * r) * progress, y),
                strokeWidth = h * 0.55f,
            )
            // One tick per rung, filled once its second has passed. Reading the
            // gaps between them is how the pacing of the ladder becomes visible.
            steps.forEach { step ->
                val x = r + (size.width - 2 * r) *
                    (step.stage.atSeconds / total).coerceIn(0f, 1f)
                drawCircle(
                    color = if (seconds >= step.stage.atSeconds) done else tickPending,
                    radius = r * 0.85f,
                    center = Offset(x, y),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            RailLabel("0s", Modifier.weight(1f), TextAlign.Start)
            RailLabel("${EpisodePipeline.Stage.VERDICT.atSeconds}s",
                      Modifier.weight(1f), TextAlign.Center)
            RailLabel("${EpisodePipeline.Stage.CLOSED.atSeconds}s",
                      Modifier.weight(1f), TextAlign.End)
        }
    }
}

@Composable
private fun RailLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = align,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One rung: a dot on a spine, the time it is due, and what it did.
 *
 * The spine is drawn per row rather than as one background so a row can be the
 * last one without a dangling tail, and so the segment above an active rung can
 * be solid while the one below it is not.
 */
@Composable
private fun PipelineRow(
    step: EpisodePipeline.Step,
    isLast: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        step.severity == Severity.URGENT || step.severity == Severity.CRITICAL ->
            SeverityUrgent
        step.severity == Severity.ATTENTION -> SeverityAttention
        else -> scheme.primary
    }
    val (dot, ring) = when (step.status) {
        EpisodePipeline.Status.DONE -> accent to accent
        EpisodePipeline.Status.ACTIVE -> accent to accent
        EpisodePipeline.Status.SKIPPED ->
            Color.Transparent to scheme.outlineVariant
        EpisodePipeline.Status.PENDING ->
            Color.Transparent to scheme.outlineVariant
    }
    val muted = step.status == EpisodePipeline.Status.PENDING ||
        step.status == EpisodePipeline.Status.SKIPPED

    Row(modifier = Modifier.fillMaxWidth()) {
        // ---- the spine
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (step.status == EpisodePipeline.Status.ACTIVE) 14.dp else 11.dp)
                    .clip(CircleShape)
                    .background(ring),
                contentAlignment = Alignment.Center,
            ) {
                // A hollow dot for a rung that has not run. Fill is what carries
                // "this happened", so it cannot also be the shape for "pending".
                Box(
                    modifier = Modifier
                        .size(if (step.status == EpisodePipeline.Status.ACTIVE) 14.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (muted) scheme.surface else dot)
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (step.detail != null) 34.dp else 26.dp)
                        .background(
                            if (step.status == EpisodePipeline.Status.DONE) accent
                            else scheme.outlineVariant
                        )
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // ---- what it is, and what it did
        Column(
            modifier = Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (step.status == EpisodePipeline.Status.ACTIVE)
                        FontWeight.SemiBold else FontWeight.Normal,
                    color = if (muted) scheme.onSurfaceVariant else scheme.onSurface,
                )
                Text(
                    // Where it actually fired if it did, and where it is due if
                    // it has not. Those differ by seconds and the difference is
                    // the only evidence that the timings are real.
                    text = step.firedAtSeconds?.let { "${it}s" }
                        ?: "${step.stage.atSeconds}s",
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The cause, and what the corpus says to do about it.
 *
 * Every word of the guidance and every step comes out of one curated entry with
 * its source named at the bottom. The headline is the only authored sentence and
 * it always contains "probably" -- the classifier scored 0.444 subject-wise
 * macro AUC, which is below chance, and the honesty about that is not a
 * disclaimer bolted on afterwards. It is why the sentence is phrased this way.
 */
@Composable
fun ReasonVerdict(
    headline: String,
    steps: List<String>,
    caution: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.primaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
            color = scheme.onPrimaryContainer,
        )

        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "WHAT TO TRY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.55f),
                        )
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

        if (caution.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("!", style = MaterialTheme.typography.bodyLarge,
                     fontWeight = FontWeight.Bold, color = SeverityUrgent)
                Text(
                    caution,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }

        Text(
            "Guidance from $source, shown as it is written. " +
                "The cause is a guess from the sound, not a diagnosis.",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}
