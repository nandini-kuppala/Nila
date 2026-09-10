package com.nila.vision

import com.nila.data.Severity

/**
 * What the camera lane is allowed to say, and when it may wake somebody.
 *
 * Split from the camera for the same reason [FaceRules] was: the part that
 * decides whether to raise an alarm can then be tested exhaustively on the JVM,
 * at whatever frame rate and in whatever order we like, without a device.
 *
 * Every rule here needs a run of consecutive frames before it fires. That is
 * not caution for its own sake -- the pose model is being asked coarse
 * questions precisely because it cannot be trusted on any single frame of an
 * infant, and a run is what converts "the model placed a landmark oddly" into
 * "the baby is in a different position now".
 *
 * The severities are fixed by what a parent can do about each one:
 *
 * ```
 *   URGENT      rolled to front · out of the safe zone · standing · out of view
 *   ATTENTION   crawling · moved well away from where it started · gone still
 *   NOTE        settled, face visible, nothing to report
 * ```
 */
class ActivityRules(private val config: Config = Config()) {

    data class Config(
        /**
         * Frames of the same posture before it is believed.
         *
         * Twelve at ~5 fps is around two and a half seconds. Short enough that
         * a roll is caught while it still matters, long enough that a baby
         * turning their head does not read as one.
         */
        val postureFramesToBelieve: Int = 12,
        /** Frames outside the zone before the alarm. Shorter: a boundary is a fact. */
        val zoneFramesToAlert: Int = 6,
        /** Frames with no body and no face before "out of view". */
        val absentFramesToAlert: Int = 15,
        /** Frames of movement across the frame before it counts as crawling. */
        val travelFramesToBelieve: Int = 10,
        /**
         * Centroid travel, in frame widths, that counts as going somewhere.
         *
         * A baby shifting in their sleep moves a few percent of the frame. A
         * baby crawling crosses a fifth of it and keeps going.
         */
        val travelThreshold: Float = 0.18f,
        /** Torso this much smaller than the baseline means further away. */
        val awayRatio: Float = 1.35f,
        /** And this much smaller is far enough to be worth waking someone. */
        val farRatio: Float = 1.9f,
        /** Frames before a distance change is believed. */
        val distanceFramesToBelieve: Int = 12,
        /** Motion relative to the scene baseline below which the baby is still. */
        val stillnessRelativeThreshold: Float = 0.22f,
        val stillnessFramesToAlert: Int = 40,
        /** Frames of stable pose used to calibrate the distance baseline. */
        val calibrationFrames: Int = 15,
    )

    /** One thing the watch can report, in the order it should be read. */
    enum class Event(val severity: Severity) {
        /** Nothing to report: body found, face visible, inside the zone. */
        SETTLED(Severity.NOTE),
        /** Lying face-up, which is where a baby should be. */
        ON_BACK(Severity.NOTE),
        ON_SIDE(Severity.ATTENTION),
        CRAWLING(Severity.ATTENTION),
        MOVED_AWAY(Severity.ATTENTION),
        UNUSUALLY_STILL(Severity.ATTENTION),
        SITTING_UP(Severity.ATTENTION),
        ROLLED_TO_FRONT(Severity.URGENT),
        LEFT_SAFE_ZONE(Severity.URGENT),
        STANDING_UP(Severity.URGENT),
        FAR_FROM_START(Severity.URGENT),
        OUT_OF_VIEW(Severity.URGENT),
        /** The pose model found nothing but the face detector still sees a face. */
        FACE_ONLY(Severity.NOTE),
        CALIBRATING(Severity.NOTE),
        /**
         * The phone was moved, so nothing the camera saw since is evidence.
         *
         * Not produced by the rules -- it comes from the accelerometer, and it
         * exists in this enum so the alert path has one vocabulary rather than
         * a second parallel notion of what happened.
         */
        CAMERA_MOVED(Severity.ATTENTION),
    }

    /** Everything one frame contributes. */
    data class Observation(
        val pose: PoseReading,
        val faceFound: Boolean,
        val motion: MotionEnergy.Reading,
        val baselineReady: Boolean,
        val zone: SafeZone,
    )

