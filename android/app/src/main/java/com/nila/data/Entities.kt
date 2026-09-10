package com.nila.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Severity tiers.
 *
 * Encoded as an ordinal so it can be compared and queried, and deliberately
 * separate from how it is rendered. L3 and L4 sound locally on the device that
 * detected them and never wait for a network round trip -- an alert that has to
 * reach a watch before it can fire is not a safety feature.
 */
enum class Severity(val level: Int, val label: String) {
    NOTE(1, "Note"),
    ATTENTION(2, "Attention"),
    URGENT(3, "Urgent"),
    CRITICAL(4, "Critical");

    companion object {
        fun of(level: Int) = entries.firstOrNull { it.level == level } ?: NOTE
    }
}

enum class EventKind {
    CRY_STARTED, CRY_ONGOING, CRY_ENDED,
    FACE_NOT_VISIBLE, FACE_RETURNED, STILLNESS, MOTION_BURST,
    SOOTHE_PLAYED, SOOTHE_WORKED, SOOTHE_FAILED,
    ESCALATED_TO_PARENT,
    GUARDIAN_FAULT,
    // The camera lane, once it could tell these apart. Recorded as distinct
    // kinds rather than one SAFETY_EVENT with the detail in a note, because a
    // week of "rolled onto their front" and a week of "outside the safe zone"
    // are different things to show a doctor.
    ROLLED_TO_FRONT, ROLLED_TO_SIDE, CRAWLING, SITTING_UP, STANDING_UP,
    LEFT_SAFE_ZONE, MOVED_AWAY, OUT_OF_VIEW, CAMERA_MOVED,
}

@Entity(tableName = "events", indices = [Index("startedAtMs"), Index("kind")])
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val severityLevel: Int,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val durationSeconds: Int = 0,
    val peakDbfs: Float = 0f,
    val trend: String? = null,
    val detectorConfidence: Float = 0f,
    /** Comma-separated 0..100 samples. Small enough to inline, and it keeps the
     *  timeline query to a single table read. */
    val envelope: String? = null,
    val hypothesisLabel: String? = null,
    val hypothesisConfidence: Float = 0f,
    val hypothesisTrustworthy: Boolean = false,
    val note: String? = null,
) {
    val severity: Severity get() = Severity.of(severityLevel)
    fun envelopePoints(): List<Int> =
        envelope?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
}

enum class CareKind { FEED, DIAPER, SLEEP_START, SLEEP_END, MEDICINE, NOTE }

/**
 * A caregiver action. These are what turn generic advice into an answer about
 * this baby -- "three hours since the last feed" is only available because
 * someone logged the feed, which is why logging has to cost one tap.
 */
@Entity(tableName = "care_log", indices = [Index("atMs"), Index("kind")])
data class CareRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val atMs: Long,
    val detail: String? = null,
    val quantity: String? = null,
)

@Entity(tableName = "baby")
data class BabyProfile(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val birthDateMs: Long = 0,
    val language: String = "en",
    /** Free text the caregiver entered: allergies, conditions, prematurity.
     *  Retrieved verbatim into the assistant's context. */
    val healthNotes: String = "",
) {
    val ageMonths: Int
        get() = if (birthDateMs <= 0) 0
        else ((System.currentTimeMillis() - birthDateMs) / (30.44 * 86_400_000L)).toInt()
}

/**
 * A question the caregiver asked and the answer given.
 *
 * Kept so the assistant can refer back to it, and so a caregiver can show a
 * paediatrician what they were told. That second reason is why the retrieved
 * sources are stored alongside the answer rather than discarded.
 */
@Entity(tableName = "conversations", indices = [Index("atMs")])
data class ConversationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val question: String,
    val answer: String,
    val sources: String? = null,
    val imagePath: String? = null,
    val language: String = "en",
)

/**
 * One medicine that was checked, kept so it can be looked at again.
 *
 * Scans were not stored at all: the verdict lived in the view model and was
 * gone the moment the screen was left. That is wrong for the thing this screen
 * produces -- somebody who checked a strip at the pharmacy counter and wants to
 * show a partner what it said an hour later had nothing to show them.
 *
 * The image path points into app-private storage, alongside the health
 * documents and under the same `allowBackup=false`. The verdict is stored as
 * its enum name rather than its label so a reworded label cannot change what a
 * past scan appears to have said.
 */
@Entity(tableName = "medicine_scans", indices = [Index("atMs")])
data class MedicineScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    /** What it was identified as, or what was typed. */
    val name: String,
    /** [com.nila.assistant.agents.Verdict] name. */
    val verdict: String,
    val headline: String,
    val summary: String,
    /** App-private JPEG of the strip, when one was photographed. */
    val imagePath: String? = null,
    /** What OCR read, for the "what the camera read" panel. */
    val scannedText: String? = null,
    /** Newline-separated, so the history entry can cite what the live card did. */
    val sources: String? = null,
    val cautions: String? = null,
) {
    fun sourceList(): List<String> =
        sources?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun cautionList(): List<String> =
        cautions?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
}
