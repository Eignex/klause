package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.ceilDiv
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
    // Positive gap in the units `scale` and `offset` map onto.
    private val tolerance: OptimalityTolerance,
    // Positive source units per working unit.
    private val scale: BigFraction = BigFraction.ONE,
    private val offset: BigFraction = BigFraction.ZERO,
    // Every feasible working value is an integer, so a proved bound rounds up to one.
    private val integral: Boolean = false,
) {
    init {
        require(!tolerance.isExact) { "an exact optimality tolerance needs a closed bound" }
        require(scale.signum() > 0) { "the working objective must preserve the minimization order" }
    }

    // Least working bound within the gap of the working incumbent.
    internal fun floor(incumbent: BigFraction): BigFraction =
        incumbent - tolerance.gapAllowance(scale * incumbent + offset) * scale.reciprocal()

    internal fun closes(incumbent: BigFraction, bound: BigFraction): Boolean =
        bound <= incumbent && proved(bound) >= floor(incumbent)

    internal fun proved(bound: BigFraction): BigFraction =
        if (integral) BigFraction.of(ceilDiv(bound.num, bound.den), BIG_ONE) else bound

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

// Early completion under a declared positive policy, with both values in original source units.
internal fun OptimalityTolerance?.closesDeclaredGap(
    incumbent: BigFraction, bound: BigFraction, sense: ObjectiveSense,
): Boolean {
    if (this == null || isExact) return false
    val distance = if (sense == ObjectiveSense.MINIMIZE) incumbent - bound else bound - incumbent
    return distance >= BigFraction.ZERO && distance <= gapAllowance(incumbent)
}

internal fun OptimalityTolerance?.closesDeclaredGap(
    incumbent: AlgebraicReal, bound: AlgebraicReal, sense: ObjectiveSense, arithmetic: AlgebraicArithmetic,
): Boolean {
    if (this == null || isExact) return false
    val distance = if (sense == ObjectiveSense.MINIMIZE) arithmetic.subtract(incumbent, bound)
    else arithmetic.subtract(bound, incumbent)
    val zero = arithmetic.rational(BigFraction.ZERO)
    if (arithmetic.compare(distance, zero) < 0) return false
    val magnitude = if (arithmetic.compare(incumbent, zero) < 0) arithmetic.negate(incumbent) else incumbent
    val floor = arithmetic.rational(BigFraction.of(absolute.numerator, absolute.denominator))
    val scaled = arithmetic.multiply(arithmetic.rational(BigFraction.of(relative.numerator, relative.denominator)),
        magnitude)
    val allowance = if (arithmetic.compare(floor, scaled) >= 0) floor else scaled
    return arithmetic.compare(distance, allowance) <= 0
}

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
