package com.nila.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nila.NilaApp
import com.nila.actions.IrActuator
import com.nila.actions.SootheMemory
import com.nila.actions.Soother
import com.nila.assistant.Assistant
import com.nila.assistant.KnowledgeIndex
import com.nila.assistant.LlmBridge
import com.nila.assistant.TextScanner
import com.nila.assistant.agents.MedicineReviewPipeline
import com.nila.assistant.agents.MedicineReviewResult
import com.nila.data.HealthRecord
import com.nila.data.MotherProfile
import com.nila.data.RecordCategory
import com.nila.data.RecordStore
import com.nila.data.RecordSubject
import android.graphics.Bitmap
import android.net.Uri
import com.nila.audio.ModelCard
import com.nila.audio.SelfTest
import com.nila.data.BabyProfile
import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.EventRecord
import com.nila.monitor.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.TimeUnit

/**
 * Everything the screens read from.
 *
 * One view model rather than several: the screens are views onto the same
 * running system, and splitting the state would mean synchronising it.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    private val db = (app as NilaApp).database
    private val sootheMemory = SootheMemory(app)
    val irActuator = IrActuator(app)
    private val reminders = com.nila.actions.Reminders(app)
    private val voiceRecorder = com.nila.actions.VoiceRecorder(app)
    val nfc = com.nila.actions.NfcLogger(app)
    private val clinicReport = com.nila.export.ClinicReport(app, db)
    private val deskBridge = com.nila.bridge.DeskBridge(app, db)

    private val index by lazy { KnowledgeIndex.load(app) }
    private val llm by lazy { LlmBridge.tryLoad(app) }
    private val modelInstaller = com.nila.assistant.ModelInstaller(app)
    private val visionInstaller = com.nila.assistant.ModelInstaller(
        app, com.nila.assistant.ModelInstaller.Model.VISION)
    val sceneDescriber = com.nila.vision.SceneDescriber(app)
    private val assistant by lazy { Assistant(app, db, index, llm) }
    private val scanner by lazy { TextScanner(app) }
    val recordStore = RecordStore(app)
    private val medicineReview by lazy {
        MedicineReviewPipeline(index, db.healthRecords())
    }

    val monitor: StateFlow<com.nila.monitor.MonitorState> = MonitorService.state

    val events: StateFlow<List<EventRecord>> = db.events().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val care: StateFlow<List<CareRecord>> = db.care().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val baby: StateFlow<BabyProfile?> = db.baby().observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _colic = MutableStateFlow(ColicSummary())
    val colic: StateFlow<ColicSummary> = _colic.asStateFlow()

    // --------------------------------------------------------- the dashboard

    /**
     * How far back the charts read, fixed when the screen is first built.
     *
     * Eight days rather than seven: the window is a *query* bound, and a query
     * bound that is exactly the display window loses the first day the moment
     * the app is left open past midnight. The extra day costs a handful of rows
     * and means [Insights] always has a full week to arrange.
     */
    private val insightWindowStartMs =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Insights.DAYS + 1L)

    /**
     * The week, arranged.
     *
     * Derived from the rows rather than stored: there is no dashboard state to
     * keep in sync, and a cry logged by the service or a feed tapped on the
     * watch redraws every chart without anything having to remember to ask.
     * The arranging itself is off the main thread because it walks a week of
     * episodes into twenty-four hourly buckets, which is cheap but not free.
     */
    val insights: StateFlow<Insights.Summary> = combine(
        db.events().observeSince(insightWindowStartMs),
        db.care().observeSince(insightWindowStartMs),
    ) { events, care ->
        Insights.build(System.currentTimeMillis(), events, care)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Insights.Summary())

    /**
     * When the sleep in progress started, or null when the baby is awake.
     *
     * The sleep log used to be write-only -- every tap wrote `SLEEP_START` and
     * nothing ever wrote an end, so "she slept" was a timestamp with no
     * duration attached and there was no honest way to chart a night. The
     * button alternates now, which costs the person using it at 3am nothing
     * extra and is the whole reason a sleep chart can exist at all.
     *
     * The tracker's running clock ticks from this in the UI rather than being
     * pushed from here on a timer: a view model that emits a new value every
     * second to redraw one line of text is a wake-up a monitor app running all
     * night cannot justify.
     */
    val asleepSinceMs: StateFlow<Long?> = care.map {
        Insights.openSleepSince(it, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * How much cry audio this phone is holding, for the settings screen.
     *
     * A count and a size rather than a list. The point of showing it is that a
     * parent can see the feature has a footprint and can end it in one tap;
     * browsing thirty recordings of their own baby crying is not something
     * anybody wants from a settings screen.
     */
    data class ClipsHeld(val count: Int = 0, val bytes: Long = 0L) {
        val readableSize: String
            get() = when {
                bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
                bytes > 0 -> "${bytes / 1000} kB"
                else -> "0 kB"
            }
    }

    private val _clipsHeld = MutableStateFlow(ClipsHeld())
    val clipsHeld: StateFlow<ClipsHeld> = _clipsHeld.asStateFlow()

    /**
     * Delete every kept recording, and forget the paths that pointed at them.
     *
     * Both halves, in that order. Deleting the files alone would leave the
     * timeline offering a play button for audio that is gone, which reads as a
     * broken app rather than as a setting that worked.
     */
    fun deleteAllClips() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.nila.audio.EpisodeRecorder.deleteAll(getApplication())
            }
            db.events().clearClips()
            refresh()
        }
    }

    /**
     * Log the other end of whichever sleep state we are in.
     *
     * Read from the care rows rather than from [asleepSinceMs], so the tap
     * cannot act on a cached value: the flow is `WhileSubscribed`, and a
     * stopwatch that starts a second sleep because its own state had gone cold
     * writes a log that no longer describes a night.
     */
    fun toggleSleep() {
        val open = Insights.openSleepSince(care.value, System.currentTimeMillis()) != null
        logCare(if (open) CareKind.SLEEP_END else CareKind.SLEEP_START)
    }

    private val _sootheStats = MutableStateFlow<List<SootheMemory.Stats>>(emptyList())
    val sootheStats: StateFlow<List<SootheMemory.Stats>> = _sootheStats.asStateFlow()

    private val _reminders =
        MutableStateFlow<List<com.nila.actions.Reminders.Setting>>(emptyList())
    val reminderSettings: StateFlow<List<com.nila.actions.Reminders.Setting>> =
        _reminders.asStateFlow()

    private val _recordings = MutableStateFlow<List<java.io.File>>(emptyList())
    val recordings: StateFlow<List<java.io.File>> = _recordings.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _modelState =
        MutableStateFlow<com.nila.assistant.ModelInstaller.State>(
            com.nila.assistant.ModelInstaller.State.Absent)
    val modelState: StateFlow<com.nila.assistant.ModelInstaller.State> =
        _modelState.asStateFlow()

    private val _visionModelState =
        MutableStateFlow<com.nila.assistant.ModelInstaller.State>(
            com.nila.assistant.ModelInstaller.State.Absent)
    val visionModelState: StateFlow<com.nila.assistant.ModelInstaller.State> =
        _visionModelState.asStateFlow()

    private val _observation =
        MutableStateFlow<com.nila.vision.SceneDescriber.Observation?>(null)
    val observation: StateFlow<com.nila.vision.SceneDescriber.Observation?> =
        _observation.asStateFlow()

    private val _looking = MutableStateFlow(false)
    val looking: StateFlow<Boolean> = _looking.asStateFlow()

    private var lastAutomaticLookMs = 0L

    private val _irRemote = MutableStateFlow<IrActuator.Remote?>(null)
    val irRemote: StateFlow<IrActuator.Remote?> = _irRemote.asStateFlow()

    val records: StateFlow<List<HealthRecord>> = db.healthRecords().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mother: StateFlow<MotherProfile?> = db.mother().observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _review = MutableStateFlow<MedicineReviewResult?>(null)
    val review: StateFlow<MedicineReviewResult?> = _review.asStateFlow()

    private val _scanStage = MutableStateFlow<String?>(null)
    val scanStage: StateFlow<String?> = _scanStage.asStateFlow()

    private val _scannedText = MutableStateFlow<String?>(null)
    val scannedText: StateFlow<String?> = _scannedText.asStateFlow()

    /** Previous medicine checks, newest first, for the collapsed history list. */
    val scanHistory: StateFlow<List<com.nila.data.MedicineScanRecord>> =
        db.medicineScans().recent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What is typed in the Ask box.
     *
     * Here rather than in the composable so it survives leaving the screen. The
     * answer already lived here, so a remembered local meant the two fell out of
     * step: navigate away and back, and the answer was still on screen with an
     * empty question box above it.
     */
    private val _draftQuestion = MutableStateFlow("")
    val draftQuestion: StateFlow<String> = _draftQuestion.asStateFlow()

    private val _answer = MutableStateFlow<Assistant.Answer?>(null)
    val answer: StateFlow<Assistant.Answer?> = _answer.asStateFlow()

    /** True while the model is shortening an answer that is already on screen. */
    private val _phrasing = MutableStateFlow(false)
    val phrasing: StateFlow<Boolean> = _phrasing.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _selfTest = MutableStateFlow<SelfTest.Result?>(null)
    val selfTest: StateFlow<SelfTest.Result?> = _selfTest.asStateFlow()

    private val _selfTestRunning = MutableStateFlow(false)
    val selfTestRunning: StateFlow<Boolean> = _selfTestRunning.asStateFlow()

    /**
     * Objective cry-duration tracking against the colic criteria.
     *
     * The Wessel definition is three or more hours a day on three or more days a
     * week. Every part of that is a duration, so measuring it makes no clinical
     * claim -- it replaces a sleep-deprived parent's paper diary with an
     * accurate count they can hand to a paediatrician.
     */
    data class ColicSummary(
        val todaySeconds: Int = 0,
        val todayEpisodes: Int = 0,
        val daysOverThreeHoursThisWeek: Int = 0,
        val weekSeconds: Int = 0,
    ) {
        val todayMinutes: Int get() = todaySeconds / 60
        val meetsDurationCriterion: Boolean get() = daysOverThreeHoursThisWeek >= 3
        val todayHours: Float get() = todaySeconds / 3600f
    }

    /**
     * One dedicated thread for the model.
     *
     * Generation is a blocking JNI call a coroutine timeout cannot interrupt,
     * so it must never run on a shared pool -- two slow calls on
     * Dispatchers.Default would starve the rest of the app.
     */
    private val llmDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "nila-llm").apply { priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()

    private val _bridgeAddress = MutableStateFlow<String?>(null)
    val bridgeAddress: StateFlow<String?> = _bridgeAddress.asStateFlow()

    // --- the second phone -----------------------------------------------------

    private val linkStore = com.nila.phonelink.LinkStore(app)

    val linkRole: StateFlow<com.nila.phonelink.LinkStore.Role> = linkStore.role
        .stateIn(viewModelScope, SharingStarted.Eagerly,
                 com.nila.phonelink.LinkStore.Role.GUARDIAN)

    val linkPaired: StateFlow<Boolean> = linkStore.paired
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Live on the receiving phone only; the sending phone reads [linkSummary]. */
    val linkStatus: StateFlow<com.nila.phonelink.ParentLink.Status> =
        com.nila.phonelink.ParentService.status

    /**
     * The six digits, while they are on screen.
     *
     * Held in memory and never written anywhere. It exists for as long as it
     * takes somebody to read it off one phone and type it into the other; the
     * key derived from it is what persists.
     */
    private val _pairingCode = MutableStateFlow<String?>(null)
    val pairingCode: StateFlow<String?> = _pairingCode.asStateFlow()

    private val _exportedReport = MutableStateFlow<java.io.File?>(null)
    val exportedReport: StateFlow<java.io.File?> = _exportedReport.asStateFlow()

    init {
        refresh()
        // Fetch the OCR models now, so the first real scan is not the one that
        // discovers they are missing.
        viewModelScope.launch(Dispatchers.IO) { runCatching { scanner.warmUp() } }

        // Build the language model in the background. Mapping the weights and
        // building an XNNPack cache takes long enough that doing it lazily
        // makes the first question the slow one.
        viewModelScope.launch(llmDispatcher) { runCatching { llm.load() } }

        // A tapped sticker logs immediately, from whatever screen is open.
        viewModelScope.launch {
            NfcInbox.taps.collect { kind -> logCare(kind, detail = "NFC tag") }
        }
        // A fresh install has no last few days, and this app is mostly a record
        // of the last few days. Seeds only into an empty database -- see DemoSeed.
        viewModelScope.launch(Dispatchers.IO) {
            if (com.nila.data.DemoSeed.applyIfEmpty(app, db)) refresh()
        }

        // Re-arm whatever was scheduled before the process was last killed.
        viewModelScope.launch { runCatching { reminders.rescheduleAll() } }
    }

    /**
     * Load the text recogniser, because the Scan screen just opened.
     *
     * Deliberately not done at startup: PP-OCR's sessions are the largest
     * single thing in this process's native heap, and an app that spends the
     * night listening should not be carrying them.
     */
    fun prepareScanner() {
        viewModelScope.launch(Dispatchers.IO) { scanner.prepare() }
    }

    /** Assign the sticker currently held by the inbox to a kind of event. */
    fun assignPendingTag(kind: CareKind) {
        NfcInbox.pendingTag.value?.let { tag ->
            nfc.assign(tag, kind)
            NfcInbox.clearPending()
            logCare(kind, detail = "NFC tag assigned")
        }
    }

    /**
     * Build the one-page PDF for a paediatric appointment.
     *
     * Written to external files so it can be shared out of the app -- this is
     * the one document the user explicitly wants to hand to someone else, and
     * the only thing in the app designed to leave the phone.
     */
    fun exportClinicReport() {
        viewModelScope.launch {
            _thinking.value = true
            _exportedReport.value = withContext(Dispatchers.IO) {
                runCatching { clinicReport.build() }.getOrNull()
            }
            _thinking.value = false
        }
    }

    fun clearExport() { _exportedReport.value = null }

    /**
     * Start or stop the laptop dashboard.
     *
     * Off by default and started only here, because it opens a listening socket
     * -- a reasonable thing to do on your own Wi-Fi, and not a reasonable thing
     * to do without being asked.
     */
    fun toggleDeskBridge() {
        if (deskBridge.isRunning) {
            deskBridge.close()
            _bridgeAddress.value = null
        } else if (deskBridge.start()) {
            _bridgeAddress.value = deskBridge.localAddress()
        }
    }

    val bridgeRunning: Boolean get() = deskBridge.isRunning

    fun refresh() {
        viewModelScope.launch {
            val recorded = com.nila.actions.VoiceRecorder.existing(getApplication())
            _recordings.value = recorded
            _sootheStats.value = sootheMemory.allStats(
                recorded.map { it.nameWithoutExtension } + Soother.builtIns.map { it.id }
            )
            _colic.value = computeColic()
            _clipsHeld.value = withContext(Dispatchers.IO) {
                val files = com.nila.audio.EpisodeRecorder.existing(getApplication())
                ClipsHeld(files.size, files.sumOf { it.length() })
            }
            _reminders.value = reminders.settings()
            _irRemote.value = irActuator.remote()
            _modelState.value = modelInstaller.state()
            _visionModelState.value = visionInstaller.state()
        }
    }

    /**
     * Ask the vision model what it can see in the cot.
     *
     * On its own thread and only on demand: the engine is over a gigabyte
     * resident, so it is built for the look and released when the screen goes
     * away. The cheap always-on watch is unaffected and keeps running.
     */
    fun lookAtCot(frame: android.graphics.Bitmap, automatic: Boolean = false) {
        if (_looking.value) return
        // An automatic look is rate-limited; a tap is not.
        //
        // The trigger is "the cheap watch lost the face", and that state
        // oscillates -- during testing it fired the model five times in eight
        // seconds, each attempt loading and failing against a gigabyte of
        // weights. A parent pressing the button gets an answer immediately.
        if (automatic) {
            val now = System.currentTimeMillis()
            if (now - lastAutomaticLookMs < AUTOMATIC_LOOK_COOLDOWN_MS) return
            lastAutomaticLookMs = now
        }
        viewModelScope.launch {
            _looking.value = true
            val seen = withContext(llmDispatcher) {
                runCatching { sceneDescriber.look(frame) }.getOrNull()
            }
            _observation.value = seen
            _looking.value = false

            // Only the concerning ones reach a notification. A model that
            // narrates a sleeping baby every few minutes is a model that gets
            // muted before it ever says anything useful.
            if (seen != null && seen.isConcerning) {
                withContext(Dispatchers.IO) {
                    val notifier = com.nila.actions.Notifier(getApplication())
                    notifier.ensureChannels()
                    notifier.alert(
                        title = "Worth a look",
                        body = seen.headline + ". " + seen.doing,
                        severity = com.nila.data.Severity.ATTENTION,
                        id = 60,
                    )
                }
            }
        }
    }

    fun downloadVisionModel() {
        viewModelScope.launch {
            visionInstaller.start()
            while (true) {
                val state = visionInstaller.state()
                _visionModelState.value = state
                if (state !is com.nila.assistant.ModelInstaller.State.Downloading) break
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    fun cancelVisionDownload() {
        viewModelScope.launch { visionInstaller.cancel(); refresh() }
    }

    /** Release the vision engine. Called when the Watch screen goes away. */
    fun releaseSceneDescriber() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { sceneDescriber.close() } }
    }

    /**
     * Download the optional language model.
     *
     * The one outbound request this app can make, behind an explicit tap. It
     * polls while running because DownloadManager reports progress by query
     * rather than by callback.
     */
    fun downloadModel() {
        viewModelScope.launch {
            modelInstaller.start()
            while (true) {
                val state = modelInstaller.state()
                _modelState.value = state
                if (state !is com.nila.assistant.ModelInstaller.State.Downloading) break
                kotlinx.coroutines.delay(1_500)
            }
            // Newly arrived: build it now rather than on the first question.
            if (_modelState.value is com.nila.assistant.ModelInstaller.State.Installed) {
                viewModelScope.launch(llmDispatcher) { runCatching { llm.load() } }
            }
        }
    }

    fun cancelModelDownload() {
        viewModelScope.launch { modelInstaller.cancel(); refresh() }
    }

    // ----------------------------------------------------------- reminders
    fun setReminderEnabled(kind: CareKind, enabled: Boolean) {
        viewModelScope.launch { reminders.setEnabled(kind, enabled); refresh() }
    }

    fun setReminderInterval(kind: CareKind, minutes: Int) {
        viewModelScope.launch { reminders.setInterval(kind, minutes); refresh() }
    }

    // ------------------------------------------------- the caregiver's voice
    fun startRecording(label: String): Boolean {
        val started = voiceRecorder.start(label)
        _recording.value = started
        return started
    }

    fun stopRecording() {
        voiceRecorder.stop()
        _recording.value = false
        refresh()
    }

    fun deleteRecording(file: java.io.File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            refresh()
        }
    }

    /** Play a recording back at the volume the monitor would use. */
    fun previewSoother(soother: Soother) {
        viewModelScope.launch(Dispatchers.IO) {
            val player = com.nila.actions.SoothePlayer(getApplication())
            player.play(soother, loop = false)
            kotlinx.coroutines.delay(8_000)
            player.stop()
        }
    }

    // ------------------------------------------------------- infrared remote
    fun saveIrRemote(address: Int, command: Int) {
        viewModelScope.launch { irActuator.saveRemote(address, command); refresh() }
    }

    fun testIrRemote(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(irActuator.nudgeRoom()) }
    }

    private suspend fun computeColic(): ColicSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val dayStart = now - dayMs

        var overThreeHours = 0
        var weekTotal = 0
        for (d in 0 until 7) {
            val to = now - d * dayMs
            val from = to - dayMs
            val seconds = db.events().cryingSecondsBetween(from, to)
            weekTotal += seconds
            if (seconds >= 3 * 3600) overThreeHours++
        }

        ColicSummary(
            todaySeconds = db.events().cryingSecondsBetween(dayStart, now),
            todayEpisodes = db.events().cryEpisodesBetween(dayStart, now),
            daysOverThreeHoursThisWeek = overThreeHours,
            weekSeconds = weekTotal,
        )
    }

    /**
     * Runs the bundled cry through the real detector on the real accelerator.
     *
     * Nothing about this path is mocked: it is the same engine, the same
     * frontend and the same TFLite graph the monitor uses, so a pass here means
     * the audio pipeline genuinely works on this device.
     */
    fun runSelfTest() {
        viewModelScope.launch {
            _selfTestRunning.value = true
            // Clear first. Leaving the old numbers up while the new run happens
            // makes a second press look like it did nothing at all.
            _selfTest.value = null
            _selfTest.value = withContext(Dispatchers.Default) {
                SelfTest(getApplication()).run()
            }
            _selfTestRunning.value = false
        }
    }

    // ---- the camera lane
    //
    // The safe zone lives in DataStore rather than in this ViewModel because
    // the service reads it on every camera bind, including when no screen and
    // therefore no ViewModel exists.
    private val safeZones = com.nila.vision.SafeZoneStore(app)

    val safeZone: StateFlow<com.nila.vision.SafeZone> = safeZones.zone
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.nila.vision.SafeZone.DEFAULT)

    suspend fun saveSafeZone(zone: com.nila.vision.SafeZone) = safeZones.save(zone)

    suspend fun resetSafeZone() = safeZones.reset()

    fun startWatching() = com.nila.monitor.WatchService.start(getApplication())
    fun stopWatching() = com.nila.monitor.WatchService.stop(getApplication())

    fun startMonitoring() = MonitorService.start(getApplication())
    fun stopMonitoring() = MonitorService.stop(getApplication())

    // --- the second phone -----------------------------------------------------

    /**
     * Switch this phone between watching and receiving.
     *
     * Stops whichever service belongs to the old role before starting the new
     * one. A phone left holding the microphone *and* listening for another
     * phone's alerts would sound its own alarm at its own cry.
     */
    fun setLinkRole(role: com.nila.phonelink.LinkStore.Role) {
        viewModelScope.launch {
            linkStore.setRole(role)
            when (role) {
                com.nila.phonelink.LinkStore.Role.GUARDIAN -> {
                    com.nila.phonelink.ParentService.stop(getApplication())
                }
                com.nila.phonelink.LinkStore.Role.PARENT -> {
                    MonitorService.stop(getApplication())
                    if (linkStore.key != null) {
                        com.nila.phonelink.ParentService.start(getApplication())
                    }
                }
            }
        }
    }

    /**
     * Show a code on this phone, and key this phone to it at the same time.
     *
     * SecureRandom rather than Random: a predictable code is a code somebody on
     * the same Wi-Fi can guess without watching the screen, which removes the
     * one thing making six digits acceptable.
     */
    fun generatePairingCode() {
        val code = com.nila.phonelink.LinkProtocol.formatCode(
            java.security.SecureRandom().nextInt(1_000_000)
        )
        viewModelScope.launch {
            linkStore.pair(code)
            _pairingCode.value = code
        }
    }

    fun hidePairingCode() { _pairingCode.value = null }

    /** Type the code shown on the other phone. Returns false if it is malformed. */
    fun pairWithCode(code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.length != 6 || !trimmed.all(Char::isDigit)) return false
        viewModelScope.launch {
            linkStore.pair(trimmed)
            if (linkStore.currentRole == com.nila.phonelink.LinkStore.Role.PARENT) {
                com.nila.phonelink.ParentService.start(getApplication())
            }
        }
        return true
    }

    fun unpairPhones() {
        viewModelScope.launch {
            com.nila.phonelink.ParentService.stop(getApplication())
            linkStore.unpair()
            _pairingCode.value = null
        }
    }

    /** What the sending phone can say about the link, read straight off it. */
    val linkSummary: String
        get() = com.nila.phonelink.GuardianLink.peek()?.describe()
            ?: "Alerts will be shared once monitoring starts"

    /** The address to type on the other phone if discovery does not find us. */
    val linkAddress: String?
        get() = com.nila.phonelink.GuardianLink.peek()?.localAddress()

    /**
     * Whether a level-4 alert can actually take this phone's screen.
     *
     * False is the normal state on Android 14 and later. Surfaced so the app
     * says so, rather than letting it be discovered on the night it matters.
     */
    val canUseFullScreenAlerts: Boolean
        get() = com.nila.actions.Notifier(getApplication()).canUseFullScreenAlerts

    /** Open the Settings page where the user can grant it. */
    fun requestFullScreenAlerts() {
        val app = getApplication<Application>()
        val intent = if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            android.content.Intent(
                android.provider.Settings
                    .ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:${app.packageName}"),
            )
        } else {
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${app.packageName}"),
            )
        }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    /**
     * Replay a recorded cry through the live pipeline.
     *
     * The one part of the audio path an emulator cannot exercise is the
     * microphone; this exercises everything else, and on a real phone it is
     * still the only way to see the escalation ladder run without waiting for a
     * baby to oblige.
     */
    fun runDemoCry() = MonitorService.simulate(getApplication())

    /**
     * Dismiss the summary a finished demo leaves on screen.
     *
     * The demo no longer clears itself when the episode ends -- the account of
     * what Nila did is the reason to run it, and it is read afterwards. This is
     * the reader saying they are done with it.
     */
    fun closeDemo() = MonitorService.closeDemo()

    /**
     * Put away the card for an episode that has closed.
     *
     * Not [closeDemo]: that resets the whole state holder, which is right after
     * a demo and wrong here -- monitoring is still running underneath the card.
     */
    fun dismissEpisode() = MonitorService.dismissEpisode()

    fun logCare(kind: CareKind, detail: String? = null) {
        viewModelScope.launch {
            db.care().insert(
                CareRecord(kind = kind.name, atMs = System.currentTimeMillis(),
                           detail = detail)
            )
            // Logging a feed silently moves the next feeding reminder. That is
            // the whole reason these are elapsed-time rather than clock alarms.
            runCatching { reminders.reschedule(kind) }
            refresh()
        }
    }

    fun deleteCare(id: Long) {
        viewModelScope.launch { db.care().delete(id); refresh() }
    }

    fun saveBaby(profile: BabyProfile) {
        viewModelScope.launch { db.baby().upsert(profile) }
    }

    fun ask(question: String) {
        if (question.isBlank()) return
        viewModelScope.launch {
            _thinking.value = true
            _answer.value = null
            val language = baby.value?.language ?: "en"
            val result = withContext(Dispatchers.Default) {
                assistant.ask(question, language)
            }
            // On screen immediately, as retrieved text. Everything below is
            // presentation, and if it fails this is what stands.
            _answer.value = result
            _thinking.value = false

            _phrasing.value = llm.isReady
            if (llm.isReady) {
                val phrased = withTimeoutOrNull(PHRASING_TIMEOUT_MS) {
                    withContext(llmDispatcher) { assistant.phrase(result, language) }
                }
                if (phrased != null && _answer.value === result) {
                    _answer.value = result.copy(text = phrased, refinedByLlm = true)
                }
                _phrasing.value = false
            }

            // No generation in this path either, for the same reason it was
            // removed from the medicine verdict -- and this time with a worse
            // example.
            //
            // Asked "I don't feel bonded with my baby", the 0.5B model returned:
            //
            //   "The baby has not yet passed the newborn hearing screening,
            //    which is a sign of potential hearing issues."
            //
            // No such thing had happened. It had taken a health record saying
            // the screening was passed, inverted it, and attached a diagnosis.
            //
            // The grounding check could not catch it and no version of that
            // check can: it measures how much of the output's vocabulary comes
            // from the source, and every word of that sentence did. Overlap
            // cannot see a flipped negation or a clause reassembled into a
            // claim the source never made. Telling a new mother her baby may be
            // deaf, on the strength of a fluent 0.5B model rearranging her own
            // notes, is not a quality trade-off.
            //
            // The retrieved answer is already complete, sourced and readable.
            // The model itself has since been removed from the app entirely:
            // 25 MB of native library and around 210 MB of resident memory,
            // for a feature that was making the answers worse.

            db.conversations().insert(
                com.nila.data.ConversationRecord(
                    atMs = System.currentTimeMillis(),
                    question = question,
                    answer = result.text,
                    sources = result.sources.joinToString(" | "),
                    language = language,
                )
            )
        }
    }

    fun identifyMedicine(ocrText: String) {
        viewModelScope.launch {
            _thinking.value = true
            _answer.value = null
            val language = baby.value?.language ?: "en"
            _answer.value = withContext(Dispatchers.Default) {
                assistant.identifyMedicine(ocrText, language)
            }
            _thinking.value = false
        }
    }

    private companion object {
        /**
         * Generous, because waiting costs the user nothing.
         *
         * The retrieved answer is already on screen by the time this starts;
         * the shorter version replaces it when it arrives. So the only thing a
         * long timeout risks is the text changing under someone who has
         * finished reading, and the only thing a short one guarantees is that
         * the feature never fires on a slow device.
         */
        const val PHRASING_TIMEOUT_MS = 45_000L

        /** Two minutes between unprompted looks. */
        const val AUTOMATIC_LOOK_COOLDOWN_MS = 120_000L
    }

    /**
     * Wipe the log and lay the demo family down again.
     *
     * For running the same demo twice. Destructive by definition, which is why
     * it sits at the bottom of Settings and says so.
     */
    fun resetDemoData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.nila.data.DemoSeed.reset(getApplication(), db)
            }
            refresh()
        }
    }

    fun setDraftQuestion(text: String) { _draftQuestion.value = text }

    fun clearAnswer() {
        _answer.value = null
        _draftQuestion.value = ""
    }

    // ------------------------------------------------------------ health records
    fun saveMother(profile: MotherProfile) {
        viewModelScope.launch { db.mother().upsert(profile) }
    }

    /**
     * Import a document and OCR it immediately.
     *
     * Doing the OCR at import rather than on demand is what makes the shelf
     * useful: the text is then searchable, and the medicine review can find a
     * condition on a prescription she photographed weeks ago without her having
     * remembered to type it in.
     */
    fun addRecord(
        subject: RecordSubject,
        category: RecordCategory,
        title: String,
        notes: String,
        bitmap: Bitmap? = null,
        uri: Uri? = null,
        recordedAtMs: Long = System.currentTimeMillis(),
    ) {
        viewModelScope.launch {
            _thinking.value = true
            val result = withContext(Dispatchers.IO) {
                var path: String? = null
                var mime: String? = null
                var text = ""

                val image = bitmap ?: uri?.let { recordStore.decodeUri(it) }
                if (image != null) {
                    path = recordStore.saveImage(image).absolutePath
                    mime = "image/jpeg"
                    text = runCatching { scanner.scan(image).text }.getOrDefault("")
                } else if (uri != null) {
                    recordStore.importUri(uri)?.let { (file, type) ->
                        path = file.absolutePath
                        mime = type
                    }
                }

                HealthRecord(
                    subject = subject.name,
                    category = category.name,
                    title = title.ifBlank { category.label },
                    notes = notes,
                    filePath = path,
                    mimeType = mime,
                    extractedText = text,
                    recordedAtMs = recordedAtMs,
                )
            }
            db.healthRecords().insert(result)
            _thinking.value = false
        }
    }

    fun deleteRecord(record: HealthRecord) {
        viewModelScope.launch {
            recordStore.delete(record.filePath)
            db.healthRecords().delete(record)
        }
    }

    // ------------------------------------------------------ medicine review
    /**
     * Runs the five-stage review over a photographed or picked medicine strip.
     *
     * Stage names are published as they run so the UI can show the pipeline
     * working rather than a spinner -- which is both better feedback and a
     * far better demo.
     */
    fun reviewMedicine(bitmap: Bitmap? = null, uri: Uri? = null, typed: String? = null) {
        viewModelScope.launch {
            _thinking.value = true
            _review.value = null
            _scannedText.value = null

            val text = when {
                typed != null -> typed
                else -> {
                    _scanStage.value = "Reading the label"
                    withContext(Dispatchers.Default) {
                        val image = bitmap ?: uri?.let { recordStore.decodeUri(it) }
                        val scan = image?.let {
                            runCatching { scanner.scan(it) }.getOrNull()
                        }
                        if (scan?.modelNotReady == true) {
                            // Say what is actually happening. "Couldn't
                            // recognise a medicine" would be a lie here.
                            _scanStage.value = "Preparing the text reader"
                            scanner.warmUp()
                            image?.let {
                                runCatching { scanner.scan(it).text }.getOrDefault("")
                            } ?: ""
                        } else {
                            scan?.text ?: ""
                        }
                    }
                }
            }
            _scannedText.value = text

            _scanStage.value = "Checking your history"
            val language = baby.value?.language ?: "en"
            val result = withContext(Dispatchers.Default) {
                medicineReview.review(text, mother.value, language)
            }
            // The answer is on screen now. Everything below is cosmetic.
            _scanStage.value = null
            _review.value = result
            _thinking.value = false

            // Kept, so it can be looked at again. Scans used to live only in
            // this view model, which meant leaving the screen destroyed the
            // verdict -- and somebody who checked a strip at the pharmacy
            // counter had nothing to show a partner an hour later.
            //
            // Only a real identification is filed. A scan that read nothing
            // usable is a photograph of a failure, and a history full of
            // "Not enough information" rows is a history nobody scrolls.
            if (result.verdict != com.nila.assistant.agents.Verdict.UNKNOWN) {
                val image = bitmap ?: uri?.let { recordStore.decodeUri(it) }
                withContext(Dispatchers.IO) {
                    val path = image?.let {
                        runCatching { recordStore.saveImage(it).absolutePath }.getOrNull()
                    }
                    db.medicineScans().insert(
                        com.nila.data.MedicineScanRecord(
                            atMs = System.currentTimeMillis(),
                            name = result.medicine?.generic?.takeIf { it.isNotBlank() }
                                ?: result.medicine?.title
                                ?: typed?.trim().orEmpty().ifBlank { "Unnamed medicine" },
                            verdict = result.verdict.name,
                            headline = result.headline,
                            summary = result.summary,
                            imagePath = path,
                            scannedText = text.take(400).ifBlank { null },
                            sources = result.sources.joinToString("\n").ifBlank { null },
                            cautions = result.cautions.joinToString("\n").ifBlank { null },
                        )
                    )
                }
            }

            // No language model in this path, deliberately and permanently.
            //
            // It was wired here and removed after watching it produce this, from
            // a correct AVOID verdict for a penicillin-allergic mother:
            //
            //   "Amoxicillin ... could cause asthma in some people and iron
            //    deficiency anaemia in others ... it was generally safe to take
            //    Amoxicillin if you are not allergic to it."
            //
            // It had read her *conditions* as side effects, invented causation,
            // and then softened a do-not-take verdict. A 0.5B model rewriting
            // safety-critical text is not a quality trade-off, it is a hazard --
            // and the rule-based summary it was "improving" was already correct,
            // sourced and readable.
        }
    }

    fun clearReview() {
        _review.value = null
        _scannedText.value = null
        _scanStage.value = null
    }

    fun deleteScan(record: com.nila.data.MedicineScanRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // The image goes with the row. A JPEG in app-private storage
                // that nothing points at is a file the user cannot reach and
                // cannot delete.
                record.imagePath?.let { runCatching { recordStore.delete(it) } }
            }
            db.medicineScans().delete(record.id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { llm.close() }
        runCatching { sceneDescriber.close() }
        runCatching { llmDispatcher.close() }
        runCatching { scanner.close() }
        runCatching { deskBridge.close() }
    }

    val knowledgeSize: Int get() = index.size
    val ocrStatus: String get() = scanner.describe()
    val llmStatus: String get() = llm.describe()
    val modelHonesty: String get() = ModelCard.honestSummary
}
