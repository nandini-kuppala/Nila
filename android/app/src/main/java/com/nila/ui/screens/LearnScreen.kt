package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.audio.ModelCard
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.StatBlock

@Composable
fun LearnScreen(state: AppState) {
    val sootheStats by state.sootheStats.collectAsState()
    val colic by state.colic.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.SemiBold)

        // What actually settles this baby -- learned by playing a sound and then
        // re-reading the cry envelope, not by asking the parent to rate it.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("What settles your baby")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (sootheStats.all { it.attempts == 0 }) {
                        Text(
                            "Nothing tried yet. When your baby cries, Nila plays a " +
                                "sound and then checks whether the crying actually " +
                                "settled. After a few nights this list means something.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sootheStats.forEach { stat ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(readableSoother(stat.id),
                                         style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (stat.attempts == 0) "untried"
                                        else "${stat.successes}/${stat.attempts}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { stat.score.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Crying this week")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("Today", "${colic.todayMinutes}m")
                        StatBlock("This week", "${colic.weekSeconds / 3600}h")
                        StatBlock("Days over 3h",
                                  "${colic.daysOverThreeHoursThisWeek}/7")
                    }
                    Text(
                        "The colic definition is three or more hours of crying a day on " +
                            "three or more days a week. That is purely a duration, which " +
                            "is why this app can measure it -- and why the measurement is " +
                            "not a diagnosis.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The model card. Shipping the reliability of our own models inside the
        // product, including the part that did not work, is the point.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("How well this works")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Cry detection",
                         style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("AUC", "%.3f".format(ModelCard.DETECT_SUBJECT_WISE_AUC))
                        StatBlock("Recall", "%.0f%%"
                            .format(ModelCard.DETECT_RECALL_CRY * 100))
                        StatBlock("Tested on", "${ModelCard.DETECT_TEST_CLIPS}")
                    }
                    Text(
                        "Measured on recordings of infants the model never heard during " +
                            "training. That is the only kind of test that predicts how it " +
                            "behaves on your baby.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text("Cause of the cry",
                         style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("AUC", "%.3f".format(ModelCard.REASON_SUBJECT_WISE_AUC))
                        StatBlock("Chance", "0.500")
                        StatBlock("Same model, leaky split",
                                  "%.3f".format(ModelCard.REASON_LEAKY_AUC))
                    }
                    HedgeNote(
                        // Parenthesised deliberately: in Kotlin `.format()` binds
                        // to the last literal in a `+` chain, so without these
                        // the earlier specifiers render as literal "%.2f".
                        (
                            "We trained the same model twice. Splitting the " +
                                "recordings randomly gave %.2f AUC. Splitting them " +
                                "so no infant appears on both sides gave %.2f -- " +
                                "chance. The %.2f difference was the split, not the " +
                                "model. So Nila tells you your baby is crying, and " +
                                "does not pretend to know why."
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
}

private fun readableSoother(id: String) = when (id) {
    "white_noise" -> "White noise"
    "shush" -> "Shushing"
    "heartbeat" -> "Heartbeat"
    else -> "Your voice"
}
