package com.nila.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.audio.ModelCard
import com.nila.ui.AppState
import com.nila.ui.Insights
import com.nila.ui.components.Bar
import com.nila.ui.components.CalmChip
import com.nila.ui.components.DonutChart
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.HourProfile
import com.nila.ui.components.LineChart
import com.nila.ui.components.MetricTile
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.Point
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.StatBlock
import com.nila.ui.components.WeekBars
import com.nila.ui.components.Wedge
import com.nila.ui.components.humanMinutes
import com.nila.ui.components.percent
import com.nila.ui.theme.chartPalette
import java.time.format.TextStyle
import java.util.Locale

/**
 * The screen that argues with itself.
 *
 * Everywhere else the app makes claims: this is crying, this is probably belly
 * pain, this sound helped. Insights is where each of those is put next to the
 * measurement behind it, including the one measurement that came back saying
 * the feature does not work. Shipping a model card inside the product, with the
 * failure in it, is the whole posture of this project.
 *
 * Four questions, in the order somebody actually asks them:
 *
 * 1. **Is it getting worse?** A week of crying as a trend, and the hours it
 *    falls in.
 * 2. **Does the thing it claims to do actually happen?** The ladder's own
 *    outcomes -- how many cries never reached a person.
 * 3. **What settles this baby?** Measured by re-reading the loudness envelope
 *    after a sound, not by asking a parent to rate it.
 * 4. **How good are the models, and how hard is the phone working?** Both
 *    answered with numbers from this device, not from a datasheet.
 */
@Composable
fun LearnScreen(state: AppState) {
    val sootheStats by state.sootheStats.collectAsState()
    val insights by state.insights.collectAsState()
    val monitor by state.monitor.collectAsState()
    val selfTest by state.selfTest.collectAsState()
    val testing by state.selfTestRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.SemiBold)

        CryTrend(insights)
        LadderOutcomes(insights)
        WhatSettles(sootheStats)
        ModelQuality()
        OnThisPhone(monitor, selfTest, testing, onRunTest = state::runSelfTest)
    }
}

// ------------------------------------------------------------- the week

/** A week of crying as a trend, with the colic line and the hours it falls in. */
@Composable
private fun CryTrend(insights: Insights.Summary) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Crying this week")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        label = "This week",
                        value = humanMinutes(insights.weekCryMinutes),
                        caption = "${insights.days.sumOf { it.cryEpisodes }} cries",
                        accent = palette.cry,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "Days over 3h",
                        value = "${insights.daysOverThreeHours}/${Insights.DAYS}",
                        caption = if (insights.meetsDurationCriterion)
                            "meets the duration rule" else "below the duration rule",
                        accent = palette.cry,
                        modifier = Modifier.weight(1f),
                    )
                }

                LineChart(
                    points = insights.days.map {
                        Point(
                            label = it.date.dayOfWeek
                                .getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            value = it.cryMinutes / 60f,
                            readout = humanMinutes(it.cryMinutes),
                            title = if (it.isToday) "Today"
                                    else it.date.dayOfWeek
                                        .getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            emphasised = it.isToday,
                        )
                    },
                    color = palette.cry,
                    valueSuffix = "h",
                    threshold = 3f,
                    thresholdLabel = "three hours",
                    selected = picked,
                    onSelect = { picked = it },
                )

                Text(
                    "Averaging ${humanMinutes(insights.averageCryMinutes)} a day, " +
                        "with a worst day of ${humanMinutes(insights.peakCryMinutes)}. " +
                        "Touch the line to read a day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("WHEN IN THE DAY",
                         style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant)
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
        HedgeNote(
            "The colic definition is three or more hours of crying a day on three " +
                "or more days a week. Every part of that is a duration, which is why " +
                "this app can measure it -- and why the measurement is not a diagnosis."
        )
    }
}

// ---------------------------------------------------------- the argument

/**
 * The ladder's own scorecard.
 *
 * This is the one figure in the app that measures the product's central claim
 * rather than a model's accuracy: every other monitor ends at *notify*, and the
 * argument for this one is the two rungs before that. Either the cries are
 * being handled without a person or they are not, and this says which.
 */
