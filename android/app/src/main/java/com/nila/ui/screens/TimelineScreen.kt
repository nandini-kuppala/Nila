package com.nila.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.data.EventRecord
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.components.CryClipPlayer
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.SeverityChip
import com.nila.ui.theme.severityColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened, one line per thing that happened.
 *
 * ### Why it is not one row per database row
 *
 * A single cry writes five or six of them: heard it, played a sound, judged the
 * sound, woke you, ended. Listed flat, that is a screen on which one difficult
 * evening pushes the previous two days off the bottom, and in which the cry
 * itself -- the thing a parent is scrolling to find -- is indistinguishable
 * from the app's own footnotes about it. The intermediate rows are still
 * written, still exported to the clinic report, and still shown here: they are
 * *underneath* the cry, one tap away, where they read as the account of that
 * cry rather than as five separate events.
 *
 * ### The recording
 *
 * A cry that woke somebody, outlasted the ladder, or came back as pain keeps
 * its audio, and the entry plays it. That is the only item on this screen a
 * parent can check for themselves, and it is the one thing a paediatrician can
 * be given that is evidence rather than a recollection. Everything about how
 * long it is kept, and how to stop it being kept at all, is in Settings.
 */
@Composable
fun TimelineScreen(state: AppState) {
    val events by state.events.collectAsState()
    val entries = remember(events) { Timeline.collapse(events) }
    val cries = entries.count { it.isCry }
    val kept = entries.count { it.clipPath != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Timeline", style = MaterialTheme.typography.headlineMedium,
                     fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        entries.isEmpty() -> "Nothing recorded yet."
                        kept > 0 -> "$cries cries and ${entries.size - cries} other " +
                            "events, newest first. $kept " +
                            (if (kept == 1) "recording is" else "recordings are") +
                            " kept on this phone."
                        else -> "${entries.size} events, newest first."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(entries, key = { it.id }) { entry ->
            if (entry.isCry) CryEntry(entry) else OtherEntry(entry)
        }
        if (kept > 0) {
            item {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    HedgeNote(
                        "Recordings are kept only for cries that woke you, ran to " +
                            "three minutes, or came back as pain. They live in this " +
                            "app's private storage, are never uploaded, and are " +
                            "deleted after ${com.nila.audio.EpisodeRecorder.KEEP_DAYS} " +
                            "days. Settings can turn recording off entirely."
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- a cry

/**
 * One cry: what it was, what was tried, and the audio if it was kept.
 *
 * Collapsed by default, because the steps are evidence rather than news. The
 * headline number is the duration, then the cause with its own uncertainty
 * attached, then whether anything worked.
 */
@Composable
private fun CryEntry(entry: Timeline.Entry) {
    var open by remember(entry.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val colors = severityColors(entry.severity)
    val flagged = entry.severity.level >= Severity.URGENT.level

    Column(
        modifier = Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLowest)
            .border(
                1.dp,
                if (flagged) colors.accent.copy(alpha = 0.5f) else scheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(entry.clock, style = MaterialTheme.typography.titleSmall,
                 color = scheme.onSurfaceVariant)
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (entry.clipPath != null) {
                // A badge on the entry itself, so a parent looking for "the one
                // I can play to the doctor" can find it by scanning rather than
                // by opening each cry in turn.
                Icon(
                    Icons.Outlined.GraphicEq,
                    contentDescription = "A recording of this cry was kept",
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (flagged) SeverityChip(entry.severity)
        }

        entry.summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }

        if (entry.envelope.size > 3) {
            IntensityCurve(points = entry.envelope, height = 34.dp)
        }

        entry.clipPath?.let { path ->
            CryClipPlayer(path = path, envelope = entry.envelope)
        }

        if (entry.steps.isNotEmpty()) {
            Text(
                text = if (open) "Hide what Nila did"
                       else "What Nila did - ${entry.steps.size} steps",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.pointerInput(entry.id) {
                    detectTapGestures { open = !open }
                },
            )
            AnimatedVisibility(visible = open) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.steps.forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 7.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(scheme.outline)
                            )
                            Column {
                                Text(
                                    step.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface,
                                )
                                step.detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------- everything else

/** A safety event or a fault. One row, as before: these are already atomic. */
@Composable
private fun OtherEntry(entry: Timeline.Entry) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(entry.clock, style = MaterialTheme.typography.titleSmall,
             color = scheme.onSurfaceVariant)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Medium)
            entry.summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                     color = scheme.onSurfaceVariant)
            }
        }
        if (entry.severity.level >= Severity.ATTENTION.level) SeverityChip(entry.severity)
    }
}

// ----------------------------------------------------------- the folding

/**
 * Turning event rows into the entries a person reads.
 *
 * Pure, and separate from the composables, so the folding can be tested on the
 * JVM -- a grouping bug here silently merges two cries into one or splits one
 * across two, and neither is visible in a screenshot.
 */
object Timeline {

    data class Step(val label: String, val detail: String?)

    data class Entry(
        val id: Long,
        val atMs: Long,
        val title: String,
        val summary: String?,
        val severity: Severity,
        val isCry: Boolean,
        val envelope: List<Int> = emptyList(),
        val clipPath: String? = null,
        val steps: List<Step> = emptyList(),
    ) {
        val clock: String
            get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))
    }

    private val CRY_KINDS = setOf(
        "CRY_STARTED", "CRY_ONGOING", "CRY_ENDED",
        "SOOTHE_PLAYED", "SOOTHE_WORKED", "SOOTHE_FAILED", "ESCALATED_TO_PARENT",
    )

    /**
     * Fold cry rows into one entry each, newest first.
     *
     * Rows are grouped on `episodeId`. A cry row with no episode id is its own
     * entry rather than being guessed into a neighbouring group: rows written
     * before this column existed, and rows from a crash mid-episode, are real
     * events and showing them alone is honest, where attaching them to the
     * nearest cry is a fabricated association.
     */
    fun collapse(events: List<EventRecord>): List<Entry> {
        val cryRows = events.filter { it.kind in CRY_KINDS && it.episodeId != null }
        val grouped = cryRows.groupBy { it.episodeId!! }

        val episodes = grouped.map { (id, rows) -> episodeEntry(id, rows) }
        val loose = events
            .filter { it.kind !in CRY_KINDS || it.episodeId == null }
            .map { plainEntry(it) }

        return (episodes + loose).sortedByDescending { it.atMs }
    }

    private fun episodeEntry(id: Long, rows: List<EventRecord>): Entry {
        val ordered = rows.sortedBy { it.id }
        val opened = ordered.firstOrNull { it.kind == "CRY_STARTED" } ?: ordered.first()
        val closed = ordered.lastOrNull { it.kind == "CRY_ENDED" }
        val escalated = ordered.firstOrNull { it.kind == "ESCALATED_TO_PARENT" }
        val settled = ordered.lastOrNull { it.kind == "SOOTHE_WORKED" }
        val failed = ordered.lastOrNull { it.kind == "SOOTHE_FAILED" }
        val played = ordered.filter { it.kind == "SOOTHE_PLAYED" }

        val seconds = closed?.durationSeconds
            ?: ordered.maxOf { it.durationSeconds }
        val cause = (closed ?: escalated ?: opened).hypothesisLabel
        val confidence = (closed ?: escalated ?: opened).hypothesisConfidence

        val summary = buildString {
            append(duration(seconds))
            closed?.trend?.let { append(", ").append(it.lowercase()) }
            if (cause != null) {
                append(". Probably ").append(cause.replace('_', ' '))
                if (confidence > 0f) append(" - ${(confidence * 100).toInt()}%")
                append(", a guess from the sound")
            }
            append(". ")
            append(
                when {
                    escalated != null -> "Nila woke you."
                    settled != null -> "${settled.note ?: "A sound"} settled her."
                    failed != null -> "${failed.note ?: "A sound"} did not help."
                    played.isNotEmpty() -> "Nila played ${played.size} " +
                        (if (played.size == 1) "sound." else "sounds.")
                    else -> "Settled on her own."
                }
            )
        }

        return Entry(
            // The opening row's id, so the key survives the episode gaining
            // more rows while the screen is open.
            id = id,
            atMs = opened.startedAtMs,
            title = if (escalated != null) "Cry - you were woken" else "Cry",
            summary = summary,
            severity = ordered.maxByOrNull { it.severityLevel }?.severity ?: Severity.NOTE,
            isCry = true,
            envelope = (closed ?: opened).envelopePoints(),
            // Written on whichever row the service stamped; searched rather
            // than assumed so a future change of anchor row cannot lose it.
            clipPath = ordered.firstNotNullOfOrNull { it.clipPath },
            steps = ordered.map { Step(readable(it), it.note) },
        )
    }

    private fun plainEntry(event: EventRecord): Entry = Entry(
        id = -event.id,
        atMs = event.startedAtMs,
        title = readable(event),
        summary = buildString {
            if (event.durationSeconds > 0) append(duration(event.durationSeconds))
            event.trend?.let {
                if (isNotEmpty()) append("  ")
                append(it.lowercase())
            }
            event.note?.let {
                if (isNotEmpty()) append("  ")
                append(it)
            }
        }.ifBlank { null },
        severity = event.severity,
        isCry = false,
    )

    private fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${seconds % 60}s"

    fun readable(event: EventRecord): String = when (event.kind) {
        "CRY_STARTED" -> "Heard crying"
        "CRY_ONGOING" -> "Still crying"
        "CRY_ENDED" -> "The crying stopped"
        "SOOTHE_PLAYED" -> "Played a sound"
        "SOOTHE_WORKED" -> "That settled her"
        "SOOTHE_FAILED" -> "That didn't help"
        "ESCALATED_TO_PARENT" -> "Alerted you"
        "FACE_NOT_VISIBLE" -> "Couldn't see the face"
        "FACE_RETURNED" -> "Face visible again"
        "STILLNESS" -> "Unusually still"
        "ROLLED_TO_FRONT" -> "Rolled onto her front"
        "ROLLED_TO_SIDE" -> "Rolled onto her side"
        "CRAWLING" -> "Crawling"
        "SITTING_UP" -> "Sat up"
        "STANDING_UP" -> "Stood up"
        "LEFT_SAFE_ZONE" -> "Left the safe zone"
        "MOVED_AWAY" -> "Moved away from the cot"
        "OUT_OF_VIEW" -> "Out of view of the camera"
        "CAMERA_MOVED" -> "The phone was moved"
        "GUARDIAN_FAULT" -> "Monitoring problem"
        else -> event.kind.lowercase().replace('_', ' ')
    }
}
