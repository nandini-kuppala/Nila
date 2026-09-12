package com.nila.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChangeCircle
import androidx.compose.material.icons.outlined.LightMode
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.data.CareKind
import com.nila.data.Severity
import com.nila.monitor.EpisodePipeline
import com.nila.monitor.MonitorState
import com.nila.ui.AppState
import com.nila.ui.Insights
import com.nila.ui.components.Bar
import com.nila.ui.components.CalmChip
import com.nila.ui.components.CryClipPlayer
import com.nila.ui.components.DonutChart
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.HourProfile
import com.nila.ui.components.InputLevelMeter
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.MetricTile
import com.nila.ui.components.LineChart
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.PipelineChart
import com.nila.ui.components.Point
import com.nila.ui.components.PulseDot
import com.nila.ui.components.ReasonVerdict
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.StatBlock
import com.nila.ui.components.VoiceNoteBar
import com.nila.ui.components.WarningBox
import com.nila.ui.components.WeekBars
import com.nila.ui.components.Wedge
import com.nila.ui.components.humanMinutes
import com.nila.ui.components.percent
import com.nila.ui.theme.ChartPalette
import com.nila.ui.theme.SeverityUrgent
import com.nila.ui.theme.chartPalette
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The night screen, and the morning-after screen.
 *
 * It does two jobs and it does them in that order, which is the whole design.
 *
 * **Above the fold it is a monitor**, written to be read at 3am by someone
 * holding a baby: numbers and controls, no prose. During a cry it runs answer,
 * then instruction, then mechanism -- how long and how loud, then the cause,
 * then what to try, and only then the ladder showing how the app got there. A
 * parent who reads nothing but the first card still has the useful half.
 *
 * **Below it is a dashboard**, and that is a different reader. Nobody studies a
 * pie chart while a baby is screaming; they look at it at ten the next morning
 * with a coffee, asking a question the monitor cannot answer -- *is this
 * getting worse, and is there a pattern in it.* The app already had every row
 * needed to answer that and showed none of it, because a timeline of events is
 * a record, not an answer. Seven days of crying against the three-hour line, a
 * night laid over the feeds that punctuated it, and the share of cries the
 * model would not even name: those are answers, and the last of them is an
 * answer about the app's own limits.
 *
 * Nothing below the fold interprets. Every chart has its own figures written
 * out beneath it in words, because a wedge is not a number and this reader is
 * short of sleep.
 */
@Composable
fun HomeScreen(state: AppState) {
    val monitor by state.monitor.collectAsState()
    val care by state.care.collectAsState()
    val insights by state.insights.collectAsState()
    val asleepSince by state.asleepSinceMs.collectAsState()
    val baby by state.baby.collectAsState()

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

        QuickLog(
            care = care,
            asleepSinceMs = asleepSince,
            onLog = state::logCare,
            onToggleSleep = state::toggleSleep,
        )

        Dashboard(
            insights = insights,
            care = care,
            babyName = baby?.name.orEmpty(),
        )
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
                    "A recording, played aloud through the real detector at its " +
                        "own speed. Nothing is sped up, so the clock on screen " +
                        "is the clock in the room. Three minutes for the full " +
                        "ladder; you are woken at ninety seconds.",
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
 * Three targets at thumb height, and a sleep clock.
 *
 * Every fact the assistant knows about this baby comes from here, and a log
 * that costs more than one tap at 3am is a log that stops being kept.
 *
 * Sleep is the one of the three that is not an instant. A feed and a nappy are
 * things that happened; a sleep is a thing that is *still happening*, and
 * logging it as a timestamp -- which is all this did, one `SLEEP_START` row per
 * tap and no end, ever -- produced a log from which no duration could be
 * recovered. There was no way to chart a night, and the assistant's "Woke"
 * field had nothing behind it.
 *
 * So the sleep button is a stopwatch with two states, not a button. Tap to
 * start, tap to stop, and while it runs it shows the time on its face. Still
 * one tap either way, which was the constraint that produced the write-only
 * version in the first place.
 */
@Composable
private fun QuickLog(
    care: List<com.nila.data.CareRecord>,
    asleepSinceMs: Long?,
    onLog: (CareKind) -> Unit,
    onToggleSleep: () -> Unit,
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
            SleepButton(
                asleepSinceMs = asleepSinceMs,
                lastStart = lastOf(care, CareKind.SLEEP_START),
                modifier = Modifier.weight(1f),
                onClick = onToggleSleep,
            )
        }
        if (asleepSinceMs != null) {
            SleepTrackerNote(asleepSinceMs)
        }
    }
}