@Composable
private fun LadderOutcomes(insights: Insights.Summary) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    val o = insights.outcomes
    if (o.total == 0) return
    val total = o.total.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("How the week's cries ended")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DonutChart(
                    wedges = listOf(
                        Wedge("Settled on her own", o.settledAlone.toFloat(), palette.sleep,
                              "${o.settledAlone} · ${percent(o.settledAlone.toFloat(), total)}%"),
                        Wedge("A sound settled her", o.settledBySound.toFloat(), palette.feed,
                              "${o.settledBySound} · ${percent(o.settledBySound.toFloat(), total)}%"),
                        Wedge("Nila woke you", o.wokeYou.toFloat(), palette.cry,
                              "${o.wokeYou} · ${percent(o.wokeYou.toFloat(), total)}%"),
                    ).filter { it.value > 0f },
                    centreValue = "${o.handledAlone}%",
                    centreLabel = "never\nreached you",
                    diameter = 132.dp,
                )
                Text(
                    "${o.settledAlone + o.settledBySound} of ${o.total} cries were over " +
                        "before anybody had to get up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
        HedgeNote(
            "\"Nila woke you\" is not a failure count. A cry that needed a parent and " +
                "got one is the system working. What this cannot count is the " +
                "opposite -- a cry that needed a parent and did not get one."
        )
    }
}

// ------------------------------------------------------------- soothers

/**
 * What actually settles this baby.
 *
 * Learned by playing a sound and then re-reading the cry's loudness envelope
 * thirty-five seconds later, not by asking a parent to rate it. The bar is the
 * smoothed score the picker uses; the fraction beside it is the raw count,
 * because a bar at 70% from two attempts and a bar at 70% from twenty are very
 * different facts and the bar alone cannot tell them apart.
 */
@Composable
private fun WhatSettles(stats: List<com.nila.actions.SootheMemory.Stats>) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("What settles your baby")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (stats.all { it.attempts == 0 }) {
                    Text(
                        "Nothing tried yet. When your baby cries, Nila plays a sound " +
                            "and then checks whether the crying actually settled. " +
                            "After a few nights this list means something.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    val best = stats.filter { it.attempts > 0 }.maxByOrNull { it.score }
                    stats.sortedByDescending { it.score }.forEach { stat ->
                        SootherBar(
                            name = readableSoother(stat.id),
                            stat = stat,
                            colour = if (stat.id == best?.id) palette.feed
                                     else palette.feed.copy(alpha = 0.55f),
                        )
                    }
                    best?.let {
                        Text(
                            "${readableSoother(it.id)} has the best record with this " +
                                "baby, so it is what Nila reaches for first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SootherBar(
    name: String,
    stat: com.nila.actions.SootheMemory.Stats,
    colour: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(colour))
                Text(name, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (stat.attempts == 0) "untried"
                else "${stat.successes} of ${stat.attempts}",
                style = MaterialTheme.typography.labelMedium
                    .copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(stat.score.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colour)
            )
        }
    }
}

// --------------------------------------------------------- the model card

/**
 * The two models, against the only line that matters: chance.
 *
 * Drawn as bars rather than printed as a table because the point is a
 * comparison, and a comparison drawn is a comparison nobody has to do in their
 * head. One bar is nearly at the top of the chart. The other is *below* the
 * dashed line. That picture is the argument this project is built around, and
 * it is more honest than any sentence we could write under it.
 */
@Composable
private fun ModelQuality() {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("How well this works")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WeekBars(
                    bars = listOf(
                        Bar("Detect", ModelCard.DETECT_SUBJECT_WISE_AUC,
                            "%.3f AUC".format(ModelCard.DETECT_SUBJECT_WISE_AUC), true),
                        Bar("Cause", ModelCard.REASON_SUBJECT_WISE_AUC,
                            "%.3f AUC".format(ModelCard.REASON_SUBJECT_WISE_AUC)),
                        Bar("Cause, leaky split", ModelCard.REASON_LEAKY_AUC,
                            "%.3f AUC".format(ModelCard.REASON_LEAKY_AUC)),
                        Bar("Forest, honest", ModelCard.RF_HONEST_AUC,
                            "%.3f AUC".format(ModelCard.RF_HONEST_AUC)),
                    ),
                    color = palette.cry,
                    height = 128.dp,
                    threshold = 0.5f,
                    thresholdLabel = "chance - 0.50 AUC",
                )
                Text(
                    "Area under the curve, on recordings of infants the models never " +
                        "heard during training. That is the only kind of test that " +
                        "predicts how they behave on your baby.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )

                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock("Detection recall",
                              "%.0f%%".format(ModelCard.DETECT_RECALL_CRY * 100))
                    StatBlock("Clips tested", "${ModelCard.DETECT_TEST_CLIPS}")
                    StatBlock("Card built", ModelCard.GENERATED_ON)
                }

                HedgeNote(
                    // Parenthesised deliberately: in Kotlin `.format()` binds
                    // to the last literal in a `+` chain, so without these the
                    // earlier specifiers render as literal "%.2f".
                    (
                        "We trained the cause model twice. Splitting the recordings " +
                            "randomly gave %.2f AUC. Splitting them so no infant " +
                            "appears on both sides gave %.2f -- below chance. The " +
                            "%.2f difference was the split, not the model, and an " +
                            "unrelated random forest lands in the same place. So " +
                            "Nila tells you your baby is crying, and does not " +
                            "pretend to know why."
                        ).format(
                        ModelCard.REASON_LEAKY_AUC,
                        ModelCard.REASON_SUBJECT_WISE_AUC,
                        ModelCard.LEAKAGE_AUC_INFLATION,
                    )
                )
            }
        }
    }
}

