package com.nila.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.nila.assistant.agents.AgentStep
import com.nila.assistant.agents.MedicineReviewResult
import com.nila.assistant.agents.Verdict
import com.nila.data.MedicineScanRecord
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.theme.SeverityColors
import com.nila.ui.theme.calmColors
import com.nila.ui.theme.severityColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    LaunchedEffect(Unit) { state.prepareScanner() }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var typed by remember { mutableStateOf("") }
    val review by state.review.collectAsState()
    val stage by state.scanStage.collectAsState()
    val scannedText by state.scannedText.collectAsState()
    val thinking by state.thinking.collectAsState()
    val mother by state.mother.collectAsState()
    val history by state.scanHistory.collectAsState()

    /**
     * Leaving the screen puts it back to a blank form.
     *
     * The verdict used to persist in the view model, so coming back to Scan
     * showed the last strip somebody had checked, still sitting under the
     * buttons. That is actively misleading on this screen in particular: the
     * next person to open it is usually about to check a *different* medicine,
     * and a stale AVOID card above a fresh photograph is the worst possible
     * arrangement of those two facts. The scan is in the history below either
     * way, which is where a previous answer belongs.
     */
    DisposableEffect(Unit) {
        onDispose { state.clearReview() }
    }

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

        // ---- the form, hidden once there is an answer
        //
        // Two buttons and a text field above a verdict is an invitation to
        // start a second check while reading the first one. Once an answer
        // exists the only control is "check another", which puts the form back.
        if (review == null) {
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
        }

        photo?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "The medicine you photographed",
                modifier = Modifier.fillMaxWidth().height(160.dp)
                    .clip(RoundedCornerShape(12.dp)),
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

            OutlinedButton(
                onClick = { photo = null; typed = ""; state.clearReview() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check another") }

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
        }

        if (review == null && stage == null) {
            HedgeNote(
                "Never about giving medicine to a baby, and never a dose."
            )
        }

        if (history.isNotEmpty()) {
            ScanHistory(history, onDelete = state::deleteScan)
        }
    }
}

// ---------------------------------------------------------------- verdict

/**
 * The answer, on a ground that carries its own text colour.
 *
 * This is the card that was unreadable. Its background was a hardcoded pale
 * green, amber or pink, and its headline and summary were
 * `MaterialTheme.colorScheme.onSurface` -- which the dark scheme resolves to a
 * near-white. On a phone in dark mode the verdict a parent had photographed a
 * strip to get was white text on pale green: rendered, correct and invisible.
 *
 * The fix is structural rather than a colour tweak. [severityColors] hands back
 * the ground and the text colour that belongs on it as one object, for the
 * theme in force, so a caller cannot take one without the other.
 */
@Composable
private fun VerdictCard(result: MedicineReviewResult) {
    val colors: SeverityColors = when (result.verdict) {
        Verdict.LIKELY_SAFE -> calmColors()
        Verdict.CAUTION -> severityColors(Severity.ATTENTION)
        Verdict.AVOID -> severityColors(Severity.URGENT)
        Verdict.UNKNOWN -> severityColors(Severity.NOTE)
    }
    Surface(
        color = colors.container,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                result.verdict.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = colors.accent,
            )
            // The headline leads, because it is the sentence somebody reads at
            // a pharmacy counter. The summary explains it underneath.
            Text(
                result.headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
                color = colors.onContainer,
            )
            // The summary repeats the headline as its first clause, which read
            // as a stutter on a card where both were the same size. Dropping
            // the duplicated opening leaves the part that adds something.
            summaryTail(result)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onContainer,
                )
            }
            result.cautions.filter { it.isNotBlank() }.forEach { caution ->
                Text(
                    caution,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onContainerMuted,
                )
            }
            if (result.sources.isNotEmpty()) {
                Text(
                    result.sources.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onContainerMuted,
                )
            }
        }
    }
}

/**
 * The summary minus its duplicated first sentence.
 *
 * `summary` is built to stand alone in a notification, so it opens by restating
 * the headline. On the card the headline is already the largest thing above it,
 * and printing it twice in two sizes is what made this card look padded.
 */
