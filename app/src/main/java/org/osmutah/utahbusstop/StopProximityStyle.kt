package org.osmutah.utahbusstop

import kotlin.math.roundToInt

/** Proximity changes color emphasis, never row opacity or availability. */
internal data class StopProximityStyle(
    val fill: Int,
    val border: Int,
    val name: Int,
    val distance: Int,
    val arrowBackground: Int,
    val arrow: Int,
)

/**
 * Give stops within 200 ft full emphasis, then fade continuously to a neutral style at 1,000 ft.
 * Approximate downtown results have no proximity treatment: they are not near the person's location.
 * Even at the quietest endpoint, names and distances use the app's readable muted ink.
 */
internal fun stopProximityStyle(distanceMeters: Double, locationIsApproximate: Boolean): StopProximityStyle {
    if (locationIsApproximate || !distanceMeters.isFinite()) return StopProximityStyle(
        fill = Palette.WHITE,
        border = Palette.BORDER,
        name = Palette.INK,
        distance = Palette.MUTED,
        arrowBackground = Palette.GREEN_SOFT,
        arrow = Palette.GREEN,
    )
    val closeMeters = 200.0 * 0.3048
    val quietMeters = 1000.0 * 0.3048
    val fade = ((distanceMeters - closeMeters) / (quietMeters - closeMeters)).coerceIn(0.0, 1.0)
    return StopProximityStyle(
        fill = blend(Palette.GREEN_SOFT, Palette.PAPER, fade),
        border = blend(Palette.GREEN, Palette.BORDER, fade),
        name = blend(Palette.INK, Palette.MUTED, fade),
        distance = blend(0xFF24663F.toInt(), Palette.MUTED, fade),
        arrowBackground = blend(Palette.GREEN, 0xFFEBEEE9.toInt(), fade),
        // White stays clear on the dark first quarter of the fade; dark ink stays clear thereafter.
        arrow = if (fade <= 0.25) Palette.WHITE else blend(Palette.INK, Palette.MUTED, fade),
    )
}

private fun blend(from: Int, to: Int, fraction: Double): Int {
    fun channel(shift: Int): Int {
        val start = (from ushr shift) and 0xFF
        val end = (to ushr shift) and 0xFF
        return (start + (end - start) * fraction).roundToInt()
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