/**
 * The sleep stopwatch.
 *
 * One word on the face, because it has a third of the row's width: "Sleep"
 * when awake, "Awake" when asleep -- each is the *action*, matching the two
 * buttons beside it, which are also actions. The earlier attempt labelled the
 * state instead ("Asleep") with the elapsed time beneath, and a third of a
 * 360dp screen wrapped "asleep 1h 12m" into an ellipsis. The running clock is
 * the subtitle and nothing else shares the line with it.
 */
@Composable
private fun SleepButton(
    asleepSinceMs: Long?,
    lastStart: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val elapsed = rememberElapsed(asleepSinceMs)
    LogButton(
        label = if (asleepSinceMs != null) "Awake" else "Sleep",
        icon = if (asleepSinceMs != null) Icons.Outlined.LightMode
               else Icons.Outlined.Bedtime,
        subtitle = elapsed ?: lastStart,
        modifier = modifier,
        emphasised = asleepSinceMs != null,
        onClick = onClick,
    )
}

/** The line under the row while a sleep is running. Says what to tap, and when it began. */
@Composable
private fun SleepTrackerNote(sinceMs: Long) {
    val scheme = MaterialTheme.colorScheme
    val started = remember(sinceMs) {
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(java.util.Date(sinceMs))
    }
    val elapsed = rememberElapsed(sinceMs) ?: "just now"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            Icons.Outlined.Bedtime, contentDescription = null,
            tint = scheme.onSecondaryContainer,
            modifier = Modifier.size(17.dp),
        )
        Text(
            "Asleep since $started - $elapsed. Tap Awake when she wakes.",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSecondaryContainer,
        )
    }
}

/**
 * A clock that ticks while a sleep is running, and costs nothing when it is not.
 *
 * Thirty seconds rather than one: the face reads in minutes, so a per-second
 * tick would recompose sixty times to change nothing fifty-nine of them, on a
 * screen that is often left open all night.
 */
@Composable
private fun rememberElapsed(sinceMs: Long?): String? {
    if (sinceMs == null) return null
    val elapsed by androidx.compose.runtime.produceState(
        initialValue = System.currentTimeMillis() - sinceMs,
        key1 = sinceMs,
    ) {
        while (true) {
            value = System.currentTimeMillis() - sinceMs
            kotlinx.coroutines.delay(30_000)
        }
    }
    val minutes = (elapsed / 60_000L).toInt()
    return when {
        minutes < 1 -> "just started"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

@Composable
private fun LogButton(
    label: String,
    icon: ImageVector,
    subtitle: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(74.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (emphasised) MaterialTheme.colorScheme.secondaryContainer
                             else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = null,
                 tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                 maxLines = 1)
        }
    }
}

// ------------------------------------------------------------- dashboard

/**
 * The week, for the reader who is no longer in the middle of a cry.
 *
 * Four figures, then three charts, in the order the questions get asked: what
 * happened today, how today compares with the week, when in the day it happens,
 * and -- last, because it is the least trustworthy thing on the screen -- what
 * the sound suggested it was about.
 */
@Composable
private fun Dashboard(
    insights: Insights.Summary,
    care: List<com.nila.data.CareRecord>,
    babyName: String,
) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (babyName.isBlank()) "The last seven days"
                else "$babyName's last seven days",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Built from the cries Nila detected and the care you logged. " +
                    "Nothing here left the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        TodayTiles(insights, care, palette)

        CryTrendCard(insights, palette)

        SleepCard(insights, palette)

        CauseCard(insights, palette)
    }
}

