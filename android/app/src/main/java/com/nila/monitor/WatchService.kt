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
import androidx.camera.core.Preview
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nila.actions.Notifier
import com.nila.data.EventKind
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import com.nila.vision.ActivityRules
import com.nila.vision.CameraWatch
import com.nila.vision.FaceWatcher
import com.nila.vision.RoomSensors
import com.nila.vision.SafeZone
import com.nila.vision.SafeZoneStore
import com.nila.vision.WatchAlert
import com.nila.vision.WatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The camera lane, as a service instead of a screen.
 *
 * ### Why this exists
 *
 * The safety watch used to live entirely inside `WatchScreen`: the camera, the
 * detectors and the rules were all held in `remember {}`, so they were torn
 * down when the composable left the tree. That meant the watch stopped when the
 * screen turned off, when the user switched tabs, and when Android decided to
 * dim -- and it stopped *silently*, which for a monitor is the worst way to
 * fail. A parent who put the phone on the shelf and walked away was being
 * watched over by nothing.
 *
 * A foreground service with `FOREGROUND_SERVICE_TYPE_CAMERA` is the only way
 * Android allows sustained camera access with the screen off, and it comes with
 * a persistent notification, which is the honest trade: an app that watches a
 * room continuously should be visibly doing so.
 *
 * ### Running alongside the microphone
 *
 * This is a second service, not an extension of [MonitorService], and the two
 * run at once: the audio lane holds the microphone and this one holds the
 * camera. It subscribes to [MonitorService.state] so that a posture event and a
 * cry can be reported as one thing -- a baby who rolls over in their sleep is a
 * notification, and a baby who rolls over and starts crying has probably fallen
 * or got stuck.
 */
class WatchService : LifecycleService() {

    companion object {
        private const val TAG = "WatchService"

        const val ACTION_START = "com.nila.WATCH_START"
        const val ACTION_STOP = "com.nila.WATCH_STOP"

        private val _state = MutableStateFlow(WatchState())
        val state: StateFlow<WatchState> = _state.asStateFlow()

        /**
         * The preview surface, handed over by whichever screen is showing.
         *
         * The service owns the camera because it has to outlive the screen, and
         * CameraX will not bind the same camera to two lifecycles. So the
         * screen does not bind a preview of its own -- it lends the service a
         * surface provider, and gets it back by clearing this.
         */
        @Volatile
        private var pendingPreview: Preview? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Attach or detach a live preview without disturbing the analysis.
         *
         * Rebinding the camera is the only way to add a preview use case, so
         * this costs a bind -- a few hundred milliseconds of the watch not
         * analysing, once, when a screen opens. The alternative is either no
         * preview or no continuity, and continuity is the whole point of this
         * class.
         */
        fun attachPreview(context: Context, preview: Preview?) {
            pendingPreview = preview
            if (_state.value.running) start(context)
        }
    }

    private lateinit var notifier: Notifier
    private lateinit var db: NilaDatabase
    private lateinit var zones: SafeZoneStore
    private lateinit var sensors: RoomSensors

    private var watch: CameraWatch? = null
    private val rules = ActivityRules()
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * The other phone, shared with the microphone lane.
     *
     * This lane is the one that produces level 4 -- climbing, out of the safe
     * zone, face-down and not recovering -- so it is the lane whose alerts can
     * take the other phone's screen. See [com.nila.phonelink.LinkProtocol.tierFor].
     */
    private var phoneLink: com.nila.phonelink.GuardianLink? = null

    /** Last time each event alerted, so the same one does not repeat all night. */
    private val alertedAt = mutableMapOf<ActivityRules.Event, Long>()

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        db = NilaDatabase.get(this)
        zones = SafeZoneStore(this)
        sensors = RoomSensors(this)
        notifier.ensureChannels()
        phoneLink = runCatching {
            com.nila.phonelink.GuardianLink.acquire(this)
        }.getOrNull()

        // The audio lane, folded in as it changes. Collected for the life of
        // the service so a cry that starts after a posture event still promotes
        // it -- the fall usually comes first and the crying a second later.
        lifecycleScope.launch {
            MonitorService.state.collect { audio ->
                val crying = audio.currentEvidence != null
                if (crying != _state.value.cryingNow) {
                    _state.value = _state.value.copy(cryingNow = crying)
                    // Re-run the decision with the cry folded in, which is what
                    // turns an ATTENTION posture into an URGENT one.
                    _state.value.assessment?.let { raiseIfNeeded(_state.value) }
                }
            }
        }

        lifecycleScope.launch {
            zones.zone.collect { zone ->
                _state.value = _state.value.copy(zone = zone)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopWatching(); stopSelf() }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        val notification = notifier.monitoringNotification(
            "Nila is watching the cot",
            "Frames are analysed on this phone and never leave it.",
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, Notifier.ID_WATCH, notification, type)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fault("Camera permission was not granted")
            return
        }

        // A rebind, for a preview being attached or detached mid-watch. The
        // rules and the distance baseline deliberately survive it: they are
        // about the baby, not about the camera binding.
        val existing = watch
        if (existing != null) {
            existing.stop()
            bind(existing)
            return
        }

        val camera = CameraWatch(this, ::onFrame)
        watch = camera
        rules.reset()
        alertedAt.clear()
        sensors.start()
        acquireWakeLock()
        bind(camera)

