package com.nila.phonelink

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.data.Severity
import com.nila.ui.theme.NilaTheme
import com.nila.ui.theme.severityColors

/**
 * The level-4 alert, over the lock screen.
 *
 * This is the one place in the app that takes the screen without being asked,
 * so the bar for reaching it is set as high as it goes: [LinkProtocol.tierFor]
 * routes only CRITICAL here, which on the guardian means a baby out of the safe
 * zone, climbing, or face-down and not recovering. Anything softer is a
 * notification, because an app that seizes the screen for a fussing baby gets
 * its full-screen permission revoked by a tired adult within two nights.
 *
 * Worth being clear about a platform limit this cannot design around: since
 * Android 14, `USE_FULL_SCREEN_INTENT` is granted at install only to calling and
 * alarm-clock apps. Everything else has to ask, and the user has to agree in
 * Settings. If they have not, Android silently downgrades the notification to a
 * heads-up banner -- so [ParentService] always sounds [AlertPlayer] alongside
 * this, and never relies on the screen coming on.
 */
class AlertActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_SEVERITY = "severity"

        fun intentFor(
            context: Context,
            title: String,
            body: String,
            severity: Int,
        ): Intent = Intent(context, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_SEVERITY, severity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Your baby needs you"
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val severity = Severity.of(intent.getIntExtra(EXTRA_SEVERITY, 4))

        setContent {
            NilaTheme {
                val colors = severityColors(severity)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.container)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        severity.label.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onContainer,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (body.isNotBlank()) {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onContainerMuted,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = { silence(); finish() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                        ),
                    ) {
                        Text("I'm going", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    /**
     * Turn the screen on and show over the keyguard.
     *
     * Both the modern calls and the legacy window flags: the flags are
     * deprecated from API 27 but are still what works on some OEM keyguards,
     * and setting both costs nothing.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // Asks, rather than dismisses: a secure lock stays secure and the user
        // is prompted. The alert is readable either way.
        runCatching {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        }
    }

    /**
     * Wrapped because the service may already be gone -- the user could have
     * stopped receiving while this screen was up, and a crash on the way out of
     * an alert is the worst possible moment for one.
     */
    private fun silence() {
        runCatching {
            startService(
                Intent(this, ParentService::class.java)
                    .setAction(ParentService.ACTION_SILENCE)
            )
        }
    }

    /**
     * Leaving the screen silences the alarm.
     *
     * Anything that takes the user away from here -- back, home, the notification
     * shade -- means they have seen it. Continuing to sound after that is how an
     * alarm becomes something people disable.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        silence()
    }
}
