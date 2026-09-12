package com.nila.ui

import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.EventRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The week, reduced to the few shapes a parent can actually act on.
 *
 * ### Why this is a pure function over rows
 *
 * Everything here is derived: there is no new measurement in this file and no
 * new claim. It takes the cry episodes the detector already wrote and the care
 * the caregiver already logged, and it arranges them so that *when* becomes
 * visible. That is the one question the rest of the app cannot answer -- the
 * monitor knows this cry is forty seconds old, and knows nothing about the fact
 * that the last five have all started within an hour of the same evening feed.
 *
 * Being a pure function of `(now, events, care)` is the point. It means every
 * number on the dashboard can be reproduced from rows anyone can read in the
 * timeline, it means none of it needs the database or a device to test, and it
 * means there is nowhere for an invented figure to hide.
 *
 * ### What it deliberately does not do
 *
 * It does not interpret. [Summary.pattern] is the closest it comes, and that is
 * a description of a count -- "crying clusters between 18:00 and 21:00" -- with
 * the count attached so the reader can disagree with it. Cause shares come
 * straight from the reason head, which scores below chance across unseen
 * infants, so the donut ships with the share of episodes the model would not
 * commit to as its own wedge rather than dropping them and flattering itself.
 */
object Insights {

    /** How much history the dashboard reads. A week is the colic window. */
    const val DAYS = 7

    /**
     * A sleep with no logged end is closed after this long.
     *
     * Someone taps "Slept", the baby wakes, and nobody taps anything -- which
     * is the normal case at 3am and must not produce a fourteen-hour sleep bar.
     * Six hours is past the longest stretch a four-month-old usually does, so
     * capping there loses very little real sleep and prevents the chart from
     * silently inventing a night.
     */
    const val OPEN_SLEEP_CAP_MINUTES = 6 * 60

    /** One stretch of sleep, from the tap that started it to the tap that ended it. */
    data class Sleep(val fromMs: Long, val toMs: Long) {
        val minutes: Int get() = ((toMs - fromMs) / 60_000L).toInt()
    }

    /** One column of the weekly charts. */
    data class Day(
        val date: LocalDate,
        val startMs: Long,
        val endMs: Long,
        /** `Mon`, or `Today` for the current one. */
        val label: String,
        val cryMinutes: Int,
        val cryEpisodes: Int,
        val sleepMinutes: Int,
        /** Naps and nights: how many separate stretches made up [sleepMinutes]. */
        val sleepSessions: Int,
        val feeds: Int,
        val isToday: Boolean,
    ) {
        val sleepHours: Float get() = sleepMinutes / 60f
        val overThreeHours: Boolean get() = cryMinutes >= 180
    }

    /**
     * How the week's cries ended, which is the product's own claim measured.
     *
     * Every baby monitor on the market ends at *notify*. The argument for this
     * one is the two rungs before that, and this is the only place in the app
     * where the argument is scored rather than described: of the cries that
     * happened, how many settled on their own, how many were settled by
     * something Nila played, and how many it ran out of options on and woke
     * somebody for.
     *
     * The third number is not a failure count. A cry that needed a parent and
     * got one is the system working; a cry that needed a parent and did not is
     * the thing this app exists to prevent, and it cannot be counted from here.
     */
    data class Outcomes(
        val settledAlone: Int = 0,
        val settledBySound: Int = 0,
        val wokeYou: Int = 0,
    ) {
        val total: Int get() = settledAlone + settledBySound + wokeYou
        /** The share that never reached a person. */
        val handledAlone: Int
            get() = if (total == 0) 0
            else ((settledAlone + settledBySound) * 100f / total).roundToInt()
    }

    /** One wedge of the cause donut. */
    data class Cause(
        /** `belly_pain`, or `""` for the bucket the model would not commit to. */
        val key: String,
        val label: String,
        val episodes: Int,
        val minutes: Int,
    )

    data class Summary(
        val days: List<Day> = emptyList(),
        val causes: List<Cause> = emptyList(),
        val outcomes: Outcomes = Outcomes(),
        /** Average minutes of crying per hour of the day, over the window. */
        val hourlyCryMinutes: List<Float> = List(24) { 0f },
        val pattern: String? = null,
        val loaded: Boolean = false,
    ) {
        val today: Day? get() = days.lastOrNull()
        val weekCryMinutes: Int get() = days.sumOf { it.cryMinutes }
        val weekSleepMinutes: Int get() = days.sumOf { it.sleepMinutes }
        val daysWithSleepLogged: Int get() = days.count { it.sleepMinutes > 0 }

        /** Average night, over the days anything was logged. Null if none were. */
        val averageSleepMinutes: Int?
            get() = daysWithSleepLogged.takeIf { it > 0 }
                ?.let { weekSleepMinutes / it }

        val averageCryMinutes: Int get() = if (days.isEmpty()) 0 else weekCryMinutes / days.size
        val peakCryMinutes: Int get() = days.maxOfOrNull { it.cryMinutes } ?: 0
        val peakSleepMinutes: Int get() = days.maxOfOrNull { it.sleepMinutes } ?: 0
        val hasCauses: Boolean get() = causes.any { it.episodes > 0 }

        /**
         * Days in the window that crossed three hours of crying.
         *
         * Counted on calendar days, which is what the Wessel criterion says and
         * what the bars above it draw. [AppState.ColicSummary] counts rolling
         * twenty-four hour windows measured back from this instant instead --
         * a defensible thing for a notification to do and the wrong thing to
         * put beside a chart, because the two disagree and the screen then
         * shows "1 of 7" above seven bars of which two clear the line.
         */
        val daysOverThreeHours: Int get() = days.count { it.overThreeHours }

        /** Three such days, which is the rest of the rule. Not a diagnosis. */
        val meetsDurationCriterion: Boolean get() = daysOverThreeHours >= 3
    }

