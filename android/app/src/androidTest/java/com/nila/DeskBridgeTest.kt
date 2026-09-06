package com.nila

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.bridge.DeskBridge
import com.nila.data.NilaDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/**
 * The laptop dashboard, served and fetched on the device.
 *
 * Tested over a real socket rather than by calling the handler directly: the
 * things most likely to break here -- binding, the response framing, the
 * local-only guard -- all live in the socket layer and a direct call would step
 * over every one of them.
 */
@RunWith(AndroidJUnit4::class)
class DeskBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Speak HTTP over a raw socket, the way a browser does.
     *
     * Not HttpURLConnection: Android forbids the *app* from making cleartext
     * requests, which is a policy about outbound traffic and has nothing to do
     * with whether the server works. The actual consumer is a browser on a
     * laptop, which is not bound by this app's network policy at all -- so a raw
     * socket is both the only way to test this from inside, and the more
     * faithful simulation of the real client.
     */
    private fun fetch(path: String): Pair<Int, String> =
        Socket("127.0.0.1", DeskBridge.PORT).use { sock ->
            sock.soTimeout = 4_000
            sock.getOutputStream().write(
                ("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            val raw = sock.getInputStream().bufferedReader().readText()
            val status = raw.lineSequence().firstOrNull().orEmpty()
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val body = raw.substringAfter("\r\n\r\n", "")
            code to body
        }

    @Test
    fun servesTheDashboardAndItsData() {
        val bridge = DeskBridge(context, NilaDatabase.get(context))
        assertTrue("bridge would not start", bridge.start())
        try {
            val (htmlCode, html) = fetch("/")
            assertTrue("expected 200, got $htmlCode", htmlCode == 200)
            assertTrue("not an HTML page", html.contains("<!doctype html>", true))
            assertTrue("dashboard title missing", html.contains("Nila"))

            val (apiCode, json) = fetch("/api")
            assertTrue("expected 200, got $apiCode", apiCode == 200)
            listOf("monitoring", "status", "cryingSecondsToday", "episodesToday",
                   "events").forEach {
                assertTrue("field '$it' missing from $json", json.contains("\"$it\""))
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun theDashboardCarriesNoAudioVideoOrDocuments() {
        // The privacy claim made in the UI, asserted rather than trusted. If
        // someone later adds a frame or a file path to this payload, this fails.
        val bridge = DeskBridge(context, NilaDatabase.get(context))
        assertTrue(bridge.start())
        try {
            val (_, json) = fetch("/api")
            listOf("filePath", "extractedText", "base64", "image", "audio",
                   "envelope", "notes", "\"path\"").forEach { forbidden ->
                assertFalse(
                    "payload leaked '$forbidden': $json",
                    json.contains(forbidden, ignoreCase = true),
                )
            }
        } finally {
            bridge.close()
        }
    }

    @Test
    fun theSocketIsReleasedOnClose() {
        // Two starts in a row would fail to bind if close() leaked the socket,
        // which is exactly what turns "stop serving" into "restart the app".
        val db = NilaDatabase.get(context)
        val first = DeskBridge(context, db)
        assertTrue(first.start())
        first.close()

        val second = DeskBridge(context, db)
        assertTrue("port was not released", second.start())
        second.close()
    }

    @Test
    fun reportsAnAddressAUserCanType() {
        val bridge = DeskBridge(context, NilaDatabase.get(context))
        assertTrue(bridge.start())
        try {
            val address = bridge.localAddress()
            assertTrue(
                "expected an http URL with the port, got $address",
                address == null || (address.startsWith("http://") &&
                    address.endsWith(":${DeskBridge.PORT}")),
            )
        } finally {
            bridge.close()
        }
    }
}