    /**
     * What to show, and whether to raise it.
     *
     * [events] is every rule currently satisfied, most severe first, so the UI
     * can list "crawling" and "outside the safe zone" together instead of
     * picking one and discarding the other -- which is what the old single-state
     * watch had to do.
     */
    data class Assessment(
        val events: List<Event>,
        val posture: Posture,
        /** 1.0 at the calibrated distance; above 1 is further away. */
        val distanceRatio: Float,
        /** Frame widths travelled over the recent window. */
        val travel: Float,
        val zoneOvershoot: Float,
    ) {
        val primary: Event get() = events.firstOrNull() ?: Event.CALIBRATING
        val severity: Severity get() = primary.severity
        val urgent: Boolean get() = severity == Severity.URGENT ||
            severity == Severity.CRITICAL
    }

    private var postureRun = 0
    private var lastPosture = Posture.UNKNOWN
    private var believedPosture = Posture.UNKNOWN
    private var previousBelieved = Posture.UNKNOWN
    private var zoneRun = 0
    private var absentRun = 0
    private var stillRun = 0
    private var awayRun = 0
    private var farRun = 0
    private var travelRun = 0

    /** Torso length when the watch was armed, for the distance comparison. */
    private var baselineTorso = 0f
    private var calibrationSamples = 0
    private var calibrationSum = 0f

    /** Recent centroids, for travel. Two seconds at ~5 fps. */
    private val trail = ArrayDeque<Pair<Float, Float>>()
    private val trailLimit = 10

    fun reset() {
        postureRun = 0
        lastPosture = Posture.UNKNOWN
        believedPosture = Posture.UNKNOWN
        previousBelieved = Posture.UNKNOWN
        zoneRun = 0
        absentRun = 0
        stillRun = 0
        awayRun = 0
        farRun = 0
        travelRun = 0
        baselineTorso = 0f
        calibrationSamples = 0
        calibrationSum = 0f
        trail.clear()
    }

    val calibrated: Boolean get() = baselineTorso > 0f

    fun update(observation: Observation): Assessment {
        val pose = observation.pose

        // ---- nothing there at all
        if (!pose.present) {
            absentRun = if (observation.faceFound) 0 else absentRun + 1
            postureRun = 0
            trail.clear()
            val events = buildList {
                if (absentRun >= config.absentFramesToAlert) add(Event.OUT_OF_VIEW)
                else if (observation.faceFound) add(Event.FACE_ONLY)
                else add(Event.CALIBRATING)
            }
            return Assessment(events, Posture.UNKNOWN, 1f, 0f, 0f)
        }
        absentRun = 0

        // ---- distance baseline, taken from the first stable frames
        if (!calibrated) {
            calibrationSum += pose.torsoLength
            calibrationSamples++
            if (calibrationSamples >= config.calibrationFrames) {
                baselineTorso = calibrationSum / calibrationSamples
            }
        }
        // Torso shrinks as the baby moves away, so the ratio is baseline over
        // current: above one means further off than where they started.
        val distanceRatio =
            if (calibrated && pose.torsoLength > 1e-4f) baselineTorso / pose.torsoLength
            else 1f

        // ---- posture, once it has held for long enough
        if (pose.posture == lastPosture) postureRun++ else postureRun = 1
        lastPosture = pose.posture
        if (postureRun >= config.postureFramesToBelieve &&
            pose.posture != believedPosture
        ) {
            previousBelieved = believedPosture
            believedPosture = pose.posture
        }

        // ---- travel across the frame
        trail.addLast(pose.cx to pose.cy)
        while (trail.size > trailLimit) trail.removeFirst()
        val travel = if (trail.size >= 2) {
            val (sx, sy) = trail.first()
            PoseClassifier.distance(sx, sy, pose.cx, pose.cy)
        } else 0f
        travelRun = if (travel >= config.travelThreshold) travelRun + 1 else 0

        // ---- the safe zone
        val overshoot = observation.zone.overshoot(pose.cx, pose.cy)
        zoneRun = if (overshoot > 0f) zoneRun + 1 else 0

        // ---- stillness, only once the scene has something to be still against
        stillRun = if (observation.baselineReady &&
            observation.motion.relativeToBaseline < config.stillnessRelativeThreshold
        ) stillRun + 1 else 0

        farRun = if (distanceRatio >= config.farRatio) farRun + 1 else 0
        awayRun = if (distanceRatio >= config.awayRatio) awayRun + 1 else 0

        val events = buildList {
            // Urgent first, and all of them: a baby who has rolled onto their
            // front *and* crossed the cot line is two facts, and reporting one
            // of them is how a parent walks in prepared for the wrong thing.
            if (believedPosture == Posture.ON_FRONT) add(Event.ROLLED_TO_FRONT)
            if (zoneRun >= config.zoneFramesToAlert) add(Event.LEFT_SAFE_ZONE)
            if (believedPosture == Posture.UPRIGHT) add(Event.STANDING_UP)
            if (farRun >= config.distanceFramesToBelieve) add(Event.FAR_FROM_START)

            if (travelRun >= config.travelFramesToBelieve &&
                believedPosture in CRAWLING_POSTURES
            ) add(Event.CRAWLING)
            if (believedPosture == Posture.SITTING) add(Event.SITTING_UP)
            if (believedPosture == Posture.ON_SIDE) add(Event.ON_SIDE)
            if (awayRun >= config.distanceFramesToBelieve &&
                farRun < config.distanceFramesToBelieve
            ) add(Event.MOVED_AWAY)
            if (stillRun >= config.stillnessFramesToAlert) add(Event.UNUSUALLY_STILL)

            if (isEmpty()) {
                if (!calibrated) add(Event.CALIBRATING)
                else if (believedPosture == Posture.ON_BACK) add(Event.ON_BACK)
                else add(Event.SETTLED)
            }
        }.sortedByDescending { it.severity.level }

        return Assessment(events, believedPosture, distanceRatio, travel, overshoot)
    }

