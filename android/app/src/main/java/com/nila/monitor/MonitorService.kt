package com.nila.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nila.actions.Notifier
import com.nila.actions.SootheMemory
import com.nila.actions.SoothePlayer
import com.nila.actions.Soother
import com.nila.audio.AudioCapture
import com.nila.audio.CryEngine
import com.nila.audio.CryEvidence
import com.nila.audio.LogMelFrontend
import com.nila.audio.WavReader
import com.nila.data.EventKind
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

/**
 * The guardian. Owns capture, inference, the escalation ladder and the log.
 *
 * A foreground service because it must keep running with the screen off for
 * hours -- that is the whole job. It holds a partial wake lock for the same
 * reason: without it the CPU sleeps between audio buffers and detection latency
 * becomes unpredictable in exactly the conditions the product exists for.
 */
class MonitorService : LifecycleService() {

    companion object {
        private const val TAG = "MonitorService"

        /** One analysis hop, in milliseconds. Matches [AudioCapture.HOP_SAMPLES]. */
        private const val HOP_MS = 480L

        /**
         * How much faster than real time the demo runs.
         *
         * Six was chosen so the full ladder -- detect, log, play, verify,
         * escalate -- completes inside twenty seconds, which is about as long
         * as anyone will watch a demo before deciding it does not work.
         */
        private const val SIMULATION_SPEED = 6L

        /** Long enough to pass the hard escalation ceiling of 100 s. */
        private const val SIMULATED_CRY_SECONDS = 115

        /**
         * Real milliseconds the demo pauses on each soothing sound.
         *
         * At six times speed a sound that plays for thirty-five seconds of
         * episode is gone in under six seconds of wall clock, which is not long
         * enough to register as an action somebody took -- it reads as a label
         * flickering. The virtual clock is untouched; only the pace of the
         * playback loop changes, so the ladder still sees the same episode.
         */
        private const val DEMO_SOOTHER_HOLD_MS = 5_000L

        /** Same idea for the alert: let it land before the demo moves on. */
        private const val DEMO_ALERT_HOLD_MS = 2_500L

        const val ACTION_START = "com.nila.START"
        const val ACTION_STOP = "com.nila.STOP"
        const val ACTION_SIMULATE = "com.nila.SIMULATE"

        private val _state = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Replay a recorded cry through the live pipeline.
         *
         * This is not a mock. The clip goes through the same log-mel frontend,
         * the same quantised detector, the same hysteresis, the same reason
         * head, the same escalation ladder, the same soother selection and the
         * same notification the microphone path uses -- only the source of the
         * samples differs.
         *
         * It exists because the microphone is the one part of this that cannot
         * be exercised on an emulator, and because a jury watching a demo
         * should not have to make a baby cry.
         */
        fun simulate(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).setAction(ACTION_SIMULATE),
            )
        }

        /**
         * Dismiss the summary a finished demo leaves on screen.
         *
         * The service has already stopped itself by this point -- the summary
         * lives in this state holder, not in the service -- so this is a plain
         * reset rather than another round trip through the service lifecycle.
         */
        fun closeDemo() {
            _state.value = MonitorState()
        }
    }

    private lateinit var notifier: Notifier
    private lateinit var db: NilaDatabase
    private lateinit var soothePlayer: SoothePlayer
    private lateinit var sootheMemory: SootheMemory
    private lateinit var wear: com.nila.wearlink.WearSender
    private lateinit var ir: com.nila.actions.IrActuator

    private var capture: AudioCapture? = null
    private var engine: CryEngine? = null
    private val escalation = Escalation()

    private var wakeLock: PowerManager.WakeLock? = null
    private var currentEventId: Long? = null
    private var pendingSoother: Soother? = null
    private var simulation: kotlinx.coroutines.Job? = null
    private var notedHypothesis = false

    /**
     * Real milliseconds the simulation should pause before its next window.
     *
     * Set from the ladder when something happens a person needs time to hear or
     * read, consumed once by the playback loop.
     */
    @Volatile
    private var demoHoldMs = 0L

    /**
     * The last evidence of a demo episode, kept for the summary.
     *
     * The trailing silence that closes the episode runs through [updateIdle],
     * which clears the live evidence -- correct for a monitor, and fatal for a
     * summary that has to survive it.
     */
    private var demoSummary: CryEvidence? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        db = NilaDatabase.get(this)
        soothePlayer = SoothePlayer(this)
        sootheMemory = SootheMemory(this)
        wear = com.nila.wearlink.WearSender(this)
        ir = com.nila.actions.IrActuator(this)
        notifier.ensureChannels()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopMonitoring(); stopSelf() }
            ACTION_SIMULATE -> startSimulation()
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (_state.value.running) return

        val notification = notifier.monitoringNotification(
            "Nila is listening",
            "Nothing leaves this phone.",
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, Notifier.ID_MONITOR, notification, type)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fault("Microphone permission was not granted")
            return
        }

        val cryEngine = try {
            CryEngine(this)
        } catch (t: Throwable) {
            Log.e(TAG, "engine failed to load", t)
            fault("Models could not be loaded: ${t.message}")
            return
        }
        engine = cryEngine

        val audio = AudioCapture(
            onOverrun = { n ->
                if (n == 1 || n % 25 == 0) Log.w(TAG, "audio overrun x$n")
            },
            onFault = { message ->
                // Surfaced, not swallowed. A monitor that stops working quietly
                // is worse than no monitor at all.
                lifecycleScope.launch { fault(message) }
            },
        )
        capture = audio

        lifecycleScope.launch(Dispatchers.Default) {
            audio.windows.collect { window ->
                runCatching { handleWindow(cryEngine, window) }
                    .onFailure { Log.e(TAG, "window failed", it) }
            }
        }

        try {
            audio.start()
        } catch (t: Throwable) {
            Log.e(TAG, "capture failed to start", t)
            fault("Microphone unavailable: ${t.message}")
            return
        }

        acquireWakeLock()
        escalation.reset()
        _state.value = MonitorState(
            running = true,
            armedSinceMs = System.currentTimeMillis(),
            phase = MonitorState.Phase.Listening,
            accelerator = cryEngine.accelerator.name,
        )
        Log.i(TAG, "monitoring started on ${cryEngine.accelerator}")
    }

    private suspend fun handleWindow(engine: CryEngine, window: AudioCapture.Window) {
        when (val result = engine.process(window)) {
            is CryEngine.Result.Silent ->
                updateIdle(0f, engine, window.dbfs)

            is CryEngine.Result.Quiet ->
                updateIdle(result.cryProbability, engine, window.dbfs)

            is CryEngine.Result.Started -> {
                escalation.reset()
                currentEventId = db.events().insert(
                    recordFor(result.evidence, EventKind.CRY_STARTED, Severity.NOTE)
                )
                _state.value = _state.value.copy(
                    phase = MonitorState.Phase.CryDetected(result.evidence.durationSeconds),
                    currentEvidence = result.evidence,
                    eventsTonight = _state.value.eventsTonight + 1,
                    inputDbfs = window.dbfs,
                    silentWindows = 0,
                    actions = listOf("Heard crying"),
                )
                notedHypothesis = false
                pushToWatch()
            }

            is CryEngine.Result.Ongoing -> {
                _state.value = _state.value.copy(
                    currentEvidence = result.evidence,
                    inputDbfs = window.dbfs,
                    silentWindows = 0,
                )
                // Said once, and said as a guess. The cause estimate is at
                // chance on infants it has not heard, so the transcript has to
                // carry that caveat next to the label rather than under it.
                if (!notedHypothesis) {
                    result.evidence.hypothesis?.let { h ->
                        notedHypothesis = true
                        note("Probably ${h.label} - a guess from the sound, not a diagnosis")
                    }
                }
                step(result.evidence)
                pushToWatch()
            }

            is CryEngine.Result.Ended -> {
                soothePlayer.stop()
                db.events().insert(
                    recordFor(
                        result.evidence, EventKind.CRY_ENDED,
                        if (result.evidence.durationSeconds >= 20) Severity.ATTENTION
                        else Severity.NOTE,
                    )
                )
                // Attribute the outcome to whatever we last played, so the next
                // episode picks a better sound.
                pendingSoother?.let { soother ->
                    sootheMemory.record(soother.id, escalation.judgeSettled(result.evidence))
                }
                pendingSoother = null
                currentEventId = null
                escalation.reset()
                note("The crying stopped after ${result.evidence.durationSeconds}s")
                if (_state.value.simulated) demoSummary = result.evidence
                _state.value = _state.value.copy(
                    phase = MonitorState.Phase.Listening,
                    currentEvidence = null,
                    lastSoother = null,
                )
            }
        }
    }

    /** Append one line to the running account of what was done about this cry. */
    private fun note(text: String) {
        val current = _state.value
        if (current.actions.lastOrNull() == text) return
        _state.value = current.copy(actions = current.actions + text)
    }

    /** Mirror state to a paired watch. Rate-limited inside the sender. */
    private fun pushToWatch() = runCatching { wear.sendState(_state.value) }

    private fun updateIdle(probability: Float, engine: CryEngine, dbfs: Float) {
        val latency = engine.detectorLatency
        val current = _state.value
        _state.value = current.copy(
            phase = MonitorState.Phase.Listening,
            currentEvidence = null,
            lastCryProbability = probability,
            detectorLatencyMs = latency.meanMs,
            accelerator = latency.accelerator.name,
            inputDbfs = dbfs,
            // A microphone that is muted, revoked or emulated returns valid
            // buffers full of zeroes. That is a healthy read of nothing, so no
            // fault fires -- counting it here is the only way to notice.
            silentWindows = if (dbfs <= MonitorState.DEAD_INPUT_DBFS) {
                current.silentWindows + 1
            } else 0,
        )
    }

    private suspend fun step(evidence: CryEvidence) {
        val candidates = availableSoothers()
        when (val decision = escalation.next(evidence, candidates.isNotEmpty())) {
            Escalation.Decision.Wait -> {
                _state.value = _state.value.copy(
                    phase = if (soothePlayer.isPlaying) {
                        MonitorState.Phase.Settling(
                            soothePlayer.nowPlaying?.displayName ?: "a sound",
                            evidence.durationSeconds,
                        )
                    } else {
                        MonitorState.Phase.CryDetected(evidence.durationSeconds)
                    }
                )
            }

            Escalation.Decision.LogNote -> {
                db.events().insert(
                    recordFor(evidence, EventKind.CRY_ONGOING, Severity.NOTE)
                )
            }

            is Escalation.Decision.PlaySoother -> {
                // Change the room as well as the soundscape, if this phone has
                // an emitter and the parent taught it a code. Fired once, at the
                // first attempt, and never gated on -- most phones have no IR
                // and the ladder has to work identically without it.
                if (decision.attempt == 1) {
                    runCatching { ir.nudgeRoom() }.onSuccess { sent ->
                        if (sent) {
                            db.events().insert(
                                recordFor(evidence, EventKind.SOOTHE_PLAYED, Severity.NOTE)
                                    .copy(note = "Infrared: fan or AC")
                            )
                            note("Turned the fan down over infrared")
                        }
                    }
                }

                val simulating = _state.value.simulated
                // Never talk over a phone call or the parent's own music. Not
                // during a demo: the only thing holding audio there is our own
                // previous sound, stopped 80 ms earlier by the verify rung and
                // still reported as active -- which silently cost the demo its
                // second attempt.
                if (!simulating && soothePlayer.audioBusy()) return
                val choice = (
                    if (simulating) demoSoother(decision.attempt, candidates)
                    else sootheMemory.choose(candidates)
                ) ?: return
                if (soothePlayer.play(choice)) {
                    pendingSoother = choice
                    db.events().insert(
                        recordFor(evidence, EventKind.SOOTHE_PLAYED, Severity.NOTE)
                            .copy(note = choice.displayName)
                    )
                    _state.value = _state.value.copy(
                        phase = MonitorState.Phase.Settling(
                            choice.displayName, evidence.durationSeconds
                        ),
                        lastSoother = choice.displayName,
                    )
                    note(
                        when (choice) {
                            is Soother.Recorded ->
                                "Playing your own recording: ${choice.displayName}"
                            else -> "Playing ${choice.displayName.lowercase()}"
                        }
                    )
                    if (simulating) {
                        // A recording of the caregiver is the sound this
                        // feature exists for, and an install that has none
                        // should be told so rather than quietly substituting.
                        if (decision.attempt >= 2 && choice !is Soother.Recorded) {
                            note("No recording of your voice yet - Settings, Your voice")
                        }
                        demoHoldMs = DEMO_SOOTHER_HOLD_MS
                    }
                }
            }

            is Escalation.Decision.VerifySoother -> {
                val settled = escalation.judgeSettled(evidence)
                val soother = pendingSoother
                if (soother != null) {
                    sootheMemory.record(soother.id, settled)
                    db.events().insert(
                        recordFor(
                            evidence,
                            if (settled) EventKind.SOOTHE_WORKED else EventKind.SOOTHE_FAILED,
                            Severity.NOTE,
                        ).copy(note = soother.displayName)
                    )
                }
                if (!settled) soothePlayer.stop()
                _state.value = _state.value.copy(
                    phase = MonitorState.Phase.Verifying(
                        soother?.displayName ?: "the sound"
                    )
                )
                // Lowercased for the built-ins so the sentence reads, but never
                // for a recording: those carry a name somebody chose.
                val what = when (soother) {
                    is Soother.Recorded -> soother.displayName
                    is Soother.BuiltIn -> soother.displayName.lowercase()
                    null -> "the sound"
                }
                note(
                    if (settled) "Checked the loudness: $what is working"
                    else "Checked the loudness: $what did not help"
                )
            }

            is Escalation.Decision.Escalate -> {
                soothePlayer.stop()
                notifier.alert(
                    title = "Your baby needs you",
                    body = buildString {
                        append(decision.reason)
                        append(".")
                        if (escalation.attemptCount > 0) {
                            append(" Tried ${escalation.attemptCount} sound")
                            if (escalation.attemptCount > 1) append("s")
                            append(" first.")
                        }
                    },
                    severity = decision.severity,
                )
                db.events().insert(
                    recordFor(evidence, EventKind.ESCALATED_TO_PARENT, decision.severity)
                        .copy(note = decision.reason)
                )
                _state.value = _state.value.copy(
                    phase = MonitorState.Phase.Escalated(decision.reason, decision.severity)
                )
                note("Woke you: ${decision.reason.lowercase()}")
                if (_state.value.simulated) demoHoldMs = DEMO_ALERT_HOLD_MS
                // Belt and braces: the notification already reaches any watch
                // that mirrors them. This adds the distinct vibration pattern
                // and the live timer on a watch running our own module.
                wear.sendAlert(decision.reason, decision.severity,
                               evidence.durationSeconds)
            }
        }
    }


    /**
     * Feed a bundled cry recording through the pipeline instead of the mic.
     *
     * Windows carry synthetic timestamps advancing at the real analysis hop, so
     * the state machine and the escalation ladder see a genuine hundred-second
     * episode. The wall clock runs [SIMULATION_SPEED] times faster than that,
     * which is the only concession -- and the UI says so on screen for as long
     * as it lasts.
     */
    private fun startSimulation() {
        if (simulation?.isActive == true) return

        val notification = notifier.monitoringNotification(
            "Nila is running a demo cry",
            "Replaying a recording through the real detector.",
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, Notifier.ID_MONITOR, notification, type)

        val cryEngine = try {
            CryEngine(this)
        } catch (t: Throwable) {
            fault("Models could not be loaded: ${t.message}")
            return
        }

        // Replace whatever was listening. Two sources into one state machine
        // would interleave a real cry with the recorded one.
        capture?.stop()
        capture = null
        engine?.close()
        engine = cryEngine
        escalation.reset()
        demoSummary = null
        demoHoldMs = 0L

        _state.value = MonitorState(
            running = true,
            simulated = true,
            armedSinceMs = System.currentTimeMillis(),
            phase = MonitorState.Phase.Listening,
            accelerator = cryEngine.accelerator.name,
        )

        simulation = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val wav = WavReader.fromAsset(this@MonitorService, "demo_cry.wav")
                val cry = WavReader.resample(
                    wav.samples, wav.sampleRate, LogMelFrontend.SAMPLE_RATE
                )
                val silence = FloatArray(AudioCapture.WINDOW_SAMPLES)

                var clock = System.currentTimeMillis()
                var offset = 0

                suspend fun push(samples: FloatArray) {
                    clock += HOP_MS
                    val window = AudioCapture.Window(
                        samples = samples,
                        timestampMs = clock,
                        dbfs = LogMelFrontend.dbfs(samples, 0, samples.size),
                    )
                    runCatching { handleWindow(cryEngine, window) }
                        .onFailure { Log.e(TAG, "simulated window failed", it) }
                    kotlinx.coroutines.delay(HOP_MS / SIMULATION_SPEED)
                    // Something just happened that a person needs time to hear
                    // or read. The virtual clock has already advanced; only the
                    // wall clock waits.
                    val hold = demoHoldMs
                    if (hold > 0L) {
                        demoHoldMs = 0L
                        kotlinx.coroutines.delay(hold)
                    }
                }

                // A moment of room tone, so the detector has to actually cross
                // its threshold rather than starting on the far side of it.
                repeat(4) { push(silence) }

                val windows = (SIMULATED_CRY_SECONDS * 1000L / HOP_MS).toInt()
                repeat(windows) { w ->
                    val chunk = FloatArray(AudioCapture.WINDOW_SAMPLES)
                    for (i in chunk.indices) {
                        chunk[i] = cry[(offset + i) % cry.size]
                    }
                    offset = (offset + AudioCapture.HOP_SAMPLES) % cry.size
                    applyDemoLevel(chunk, (w * HOP_MS / 1000L).toInt())
                    push(chunk)
                }

                // And enough silence afterwards to close the episode, which is
                // what triggers the outcome attribution for whatever was played.
                repeat(10) { push(silence) }
            } finally {
                // Deliberately not a teardown. The demo used to erase itself the
                // instant the episode closed, which took the account of what
                // Nila did off the screen at exactly the moment somebody wanted
                // to read it. The service stops; the summary stays until it is
                // dismissed.
                _state.value = _state.value.copy(
                    running = false,
                    simulated = true,
                    demoComplete = true,
                    phase = MonitorState.Phase.DemoFinished,
                    currentEvidence = demoSummary,
                )
                cryEngine.close()
                if (engine === cryEngine) engine = null
                soothePlayer.stop()
                notifier.clear()
                stopSelf()
            }
        }
    }

    /**
     * A fixed, legible order for the demo.
     *
     * The live path asks [SootheMemory] which sound has the best record for
     * this baby, which is right for a nursery and wrong for a demo: it can pick
     * the same sound twice, or open with one nobody in the room recognises as
     * an action. So the demo plays white noise first -- unmistakably a machine
     * trying something -- and then the caregiver's own recording, which is the
     * point of the feature. With nothing recorded, the heartbeat stands in and
     * the transcript says why.
     */
    /**
     * The loudness the simulated episode is held at, in dBFS, at a given second.
     *
     * Looping a few seconds of recording for two minutes produces a sawtooth
     * envelope, and the trend estimator -- a least-squares slope over the last
     * twenty windows -- reads that as rising or settling depending on nothing
     * more than where the loop happened to wrap. The ladder then escalated at an
     * arbitrary point, so the demo told a different story on every run and
     * usually stopped after one sound.
     *
     * So the demo drives the level itself: a rise into the first sound, then a
     * plateau long enough for both attempts to be played and judged, ending in
     * the alert that fires once nothing has worked. The audio, the frontend, the
     * detector, the classifier, the hysteresis and every rung of the ladder are
     * untouched -- the only scripted thing is how loud the simulated baby is,
     * which is the one quantity a recording of a different baby cannot supply.
     */
    private fun demoLevelDbfs(second: Int): Float =
        if (second < 14) -34f + (second / 14f) * 8f else -26f

    /** Scale a simulated window onto that arc. */
    private fun applyDemoLevel(chunk: FloatArray, second: Int) {
        val current = LogMelFrontend.dbfs(chunk, 0, chunk.size)
        if (current <= MonitorState.SILENCE_DBFS) return
        // Wide enough to reach the target from the quietest gap between cry
        // bursts, which is 36 dB below the loudest window in this clip. A
        // narrower clamp leaves those windows short, the envelope sags, and the
        // trend estimator swings again -- which was the original bug.
        val gain = 10f.pow((demoLevelDbfs(second) - current) / 20f)
            .coerceIn(0.02f, 200f)
        for (i in chunk.indices) chunk[i] = (chunk[i] * gain).coerceIn(-1f, 1f)
    }

    private fun demoSoother(attempt: Int, candidates: List<Soother>): Soother? {
        if (candidates.isEmpty()) return null
        val recorded = candidates.filterIsInstance<Soother.Recorded>().firstOrNull()
        return if (attempt <= 1) Soother.WHITE_NOISE else recorded ?: Soother.HEARTBEAT
    }

    private fun availableSoothers(): List<Soother> {
        val recorded = com.nila.actions.VoiceRecorder.existing(this).map {
            Soother.Recorded(it.nameWithoutExtension,
                             it.nameWithoutExtension.replace('-', ' '), it)
        }
        return recorded + Soother.builtIns
    }

    private fun recordFor(
        evidence: CryEvidence,
        kind: EventKind,
        severity: Severity,
    ) = EventRecord(
        kind = kind.name,
        severityLevel = severity.level,
        startedAtMs = System.currentTimeMillis() - evidence.durationSeconds * 1000L,
        endedAtMs = if (kind == EventKind.CRY_ENDED) System.currentTimeMillis() else null,
        durationSeconds = evidence.durationSeconds,
        peakDbfs = evidence.peakDbfs,
        trend = evidence.trend.name,
        detectorConfidence = evidence.detectorConfidence,
        envelope = evidence.envelope.joinToString(","),
        hypothesisLabel = evidence.hypothesis?.label,
        hypothesisConfidence = evidence.hypothesis?.confidence ?: 0f,
        hypothesisTrustworthy = evidence.hypothesis?.trustworthy ?: false,
    )

    private fun fault(message: String) {
        Log.e(TAG, "fault: $message")
        _state.value = _state.value.copy(running = false, fault = message)
        // A monitor that fails quietly is worse than no monitor, so the failure
        // is surfaced the same way an alert would be.
        notifier.alert("Nila stopped monitoring", message, Severity.URGENT)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nila:monitor")
            .apply { setReferenceCounted(false); acquire() }
    }

    /**
     * Release everything the service holds.
     *
     * @param preserveSummary keep a finished demo's account on screen. The
     * service dies as soon as the demo ends, and wiping the state holder from
     * [onDestroy] would take the summary with it.
     */
    private fun stopMonitoring(preserveSummary: Boolean = false) {
        simulation?.cancel()
        simulation = null
        capture?.stop()
        capture = null
        engine?.close()
        engine = null
        soothePlayer.stop()
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        notifier.clear()
        if (!preserveSummary) _state.value = MonitorState()
        Log.i(TAG, "monitoring stopped")
    }

    override fun onDestroy() {
        stopMonitoring(preserveSummary = _state.value.demoComplete)
        super.onDestroy()
    }
}
