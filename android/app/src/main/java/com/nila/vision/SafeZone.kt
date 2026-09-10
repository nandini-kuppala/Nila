package com.nila.vision

/**
 * The part of the frame the baby is supposed to stay inside.
 *
 * Normalised 0..1 so it survives every resolution the analysis path might use,
 * and stored as a rectangle rather than a polygon because a cot is a rectangle
 * and a draggable quadrilateral is a worse thing to adjust at 11pm.
 *
 * The default is the inner 70% of the frame, which is a compromise worth being
 * honest about: it is a *frame* edge, not a cot edge, so out of the box this
 * fires on where the phone happens to be pointed. It exists so the feature does
 * nothing dangerous before it is set up -- a zone the parent has dragged over
 * the actual cot rails is the one that means something, and the watch screen
 * says which of the two is in force.
 */
data class SafeZone(
    val left: Float = DEFAULT_INSET,
    val top: Float = DEFAULT_INSET,
    val right: Float = 1f - DEFAULT_INSET,
    val bottom: Float = 1f - DEFAULT_INSET,
    /** False while this is still the untouched default. */
    val configured: Boolean = false,
) {
    companion object {
        /** Inner 70% of the frame: 15% inset on each side. */
        const val DEFAULT_INSET = 0.15f

        /** Small enough to be deliberate, large enough to hold a baby. */
        const val MIN_SIDE = 0.12f

        val DEFAULT = SafeZone()

        /** Parse the persisted form, falling back to the default on anything odd. */
        fun decode(encoded: String?): SafeZone {
            val parts = encoded?.split(',') ?: return DEFAULT
            if (parts.size != 4) return DEFAULT
            val v = parts.map { it.toFloatOrNull() ?: return DEFAULT }
            return SafeZone(v[0], v[1], v[2], v[3], configured = true).normalised()
        }
    }

    fun encode(): String = "$left,$top,$right,$bottom"

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Clamp into the frame and enforce a minimum size.
     *
     * A drag can invert the rectangle or collapse it to nothing, and a zone of
     * zero area reports every baby as outside it -- an alarm that fires
     * constantly and gets the whole feature turned off.
     */
    fun normalised(): SafeZone {
        var l = left.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (l > r) { val s = l; l = r; r = s }
        if (t > b) { val s = t; t = b; b = s }
        if (r - l < MIN_SIDE) {
            val mid = ((l + r) / 2f).coerceIn(MIN_SIDE / 2f, 1f - MIN_SIDE / 2f)
            l = mid - MIN_SIDE / 2f; r = mid + MIN_SIDE / 2f
        }
        if (b - t < MIN_SIDE) {
            val mid = ((t + b) / 2f).coerceIn(MIN_SIDE / 2f, 1f - MIN_SIDE / 2f)
            t = mid - MIN_SIDE / 2f; b = mid + MIN_SIDE / 2f
        }
        return copy(left = l, top = t, right = r, bottom = b)
    }

    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom

    /**
     * How far outside the zone a point is, as a fraction of the zone's size.
     *
     * Zero inside. Used to tell a baby whose shoulder has crossed the line from
     * one who is halfway across the room, which are different alerts.
     */
    fun overshoot(x: Float, y: Float): Float {
        val dx = maxOf(left - x, x - right, 0f) / width.coerceAtLeast(1e-3f)
        val dy = maxOf(top - y, y - bottom, 0f) / height.coerceAtLeast(1e-3f)
        return maxOf(dx, dy)
    }
}
