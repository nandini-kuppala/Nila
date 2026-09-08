package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.data.CareKind
import com.nila.monitor.MonitorState
import com.nila.ui.AppState
import com.nila.ui.components.CalmChip
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.InputLevelMeter
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.PulseDot
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.StatBlock
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
        verticalArrangement = Arrangement.spacedBy(18.dp),
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

        monitor.currentEvidence?.let { CurrentCry(it, monitor.actions) }

        TryItRow(state, monitor)

        QuickLog(care = care, onLog = state::logCare)

        Today(colic)
    }
}

// --------------------------------------------------------------- status

@Composable
private fun StatusCard(
    monitor: MonitorState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCloseDemo: () -> Unit,
) {
    val urgent = monitor.phase is MonitorState.Phase.Escalated ||
        monitor.phase is MonitorState.Phase.Safety

    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulseDot(active = monitor.running)
                Text(
                    text = monitor.statusLine,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (urgent) SeverityUrgent
                            else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (monitor.running || monitor.demoComplete) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    StatBlock(
                        if (monitor.demoComplete) "Demo ran for" else "Listening for",
                        uptime(monitor.armedSinceMs),
                    )
                    StatBlock("Events", monitor.eventsTonight.toString())
                    StatBlock("Cry score", "${(monitor.lastCryProbability * 100).toInt()}%")
                }

                // The live signal. A bar that moves when you clap is the only
                // thing that distinguishes "listening" from a dead microphone.
                if (!monitor.simulated && !monitor.demoComplete) {
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

@Composable
private fun CurrentCry(
    evidence: com.nila.audio.CryEvidence,
    actions: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("This cry")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatBlock("Duration", "${evidence.durationSeconds}s")
                    StatBlock(
                        "Trend",
                        evidence.trend.name.lowercase()
                            .replaceFirstChar { it.uppercase() },
                    )
                    StatBlock(
                        "Confidence",
                        "${(evidence.detectorConfidence * 100).toInt()}%",
                    )
                }
                IntensityCurve(points = evidence.envelope)

                // Why, then what about it. The cause is a guess and is labelled
                // as one; the actions are facts.
                evidence.hypothesis?.let { h ->
                    Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "PROBABLY",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            buildString {
                                append(h.label.replace('_', ' ')
                                    .replaceFirstChar { it.uppercase() })
                                append("  ")
                                append((h.confidence * 100).toInt())
                                append("%")
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (h.ambiguous && h.runnerUp != null) {
                                "Close to ${h.runnerUp.replace('_', ' ')}. A best " +
                                    "guess from the sound, not a diagnosis."
                            } else {
                                "A best guess from the sound, not a diagnosis."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (actions.isNotEmpty()) {
                    Divider()
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "WHAT NILA DID",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        actions.forEach { action ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "\u2192",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    action,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
