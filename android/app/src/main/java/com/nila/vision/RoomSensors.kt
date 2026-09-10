package com.nila.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The two sensors on the guardian phone that a camera cannot replace.
 *
 * Both answer questions the vision lane silently gets wrong. If the phone is
 * knocked off the shelf, every frame after that is of a wall -- and the face
 * detector reports a missing face, the pose model reports a missing body, and
 * the parent is woken for a baby who is fine. The accelerometer knows the
 * difference in one reading. If the room light goes off, the camera goes dark
 * and reports the same thing; the light sensor knows that too.
 *
 * Neither is used to make a claim about the baby. They exist to stop the camera
 * lane making one it is not entitled to, which is why they are here and not in
 * [ActivityRules].
 */
class RoomSensors(context: Context) : Closeable {

    companion object {
        private const val TAG = "RoomSensors"

        /**
         * Acceleration away from gravity that counts as the phone being moved.
         *
         * 1.8 m/s^2 is well above the noise floor of a phone sitting on
         * furniture and well below a deliberate pick-up, so a passing lorry
         * does not trigger it and a nudge from a cot rail does.
         */
        private const val MOVE_THRESHOLD = 1.8f

        /** How long a knock keeps the view suspect. */
        private const val MOVED_HOLD_MS = 20_000L

        /** Fractional change in lux that counts as the room light changing. */
        private const val LIGHT_CHANGE_RATIO = 3.0f

        private const val LIGHT_HOLD_MS = 8_000L
    }

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val light = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    @Volatile private var movedAtMs = 0L
    @Volatile private var lightChangedAtMs = 0L
    @Volatile private var lastLux = -1f
    @Volatile private var settledLux = -1f

    /** Current lux, or null when this phone has no light sensor. */
    @Volatile var lux: Float? = null; private set

    /** True while the camera's view is suspect because the phone was moved. */
    val cameraMoved: Boolean
        get() = System.currentTimeMillis() - movedAtMs < MOVED_HOLD_MS

    /** True just after the room light changed materially. */
    val lightChanged: Boolean
        get() = System.currentTimeMillis() - lightChangedAtMs < LIGHT_HOLD_MS

    /** True when the room is dark enough that the camera is nearly blind. */
    val roomDark: Boolean get() = (lux ?: Float.MAX_VALUE) < 3f

    val available: Boolean get() = accelerometer != null || light != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                    // Magnitude minus gravity: a phone at rest in any
                    // orientation reads about 9.81, so the deviation is the
                    // signal and the orientation does not matter.
                    val deviation = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
                    if (deviation > MOVE_THRESHOLD) movedAtMs = System.currentTimeMillis()
                }
                Sensor.TYPE_LIGHT -> {
                    val value = event.values[0]
                    lux = value
                    if (settledLux < 0f) { settledLux = value; lastLux = value; return }
                    val ratio = if (value > settledLux) value / settledLux.coerceAtLeast(0.1f)
                                else settledLux / value.coerceAtLeast(0.1f)
                    if (ratio >= LIGHT_CHANGE_RATIO) {
                        lightChangedAtMs = System.currentTimeMillis()
                        settledLux = value
                    } else {
                        // Drift slowly towards the current reading so dusk does
                        // not register as somebody turning a lamp on.
                        settledLux = settledLux * 0.98f + value * 0.02f
                    }
                    lastLux = value
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val m = manager ?: return
        // SENSOR_DELAY_NORMAL is ~200 ms, which is the right rate for "has the
        // phone been knocked" and cheap enough to leave on all night. Anything
        // faster is battery spent to answer the same question.
        accelerometer?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        light?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i(TAG, "sensors: accel=${accelerometer != null} light=${light != null}")
    }

    fun stop() {
        runCatching { manager?.unregisterListener(listener) }
    }

    override fun close() = stop()
}
