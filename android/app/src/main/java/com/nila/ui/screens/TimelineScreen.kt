package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.data.EventRecord
import com.nila.ui.AppState
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.SeverityChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimelineScreen(state: AppState) {
    val events by state.events.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text("Timeline", style = MaterialTheme.typography.headlineMedium,
                     fontWeight = FontWeight.SemiBold)
                Text(
                    if (events.isEmpty()) "Nothing recorded yet."
                    else "${events.size} events, newest first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(events, key = { it.id }) { event ->
            EventRow(event)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun EventRow(event: EventRecord) {
    val time = remember(event.startedAtMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.startedAtMs))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(time, style = MaterialTheme.typography.titleSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(readable(event), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Medium)
            SeverityChip(event.severity)
        }

        val detail = buildString {
            if (event.durationSeconds > 0) append("${event.durationSeconds}s")
            event.trend?.let {
                if (isNotEmpty()) append("  ")
                append(it.lowercase())
            }
            event.note?.let {
                if (isNotEmpty()) append("  ")
                append(it)
            }
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val points = event.envelopePoints()
        if (points.size > 3) IntensityCurve(points = points, height = 32.dp)
    }
}

private fun readable(event: EventRecord): String = when (event.kind) {
    "CRY_STARTED" -> "Crying started"
    "CRY_ONGOING" -> "Fussing"
    "CRY_ENDED" -> "Cry ended"
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
