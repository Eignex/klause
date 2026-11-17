package com.eignex.klause.solver.objective

import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.util.numbers.BigFraction
import kotlin.math.ceil
import kotlin.math.nextDown
import kotlin.math.nextUp

/**
 * An optimality gap that ends a minimization over a working objective. A working value `w` reads
 * `scale * w + offset` in the units and minimization direction of `tolerance`, and the search may report its
 * incumbent `z` optimal once it has proved a bound `b` with `z - b <= max(absolute, relative * abs(z))` there.
 *
 * A complete search bounds its nodes against the least such `b` instead of the incumbent: a node whose proved bound
 * reaches it holds no point outside the gap, and exhausting the search proves it a global bound. That cutoff never
 * falls as the incumbent rises, so the cutoff of an earlier, worse incumbent proved at least as much.
 */
class OptimalityGap(
    /** Positive gap in the units `scale` and `offset` map onto. */
    private val tolerance: OptimalityTolerance,
    /** Positive source units per working unit. */
    private val scale: BigFraction = BigFraction.ONE,
    /** Source value at working zero. */
    private val offset: BigFraction = BigFraction.ZERO,
    /** Whether every feasible working value is an integer, so a proved bound rounds up to one. */
    private val integral: Boolean = false,
) {
    init {
        require(!tolerance.isExact) { "an exact optimality tolerance needs a closed bound" }
        require(scale.signum() > 0) { "the working objective must preserve the minimization order" }
    }

    // Least working bound within the gap of the working incumbent.
    internal fun floor(incumbent: BigFraction): BigFraction =
        incumbent - tolerance.gapAllowance(scale * incumbent + offset) * scale.reciprocal()

    // What a proved working bound proves about every feasible working value.
    internal fun proved(bound: Double): Double = if (integral) ceil(bound) else bound

    // Whether the proved working bound closes the gap of the incumbent; an infinite bound proves nothing.
    internal fun closes(incumbent: Double, bound: Double): Boolean {
        if (!incumbent.isFinite() || !bound.isFinite()) return false
        val proved = checkNotNull(BigFraction.ofDouble(proved(bound)))
        return proved >= floor(checkNotNull(BigFraction.ofDouble(incumbent)))
    }

    // Smallest double at or above the floor of the incumbent, never above the incumbent: a node bound reaching it
    // holds no point outside the gap. An integral objective whose gap admits no integer below the incumbent keeps the
    // incumbent itself, so its search proves the optimum exactly.
    internal fun cutoff(incumbent: Double): Double {
        if (!incumbent.isFinite()) return incumbent
        val ceiling = floor(checkNotNull(BigFraction.ofDouble(incumbent))).ceilingDouble()
        if (!ceiling.isFinite() || proved(ceiling) >= incumbent) return incumbent
        return minOf(ceiling, incumbent)
    }

    // The global bound a search exhausted under the cutoff of the incumbent proves, raised by an independently proved
    // known bound. Null when the bound reaches the incumbent: the optimum is then proved exactly.
    internal fun exhaustedBound(incumbent: Double, known: Double = Double.NEGATIVE_INFINITY): Double? {
        if (!incumbent.isFinite() || known >= incumbent) return null
        val cutoff = cutoff(incumbent)
        val bound = proved(if (known > cutoff) known else cutoff)
        return bound.takeIf { it < incumbent }
    }
}

// Whether a proved bound closes the gap of the incumbent, both source values in minimization direction:
// `incumbent - bound <= max(absolute, relative * abs(incumbent))`. A bound at or above the incumbent always closes,
// so a missing or zero tolerance requires a closed bound.
internal fun OptimalityTolerance?.closesGap(incumbent: BigFraction, bound: BigFraction): Boolean =
    bound >= incumbent || (this != null && incumbent - bound <= gapAllowance(incumbent))

// The permitted distance `max(absolute, relative * abs(incumbent))` of a proved bound below the incumbent.
internal fun OptimalityTolerance.gapAllowance(incumbent: BigFraction): BigFraction {
    val floor = BigFraction.of(absolute.numerator, absolute.denominator)
    val magnitude = if (incumbent.signum() < 0) incumbent.negated() else incumbent
    val scaled = BigFraction.of(relative.numerator, relative.denominator) * magnitude
    return if (scaled > floor) scaled else floor
}

// Largest double at or below this value, so a rounded bound stays proved.
internal fun BigFraction.floorDouble(): Double = -negated().ceilingDouble()

private fun BigFraction.ceilingDouble(): Double {
    var value = toDouble()
    if (!value.isFinite()) return value
    while (checkNotNull(BigFraction.ofDouble(value)) < this) value = value.nextUp()
    while (true) {
        val below = value.nextDown()
        if (!below.isFinite() || checkNotNull(BigFraction.ofDouble(below)) < this) return value
        value = below
    }
}