/** Today in four numbers, each in the colour of the chart it belongs to. */
@Composable
private fun TodayTiles(
    insights: Insights.Summary,
    care: List<com.nila.data.CareRecord>,
    palette: ChartPalette,
) {
    // Every figure on the dashboard comes from one place. The colic summary is
    // the same crying counted a different way -- rolling twenty-four hour
    // windows rather than calendar days -- and mixing the two on one screen is
    // how a tile ends up disagreeing with the chart directly beneath it.
    val today = insights.today
    val cryMinutes = today?.cryMinutes ?: 0
    val episodes = today?.cryEpisodes ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "Crying today",
                value = humanMinutes(cryMinutes),
                caption = when (episodes) {
                    0 -> "no episodes yet"
                    1 -> "1 episode"
                    else -> "$episodes episodes"
                },
                accent = palette.cry,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Slept today",
                value = today?.sleepMinutes?.takeIf { it > 0 }
                    ?.let { humanMinutes(it) } ?: "-",
                caption = if ((today?.sleepMinutes ?: 0) > 0) "from your sleep log"
                          else "tap Slept to start one",
                accent = palette.sleep,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "Feeds today",
                value = (today?.feeds ?: 0).toString(),
                caption = "last ${lastOf(care, CareKind.FEED)}",
                accent = palette.feed,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Days over 3h",
                value = "${insights.daysOverThreeHours}/${Insights.DAYS}",
                caption = if (insights.meetsDurationCriterion) "worth a doctor's eye"
                          else "the colic duration rule",
                accent = palette.cry,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ----------------------------------------------------------- cry trend

/**
 * Seven days of crying against the three-hour line.
 *
 * The line is the Wessel duration criterion and nothing else. It is a count of
 * hours, which is why the app is allowed to draw it: measuring a duration
 * against a published duration makes no clinical claim. What it means, and why
 * crossing it three times is not a diagnosis, is in Insights.
 */
@Composable
private fun CryTrendCard(
    insights: Insights.Summary,
    palette: ChartPalette,
) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Crying, day by day")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WeekBars(
                    bars = insights.days.map {
                        Bar(
                            label = it.date.dayOfWeek
                                .getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            value = it.cryMinutes.toFloat(),
                            readout = humanMinutes(it.cryMinutes),
                            emphasised = it.isToday,
                        )
                    },
                    color = palette.cry,
                    threshold = 180f,
                    thresholdLabel = "three hours - the colic duration line",
                    selected = picked,
                    onSelect = { picked = if (picked == it) null else it },
                )

                val day = picked?.let(insights.days::getOrNull)
                Text(
                    text = day?.let {
                        "${dayTitle(it)}: ${humanMinutes(it.cryMinutes)} over " +
                            "${it.cryEpisodes} ${if (it.cryEpisodes == 1) "episode" else "episodes"}."
                    } ?: ("Averaging ${humanMinutes(insights.averageCryMinutes)} a day, " +
                        "with a worst day of ${humanMinutes(insights.peakCryMinutes)}. " +
                        "Tap a bar for that day."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "WHEN IN THE DAY",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    HourProfile(insights.hourlyCryMinutes, palette.cry)
                    Text(
                        insights.pattern
                            ?: "No time of day stands out yet - the crying is spread " +
                                "fairly evenly, or there is not enough of it to tell.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            }
        }
        if (insights.meetsDurationCriterion) {
            HedgeNote(
                "Over three hours on ${insights.daysOverThreeHours} days this " +
                    "week. That is the duration part of the colic definition and " +
                    "nothing more - it is worth showing your doctor, not a diagnosis."
            )
        }
    }
}

// --------------------------------------------------------------- sleep

/**
 * Seven days of sleep, as a line through seven points.
 *
 * A line rather than the bars used for crying, because the two charts are
 * asked different questions. Crying is measured against a fixed rule -- three
 * hours -- so its chart has to support *counting* the days that crossed it,
 * which is what columns against a threshold do. Sleep has no threshold worth
 * drawing for a four-month-old, and the only question a parent actually has
 * about it is whether it is getting better or worse. That is a slope.
 *
 * A day with nothing logged is a hole in the line, not a zero. "We forgot to
 * tap" and "she did not sleep" are wildly different facts and this chart will
 * not print one as the other.
 */
