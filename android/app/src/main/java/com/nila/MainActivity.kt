package com.nila

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.nila.ui.NilaRoot
import com.nila.ui.theme.NilaTheme

class MainActivity : ComponentActivity() {

    private val permissionsGranted = mutableStateOf(false)
    private val nfc by lazy { com.nila.actions.NfcLogger(this) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted.value = result[Manifest.permission.RECORD_AUDIO] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NilaTheme {
                NilaRoot(
                    permissionsGranted = permissionsGranted.value,
                    onRequestPermissions = { askForPermissions() },
                )
            }
        }
        askForPermissions()
    }

    override fun onResume() {
        super.onResume()
        nfc.enableForeground(this)
    }

    override fun onPause() {
        nfc.disableForeground(this)
        super.onPause()
    }

    /**
     * A sticker was tapped.
     *
     * Handled here rather than in a composable because foreground dispatch
     * delivers to the activity, and the tap must work from whichever screen
     * happens to be open -- the point of the feature is that it costs no
     * navigation.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = nfc.tagFrom(intent) ?: return
        val kind = nfc.kindFor(tag)
        if (kind == null) {
            com.nila.ui.NfcInbox.unassigned(tag)
        } else {
            com.nila.ui.NfcInbox.tapped(kind)
        }
    }

    private fun askForPermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            permissionsGranted.value = true
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }
}
