package com.nila.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChangeCircle
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.data.CareKind
import com.nila.data.Severity
import com.nila.monitor.EpisodePipeline
import com.nila.monitor.MonitorState
import com.nila.ui.AppState
import com.nila.ui.components.CalmChip
import com.nila.ui.components.CryClipPlayer
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.InputLevelMeter
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.PipelineChart
import com.nila.ui.components.PulseDot
import com.nila.ui.components.ReasonVerdict
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.StatBlock
import com.nila.ui.components.VoiceNoteBar
import com.nila.ui.components.WarningBox
import com.nila.ui.theme.SeverityUrgent
import java.util.concurrent.TimeUnit

/**
 * The night screen.
 *
 * Written to be read at 3am by someone holding a baby, so it is numbers and
 * controls rather than prose. Everything that explains *why* the app behaves as
 * it does lives in Insights; this screen only ever says what is happening now
 * and what the next tap does.
 *
 * The order of the cards is the whole design. During a cry it runs answer,
 * then instruction, then mechanism: how long and how loud, then the cause, then
 * what to try, and only then the ladder showing how the app got there. A parent
 * who reads nothing but the first card should still have the useful half.
 */
@Composable
fun HomeScreen(state: AppState) {
    val monitor by state.monitor.collectAsState()
    val care by state.care.collectAsState()
    val colic by state.colic.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(
            monitor = monitor,
            onStart = state::startMonitoring,
            onStop = state::stopMonitoring,
            onCloseDemo = state::closeDemo,
        )

        monitor.fault?.let {
            WarningBox("Monitoring stopped", it, Modifier.fillMaxWidth())
        }

        // The verdict outranks everything else on the screen the moment it
        // exists: it is the only card that tells the reader what to do.
        monitor.advice?.let { advice ->
            ReasonVerdict(
                headline = advice.headline,
                steps = advice.steps,
                caution = advice.caution,
                source = advice.source,
            )
        }

        val evidence = monitor.currentEvidence
        if (evidence != null) {
            LiveEpisode(evidence, monitor)
        }

        monitor.lastEpisode?.let { episode ->
            EpisodeSummaryCard(episode, onDismiss = state::dismissEpisode)
        }

        TryItRow(state, monitor)

        QuickLog(care = care, onLog = state::logCare)

        Today(colic)
    }
}

// --------------------------------------------------------------- status

/**
 * The one card that has to be readable from across a dark room.
 *
 * It changes shape with the phase rather than only changing a word: idle shows
 * uptime and the input meter, an episode shows the elapsed time as the largest
 * thing on the screen and the ladder's position underneath it.
 */
@Composable
private fun StatusCard(
    monitor: MonitorState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCloseDemo: () -> Unit,
) {
    val phase = monitor.phase
    val urgent = phase is MonitorState.Phase.Escalated || phase is MonitorState.Phase.Safety
    val evidence = monitor.currentEvidence

    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseDot(active = monitor.running)
                Text(
                    text = headline(monitor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (urgent) SeverityUrgent
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (urgent) SeverityChip(Severity.URGENT)
            }

            if (evidence != null) {
                // Elapsed time as the hero number. During an episode this is
                // the single fact that decides what a parent does next, and it
                // used to be one of three equal-weight stat blocks.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = elapsed(evidence.durationSeconds),
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = if (urgent) SeverityUrgent
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            evidence.trend.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "detector ${(evidence.detectorConfidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IntensityCurve(points = evidence.envelope)

                if (monitor.clipSeconds > 0.5f) {
                    ClipIndicator(monitor.clipSeconds)
                }
            } else if (monitor.running || monitor.demoComplete) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    StatBlock(
                        if (monitor.demoComplete) "Demo ran for" else "Listening for",
                        uptime(monitor.armedSinceMs),
                    )
                    StatBlock("Cries tonight", monitor.eventsTonight.toString())
                    StatBlock("Cry score", "${(monitor.lastCryProbability * 100).toInt()}%")
                }
            }

            monitor.nowPlaying?.let { sound ->
                VoiceNoteBar(name = sound, isVoice = monitor.nowPlayingIsVoice)
            }

            if (monitor.running || monitor.demoComplete) {
                // The live signal. A bar that moves when you clap is the only
                // thing that distinguishes "listening" from a dead microphone.
                if (!monitor.simulated && !monitor.demoComplete && evidence == null) {
                    InputLevelMeter(monitor.inputLevel, monitor.inputDbfs)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalmChip("On device")
                    CalmChip(monitor.accelerator)
                    if (monitor.detectorLatencyMs > 0) {
                        CalmChip("%.1f ms".format(monitor.detectorLatencyMs))
                    }
                }
            }

            if (monitor.demoComplete) {
                WarningBox(
                    "Demo finished",
                    "Everything above ran through the real pipeline. " +
                        "Read what Nila did, then close this.",
                )
            } else if (monitor.simulated) {
                WarningBox(
                    "Demo cry",
                    "A recording, played through the real detector at 6x speed.",
                )
            } else if (monitor.microphoneLooksDead) {
                WarningBox(
                    "No sound reaching the microphone",
                    "A minute of silence. Check nothing else is using the mic.",
                )
            }

            if (monitor.demoComplete) {
                // Deliberately the only button here. The summary is the point of
                // having run the demo, and a second control beside it invites
                // dismissing it before reading it.
                Button(onClick = onCloseDemo, modifier = Modifier.fillMaxWidth()) {
                    Text("Close the demo")
                }
            } else if (monitor.running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(if (monitor.simulated) "Stop the demo" else "Stop monitoring")
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start monitoring")
                }
            }
        }
    }
}

