package com.nila.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.nila.audio.ModelCard
import com.nila.actions.Reminders
import com.nila.actions.Soother
import com.nila.data.BabyProfile
import com.nila.data.CareKind
import com.nila.ui.AppState
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader

@Composable
fun SettingsScreen(state: AppState) {
    val baby by state.baby.collectAsState()
    val exported by state.exportedReport.collectAsState()
    val thinking by state.thinking.collectAsState()
    val pendingTag by com.nila.ui.NfcInbox.pendingTag.collectAsState()
    val bridgeAddress by state.bridgeAddress.collectAsState()
    val reminderSettings by state.reminderSettings.collectAsState()
    val recordings by state.recordings.collectAsState()
    val recording by state.recording.collectAsState()
    val irRemote by state.irRemote.collectAsState()
    val modelState by state.modelState.collectAsState()
    val visionModelState by state.visionModelState.collectAsState()
    var name by remember(baby) { mutableStateOf(baby?.name ?: "") }
    var notes by remember(baby) { mutableStateOf(baby?.healthNotes ?: "") }
    var language by remember(baby) { mutableStateOf(baby?.language ?: "en") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.SemiBold)

        // Shown the moment an unknown sticker is tapped, wherever the user is.
        if (pendingTag != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("New sticker detected")
                OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What should tapping this sticker log?",
                             style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.nila.data.CareKind.FEED to "Feed",
                                com.nila.data.CareKind.DIAPER to "Change",
                                com.nila.data.CareKind.SLEEP_START to "Sleep",
                            ).forEach { (kind, label) ->
                                OutlinedButton(
                                    onClick = { state.assignPendingTag(kind) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Your baby")
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Health notes (allergies, conditions)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            OutlinedTextField(
                value = language, onValueChange = { language = it },
                label = { Text("Answer language (en, hi, ta, te, kn, bn, mr)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    state.saveBaby(
                        (baby ?: BabyProfile()).copy(
                            name = name, healthNotes = notes, language = language,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }


        // ---------------------------------------------------- language model
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Shorter answers")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A small model that shortens answers into two or three " +
                            "sentences. Facts always come from the sourced " +
                            "entries, never from the model.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (val m = modelState) {
                        is com.nila.assistant.ModelInstaller.State.Installed ->
                            Capability("Installed", state.llmStatus)
                        is com.nila.assistant.ModelInstaller.State.Downloading -> {
                            Capability("Downloading", "${m.percent}% of 547 MB")
                            OutlinedButton(
                                onClick = state::cancelModelDownload,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Cancel") }
                        }
                        is com.nila.assistant.ModelInstaller.State.Failed -> {
                            Capability("Failed", m.reason)
                            OutlinedButton(
                                onClick = state::downloadModel,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Try again") }
                        }
                        else -> {
                            Text(
                                "547 MB, once, over Wi-Fi. This is the only " +
                                    "outbound request Nila ever makes, and " +
                                    "everything works without it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = state::downloadModel,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download") }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------- reminders
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Reminders")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Counted from your last log, not the clock.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reminderSettings.forEach { setting ->
                        ReminderRow(
                            setting = setting,
                            onEnabled = { state.setReminderEnabled(setting.kind, it) },
                            onInterval = { state.setReminderInterval(setting.kind, it) },
                        )
                    }
                }
            }
        }

        // ------------------------------------------------- caregiver's voice
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Your voice")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Played to settle your baby before you are woken.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    var label by remember { mutableStateOf("") }
                    if (!recording) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("What is it? e.g. Amma humming") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = {
                            if (recording) state.stopRecording()
                            else { state.startRecording(label); label = "" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (recording) "Stop and save" else "Record")
                    }

                    recordings.forEach { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                file.nameWithoutExtension.replace('-', ' '),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                state.previewSoother(
                                    Soother.Recorded(
                                        file.nameWithoutExtension, "Your voice", file
                                    )
                                )
                            }) { Text("Play") }
                            TextButton(onClick = { state.deleteRecording(file) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }

        // Hardware capability reporting. The IR blaster is genuinely uncertain
        // on this platform, so the app states which situation it is in rather
        // than hiding a silent failure.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("This device")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Capability("Infrared blaster", state.irActuator.describe())
                    Capability("NFC stickers", state.nfc.describe())
                    Capability("Answers", "Retrieved and sourced, then phrased")
                    Capability("Knowledge base", "${state.knowledgeSize} entries, offline")
                    Capability("Alerts to a watch", "Any paired watch or band, no setup")
                    Capability("Text recognition", state.ocrStatus)
                    // The optional vision model lives here rather than on the
                    // Watch screen. It had a card there and nobody could tell
                    // what it was for -- an optional download belongs beside
                    // the other optional download, not in the middle of the
                    // thing you are trying to watch.
                    Capability("Describing the cot", state.sceneDescriber.describe())
                    if (state.sceneDescriber.isSupported &&
                        !state.sceneDescriber.isInstalled
                    ) {
                        when (val v = visionModelState) {
                            is com.nila.assistant.ModelInstaller.State.Downloading ->
                                Text("Downloading ${v.percent}% of 1.1 GB",
                                     style = MaterialTheme.typography.bodyMedium)
                            else -> OutlinedButton(
                                onClick = state::downloadVisionModel,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download vision model (1.1 GB)") }
                        }
                    }

                    // Only offered where it can actually work. On the far more
                    // common phone with no emitter, an input box that silently
                    // does nothing is worse than no input box.
                    if (state.irActuator.isReady) {
                        var address by remember(irRemote) {
                            mutableStateOf(irRemote?.address?.toString() ?: "")
                        }
                        var command by remember(irRemote) {
                            mutableStateOf(irRemote?.command?.toString() ?: "")
                        }
                        var sent by remember { mutableStateOf<Boolean?>(null) }

                        Text(
                            "Enter your remote's NEC code to control a fan or AC.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = address, onValueChange = { address = it },
                                label = { Text("Address 0-255") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = command, onValueChange = { command = it },
                                label = { Text("Command 0-255") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    state.saveIrRemote(
                                        address.toIntOrNull() ?: -1,
                                        command.toIntOrNull() ?: -1,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Save") }
                            OutlinedButton(
                                onClick = { state.testIrRemote { sent = it } },
                                modifier = Modifier.weight(1f),
                            ) { Text("Send it now") }
                        }
                        sent?.let {
                            Text(
                                if (it) "Transmitted. If nothing moved, the code is wrong."
                                else "Nothing was sent -- no code saved, or the system " +
                                    "refused the emitter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Models")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Capability("Cry detection", "Trained on unseen infants, runs offline")
                    Capability("Cause estimate", "A best guess, shown as one")
                    Capability("Everything", "On this phone. No account, no network.")
                }
            }
        }

        // The one thing in this app designed to leave the phone, so it gets its
        // own section rather than being buried as an action on another screen.
        // Kept next to Privacy, and framed honestly: this opens a socket.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Watch from a laptop")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A read-only view in a browser on the same Wi-Fi. " +
                            "Counts and timings only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bridgeAddress?.let {
                        Text("Open $it on your laptop",
                             style = MaterialTheme.typography.titleSmall)
                    }
                    OutlinedButton(
                        onClick = state::toggleDeskBridge,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (bridgeAddress != null) "Stop serving"
                             else "Start the laptop view")
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("For your paediatrician")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A one-page PDF of crying, day by day, to take to an " +
                            "appointment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    exported?.let {
                        Text("Saved to ${it.absolutePath}",
                             style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = state::exportClinicReport,
                        enabled = !thinking,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (thinking) "Building..." else "Export the last 14 days") }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Demo data")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A fresh install is seeded with one example family so " +
                            "every screen has something to show. Resetting wipes " +
                            "the log and lays it down again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = state::resetDemoData,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset to demo data") }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Privacy")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "No network requests. No account. No analytics.",
                        "Monitored audio is never recorded to disk.",
                        "Your log and documents are excluded from cloud backup.",
                        "An awareness aid, not a medical device. It does not " +
                            "monitor breathing or vital signs.",
                    ).forEach {
                        Text(
                            "- $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One reminder: a switch and an interval.
 *
 * The interval is a slider in fifteen-minute steps rather than a text field
 * because it is a preference, not a measurement -- and because nobody wants to
 * type "165" at three in the morning.
 */
@Composable
private fun ReminderRow(
    setting: Reminders.Setting,
    onEnabled: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (setting.kind) {
                        CareKind.FEED -> "Feeding"
                        else -> "Nappy change"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "every ${format(setting.intervalMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = setting.enabled, onCheckedChange = onEnabled)
        }
        if (setting.enabled) {
            var minutes by remember(setting.intervalMinutes) {
                mutableStateOf(setting.intervalMinutes.toFloat())
            }
            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                onValueChangeFinished = { onInterval(minutes.toInt()) },
                valueRange = 30f..360f,
                steps = 21,
            )
        }
    }
}

private fun format(minutes: Int): String = when {
    minutes < 60 -> "${minutes} minutes"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

@Composable
private fun Capability(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
