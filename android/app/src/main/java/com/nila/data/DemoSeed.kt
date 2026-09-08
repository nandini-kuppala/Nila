package com.nila.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.nila.actions.SootheMemory
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Context.seedStore by preferencesDataStore("demo_seed")

/**
 * Fills an empty install with one plausible family.
 *
 * An app for a four-month-old is mostly a record of the last few days, and on a
 * fresh install there are no last few days -- every screen is an empty state and
 * the interesting parts (a week of crying against the colic line, a medicine
 * checked against a real allergy, which sound settles *this* baby) have nothing
 * to work with. That is a poor first five minutes for anyone, and useless for a
 * demo on a device that has never been used.
 *
 * The data is authored in MongoDB and exported into the APK by
 * `ml/src/demo_seed.py`. It is read from an asset, not fetched: the app makes no
 * network requests, and a judge running this in a browser emulator or on a plane
 * gets the same populated app as everyone else. There is no connection string in
 * the APK to find.
 *
 * ### Two rules
 *
 * **Only ever into an empty database.** If anything real has been logged, this
 * does nothing at all -- a demo seeder that can overwrite a parent's own record
 * of their baby is a bug with consequences.
 *
 * **Every time is relative.** The asset stores "165 minutes ago", never a date,
 * so an APK built in September still shows a baby who fed two hours ago when
 * someone installs it in November.
 */
object DemoSeed {

    private const val TAG = "DemoSeed"
    private const val ASSET = "demo_seed.json"
    private val SEEDED = booleanPreferencesKey("seeded")

    /**
     * Seed if this install has never been used.
     *
     * @return true if data was written
     */
    suspend fun applyIfEmpty(context: Context, db: NilaDatabase): Boolean {
        if (context.seedStore.data.first()[SEEDED] == true) return false

        // The flag alone is not enough: it lives in a separate store from the
        // database, and a cleared app data directory can leave them disagreeing.
        // The database itself is the authority on whether anything real exists.
        val used = db.care().latestOf(CareKind.FEED.name) != null ||
            db.baby().get() != null ||
            db.healthRecords().count() > 0
        if (used) {
            markDone(context)
            return false
        }

        return try {
            val json = JSONObject(
                context.assets.open(ASSET).bufferedReader().use { it.readText() }
            )
            write(context, db, json)
            markDone(context)
            Log.i(TAG, "seeded the demo family")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not seed", t)
            false
        }
    }

    /** Wipe everything and seed again. Offered in Settings, for a repeat demo. */
    suspend fun reset(context: Context, db: NilaDatabase) {
        db.events().deleteAll()
        db.care().deleteAll()
        db.healthRecords().deleteAll()
        context.seedStore.edit { it.remove(SEEDED) }
        applyIfEmpty(context, db)
    }

    private suspend fun markDone(context: Context) {
        context.seedStore.edit { it[SEEDED] = true }
    }

    private suspend fun write(context: Context, db: NilaDatabase, json: JSONObject) {
        val now = System.currentTimeMillis()

        json.optJSONObject("baby")?.let { baby ->
            db.baby().upsert(
                BabyProfile(
                    name = baby.optString("name"),
                    birthDateMs = now - TimeUnit.DAYS.toMillis(
                        baby.optLong("ageDays", 120)
                    ),
                    language = baby.optString("language", "en"),
                    healthNotes = baby.optString("healthNotes"),
                )
            )
        }

        json.optJSONObject("mother")?.let { mother ->
            db.mother().upsert(
                MotherProfile(
                    name = mother.optString("name"),
                    isBreastfeeding = mother.optBoolean("isBreastfeeding", true),
                    deliveryDateMs = now - TimeUnit.DAYS.toMillis(
                        mother.optLong("deliveryDaysAgo", 120)
                    ),
                    conditions = mother.optString("conditions"),
                    allergies = mother.optString("allergies"),
                    currentMedicines = mother.optString("currentMedicines"),
                    notes = mother.optString("notes"),
                )
            )
        }

        json.optJSONArray("care")?.forEach { entry ->
            db.care().insert(
                CareRecord(
                    kind = entry.getString("kind"),
                    atMs = now - TimeUnit.MINUTES.toMillis(entry.getLong("minutesAgo")),
                    detail = entry.optString("detail").ifBlank { null },
                )
            )
        }

        json.optJSONArray("cries")?.forEach { cry -> writeEpisode(db, now, cry) }

        installSampleVoice(context)

        json.optJSONArray("records")?.forEach { record ->
            db.healthRecords().insert(
                HealthRecord(
                    subject = record.getString("subject"),
                    category = record.getString("category"),
                    title = record.getString("title"),
                    notes = record.optString("notes"),
                    extractedText = record.optString("text"),
                    recordedAtMs = now - TimeUnit.DAYS.toMillis(
                        record.optLong("daysAgo")
                    ),
                )
            )
        }

        // Soother outcomes live in their own preference store, so they are
        // replayed through the same call the monitor makes rather than written
        // behind its back -- the Laplace smoothing then matches exactly what a
        // real run of those attempts would have produced.
        val memory = SootheMemory(context)
        json.optJSONArray("soothers")?.forEach { soother ->
            val id = soother.getString("id")
            val attempts = soother.getInt("attempts")
            val successes = soother.getInt("successes")
            repeat(attempts) { i -> memory.record(id, i < successes) }
        }
    }