    /** Build the whole dashboard from two lists of rows. */
    fun build(
        nowMs: Long,
        events: List<EventRecord>,
        care: List<CareRecord>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = today.minusDays((DAYS - 1).toLong())

        val cries = events
            .filter { it.kind == "CRY_ENDED" && it.durationSeconds > 0 }
            .sortedBy { it.startedAtMs }
        val sleeps = sleepBlocks(care, nowMs)
        val feeds = care.filter { it.kind == CareKind.FEED.name }

        val days = (0 until DAYS).map { offset ->
            val date = first.plusDays(offset.toLong())
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            Day(
                date = date,
                startMs = from,
                endMs = to,
                label = if (date == today) "Today"
                        else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                // Overlap rather than start-time membership: a cry that begins
                // at 23:50 and runs twenty minutes belongs to both days, in the
                // proportion it actually occupied each of them. Filing all of
                // it under the day it started is how a colic total quietly
                // gains an hour that happened after midnight.
                cryMinutes = cries.sumOf {
                    overlapMs(it.startedAtMs, it.startedAtMs + it.durationSeconds * 1000L,
                              from, to)
                }.let { (it / 60_000L).toInt() },
                cryEpisodes = cries.count { it.startedAtMs in from until to },
                sleepMinutes = sleeps.sumOf {
                    overlapMs(it.fromMs, it.toMs, from, to)
                }.let { (it / 60_000L).toInt() },
                sleepSessions = sleeps.count {
                    overlapMs(it.fromMs, it.toMs, from, to) > 0
                },
                feeds = feeds.count { it.atMs in from until to },
                isToday = date == today,
            )
        }

        val windowFrom = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val inWindow = cries.filter { it.startedAtMs >= windowFrom }
        val hourly = hourly(inWindow, zone)

        return Summary(
            days = days,
            causes = causes(inWindow),
            outcomes = outcomes(events, windowFrom),
            hourlyCryMinutes = hourly,
            pattern = pattern(hourly, inWindow.size),
            loaded = true,
        )
    }

    // ------------------------------------------------------------- sleep

    /**
     * When the sleep now in progress began, or null if the baby is awake.
     *
     * The sleep tracker's clock reads from this rather than from the block
     * list, because a running sleep is not a block yet -- it has no end, and
     * [sleepBlocks] closes it at "now" for charting, which would make the
     * tracker's own elapsed time circular.
     */
    fun openSleepSince(care: List<CareRecord>, nowMs: Long): Long? {
        val last = care
            .filter {
                it.kind == CareKind.SLEEP_START.name || it.kind == CareKind.SLEEP_END.name
            }
            .filter { it.atMs <= nowMs }
            .maxByOrNull { it.atMs }
            ?: return null
        return last.atMs.takeIf { last.kind == CareKind.SLEEP_START.name }
    }

    /**
     * Pair the sleep log into blocks.
     *
     * `SLEEP_START` opens one, `SLEEP_END` closes it. Two starts in a row close
     * the first at the second -- somebody logged a wake and forgot, and the
     * alternative is one block swallowing the day. A block still open when the
     * log runs out is closed at [OPEN_SLEEP_CAP_MINUTES] or now, whichever
     * comes first, so the chart never draws sleep that has not happened yet.
     */
    fun sleepBlocks(care: List<CareRecord>, nowMs: Long): List<Sleep> {
        val marks = care
            .filter { it.kind == CareKind.SLEEP_START.name || it.kind == CareKind.SLEEP_END.name }
            .sortedBy { it.atMs }
        val out = mutableListOf<Sleep>()
        var openedAt: Long? = null
        val capMs = OPEN_SLEEP_CAP_MINUTES * 60_000L

        fun close(at: Long) {
            val from = openedAt ?: return
            openedAt = null
            val to = min(at, from + capMs)
            if (to > from) out += Sleep(from, to)
        }

        marks.forEach { mark ->
            if (mark.kind == CareKind.SLEEP_START.name) {
                close(mark.atMs)
                openedAt = mark.atMs
            } else {
                close(mark.atMs)
            }
        }
        close(nowMs)
        return out
    }

    // ------------------------------------------------------------ causes