        _state.value = _state.value.copy(
            running = true,
            fault = null,
            zone = zones.current,
        )
        Log.i(TAG, "watch started")
    }

    private fun bind(camera: CameraWatch) {
        camera.start(this, pendingPreview)
        _state.value = _state.value.copy(
            poseAvailable = camera.poseAvailable,
            poseDelegate = camera.poseDelegate,
        )
    }

    /**
     * One analysed frame, through the rules and out to the state.
     *
     * Runs on the camera executor. Nothing here touches the database or the
     * notification manager directly -- [raiseIfNeeded] does, and it is
     * deliberately the only place that can, so there is one path to an alarm.
     */
    private fun onFrame(frame: CameraWatch.Frame) {
        val assessment = rules.update(
            ActivityRules.Observation(
                pose = frame.pose,
                faceFound = frame.face is FaceWatcher.State.FaceVisible,
                motion = frame.motion,
                baselineReady = frame.motion.relativeToBaseline > 0f,
                zone = _state.value.zone,
            )
        )
        val next = _state.value.copy(
            face = frame.face,
            assessment = assessment,
            motion = frame.motion,
            framesAnalysed = watch?.framesAnalysed ?: 0L,
            cameraMoved = sensors.cameraMoved,
            lightChanged = sensors.lightChanged,
            roomDark = sensors.roomDark,
            lux = sensors.lux,
        )
        _state.value = next
        raiseIfNeeded(next)
    }

    /** The only route from a camera frame to a woken parent. */
    private fun raiseIfNeeded(state: WatchState) {
        val event = state.assessment?.primary ?: return
        val now = System.currentTimeMillis()
        fun since(e: ActivityRules.Event) =
            alertedAt[e]?.let { now - it } ?: Long.MAX_VALUE
        val decision = WatchAlert.decide(
            state,
            sinceSameEventMs = since(event),
            sinceCameraMovedMs = since(ActivityRules.Event.CAMERA_MOVED),
        ) ?: return

        alertedAt[decision.event] = System.currentTimeMillis()
        notifier.alert(decision.title, decision.body, decision.severity,
                       id = Notifier.ID_SAFETY)
        runCatching {
            phoneLink?.sendAlert(
                decision.title, decision.body, decision.severity, seconds = 0
            )
        }
        _state.value = _state.value.copy(lastAlert = decision.body)

        val kind = kindOf(decision.event) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                db.events().insert(
                    EventRecord(
                        kind = kind.name,
                        severityLevel = decision.severity.level,
                        startedAtMs = System.currentTimeMillis(),
                        note = decision.body,
                    )
                )
            }.onFailure { Log.w(TAG, "could not log a safety event", it) }
        }
    }

    /**
     * The rule that fired, as a row in the log.
     *
     * Distinct kinds rather than one catch-all, because the timeline is what
     * gets shown to a doctor and "rolled onto her front, four times this week"
     * is a sentence a catch-all cannot produce.
     */
    private fun kindOf(event: ActivityRules.Event): EventKind? = when (event) {
        ActivityRules.Event.ROLLED_TO_FRONT -> EventKind.ROLLED_TO_FRONT
        ActivityRules.Event.ON_SIDE -> EventKind.ROLLED_TO_SIDE
        ActivityRules.Event.CRAWLING -> EventKind.CRAWLING
        ActivityRules.Event.SITTING_UP -> EventKind.SITTING_UP
        ActivityRules.Event.STANDING_UP -> EventKind.STANDING_UP
        ActivityRules.Event.LEFT_SAFE_ZONE -> EventKind.LEFT_SAFE_ZONE
        ActivityRules.Event.MOVED_AWAY,
        ActivityRules.Event.FAR_FROM_START -> EventKind.MOVED_AWAY
        ActivityRules.Event.OUT_OF_VIEW -> EventKind.OUT_OF_VIEW
        ActivityRules.Event.UNUSUALLY_STILL -> EventKind.STILLNESS
        ActivityRules.Event.CAMERA_MOVED -> EventKind.CAMERA_MOVED
        // These never raise an alert, so they never reach the log either.
        // Returning null rather than inventing a kind keeps the timeline free
        // of rows saying nothing happened.
        ActivityRules.Event.SETTLED,
        ActivityRules.Event.ON_BACK,
        ActivityRules.Event.FACE_ONLY,
        ActivityRules.Event.CALIBRATING -> null
    }

    private fun fault(message: String) {
        Log.e(TAG, "fault: $message")
        _state.value = _state.value.copy(running = false, fault = message)
        // A watch that stops quietly is worse than no watch, so a failure is
        // surfaced the same way an event would be.
        notifier.alert("Nila stopped watching", message, Severity.URGENT)
        stopSelf()
    }

    /**
     * A partial wake lock, for the same reason the audio lane holds one.
     *
     * Without it the CPU sleeps between frames and analysis becomes
     * unpredictable exactly when the screen is off -- which is the condition
     * this service exists for.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nila:watch")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun stopWatching() {
        watch?.shutdown()
        watch = null
        sensors.stop()
        rules.reset()
        alertedAt.clear()
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        pendingPreview = null
        // No goodbye: the microphone lane may still be running, and from the
        // other phone's point of view nothing has stopped. release() only tears
        // the socket down once both lanes have let go.
        if (phoneLink != null) {
            runCatching { com.nila.phonelink.GuardianLink.release(phoneLink) }
            phoneLink = null
        }
        _state.value = WatchState(zone = zones.current)
        Log.i(TAG, "watch stopped")
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }
}
