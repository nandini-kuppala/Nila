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
    /**
     * Versioned, and the version is part of the key on purpose.
     *
     * The database is built with `fallbackToDestructiveMigration`, so a schema
     * change empties it -- and the flag lives in a *different* store, which
     * survives. The two then disagree in the worst possible direction: a
     * database with nothing in it and a flag saying it has already been
     * seeded, which is an app that opens on seven empty screens.
     *
     * Bumping this alongside the schema version lets the seeder run once more
     * against the now-empty database. The `used` check below still protects
     * anyone whose real data survived.
     */
    private val SEEDED = booleanPreferencesKey("seeded_v4")

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
        db.medicineScans().clear()
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
            val at = momentOf(entry, now) ?: return@forEach
            db.care().insert(
                CareRecord(
                    kind = entry.getString("kind"),
                    atMs = at,
                    detail = entry.optString("detail").ifBlank { null },
                )
            )
        }

        val demoClip = installDemoClip(context)
        json.optJSONArray("cries")?.forEach { cry -> writeEpisode(db, now, cry, demoClip) }

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

        // Three medicines already checked, so the history list on Scan opens
        // with something in it. A collapsed list with no rows is indis-
        // tinguishable from a list that is broken, and the three chosen cover
        // one of each verdict -- including the AVOID that comes from her own
        // penicillin allergy rather than from the general guidance, which is
        // the whole argument for checking against a health record.
        json.optJSONArray("scans")?.forEach { scan ->
            db.medicineScans().insert(
                MedicineScanRecord(
                    atMs = now - scan.getLong("hoursAgo") * 3_600_000L,
                    name = scan.getString("name"),
                    verdict = scan.getString("verdict"),
                    headline = scan.getString("headline"),
                    summary = scan.getString("summary"),
                    // No image: these were typed, not photographed. Shipping a
                    // photograph of a real strip in the APK would be inventing
                    // evidence for a demo.
                    imagePath = null,
                    sources = scan.optString("sources").split('|')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString("\n").ifBlank { null },
                    cautions = scan.optString("cautions").ifBlank { null },
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
     * When a seeded row happened, in one of two ways, and never in the future.
     *
     * `minutesAgo` / `hoursAgo` is an offset from install: the right shape for
     * "she fed an hour ago", which has to stay true whenever anyone installs
     * this.
     *
     * `daysAgo` with `atHour` and `atMinute` is a *clock* time on a past day.
     * That form exists because the dashboard plots against the hour of the day,
     * and a routine expressed purely as offsets rotates with the install: seed
     * it at nine in the morning and the demo family's night feeds land at
     * lunchtime, on top of the shading that says which hours are night. A
     * routine has to be anchored to the clock or it is not a routine.
     *
     * Today's entries that have not happened yet are dropped rather than
     * written. An app installed at eight in the morning showing this evening's
     * bath and bedtime as already logged is inventing a future, and every
     * elapsed-time reading on the screen ("fed -6h ago") goes with it.
     */
    private fun momentOf(entry: JSONObject, now: Long): Long? {
        if (entry.has("minutesAgo")) {
            return now - TimeUnit.MINUTES.toMillis(entry.getLong("minutesAgo"))
        }
        if (entry.has("hoursAgo")) {
            return now - (entry.getDouble("hoursAgo") * 3_600_000.0).toLong()
        }
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .minusDays(entry.optLong("daysAgo", 0))
        val at = day.atTime(entry.optInt("atHour", 0), entry.optInt("atMinute", 0))
            .atZone(zone).toInstant().toEpochMilli()
        return at.takeIf { it <= now }
    }

    /**
     * One cry episode becomes the rows the monitor would have written for it:
     * a start, the soothing attempt and its outcome, an escalation, an end.
     *
     * Writing only a summary row would leave the timeline showing effects with
     * no causes -- an alert with nothing before it.
     */
    /**
     * Lay the bundled cry into the kept-clip store, once, and return its path.
     *
     * A single file that every flagged demo episode points at. Copying it per
     * episode would put twenty-two copies of the same ninety seconds on a
     * phone to make a list look busier, which is the sort of thing a demo does
     * and a product should not.
     */
    private fun installDemoClip(context: Context): String? = runCatching {
        val target = java.io.File(
            com.nila.audio.EpisodeRecorder.keptDir(context), "cry-demo-family.wav"
        )
        if (!target.exists()) {
            val wav = com.nila.audio.WavReader.fromAsset(context, "demo_cry.wav")
            // Looped to about half a minute. The bundled recording is six
            // seconds long, and a play button offering six seconds beside an
            // episode that lasted four minutes reads as a bug rather than as a
            // clip. It is the same six seconds repeated -- the demo playback
            // loops it too -- not a longer recording we do not have.
            val loops = (DEMO_CLIP_SECONDS * wav.sampleRate / wav.samples.size)
                .coerceAtLeast(1)
            writeWav(target, wav.sampleRate) { out ->
                repeat(loops) { out(wav.samples) }
            }
        }
        target.absolutePath
    }.onFailure { Log.w(TAG, "could not install the demo clip", it) }.getOrNull()

    private const val DEMO_CLIP_SECONDS = 30

    /**
     * A 16-bit PCM WAV, written the same way [com.nila.audio.EpisodeRecorder]
     * writes one, so the player cannot tell a seeded clip from a real one.
     */
    private fun writeWav(
        target: java.io.File,
        sampleRate: Int,
        body: ((FloatArray) -> Unit) -> Unit,
    ) {
        val pcm = java.io.ByteArrayOutputStream()
        body { samples ->
            val bytes = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, f ->
                val v = (f.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            pcm.write(bytes)
        }
        val data = pcm.toByteArray()
        val header = java.nio.ByteBuffer.allocate(44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        target.outputStream().use { it.write(header.array()); it.write(data) }
    }

    private suspend fun writeEpisode(
        db: NilaDatabase,
        now: Long,
        cry: JSONObject,
        demoClip: String?,
    ) {
        val seconds = cry.getInt("seconds")
        // The clock time in the entry is when the cry *started*, so an episode
        // still running at the moment of install is dropped whole rather than
        // written with an end that has not happened.
        val started = momentOf(cry, now) ?: return
        val ended = started + seconds * 1000L
        if (ended > now) return
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

        val episodeId = db.events().insert(
            row(EventKind.CRY_STARTED, started, Severity.NOTE)
        )
        db.events().setEpisode(episodeId, episodeId)

        suspend fun add(record: EventRecord) {
            db.events().insert(record.copy(episodeId = episodeId))
        }

        val soother = cry.optString("soothed").ifBlank { null }
        if (soother != null && seconds > 20) {
            val playedAt = started + 20_000
            add(row(EventKind.SOOTHE_PLAYED, playedAt, Severity.NOTE, soother, true))
            val settled = cry.optBoolean("settled", false)
            add(
                row(
                    if (settled) EventKind.SOOTHE_WORKED else EventKind.SOOTHE_FAILED,
                    minOf(playedAt + 35_000, ended),
                    Severity.NOTE,
                    soother,
                )
            )
        }

        val escalated = cry.optBoolean("escalated", false)
        if (escalated) {
            add(
                row(
                    EventKind.ESCALATED_TO_PARENT,
                    minOf(started + 100_000, ended),
                    Severity.URGENT,
                    "Crying for ${minOf(100, seconds)}s and still going",
                    true,
                )
            )
        }

        // `startedAtMs` on a CRY_ENDED row is the start of the cry, not its
        // end. That is what MonitorService.recordFor writes, and it is what
        // every query that buckets crying into days or hours assumes. The
        // seeder used to put the end time here, which filed a fifty-five minute
        // cry that began at 23:30 under the following day -- invisible in a
        // timeline, and a whole hour in the wrong column of the colic total.
        add(
            row(
                EventKind.CRY_ENDED,
                ended,
                if (seconds >= 20) Severity.ATTENTION else Severity.NOTE,
                withCause = cause != null,
            ).copy(startedAtMs = started, durationSeconds = seconds)
        )

        // The demo family's flagged cries carry audio, because a play button
        // that is only real once somebody's own baby has had a bad night is a
        // feature nobody can see. It is the bundled demo cry -- one recording,
        // pointed at by every flagged episode -- and this is the demo family,
        // every number of which is authored. Nothing here is presented as a
        // recording of a real event that happened to a real infant.
        // The same rule the service uses: woken, or the reason head came back
        // pain. Seeding every long cry with audio would put nineteen playable
        // recordings in a twenty-one-cry timeline and make the badge -- whose
        // job is to say "this one is worth listening to" -- mean nothing.
        val flagged = escalated || cause == "belly_pain"
        if (ClipPolicy.enabled.value && flagged) {
            demoClip?.let { db.events().setClip(episodeId, it) }
        }
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
