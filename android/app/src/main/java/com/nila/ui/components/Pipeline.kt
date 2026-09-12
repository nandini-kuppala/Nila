package com.nila.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.nila.ui.theme.severityColors

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
 *
 * ### Why it is not a block of colour any more
 *
 * This card used to be a filled `primaryContainer` panel: in the light theme a
 * saturated mint rectangle, in the dark theme a solid teal one. It read as
 * loud rather than as important, and it arrived on screen directly underneath
 * an amber warning box and above a white caution strip -- three saturated
 * grounds stacked, none of them agreeing, at the exact moment the reader is
 * being asked to follow three numbered instructions. The card was hardest to
 * read precisely when it mattered most.
 *
 * It is now the same white-on-outline card as everything else on the screen,
 * and it earns its rank by *rule and eyebrow* instead: a heavier border in the
 * accent, a coloured label above the headline, and numbered step badges. Small
 * areas of colour, in the places the eye has to land, on a ground that stays
 * legible in both themes. The caution inside it takes its colours from the
 * severity palette rather than a hardcoded red, which is what made it a near
 * black-on-black strip in the dark theme.
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
            .background(scheme.surfaceContainerLowest)
            .border(1.5.dp, scheme.primary.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
            )
            Text(
                "WHAT TO DO NOW",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.primary,
            )
        }

        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
            color = scheme.onSurface,
        )

        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                steps.forEachIndexed { i, step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // The numeral carries the only filled colour in the
                        // body. It is a badge rather than a bare digit because
                        // the steps are an ordered list someone works through
                        // one-handed, and losing your place in it is the whole
                        // failure mode this card has.
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(scheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }

        if (caution.isNotBlank()) {
            // Amber, from the severity palette, because that is what a caution
            // is -- and taken as a matched trio so the body text cannot end up
            // the theme's near-white on a pale ground.
            val cautionColors = severityColors(Severity.ATTENTION)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cautionColors.container)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("!", style = MaterialTheme.typography.bodyLarge,
                     fontWeight = FontWeight.Bold, color = cautionColors.accent)
                Text(
                    caution,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cautionColors.onContainer,
                )
            }
        }

        Text(
            "Guidance from $source, shown as it is written. " +
                "The cause is a guess from the sound, not a diagnosis.",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