// -------------------------------------------------------- the silicon

/**
 * What the phone is actually doing, measured on the phone.
 *
 * Two numbers carry this card and neither is quotable from a spec sheet.
 *
 * **Where the graph runs.** The detector is offered to NNAPI first, then the
 * GPU, then the CPU, and each option is proved with a real inference before it
 * is accepted -- a delegate that constructs successfully can still fail at
 * first invoke, and 3am in a foreground service is the wrong time to find out.
 * The chip below is whichever one survived that, on this device.
 *
 * **How little of the night reaches it.** Below the silence gate a window is a
 * dBFS comparison and nothing else: no log-mel, no inference, no NPU. In a
 * quiet nursery that is almost all of them, and the ratio is why this can run
 * all night on a battery. It is counted rather than estimated.
 */
@Composable
private fun OnThisPhone(
    monitor: com.nila.monitor.MonitorState,
    selfTest: com.nila.audio.SelfTest.Result?,
    testing: Boolean,
    onRunTest: () -> Unit,
) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    val accelerator = monitor.accelerator.takeIf { it != "-" }
        ?: selfTest?.accelerator?.takeIf { it != "-" }
    val seen = monitor.windowsSeen
    val inferred = monitor.windowsInferred
    val dutyCycle = if (seen > 0) (inferred * 100f / seen) else null

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Running on this phone")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalmChip(accelerator?.let(::acceleratorLabel) ?: "Not measured yet")
                    CalmChip("On device")
                    CalmChip("0 network calls")
                }

                if (monitor.latencySamples > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DETECTOR LATENCY, THIS SESSION",
                             style = MaterialTheme.typography.labelMedium,
                             color = scheme.onSurfaceVariant)
                        WeekBars(
                            bars = listOf(
                                Bar("mean", monitor.detectorLatencyMs.toFloat(),
                                    "%.1f ms".format(monitor.detectorLatencyMs)),
                                Bar("p50", monitor.detectorP50Ms.toFloat(),
                                    "%.1f ms".format(monitor.detectorP50Ms)),
                                Bar("p95", monitor.detectorP95Ms.toFloat(),
                                    "%.1f ms".format(monitor.detectorP95Ms), true),
                            ),
                            color = palette.feed,
                            height = 96.dp,
                        )
                        Text(
                            "Over ${monitor.latencySamples} inferences. One analysis " +
                                "window arrives every 480 ms, so a p95 of " +
                                "%.1f ms leaves the detector idle %.1f%% of the time."
                                    .format(
                                        monitor.detectorP95Ms,
                                        100.0 - (monitor.detectorP95Ms / 480.0 * 100.0),
                                    ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                    }
                }

                if (dutyCycle != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("HOW MUCH OF THE ROOM REACHES THE DETECTOR",
                             style = MaterialTheme.typography.labelMedium,
                             color = scheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricTile(
                                label = "Windows analysed",
                                value = "%.0f%%".format(dutyCycle),
                                caption = "$inferred of $seen",
                                accent = palette.feed,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Skipped at the gate",
                                value = "%.0f%%".format(100f - dutyCycle),
                                caption = "a dBFS compare, no inference",
                                accent = palette.sleep,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                selfTest?.let {
                    Text(
                        it.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }

                OutlinedButton(
                    onClick = onRunTest,
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (testing) "Measuring..."
                        else "Run the detector on this phone"
                    )
                }

                if (monitor.latencySamples == 0 && dutyCycle == null) {
                    Text(
                        "Start monitoring, or run the check above, and this card " +
                            "fills in with numbers measured on this device rather " +
                            "than quoted from a datasheet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        HedgeNote(
            "Nothing on this screen was measured anywhere but here. The latency is " +
                "this phone's, the accelerator is whichever one accepted the graph " +
                "on this phone, and the model scores are our own evaluation shipped " +
                "inside the app so they cannot drift from what we claim."
        )
    }
}

/** `NNAPI` is a framework; what a reader wants to know is which silicon. */
private fun acceleratorLabel(name: String): String = when (name) {
    "NNAPI" -> "NNAPI - NPU or DSP"
    "GPU" -> "GPU delegate"
    "CPU" -> "CPU, XNNPACK"
    else -> name
}

private fun readableSoother(id: String) = when (id) {
    "white_noise" -> "White noise"
    "shush" -> "Shushing"
    "heartbeat" -> "Heartbeat"
    else -> "Your voice"
}
