package com.nila.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.data.HealthRecord
import com.nila.data.MotherProfile
import com.nila.data.RecordCategory
import com.nila.data.RecordSubject
import com.nila.ui.AppState
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The health shelf, for both people being cared for.
 *
 * Two things make this more than a photo album. Documents are OCR'd at import,
 * so the assistant can retrieve over them; and the mother's profile feeds the
 * medicine review directly, which is what lets "is this safe" become "is this
 * safe *for you*".
 */
@Composable
fun HealthScreen(state: AppState) {
    var subject by remember { mutableStateOf(RecordSubject.BABY) }
    val records by state.records.collectAsState()
    val mother by state.mother.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    val visible = records.filter { it.recordSubject == subject }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = subject.ordinal) {
            RecordSubject.entries.forEach { s ->
                Tab(
                    selected = subject == s,
                    onClick = { subject = s },
                    text = { Text(s.label) },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (subject == RecordSubject.MOTHER) {
                MotherCard(mother, state::saveMother)
            }

            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add a ${subject.label.lowercase()} record")
            }

            if (visible.isEmpty()) {
                OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Photograph a prescription, report or vaccination card. " +
                            "It stays on this phone and stays searchable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Grouped by category rather than a flat reverse-chronological
                // list: people look for "the vaccination card", not "the thing
                // from March".
                RecordCategory.forSubject(subject)
                    .mapNotNull { cat ->
                        visible.filter { it.recordCategory == cat }
                            .takeIf { it.isNotEmpty() }?.let { cat to it }
                    }
                    .forEach { (category, items) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader("${category.label}  (${items.size})")
                            items.forEach { record ->
                                RecordRow(record) { state.deleteRecord(record) }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
            }

            HedgeNote(
                "Everything here stays in this app's private storage and is left " +
                    "out of cloud backup. Nothing is uploaded."
            )
        }
    }

    if (showAdd) {
        AddRecordDialog(
            subject = subject,
            onDismiss = { showAdd = false },
            onSave = { category, title, notes, uri ->
                state.addRecord(subject, category, title, notes, uri = uri)
                showAdd = false
            },
        )
    }
}

@Composable
private fun MotherCard(profile: MotherProfile?, onSave: (MotherProfile) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var conditions by remember(profile) { mutableStateOf(profile?.conditions ?: "") }
    var allergies by remember(profile) { mutableStateOf(profile?.allergies ?: "") }
    var medicines by remember(profile) {
        mutableStateOf(profile?.currentMedicines ?: "")
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Your health")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = conditions, onValueChange = { conditions = it },
                        label = { Text("Conditions (asthma, diabetes...)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    OutlinedTextField(
                        value = allergies, onValueChange = { allergies = it },
                        label = { Text("Allergies (penicillin...)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = medicines, onValueChange = { medicines = it },
                        label = { Text("Medicines you take now") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onSave(
                                (profile ?: MotherProfile()).copy(
                                    conditions = conditions,
                                    allergies = allergies,
                                    currentMedicines = medicines,
                                )
                            )
                            editing = false
                        }) { Text("Save") }
                        OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                } else {
                    if (profile?.isComplete != true) {
                        // Said plainly, because the medicine checker is
                        // meaningfully weaker without this and the user should
                        // know why it is asking.
                        Text(
                            "The medicine checker uses this. Without it, it can " +
                                "only give you the generic answer rather than one " +
                                "about you.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Fact("Conditions", profile.conditions)
                        Fact("Allergies", profile.allergies)
                        Fact("Taking now", profile.currentMedicines)
                    }
                    OutlinedButton(
                        onClick = { editing = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (profile?.isComplete == true) "Edit" else "Fill this in")
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RecordRow(record: HealthRecord, onDelete: () -> Unit) {
    val date = remember(record.recordedAtMs) {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            .format(Date(record.recordedAtMs))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(record.title, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onDelete) { Text("Remove") }
        }
        Text(date, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.notes.isNotBlank()) {
            Text(record.notes, style = MaterialTheme.typography.bodyMedium)
        }
        if (record.extractedText.isNotBlank()) {
            // Shown so it is obvious the text was captured and is searchable --
            // otherwise the OCR is invisible work the user has no reason to
            // believe happened.
            Text(
                "Readable text captured: " +
                    record.extractedText.take(110).replace("\n", " ") + "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddRecordDialog(
    subject: RecordSubject,
    onDismiss: () -> Unit,
    onSave: (RecordCategory, String, String, Uri?) -> Unit,
) {
    val categories = RecordCategory.forSubject(subject)
    var category by remember { mutableStateOf(categories.first()) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> picked = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a ${subject.label.lowercase()} record") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c ->
                                FilterChip(
                                    selected = category == c,
                                    onClick = { category = c },
                                    label = { Text(c.label) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                AssistChip(
                    onClick = { pickDocument.launch("*/*") },
                    label = {
                        Text(if (picked == null) "Attach a photo or PDF"
                             else "Attached - tap to change")
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(category, title, notes, picked) }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
