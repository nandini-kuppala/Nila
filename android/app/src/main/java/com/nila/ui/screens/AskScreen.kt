package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.assistant.Assistant
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.theme.SeverityUrgent
import com.nila.ui.theme.SeverityUrgentBg

// Deliberately mixed between the two people being cared for, because the app
// answers for both and nobody discovers that from an empty text box.
private val SUGGESTIONS = listOf(
    "How much crying is normal?",
    "What can I eat while breastfeeding?",
    "When did she last feed?",
    "Is my milk supply enough?",
    "When should I worry about a fever?",
    "I feel low since the birth",
)

@Composable
fun AskScreen(state: AppState) {
    // Held in the view model, not in the composable.
    //
    // A remembered local is discarded when the screen leaves the back stack,
    // while the answer lives in the view model and survives -- so coming back
    // to Ask showed an answer with an empty box above it, and no way to tell
    // what had been asked.
    val question by state.draftQuestion.collectAsState()
    val answer by state.answer.collectAsState()
    val thinking by state.thinking.collectAsState()
    val phrasing by state.phrasing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Ask",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "About your baby or about you. ${state.knowledgeSize} sourced " +
                "entries, all on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = question,
            onValueChange = state::setDraftQuestion,
            label = { Text("Your question") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Button(
            onClick = { state.ask(question) },
            enabled = question.isNotBlank() && !thinking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (thinking) "Looking..." else "Ask") }

        if (answer == null && !thinking) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Try one of these")
                SUGGESTIONS.forEach { suggestion ->
                    AssistChip(
                        onClick = {
                            state.setDraftQuestion(suggestion)
                            state.ask(suggestion)
                        },
                        label = { Text(suggestion) },
                    )
                }
            }
        }

        if (thinking) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Searching",
                     style = MaterialTheme.typography.bodyMedium)
            }
        }

        answer?.let { AnswerCard(it) }

        // The answer is already readable; this only says a shorter version is
        // on its way, so it sits under the answer rather than replacing it.
        if (phrasing) {
            Text(
                "Shortening...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnswerCard(answer: Assistant.Answer) {
    // An emergency answer must not look like every other answer. Same weight,
    // same card, same grey text is how someone skims past the one that mattered.
    if (answer.kind == Assistant.Answer.Kind.EMERGENCY) {
        Surface(
            color = SeverityUrgentBg,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("GET HELP NOW",
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Bold, color = SeverityUrgent)
                Text(answer.text,
                     style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(answer.text, style = MaterialTheme.typography.bodyLarge)

                // Facts about this baby, shown as the inputs the answer used.
                // The reasoning is inspectable rather than asserted.
                if (answer.context.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "USING",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        answer.context.forEach {
                            Text("- $it",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Every retrieved document carries its own caution, so an answer cannot
        // be assembled without the limit that belongs to it.
        answer.cautions.filter { it.isNotBlank() }.forEach { HedgeNote(it) }

        if (answer.sources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "SOURCES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                answer.sources.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
