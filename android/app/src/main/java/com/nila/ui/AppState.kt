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
