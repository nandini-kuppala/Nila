package com.nila.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.assistant.agents.AgentStep
import com.nila.assistant.agents.MedicineReviewResult
import com.nila.assistant.agents.Verdict
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.theme.SeverityAttention
import com.nila.ui.theme.SeverityAttentionBg
import com.nila.ui.theme.SeverityCalm
import com.nila.ui.theme.SeverityCalmBg
import com.nila.ui.theme.SeverityUrgent
import com.nila.ui.theme.SeverityUrgentBg

/**
 * Photograph, upload or type a medicine, and watch four specialists check it.
 *
 * The pipeline is shown rather than hidden behind a spinner. Each stage names
 * what it looked at, what it concluded, and what it could not determine -- so a
 * "likely safe" that was reached without knowing her allergies looks visibly
 * different from one that cross-checked three conditions and found nothing.
 */
@Composable
fun ScanScreen(state: AppState) {
    // Build the OCR sessions while the user is still deciding which button to
    // press, rather than on the shutter.
    androidx.compose.runtime.LaunchedEffect(Unit) { state.prepareScanner() }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var typed by remember { mutableStateOf("") }
    val review by state.review.collectAsState()
    val stage by state.scanStage.collectAsState()
    val scannedText by state.scannedText.collectAsState()
    val thinking by state.thinking.collectAsState()
    val mother by state.mother.collectAsState()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            photo = bitmap
            state.reviewMedicine(bitmap = bitmap)
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photo = state.recordStore.decodeUri(uri)
            state.reviewMedicine(uri = uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Check a medicine", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.SemiBold)
        Text(
            "For a breastfeeding parent taking it. Checked against your own " +
                "health record, on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (mother?.isComplete != true) {
            HedgeNote(
                "Add your conditions and allergies under Health to make this " +
                    "a check about you."
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { takePicture.launch(null) },
                   modifier = Modifier.weight(1f)) { Text("Take a photo") }
            OutlinedButton(
                onClick = {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Upload a picture") }
        }

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("...or type the name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = { photo = null; state.reviewMedicine(typed = typed) },
                enabled = typed.isNotBlank() && !thinking,
            ) { Text("Check") }
        }

        photo?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "The medicine you photographed",
                modifier = Modifier.fillMaxWidth().height(180.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }

        stage?.let {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        review?.let { result ->
            VerdictCard(result)
            AgentTrace(result.steps)

            scannedText?.takeIf { it.isNotBlank() }?.let { text ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("What the camera read")
                    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                        Text(text.take(240),
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            result.cautions.forEach { HedgeNote(it) }
            result.sources.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedButton(
                onClick = { photo = null; typed = ""; state.clearReview() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check another") }
        }

        if (review == null && stage == null) {
            HedgeNote(
                "Never about giving medicine to a baby, and never a dose."
            )
        }
    }
}

@Composable
private fun VerdictCard(result: MedicineReviewResult) {
    val (fg, bg) = when (result.verdict) {
        Verdict.LIKELY_SAFE -> SeverityCalm to SeverityCalmBg
        Verdict.CAUTION -> SeverityAttention to SeverityAttentionBg
        Verdict.AVOID -> SeverityUrgent to SeverityUrgentBg
        Verdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to
            MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.verdict.label.uppercase(),
                 style = MaterialTheme.typography.labelMedium,
                 fontWeight = FontWeight.Bold, color = fg)
            Text(result.headline, style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.onSurface)
            Text(result.summary, style = MaterialTheme.typography.bodyLarge,
                 color = MaterialTheme.colorScheme.onSurface)
            if (result.summaryFromLlm) {
                Text("Summarised on this phone by the local model",
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The chain of specialists, each with its own finding and its own blind spot. */
@Composable
private fun AgentTrace(steps: List<AgentStep>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("How this was checked")
        steps.forEachIndexed { i, step ->
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(22.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}",
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(step.agent, style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                        ConfidenceDot(step.confidence)
                    }
                    Text("Looked at: ${step.looked_at}",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(step.finding, style = MaterialTheme.typography.bodyLarge)
                    if (step.flags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            step.flags.take(3).forEach { flag ->
                                Surface(
                                    color = SeverityAttentionBg,
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(flag, color = SeverityAttention,
                                         style = MaterialTheme.typography.labelMedium,
                                         modifier = Modifier.padding(
                                             horizontal = 7.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }
                    step.couldNotCheck?.let {
                        Text("Could not check: $it",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceDot(confidence: AgentStep.Confidence) {
    val (label, color) = when (confidence) {
        AgentStep.Confidence.HIGH -> "confident" to SeverityCalm
        AgentStep.Confidence.MEDIUM -> "partial" to SeverityAttention
        AgentStep.Confidence.LOW -> "weak" to SeverityAttention
        AgentStep.Confidence.NONE -> "no data" to MaterialTheme.colorScheme.outline
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}