    private fun causes(cries: List<EventRecord>): List<Cause> {
        if (cries.isEmpty()) return emptyList()
        val named = cries.groupBy { it.hypothesisLabel?.takeIf { l -> l.isNotBlank() } }
        val out = named.mapNotNull { (key, rows) ->
            if (key == null) null
            else Cause(key, plain(key), rows.size, rows.sumOf { it.durationSeconds } / 60)
        }.sortedByDescending { it.episodes }

        // The episodes the model would not name are a wedge, not an omission.
        // A donut of five confident causes, drawn from the eleven of eighteen
        // cries the reason head actually committed to, is a chart that has
        // quietly deleted its own error rate.
        val unnamed = named[null].orEmpty()
        return if (unnamed.isEmpty()) out
        else out + Cause("", "Not guessed", unnamed.size,
                         unnamed.sumOf { it.durationSeconds } / 60)
    }

    /**
     * Score the ladder from the rows it wrote.
     *
     * An episode is attributed by the strongest thing that happened in it:
     * woken beats settled-by-a-sound beats settled-alone, because that is the
     * order the ladder tries them in and an episode that reached the top rung
     * does not get credit for the rung below it.
     *
     * Episodes are keyed on `episodeId`, which is why it exists. Grouping on
     * timestamps would merge two cries five minutes apart on a bad evening,
     * and those are exactly the evenings this figure is about.
     */
    private fun outcomes(events: List<EventRecord>, sinceMs: Long): Outcomes {
        val byEpisode = events
            .filter { it.startedAtMs >= sinceMs && it.episodeId != null }
            .groupBy { it.episodeId!! }
        var alone = 0
        var sound = 0
        var woke = 0
        byEpisode.forEach { (_, rows) ->
            // Only completed episodes are scored. One still running has not
            // had its chance to settle and would count as a success it has not
            // earned, or a failure it has not had.
            if (rows.none { it.kind == "CRY_ENDED" }) return@forEach
            when {
                rows.any { it.kind == "ESCALATED_TO_PARENT" } -> woke++
                rows.any { it.kind == "SOOTHE_WORKED" } -> sound++
                else -> alone++
            }
        }
        return Outcomes(alone, sound, woke)
    }

    // ------------------------------------------------------------ hourly

    /**
     * Crying spread across the hours it actually occupied.
     *
     * An hour-long cry that starts at 19:40 is twenty minutes of the 19:00 hour
     * and forty of the 20:00 one. Attributing all sixty to the hour it started
     * in is what turns a real evening cluster into a spike an hour early.
     */
    private fun hourly(cries: List<EventRecord>, zone: ZoneId): List<Float> {
        val buckets = FloatArray(24)
        cries.forEach { cry ->
            var cursor = cry.startedAtMs
            val end = cry.startedAtMs + cry.durationSeconds * 1000L
            var guard = 0
            while (cursor < end && guard++ < 48) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zone)
                val hourEnd = zoned.withMinute(0).withSecond(0).withNano(0)
                    .plusHours(1).toInstant().toEpochMilli()
                val slice = min(end, hourEnd) - cursor
                buckets[zoned.hour] += slice / 60_000f
                cursor = hourEnd
            }
        }
        return buckets.map { it / DAYS }
    }

    /**
     * The one sentence the dashboard is allowed to write.
     *
     * It names the three-hour stretch holding the most crying, and only when
     * that stretch holds a real share of it -- otherwise the honest reading is
     * that there is no pattern yet, and saying so is more use than naming the
     * marginally largest bucket of a flat week.
     */
    private fun pattern(hourly: List<Float>, episodes: Int): String? {
        if (episodes < 4) return null
        val total = hourly.sum()
        if (total <= 0f) return null
        var bestHour = 0
        var best = 0f
        for (h in 0 until 24) {
            val window = (0 until 3).sumOf { hourly[(h + it) % 24].toDouble() }.toFloat()
            // Ties go to the window that *opens* on the crying. Every cry at
            // seven in the evening makes the windows at 17:00, 18:00 and 19:00
            // identical, and picking the first of those tells a parent the
            // trouble starts at five when it starts at seven.
            val better = window > best + 0.001f ||
                (window > best - 0.001f && hourly[h] > hourly[bestHour])
            if (better) { best = window; bestHour = h }
        }
        val share = (best / total * 100).roundToInt()
        // Three hours out of twenty-four is an eighth of the day; a cluster
        // worth naming has to hold well more than its share of the crying.
        if (share < 30) return null
        return "Most crying falls between %02d:00 and %02d:00 - %d%% of it, across %d episodes."
            .format(bestHour, (bestHour + 3) % 24, share, episodes)
    }

    // ------------------------------------------------------------- utility

    private fun overlapMs(aFrom: Long, aTo: Long, bFrom: Long, bTo: Long): Long =
        max(0L, min(aTo, bTo) - max(aFrom, bFrom))

    /** `belly_pain` is a class name; "belly pain" is what a person reads. */
    fun plain(label: String): String =
        label.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
