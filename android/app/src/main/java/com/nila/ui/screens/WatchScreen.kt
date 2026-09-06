package com.nila.ui.screens

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nila.ui.AppState
import com.nila.ui.components.CalmChip
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.OutlinedBox
import com.nila.ui.components.SectionHeader
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.StatBlock
import com.nila.vision.CameraWatch
import com.nila.vision.FaceWatcher
import com.nila.ui.components.WarningBox
import com.nila.vision.DemoFootage
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nila.vision.MotionEnergy
import com.nila.data.Severity

/**
 * The camera lane, live.
 *
 * What this reports is deliberately narrow: whether a face is visible, and how
 * much the scene is moving. Both are things a camera can actually establish.
 * It does not estimate pose, because pose models are trained on adult body
 * proportions and measurably fail on infants -- and "I can't see your baby's
 * face" is a more useful alert anyway, covering a roll to prone, a blanket over
 * the face and a blocked lens without pretending to know which one happened.
 */
@Composable
fun WatchScreen(state: AppState) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var faceState by remember {
        mutableStateOf<FaceWatcher.State>(FaceWatcher.State.Starting)
    }
    var motion by remember { mutableStateOf(MotionEnergy.Reading(0f, 0f, 1f)) }
    var running by remember { mutableStateOf(false) }
    var frames by remember { mutableStateOf(0L) }
    var demo by remember { mutableStateOf(false) }
    // The frame currently on screen. Deliberately not recycled when replaced:
    // Compose may still be drawing it, and recycling a bitmap out from under
    // the canvas is a crash rather than a glitch. They are 480px wide and live
    // a fifth of a second; the collector copes.
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }


    val watch = remember {
        CameraWatch(context) { s, m ->
            faceState = s
            motion = m
        }
    }

    val footage = remember { DemoFootage(context) }

    /**
     * Replay the bundled clip through the real watcher.
     *
     * Loops, because the point is to sit on it during a demo. The camera stays
     * off: on an emulator binding it would put a rendered living room
     * underneath a video of a baby.
     */
    LaunchedEffect(demo) {
        if (!demo) return@LaunchedEffect
        if (!footage.open()) { demo = false; return@LaunchedEffect }
        watch.startHeadless()
        var position = 0L
        while (demo) {
            footage.frameAt(position)?.let {
                watch.offerFrame(it)
                shown = it
            }
            frames = watch.framesAnalysed
            position += DemoFootage.FRAME_INTERVAL_MS
            if (position >= footage.durationMs) position = 0L
            kotlinx.coroutines.delay(DemoFootage.FRAME_INTERVAL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            demo = false
            shown = null
            footage.close()
            watch.shutdown()
            // Over a gigabyte resident. It does not stay loaded behind a
            // screen nobody is looking at.
            state.releaseSceneDescriber()
        }
    }

    // A second opinion, automatically, when the cheap watch loses the face.
    //
    // Not on a timer: a model narrating a sleeping baby every few minutes is
    // one that gets muted before it says anything useful. It looks when
    // something has already changed.
    LaunchedEffect(faceState) {
        if (faceState is FaceWatcher.State.FaceMissing &&
            state.sceneDescriber.isInstalled
        ) {
            watch.snapshot()?.let { state.lookAtCot(it, automatic = true) }
        }
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
                "never recorded or sent anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )


        if (running) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            val preview = Preview.Builder().build()
                            preview.surfaceProvider = view.surfaceProvider
                            watch.start(owner, preview)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The clip, in the same place the camera preview would be. Showing the
        // frames the detector is reading is the whole point -- numbers moving
        // beside a blank space prove nothing to anyone watching.
        shown?.takeIf { demo }?.let { frame ->
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Recorded clip being analysed",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.FillWidth,
            )
        }

        OutlinedBox(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val (headline, severity) = describe(faceState)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(headline, style = MaterialTheme.typography.titleMedium)
                    if (severity != null) SeverityChip(severity)
                    else if (running || demo) CalmChip("All clear")
                }

                if (running || demo) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock("Movement",
                                  "${(motion.activeFraction * 100).toInt()}%")
                        StatBlock("vs usual",
                                  "%.1fx".format(motion.relativeToBaseline))
                        StatBlock("Frames", watch.framesAnalysed.toString())
                    }
                }

                when {
                    demo -> OutlinedButton(
                        onClick = { demo = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop the demo") }

                    running -> OutlinedButton(
                        onClick = { watch.stop(); running = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop watching") }

                    else -> {
                        Button(
                            onClick = { running = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Start safety watch") }
                        TextButton(
                            onClick = { demo = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Play demo footage") }
                    }
                }
            }
        }

        HedgeNote(
            "Watches for the face going out of view, and for stillness. " +
                "Not a breathing or vital-signs monitor."
        )

    }
}

private fun describe(state: FaceWatcher.State): Pair<String, Severity?> = when (state) {
    FaceWatcher.State.Starting -> "Getting a baseline" to null
    is FaceWatcher.State.FaceVisible -> "Face visible" to null
    is FaceWatcher.State.FaceMissing ->
        (if (state.stillMoving) "Can't see the face, still moving"
         else "Can't see the face") to Severity.URGENT
    is FaceWatcher.State.UnusuallyStill -> "Unusually still" to Severity.ATTENTION
    is FaceWatcher.State.Unavailable -> "Camera unavailable: ${state.reason}" to null
}