/** Says a clip is being kept, and how much of the ninety seconds is used. */
@Composable
private fun ClipIndicator(seconds: Float) {
    val fraction by animateFloatAsState(
        targetValue = (seconds / com.nila.audio.EpisodeRecorder.CAP_SECONDS)
            .coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "clip-progress",
    )
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Keeping a clip of this cry",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "${seconds.toInt()}s / ${com.nila.audio.EpisodeRecorder.CAP_SECONDS}s",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.surfaceContainerHighest)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxSize()
                    .background(scheme.primary)
            ) {}
        }
    }
}

/**
 * The two things a new user should try first, as one quiet row.
 *
 * These were a card each, with a paragraph each, taking up more of the screen
 * than the monitor itself. They are affordances, not features.
 */
@Composable
private fun TryItRow(state: AppState, monitor: MonitorState) {
    val result by state.selfTest.collectAsState()
    val running by state.selfTestRunning.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = state::runDemoCry,
                enabled = !monitor.running && !monitor.demoComplete,
            ) { Text("Play a demo cry") }
            TextButton(
                onClick = state::runSelfTest,
                enabled = !running,
            ) { Text(if (running) "Testing..." else "Test the detector") }
        }
        result?.let {
            Text(
                it.shortLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

// ------------------------------------------------------------ this cry

/**
 * The cause, and the ladder working through it, while the cry is still going.
 *
 * The reason appears here from the twenty-second rung -- as a hypothesis with
 * its own uncertainty attached. The instructions for acting on it deliberately
 * do not: those wait for the verdict at ninety seconds, by which point the app
 * has tried what it can on its own and there is a person reading.
 */
@Composable
private fun LiveEpisode(
    evidence: com.nila.audio.CryEvidence,
    monitor: MonitorState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        evidence.hypothesis?.let { h ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Cry reason")
                OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "PROBABLY",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                h.label.replace('_', ' ')
                                    .replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${(h.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            buildString {
                                if (h.ambiguous && h.runnerUp != null) {
                                    append("Close to ")
                                    append(h.runnerUp.replace('_', ' '))
                                    append(". ")
                                }
                                append("Averaged over ")
                                append(h.windowsAveraged)
                                append(if (h.windowsAveraged == 1) " window" else " windows")
                                append(" of this cry. A best guess from the sound, ")
                                append("not a diagnosis.")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (monitor.pipeline.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("What Nila is doing")
                OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                    PipelineChart(
                        steps = monitor.pipeline,
                        seconds = evidence.durationSeconds,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------- closed episode

/**
 * The record of an episode that has finished.
 *
 * Everything the app claimed, with the audio it claimed it about. The clip is
 * first because it is the only item on the card a reader can check for
 * themselves; the ladder is collapsed because by this point it is evidence
 * rather than news.
 */
@Composable
private fun EpisodeSummaryCard(
    episode: MonitorState.EpisodeSummary,
    onDismiss: () -> Unit,
) {
    var showSteps by remember(episode.startedAtMs) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            if (episode.closedByTimeout) "Last cry - closed at three minutes"
            else "Last cry"
        )
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatBlock("Ran for", elapsed(episode.durationSeconds))
                    StatBlock("Sounds tried", episode.soothersTried.toString())
                    StatBlock("Woke you", if (episode.wokeSomebody) "Yes" else "No")
                }

                episode.clipPath?.let { path ->
                    CryClipPlayer(
                        path = path,
                        envelope = episode.evidence?.envelope.orEmpty(),
                    )
                }

                episode.advice?.let { advice ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "REASON DETERMINED",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            advice.headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 24.sp,
                        )
                    }
                } ?: episode.evidence?.hypothesis?.let { h ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "REASON DETERMINED",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Probably ${h.label.replace('_', ' ')} - " +
                                "${(h.confidence * 100).toInt()}%, a guess from the sound.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                TextButton(onClick = { showSteps = !showSteps }) {
                    Text(
                        if (showSteps) "Hide the steps Nila took"
                        else "Show the ${episode.pipeline.count {
                            it.status == EpisodePipeline.Status.DONE
                        }} steps Nila took"
                    )
                }
                AnimatedVisibility(visible = showSteps) {
                    PipelineChart(
                        steps = episode.pipeline,
                        seconds = episode.durationSeconds,
                        showRail = false,
                    )
                }

                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Dismiss")
                }
            }
        }
        HedgeNote(
            "The clip is ${episode.clipSeconds.toInt()}s of audio in this app's " +
                "private storage. It is deleted after " +
                "${com.nila.audio.EpisodeRecorder.RETAIN_DAYS} days and is never uploaded."
        )
    }
}