@Composable
private fun SleepCard(insights: Insights.Summary, palette: ChartPalette) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Sleep, day by day")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            if (insights.daysWithSleepLogged == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "No sleep tracked yet.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Tap Sleep when she goes down and Awake when she wakes. " +
                            "Two taps a nap is all this chart needs, and Nila will " +
                            "not guess at the ones you miss.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LineChart(
                        points = insights.days.map {
                            Point(
                                label = it.date.dayOfWeek
                                    .getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                value = it.sleepMinutes.takeIf { m -> m > 0 }
                                    ?.let { m -> m / 60f },
                                readout = humanMinutes(it.sleepMinutes),
                                title = dayTitle(it),
                                emphasised = it.isToday,
                            )
                        },
                        color = palette.sleep,
                        valueSuffix = "h",
                        selected = picked,
                        onSelect = { picked = it },
                    )
                    val day = picked?.let(insights.days::getOrNull)
                    Text(
                        text = day?.let {
                            if (it.sleepMinutes == 0) "${dayTitle(it)}: nothing tracked."
                            else "${dayTitle(it)}: ${humanMinutes(it.sleepMinutes)} over " +
                                "${it.sleepSessions} " +
                                (if (it.sleepSessions == 1) "stretch." else "stretches.")
                        } ?: (insights.averageSleepMinutes?.let {
                            "Averaging ${humanMinutes(it)} a day across the " +
                                "${insights.daysWithSleepLogged} days you tracked. " +
                                "Touch the line to read a day."
                        } ?: "Touch the line to read a day."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                    if (insights.daysWithSleepLogged < Insights.DAYS) {
                        Text(
                            "A gap in the line is a day with nothing tracked, " +
                                "not a day without sleep.",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- causes

/**
 * What the sound suggested, and how often it suggested nothing.
 *
 * This is the one chart on the screen that is drawn from a model the project
 * says out loud does not work: the reason head scores 0.444 macro AUC across
 * unseen infants, which is below chance. It is here anyway, because a parent
 * who has been shown "probably belly pain" on nine separate nights deserves to
 * see that the guess was "belly pain" on nine separate nights -- a bias that is
 * invisible one cry at a time and obvious in a ring.
 *
 * The wedge for the episodes the model would not name is the point of the
 * figure as much as the named ones are, which is why it is never dropped.
 */
@Composable
private fun CauseCard(insights: Insights.Summary, palette: ChartPalette) {
    val scheme = MaterialTheme.colorScheme
    if (!insights.hasCauses) return
    val total = insights.causes.sumOf { it.episodes }.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("What the sound suggested")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DonutChart(
                    wedges = insights.causes.mapIndexed { i, cause ->
                        Wedge(
                            label = cause.label,
                            value = cause.episodes.toFloat(),
                            // The unnamed bucket is always the last colour, a
                            // deliberate grey, whatever rank it came out at.
                            color = if (cause.key.isEmpty()) palette.causes.last()
                                    else palette.cause(i),
                            readout = "${cause.episodes} · " +
                                "${percent(cause.episodes.toFloat(), total)}%",
                        )
                    },
                    centreValue = total.toInt().toString(),
                    centreLabel = "cries\nthis week",
                )
                Text(
                    insights.causes.firstOrNull()?.let {
                        "Most often ${it.label.lowercase()} - " +
                            "${it.episodes} of ${total.toInt()} cries."
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
        HedgeNote(
            "These are the detector's guesses from the sound, not findings. The " +
                "cause model scores below chance on infants it has never heard, " +
                "so read this as what Nila kept guessing, not as what was wrong."
        )
    }
}

// ---------------------------------------------------------------- helpers

private fun dayTitle(day: Insights.Day): String =
    if (day.isToday) "Today"
    else day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

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
