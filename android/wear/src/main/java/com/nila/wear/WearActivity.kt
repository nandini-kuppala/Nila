package com.nila.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * The watch face of the app: one glance, no interaction required.
 *
 * Deliberately almost empty. Someone looking at this is standing in a dark room
 * deciding whether to go in, and the only questions that matter are whether the
 * baby is crying and for how long. Everything else is on the phone.
 */
class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchFace() }
    }
}

@Composable
private fun WatchFace() {
    val state by PhoneListener.state.collectAsState()

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimeText()

            val s = state
            when {
                s == null -> Text(
                    "Waiting for the phone",
                    textAlign = TextAlign.Center,
                    color = Color(0xFF8D9895),
                )
                !s.monitoring -> Text(
                    "Not monitoring",
                    textAlign = TextAlign.Center,
                    color = Color(0xFF8D9895),
                )
                else -> {
                    Text(
                        s.headline,
                        textAlign = TextAlign.Center,
                        color = when {
                            s.severity >= 3 -> Color(0xFFE8887A)
                            s.severity == 2 -> Color(0xFFD9A44A)
                            else -> Color(0xFF4FBFA6)
                        },
                        style = MaterialTheme.typography.title3,
                    )
                    if (s.cryingSeconds > 0) {
                        Text(
                            "${s.cryingSeconds}s" +
                                if (s.trend.isNotBlank()) "  ${s.trend.lowercase()}" else "",
                            color = Color(0xFFB6C0BD),
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }
    }
}
