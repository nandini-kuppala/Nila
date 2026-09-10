package com.nila.ui.screens

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nila.data.Severity
import com.nila.monitor.WatchService
import com.nila.ui.AppState
import com.nila.ui.components.CalmChip
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.StatBlock
import com.nila.ui.components.WarningBox
import com.nila.ui.theme.SeverityAttention
import com.nila.ui.theme.SeverityCalm
import com.nila.ui.theme.SeverityUrgent
import com.nila.vision.ActivityRules
import com.nila.vision.CameraWatch
import com.nila.vision.DemoFootage
import com.nila.vision.FaceWatcher
import com.nila.vision.Posture
import com.nila.vision.SafeZone
import com.nila.vision.WatchState
import kotlinx.coroutines.launch

/**
 * The camera lane.
 *
 * ### What changed, and why
 *
 * This screen used to *be* the safety watch: it held the camera, the detectors
 * and the rules in `remember {}`, so all three were destroyed when the
 * composable left the tree. The watch therefore stopped when the screen turned
 * off, when the user changed tabs, and when Android dimmed the display -- and
 * it stopped without saying so, which for a monitor is the worst available
 * failure. The camera now lives in [WatchService] and this screen only observes
 * it and lends it a preview surface.
 *
 * ### What it is allowed to claim
 *
 * More than it used to, and still nothing about breathing. Face presence and
 * motion energy answered one question -- can I see the face -- and reported a
 * blanket over the lens, a roll to prone and a knocked phone as the same
 * urgent event. Pose adds the coarse posture and the movement across the frame
 * needed to tell those apart, and the accelerometer settles the third. Every
 * claim on this screen carries what it is read from, and the two things a
 * single camera genuinely cannot establish -- a distance in centimetres, and
 * the difference between leaving the room and being under a blanket -- are
 * written on the screen as limits rather than left for the reader to discover.
 */