    /** The posture a crawling baby is in. Prone, or up on all fours. */
    private val CRAWLING_POSTURES = setOf(Posture.ON_FRONT, Posture.SITTING)

    /** The posture believed before the current one, for a transition message. */
    val cameFrom: Posture get() = previousBelieved

    companion object {
        /**
         * One sentence per event, written for a lock screen.
         *
         * Kept here rather than in the UI so the notification, the watch face
         * and the screen cannot drift into describing the same event three
         * different ways.
         */
        fun describe(event: Event): String = when (event) {
            Event.SETTLED -> "Settled"
            Event.ON_BACK -> "On their back, face visible"
            Event.ON_SIDE -> "Rolled onto their side"
            Event.CRAWLING -> "Crawling"
            Event.MOVED_AWAY -> "Moved away from where they started"
            Event.UNUSUALLY_STILL -> "Unusually still"
            Event.SITTING_UP -> "Sitting up"
            Event.ROLLED_TO_FRONT -> "Rolled onto their front"
            Event.LEFT_SAFE_ZONE -> "Outside the safe zone"
            Event.STANDING_UP -> "Standing up"
            Event.FAR_FROM_START -> "Much further away than where they started"
            Event.OUT_OF_VIEW -> "Out of view of the camera"
            Event.FACE_ONLY -> "Face visible, body not in frame"
            Event.CALIBRATING -> "Getting a baseline"
            Event.CAMERA_MOVED -> "The phone was moved"
        }

        /**
         * What the app cannot tell, said out loud.
         *
         * One camera cannot distinguish a baby who has left the room from one
         * hidden behind a blanket, and it cannot measure a distance in
         * centimetres from a single view without knowing the lens and the
         * baby's size. Saying so on the screen is the same discipline the cause
         * classifier gets, and for the same reason.
         */
        fun caveat(event: Event): String? = when (event) {
            Event.OUT_OF_VIEW ->
                "Could be out of the room, or under a blanket. " +
                    "One camera cannot tell the difference."
            Event.FAR_FROM_START, Event.MOVED_AWAY ->
                "Distance is measured against where the baby was when the " +
                    "watch started, not in centimetres."
            Event.ROLLED_TO_FRONT ->
                "Read from a flat torso with no face in view. " +
                    "Not a breathing monitor."
            Event.CAMERA_MOVED ->
                "Nothing the camera reported since the knock is reliable."
            else -> null
        }
    }
}