// ------------------------------------------------------------ quick log

/**
 * Three big targets at thumb height.
 *
 * Every fact the assistant knows about this baby comes from here, and a log
 * that costs more than one tap at 3am is a log that stops being kept.
 */
@Composable
private fun QuickLog(
    care: List<com.nila.data.CareRecord>,
    onLog: (CareKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Quick log")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogButton("Fed", Icons.Outlined.LocalDrink,
                      lastOf(care, CareKind.FEED), Modifier.weight(1f)) {
                onLog(CareKind.FEED)
            }
            LogButton("Changed", Icons.Outlined.ChangeCircle,
                      lastOf(care, CareKind.DIAPER), Modifier.weight(1f)) {
                onLog(CareKind.DIAPER)
            }
            LogButton("Slept", Icons.Outlined.Bedtime,
                      lastOf(care, CareKind.SLEEP_START), Modifier.weight(1f)) {
                onLog(CareKind.SLEEP_START)
            }
        }
    }
}

@Composable
private fun LogButton(
    label: String,
    icon: ImageVector,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(74.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = null,
                 tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- today

/**
 * Crying measured against the colic duration criteria.
 *
 * Numbers only. What the three-hour line means, and why measuring it makes no
 * clinical claim, is explained once in Insights rather than on this screen
 * every night.
 */
@Composable
private fun Today(colic: AppState.ColicSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Crying today")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatBlock("Total", "${colic.todayMinutes} min")
                    StatBlock("Episodes", colic.todayEpisodes.toString())
                    StatBlock("Days over 3h", "${colic.daysOverThreeHoursThisWeek}/7")
                }
                if (colic.meetsDurationCriterion) {
                    HedgeNote(
                        "Over three hours on ${colic.daysOverThreeHoursThisWeek} " +
                            "days this week. Worth showing your doctor."
                    )
                }
            }
        }
    }
}

private fun lastOf(care: List<com.nila.data.CareRecord>, kind: CareKind): String {
    val record = care.firstOrNull { it.kind == kind.name } ?: return "not yet"
    val minutes = (System.currentTimeMillis() - record.atMs) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ${minutes % 60}m ago"
    }
}

/**
 * The status line, shortened for a card header.
 *
 * [MonitorState.statusLine] is written for a watch face and a notification,
 * where it is the only text there is. Here the duration is already the largest
 * thing on the card, so repeating it in the header wastes the one line that
 * could say what the app is *doing* instead.
 */
private fun headline(monitor: MonitorState): String = when (monitor.phase) {
    is MonitorState.Phase.CryDetected -> "Crying"
    is MonitorState.Phase.Settling -> "Trying to settle"
    is MonitorState.Phase.Verifying -> "Checking whether that helped"
    is MonitorState.Phase.Escalated -> "Your baby needs you"
    else -> monitor.statusLine
}

/** `1:42`, or `42s` below a minute. Cries are read in both units. */
private fun elapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s"
    else "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Seconds below a minute, then minutes, then hours.
 *
 * It used to start at minutes, so the whole of a twenty-second demo showed
 * "0m" and looked frozen -- the one number on screen that should have been
 * visibly counting.
 */
private fun uptime(sinceMs: Long): String {
    if (sinceMs <= 0) return "-"
    val ms = System.currentTimeMillis() - sinceMs
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return when {
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m ${s % 60}s"
        else -> "${s}s"
    }
}