@Composable
fun WatchScreen(state: AppState) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val live by WatchService.state.collectAsState()
    val monitor by state.monitor.collectAsState()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var editingZone by remember { mutableStateOf<SafeZone?>(null) }

    // ---- the demo path, which stays in the UI on purpose
    //
    // The service owns the real camera and must keep owning it; the demo needs
    // the detectors with the camera *off*, or an emulator shows a rendered
    // living room underneath a video of a baby. So the demo runs its own
    // headless watcher and its own rules, and produces the same [WatchState]
    // the service does -- which is what keeps the card below identical either
    // way.
    var demo by remember { mutableStateOf(false) }
    var demoState by remember { mutableStateOf(WatchState()) }
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val demoRules = remember { ActivityRules() }

    // The audio lane, folded in for display. The service does the same fold
    // for the alert path -- this one only ever reaches the screen, so the two
    // are deliberately independent and neither can break the other.
    val shownState =
        if (demo) demoState
        else live.copy(cryingNow = monitor.currentEvidence != null)

    /**
     * Keep the display awake while this screen is open.
     *
     * The watch itself no longer depends on the screen -- that is the point of
     * the service -- but a parent who has deliberately opened the live view to
     * watch their baby should not have it black out in twenty seconds. Scoped
     * to this composable, so leaving the screen gives the display back to
     * Android's own timeout.
     */
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Lend the service a surface while the screen is showing, and take it back
    // on the way out so the watch is not rendering into a dead view all night.
    LaunchedEffect(previewView, live.running) {
        val target = previewView
        if (live.running && target != null) {
            val preview = Preview.Builder().build()
            preview.surfaceProvider = target.surfaceProvider
            WatchService.attachPreview(context, preview)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            WatchService.attachPreview(context, null)
            state.releaseSceneDescriber()
        }
    }

    val footage = remember { DemoFootage(context) }
    LaunchedEffect(demo) {
        if (!demo) { shown = null; return@LaunchedEffect }
        if (!footage.open()) { demo = false; return@LaunchedEffect }
        demoRules.reset()
        val camera = CameraWatch(context) { frame ->
            val assessment = demoRules.update(
                ActivityRules.Observation(
                    pose = frame.pose,
                    faceFound = frame.face is FaceWatcher.State.FaceVisible,
                    motion = frame.motion,
                    baselineReady = frame.motion.relativeToBaseline > 0f,
                    zone = live.zone,
                )
            )
            demoState = demoState.copy(
                running = true, simulated = true,
                face = frame.face, motion = frame.motion,
                assessment = assessment, zone = live.zone,
            )
        }
        camera.startHeadless()
        demoState = WatchState(running = true, simulated = true, zone = live.zone)
        var position = 0L
        try {
            while (demo) {
                footage.frameAt(position)?.let {
                    camera.offerFrame(it)
                    shown = it
                    demoState = demoState.copy(framesAnalysed = camera.framesAnalysed)
                }
                position += DemoFootage.FRAME_INTERVAL_MS
                if (position >= footage.durationMs) position = 0L
                kotlinx.coroutines.delay(DemoFootage.FRAME_INTERVAL_MS)
            }
        } finally {
            camera.shutdown()
            demoState = WatchState()
        }
    }

    DisposableEffect(Unit) {
        onDispose { demo = false; footage.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Safety watch", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.SemiBold)
        Text(
            "Point the camera at the cot. Frames are analysed on this phone and " +
                "never leave it. The watch keeps running with the screen off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (live.running || demo) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                if (live.running) {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The clip, in the same place the camera preview would be.
                // Showing the frames the detectors are reading is the whole
                // point -- numbers moving beside a blank space prove nothing.
                shown?.takeIf { demo }?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Recorded clip being analysed",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                ZoneOverlay(
                    zone = editingZone ?: shownState.zone,
                    pose = shownState.assessment,
                    editing = editingZone != null,
                    onChange = { editingZone = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        StatusCard(
            state = shownState,
            onStart = { WatchService.start(context) },
            onStop = { WatchService.stop(context) },
            demo = demo,
            onDemo = { demo = it },
        )

        ZoneCard(
            zone = shownState.zone,
            editing = editingZone,
            watching = live.running || demo,
            onEdit = { editingZone = shownState.zone },
            onSave = {
                editingZone?.let { z -> scope.launch { state.saveSafeZone(z) } }
                editingZone = null
            },
            onCancel = { editingZone = null },
            onReset = { scope.launch { state.resetSafeZone() }; editingZone = null },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Where to put the phone")
            OutlinedBox(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Place it to the side of the cot, so the baby lies across " +
                        "the picture rather than up and down it. A camera looking " +
                        "along the length of the cot sees a lying baby the same " +
                        "way it sees a standing one, and no single view can " +
                        "separate those.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HedgeNote(
            "Reads coarse posture, movement across the frame, and distance " +
                "relative to where the baby started. It is not a breathing or " +
                "vital-signs monitor, and it cannot tell a baby who has left " +
                "the room from one hidden under a blanket."
        )
    }
}

// --------------------------------------------------------------- status

@Composable
private fun StatusCard(
    state: WatchState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    demo: Boolean,
    onDemo: (Boolean) -> Unit,
) {
    OutlinedBox(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    state.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (state.severity) {
                        Severity.URGENT, Severity.CRITICAL -> SeverityUrgent
                        Severity.ATTENTION -> SeverityAttention
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                when {
                    !state.running -> Unit
                    state.severity.level >= Severity.ATTENTION.level ->
                        SeverityChip(state.severity)
                    state.calibrating -> CalmChip("Warming up")
                    else -> CalmChip("All clear")
                }
            }

            state.fault?.let {
                WarningBox("Camera unavailable", it, Modifier.fillMaxWidth())
            }

            // Everything believed, not just the worst of it. A baby who has
            // rolled onto their front and crossed the cot line is two facts,
            // and the old single-state watch could only ever show one.
            if (state.running && state.descriptions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.descriptions.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("→", color = MaterialTheme.colorScheme.primary,
                                 style = MaterialTheme.typography.bodyLarge)
                            Text(line, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            state.caveat?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.running) {
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock("Posture", posture(state.assessment?.posture))
                    StatBlock("Distance", distance(state.assessment?.distanceRatio))
                    StatBlock("Movement",
                              "${(state.motion.activeFraction * 100).toInt()}%")
                    StatBlock("Frames", state.framesAnalysed.toString())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalmChip("On device")
                    if (state.poseAvailable) CalmChip("Pose ${state.poseDelegate}")
                    else CalmChip("Face only")
                    state.lux?.let { CalmChip("${it.toInt()} lx") }
                }
            }

            if (!state.poseAvailable && state.running && !demo) {
                WarningBox(
                    "Posture is unavailable on this phone",
                    "The pose model could not run, so the watch is reporting face " +
                        "presence and movement only -- what it did before.",
                )
            }

            when {
                demo -> OutlinedButton(
                    onClick = { onDemo(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop the demo") }

                state.running -> OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop watching") }

                else -> {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Start safety watch")
                    }
                    TextButton(
                        onClick = { onDemo(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Play demo footage") }
                }
            }
        }
    }
}

// ------------------------------------------------------------- safe zone

@Composable
private fun ZoneCard(
    zone: SafeZone,
    editing: SafeZone?,
    watching: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Safe zone")
        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        editing != null ->
                            "Drag the corners over the cot, then save."
                        zone.configured ->
                            "Set over the cot. The baby leaving it is an urgent alert."
                        else ->
                            "Using the default inner 70% of the frame. That is a " +
                                "frame edge, not a cot edge -- drag it over the cot " +
                                "rails to make it mean something."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (editing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                            Text("Save")
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onEdit,
                            enabled = watching,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (zone.configured) "Adjust" else "Set it over the cot") }
                        if (zone.configured) {
                            TextButton(onClick = onReset) { Text("Reset") }
                        }
                    }
                    if (!watching) {
                        Text(
                            "Start the watch first, so you can see what you are drawing on.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The zone drawn over the preview, and dragged if the parent is editing it.
 *
 * Corner handles resize, the middle moves the whole rectangle. All arithmetic
 * is in normalised coordinates so the overlay lines up with the analysis frame
 * rather than with whatever size the preview happens to be laid out at.
 */
@Composable
private fun ZoneOverlay(
    zone: SafeZone,
    pose: ActivityRules.Assessment?,
    editing: Boolean,
    onChange: (SafeZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outside = pose?.zoneOvershoot?.let { it > 0f } == true
    val line = when {
        outside -> SeverityUrgent
        editing -> Color.White
        else -> SeverityCalm
    }

    androidx.compose.foundation.Canvas(
        modifier = modifier.then(
            if (!editing) Modifier else Modifier.pointerInput(zone) {
                var grabbed: Corner? = null
                detectDragGestures(
                    onDragStart = { start ->
                        grabbed = nearestCorner(start, zone, size.width.toFloat(),
                                                size.height.toFloat())
                    },
                    onDragEnd = { grabbed = null },
                ) { change, _ ->
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                    onChange(
                        when (grabbed) {
                            Corner.TOP_LEFT -> zone.copy(left = nx, top = ny)
                            Corner.TOP_RIGHT -> zone.copy(right = nx, top = ny)
                            Corner.BOTTOM_LEFT -> zone.copy(left = nx, bottom = ny)
                            Corner.BOTTOM_RIGHT -> zone.copy(right = nx, bottom = ny)
                            null -> zone
                        }.normalised().copy(configured = true)
                    )
                }
            }
        )
    ) {
        val l = zone.left * size.width
        val t = zone.top * size.height
        val r = zone.right * size.width
        val b = zone.bottom * size.height

        drawRect(
            color = line,
            topLeft = Offset(l, t),
            size = androidx.compose.ui.geometry.Size(r - l, b - t),
            style = Stroke(width = if (editing) 4f else 3f),
        )

        if (editing) {
            listOf(l to t, r to t, l to b, r to b).forEach { (x, y) ->
                drawCircle(color = Color.White, radius = 18f, center = Offset(x, y))
                drawCircle(color = line, radius = 18f, center = Offset(x, y),
                           style = Stroke(width = 3f))
            }
        }
    }
}

private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private fun nearestCorner(
    at: Offset,
    zone: SafeZone,
    width: Float,
    height: Float,
): Corner {
    val candidates = mapOf(
        Corner.TOP_LEFT to Offset(zone.left * width, zone.top * height),
        Corner.TOP_RIGHT to Offset(zone.right * width, zone.top * height),
        Corner.BOTTOM_LEFT to Offset(zone.left * width, zone.bottom * height),
        Corner.BOTTOM_RIGHT to Offset(zone.right * width, zone.bottom * height),
    )
    return candidates.minByOrNull { (_, corner) ->
        (corner.x - at.x) * (corner.x - at.x) + (corner.y - at.y) * (corner.y - at.y)
    }!!.key
}

private fun posture(posture: Posture?): String = when (posture) {
    null, Posture.UNKNOWN -> "-"
    Posture.ON_BACK -> "On back"
    Posture.ON_FRONT -> "On front"
    Posture.ON_SIDE -> "On side"
    Posture.SITTING -> "Sitting"
    Posture.UPRIGHT -> "Upright"
}

/**
 * Distance as a multiple of where the baby started, never as a length.
 *
 * A number in centimetres would need the camera's field of view and the baby's
 * real torso length, and getting either wrong produces a confident figure that
 * is simply false. "1.4x further than where they started" is what a single
 * camera can actually support.
 */
private fun distance(ratio: Float?): String = when {
    ratio == null || ratio <= 0f -> "-"
    ratio < 1.1f -> "As placed"
    else -> "%.1fx away".format(ratio)
}