private fun summaryTail(result: MedicineReviewResult): String? {
    val summary = result.summary.trim()
    if (summary.isEmpty()) return null
    val headline = result.headline.trim().trimEnd('.')
    val tail = summary
        .removePrefix(headline)
        .removePrefix(".")
        .trim()
    // A summary that was *only* the headline leaves nothing, and an empty
    // paragraph under a title is worse than no paragraph.
    return tail.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------- history

/**
 * Previous checks, collapsed.
 *
 * One row per medicine, showing the two things that identify it from across the
 * room -- the name and the verdict -- and nothing else until it is tapped.
 * Expanded, it shows the strip that was photographed and what the app decided,
 * which is what somebody comes back to a history for.
 */
@Composable
private fun ScanHistory(
    scans: List<MedicineScanRecord>,
    onDelete: (MedicineScanRecord) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("History")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            scans.forEach { scan ->
                ScanHistoryRow(scan, onDelete = { onDelete(scan) })
            }
        }
        Text(
            "Kept on this phone only.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanHistoryRow(
    scan: MedicineScanRecord,
    onDelete: () -> Unit,
) {
    var expanded by remember(scan.id) { mutableStateOf(false) }
    val verdict = runCatching { Verdict.valueOf(scan.verdict) }
        .getOrDefault(Verdict.UNKNOWN)
    val colors = when (verdict) {
        Verdict.LIKELY_SAFE -> calmColors()
        Verdict.CAUTION -> severityColors(Severity.ATTENTION)
        Verdict.AVOID -> severityColors(Severity.URGENT)
        Verdict.UNKNOWN -> severityColors(Severity.NOTE)
    }

    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
        Column {
            // ---- the collapsed row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A colour bar rather than a coloured row. The verdict has to
                // be readable at a glance without the row itself shouting,
                // because a list where every entry is a coloured block is a
                // list where none of them stand out.
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        scan.name.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            verdict.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                        )
                        Text(
                            "·",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            when {
                                scan.imagePath != null -> "photographed"
                                else -> "typed"
                            } + " " + relativeDay(scan.atMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess
                                  else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- the detail
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    scan.imagePath?.let { path ->
                        StripThumbnail(path)
                    }
                    Surface(
                        color = colors.container,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                scan.headline,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onContainer,
                            )
                            Text(
                                scan.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onContainer,
                            )
                            scan.cautionList().forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onContainerMuted,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            fullTimestamp(scan.atMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete this scan",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The photographed strip, decoded only while the row is open.
 *
 * Twenty rows each holding a decoded bitmap is tens of megabytes for a list
 * where nineteen of them are not being looked at, so the file is read when the
 * row expands and dropped when it closes.
 */
@Composable
private fun StripThumbnail(path: String) {
    val bitmap = remember(path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
    }
    if (bitmap == null) {
        Text(
            "The photo for this scan is no longer on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "The medicine strip that was checked",
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Fit,
    )
}

/** "today", "yesterday", then a date. What a person actually needs here. */
private fun relativeDay(atMs: Long): String {
    val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(atMs)) /
        86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(atMs))
    }
}

private fun fullTimestamp(atMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(atMs))

private fun startOfDay(ms: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

// ------------------------------------------------------------ agent trace

/** The chain of specialists, each with its own finding and its own blind spot. */
@Composable
private fun AgentTrace(steps: List<AgentStep>) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "How this was checked",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${steps.size} steps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess
                              else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                steps.forEachIndexed { i, step ->
                    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier.size(22.dp).clip(CircleShape)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${i + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme
                                            .onPrimaryContainer,
                                    )
                                }
                                Text(step.agent,
                                     style = MaterialTheme.typography.titleSmall,
                                     fontWeight = FontWeight.SemiBold)
                                ConfidenceDot(step.confidence)
                            }
                            Text("Looked at: ${step.looked_at}",
                                 style = MaterialTheme.typography.bodyMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(step.finding,
                                 style = MaterialTheme.typography.bodyLarge)
                            if (step.flags.isNotEmpty()) {
                                val flagColors = severityColors(Severity.ATTENTION)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    step.flags.take(3).forEach { flag ->
                                        Surface(
                                            color = flagColors.container,
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                flag, color = flagColors.accent,
                                                style = MaterialTheme.typography
                                                    .labelMedium,
                                                modifier = Modifier.padding(
                                                    horizontal = 7.dp, vertical = 3.dp),
                                            )
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
    }
}

@Composable
private fun ConfidenceDot(confidence: AgentStep.Confidence) {
    val attention = severityColors(Severity.ATTENTION).accent
    val (label, color) = when (confidence) {
        AgentStep.Confidence.HIGH -> "confident" to calmColors().accent
        AgentStep.Confidence.MEDIUM -> "partial" to attention
        AgentStep.Confidence.LOW -> "weak" to attention
        AgentStep.Confidence.NONE -> "no data" to MaterialTheme.colorScheme.outline
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}