    /**
     * Lay down one sample caregiver recording, so a fresh install can show what
     * "it plays your own voice first" actually looks and sounds like.
     *
     * This is a synthesised stand-in shipped with the demo family, not anyone's
     * real voice, and it is written the same way a recording made in the app
     * would be -- so the monitor finds it through the ordinary path and nothing
     * downstream needs a special case. Recording over it in Settings replaces
     * it, which is what anyone demonstrating this should do.
     */
    private fun installSampleVoice(context: Context) {
        runCatching {
            val target = java.io.File(
                com.nila.actions.VoiceRecorder.directory(context), "Amma-humming.m4a"
            )
            if (target.exists()) return
            context.assets.open("demo_mother_voice.m4a").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { Log.w(TAG, "could not install the sample voice", it) }
    }

    /**
     * One cry episode becomes the rows the monitor would have written for it:
     * a start, the soothing attempt and its outcome, an escalation, an end.
     *
     * Writing only a summary row would leave the timeline showing effects with
     * no causes -- an alert with nothing before it.
     */
    private suspend fun writeEpisode(db: NilaDatabase, now: Long, cry: JSONObject) {
        val started = now - (cry.getDouble("hoursAgo") * 3_600_000).toLong()
        val seconds = cry.getInt("seconds")
        val ended = started + seconds * 1000L
        val cause = cry.optString("cause").ifBlank { null }

        fun row(
            kind: EventKind,
            atMs: Long,
            severity: Severity,
            note: String? = null,
            withCause: Boolean = false,
        ) = EventRecord(
            kind = kind.name,
            severityLevel = severity.level,
            startedAtMs = atMs,
            endedAtMs = if (kind == EventKind.CRY_ENDED) ended else null,
            durationSeconds = ((atMs - started) / 1000L).toInt().coerceAtLeast(0),
            peakDbfs = -22f,
            trend = cry.optString("trend", "STEADY"),
            detectorConfidence = cry.optDouble("confidence", 0.9).toFloat(),
            envelope = envelopeFor(cry.optString("trend", "STEADY")),
            hypothesisLabel = if (withCause) cause else null,
            hypothesisConfidence =
                if (withCause) cry.optDouble("causeConfidence", 0.0).toFloat() else 0f,
            // Never true. The cause estimate did not clear chance on infants it
            // had not heard, and seed data that claimed otherwise would put a
            // false badge on a real screen.
            hypothesisTrustworthy = false,
            note = note,
        )

        db.events().insert(row(EventKind.CRY_STARTED, started, Severity.NOTE))

        val soother = cry.optString("soothed").ifBlank { null }
        if (soother != null && seconds > 20) {
            val playedAt = started + 20_000
            db.events().insert(
                row(EventKind.SOOTHE_PLAYED, playedAt, Severity.NOTE, soother, true)
            )
            val settled = cry.optBoolean("settled", false)
            db.events().insert(
                row(
                    if (settled) EventKind.SOOTHE_WORKED else EventKind.SOOTHE_FAILED,
                    minOf(playedAt + 35_000, ended),
                    Severity.NOTE,
                    soother,
                )
            )
        }

        if (cry.optBoolean("escalated", false)) {
            db.events().insert(
                row(
                    EventKind.ESCALATED_TO_PARENT,
                    minOf(started + 100_000, ended),
                    Severity.URGENT,
                    "Crying for ${minOf(100, seconds)}s and still going",
                    true,
                )
            )
        }

        db.events().insert(
            row(
                EventKind.CRY_ENDED,
                ended,
                if (seconds >= 20) Severity.ATTENTION else Severity.NOTE,
                withCause = cause != null,
            )
        )
    }

    /** A sparkline with the right shape for the trend the episode had. */
    private fun envelopeFor(trend: String): String {
        val points = when (trend) {
            "RISING" -> listOf(38, 42, 47, 45, 52, 58, 61, 59, 66, 71, 74, 78)
            "SETTLING" -> listOf(74, 71, 68, 70, 62, 57, 55, 48, 44, 41, 36, 32)
            else -> listOf(55, 58, 54, 57, 53, 59, 55, 52, 57, 54, 58, 55)
        }
        return points.joinToString(",")
    }

    private inline fun JSONArray.forEach(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }
}
