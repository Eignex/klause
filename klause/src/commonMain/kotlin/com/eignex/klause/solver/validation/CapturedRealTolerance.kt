package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntDisjointSet
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.div
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

internal fun Factor.hasCapturedLinearTolerance(): Boolean =
    this is Linear || this is ExactLinear || this is LinearIndicator

internal fun capturedSourceSlack(
    policy: SourceTolerance?,
    bound: BigFraction,
    magnitude: BigFraction = BigFraction.ZERO,
): BigFraction {
    if (policy == null || policy.isExact) return BigFraction.ZERO
    val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
    val relative = BigFraction.of(policy.relative.numerator, policy.relative.denominator)
    val boundMagnitude = bound.magnitude()
    val scale = if (boundMagnitude > magnitude) boundMagnitude else magnitude
    val scaled = relative * scale
    return if (absolute > scaled) absolute else scaled
}

// The tolerance verdict of a projected source point: every real declaration and every relation over a real
// coordinate holds within [policy], and integer rows with a captured linear form read the same slack.
internal fun verifyCapturedRealTolerance(
    source: Problem,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: ExecutionControl,
): Verification<Sample, Unit> =
    RealToleranceScope.of(source).verify(source, sample, policy, cancellation, integerRows = true)

// Acceptance of real completions within the tolerance the source declares. It is null when that tolerance is
// zero or absent, when there is no real coordinate, or when no real component reads the slack.
//
// Every relation over real coordinates has a tolerance reading, defined once in [RealToleranceScope]. Real
// coordinates that share a relation form a component, and a component that contains a relation without one holds
// exactly, declarations included, so such a relation never decides how an unrelated component is judged. Factors
// over integers and Booleans alone are left to the engine that enforces them.
internal fun capturedRealAcceptance(source: Problem, cancellation: ExecutionControl): FiniteSourceAcceptance? {
    val policy = source.semantics.effective.feasibilityTolerance
    if (policy == null || policy.isExact || source.numRealVars == 0) return null
    val scope = RealToleranceScope.of(source)
    if (!scope.readsSlack) return null
    return FiniteSourceAcceptance(
        { sample ->
            scope.verify(source, sample, policy, cancellation, integerRows = false) is Verification.Accepted
        },
        permitsToleranceOptimum = true,
    )
}

// The tolerance reading of each relation over real coordinates, and which real coordinates hold exactly.
//
// Rows read the slack as MPS rows do: an active non-strict row holds within `max(absolute, relative *
// max(abs(bound), sum(abs(a(i) * x(i)))))`. A strict row, a disequality and a row whose activator is false hold
// exactly. A finite membership admits a value within the slack of a member, measured as a declaration bound.
// Absolute value, minimum and maximum, selection over variables, an integer times a real, a real product and a
// power state `r = f(x)` and read the slack as the row `r - f(x) = 0`: the residual `abs(r - f(x))` must stay
// within `max(absolute, relative * (abs(r) + abs(f(x))))`. Selection from constants reads it as the row `r = c`.
// A product whose operand has finite choices selects one member: the operand lies within the slack of that member
// `v` and `r - v * y = 0` holds within the slack, where choice guards name the selected member. A real
// disequality holds exactly. A disjunction that states more than one equality, and any other relation, has no
// tolerance reading, so its component holds exactly.
internal class RealToleranceScope private constructor(
    private val relations: IntArray,
    private val integerRows: IntArray,
    private val exact: BooleanArray,
    private val roots: IntArray,
    // Whether some relation over a real coordinate reads the slack.
    val readsSlack: Boolean,
) {
    // Whether real coordinate [id] lies in a component that holds exactly.
    fun isExact(id: Int): Boolean = exact[id]

    // The representative of the component real coordinate [id] lies in.
    fun component(id: Int): Int = roots[id]

    fun verify(
        source: Problem,
        sample: Sample,
        policy: SourceTolerance,
        cancellation: ExecutionControl,
        integerRows: Boolean,
    ): Verification<Sample, Unit> {
        if (sample.numRealVars != source.numRealVars || sample.numIntVars != source.numIntVars ||
            sample.numBoolVars != source.numBoolVars
        ) return Verification.Rejected("assignment does not cover original source coordinates")
        val reals = sample.exactReals ?: List(source.numRealVars) {
            BigFraction.ofDouble(sample.approximateRealValue(it))
                ?: return Verification.Rejected("real $it has no finite value")
        }
        val point = if (sample.exactReals != null) sample else sample.copy(exactReals = reals)
        val exactPoint = ExactRealPoint(point, reals)
        val check = ToleranceWitness(exactPoint, policy, cancellation)
        for (id in reals.indices) {
            cancellation.charge(1)
            if (cancellation()) return Verification.Indeterminate("tolerance declaration check cancelled")
            if (!check.withinDeclaration(id, source.realLower[id], source.realUpper[id], exact[id])) {
                return Verification.Rejected("real $id is outside its declared range")
            }
        }
        if (integerRows) {
            for (id in this.integerRows) {
                cancellation.charge(1)
                if (cancellation()) return Verification.Indeterminate("tolerance row check cancelled")
                check.rows(id, source.factors[id])?.let { return it }
            }
        }
        for (id in relations) {
            cancellation.charge(1)
            if (cancellation()) return Verification.Indeterminate("tolerance relation check cancelled")
            val factor = source.factors[id]
            val verdict = if (factor is RealFiniteDomain) {
                check.membership(id, factor, exact[factor.variable])
            } else if (exact[factor.variables.reals[0]]) {
                checkExactRealRelation(id, factor, exactPoint, cancellation)
            } else check.relation(id, factor)
            verdict?.let { return it }
        }
        return if (cancellation()) Verification.Indeterminate("tolerance check cancelled")
        else Verification.Accepted(Candidate(sample, Unit))
    }

    companion object {
        fun of(source: Problem): RealToleranceScope {
            val relations = IntArrayList()
            val integerRows = IntArrayList()
            val components = IntDisjointSet(source.numRealVars)
            for ((id, factor) in source.factors.withIndex()) {
                val reals = factor.variables.reals
                if (reals.isEmpty()) {
                    if (factor.hasCapturedLinearTolerance()) integerRows.add(id)
                    continue
                }
                for (real in reals) components.union(reals[0], real)
                relations.add(id)
            }
            val checked = relations.toIntArray()
            val exactRoots = BooleanArray(source.numRealVars)
            for (id in checked) {
                val factor = source.factors[id]
                if (!factor.hasToleranceReading()) exactRoots[components.find(factor.variables.reals[0])] = true
            }
            val roots = IntArray(source.numRealVars) { components.find(it) }
            val exact = BooleanArray(source.numRealVars) { exactRoots[roots[it]] }
            val readsSlack = checked.any { id ->
                val factor = source.factors[id]
                factor !is Distinct && !exact[factor.variables.reals[0]]
            }
            return RealToleranceScope(checked, integerRows.toIntArray(), exact, roots, readsSlack)
        }
    }
}

// Whether this relation over real coordinates has a tolerance reading, as [RealToleranceScope] defines it.
internal fun Factor.hasToleranceReading(): Boolean = when (this) {
    is LinearIndicator, is RealFiniteDomain, is RealAbs, is RealArrayMinMax, is RealElement, is RealProduct,
    is ContinuousProduct, is Distinct -> true
    is Power -> base is Power.Coordinate.Real
    else -> when (val form = linearForm) {
        is LinearForm.Conjunction -> true
        is LinearForm.Disjunction -> form.rows.count { it.relation == LinearOp.EQ } <= 1
        else -> false
    }
}

private class ToleranceWitness(
    private val exactPoint: ExactRealPoint,
    private val policy: SourceTolerance,
    private val cancellation: ExecutionControl,
) {
    private val point = exactPoint.sample
    private val reals = exactPoint.reals

    fun withinDeclaration(id: Int, lowerBound: Double, upperBound: Double, exact: Boolean): Boolean {
        val lower = BigFraction.ofDouble(lowerBound)
        val upper = BigFraction.ofDouble(upperBound)
        return (lower == null || reals[id] >= lower - slack(lower, exact)) &&
            (upper == null || reals[id] <= upper + slack(upper, exact))
    }

    fun membership(id: Int, factor: RealFiniteDomain, exact: Boolean): Verification<Nothing, Nothing>? {
        for (index in 0 until factor.size) {
            cancellation.charge(1)
            if (cancellation()) return Verification.Indeterminate("tolerance membership check cancelled")
            val member = checkNotNull(BigFraction.ofDouble(factor.valueAt(index)))
            if ((reals[factor.variable] - member).magnitude() <= slack(member, exact)) return null
        }
        return Verification.Rejected("real factor $id refutes its finite membership")
    }

    fun relation(id: Int, factor: Factor): Verification<Nothing, Nothing>? {
        val holds = when (factor) {
            is Distinct -> return checkExactRealRelation(id, factor, exactPoint, cancellation)
            is RealAbs -> reals[factor.operand].magnitude().let { value -> residual(reals[factor.result], value) }
            is RealArrayMinMax -> residual(reals[factor.result], extremum(factor)
                ?: return Verification.Indeterminate("tolerance extrema check cancelled"))
            is RealElement -> {
                val index = point.exactIntValue(factor.idx)
                val position = (if (index.fitsLong()) factor.position(index.toLongExact()) else null)
                    ?: return Verification.Rejected("real factor $id has an invalid index")
                if (factor.arrIsVars) residual(reals[factor.result], reals[factor.entries[position]])
                else constant(reals[factor.result], checkNotNull(BigFraction.ofDouble(factor.constants[position])))
            }
            is RealProduct -> residual(reals[factor.result],
                BigFraction.of(point.exactIntValue(factor.intOperand), BIG_ONE) * reals[factor.realOperand])
            is ContinuousProduct -> product(factor)
                ?: return Verification.Indeterminate("tolerance choice check cancelled")
            is Power -> power(factor) ?: return Verification.Indeterminate("tolerance power check unavailable")
            else -> return rows(id, factor)
        }
        if (cancellation()) return Verification.Indeterminate("tolerance relation check cancelled")
        return if (holds) null else Verification.Rejected("real factor $id refutes the assignment within tolerance")
    }

    fun rows(id: Int, factor: Factor): Verification<Nothing, Nothing>? {
        val valid = if (factor is LinearIndicator) {
            point.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L) || holds(factor.row)
        } else when (val form = factor.linearForm) {
            is LinearForm.Conjunction -> form.rows.all(::holds)
            is LinearForm.Disjunction -> form.rows.any(::holds)
            is LinearForm.Relaxation, null ->
                return Verification.Indeterminate("real factor $id has no complete witness checker")
        }
        if (cancellation()) return Verification.Indeterminate("tolerance row check cancelled")
        return if (valid) null else Verification.Rejected("real factor $id refutes the assignment within tolerance")
    }

    private fun holds(row: LinearRow): Boolean {
        cancellation.charge(row.size.toLong() + 1L)
        if (cancellation()) return false
        val active = row.activator == LinearRow.ALWAYS || exactPoint.truth(row.activator)
        if (active && !row.strict && row.relation != LinearOp.NE) {
            slackedRow(row, point, policy, cancellation)?.let { return it }
        }
        return row.exactComparison(reals.size, active, exactPoint::truth).holdsAt(exactPoint)
    }

    // `result = value` read as the row `result - value = 0`.
    private fun residual(result: BigFraction, value: BigFraction): Boolean =
        (result - value).magnitude() <= capturedSourceSlack(policy, BigFraction.ZERO,
            result.magnitude() + value.magnitude())

    // `result = constant` read as the row `result = constant`.
    private fun constant(result: BigFraction, constant: BigFraction): Boolean =
        (result - constant).magnitude() <= capturedSourceSlack(policy, constant, result.magnitude())

    private fun extremum(factor: RealArrayMinMax): BigFraction? {
        var selected = reals[factor.xs[0]]
        for (operand in factor.xs) {
            cancellation.charge(1)
            if (cancellation()) return null
            val value = reals[operand]
            if (if (factor.max) value > selected else value < selected) selected = value
        }
        return selected
    }

    private fun product(factor: ContinuousProduct): Boolean? {
        val choices = factor.finiteChoices ?: return residual(reals[factor.result], reals[factor.a] * reals[factor.b])
        val other = reals[if (choices.operand == factor.a) factor.b else factor.a]
        fun selects(value: BigFraction): Boolean =
            (reals[choices.operand] - value).magnitude() <= capturedSourceSlack(policy, value) &&
                residual(reals[factor.result], value * other)
        if (!choices.hasGuards) {
            for (index in 0 until choices.size) {
                cancellation.charge(1)
                if (cancellation()) return null
                if (selects(checkNotNull(BigFraction.ofDouble(choices.valueAt(index))))) return true
            }
            return false
        }
        var selected: Double? = null
        for (index in 0 until choices.size) {
            cancellation.charge(1)
            if (cancellation()) return null
            val literal = choices.literalAt(index)
            if (!Lit.evaluate(literal, point.boolValue(Lit.variable(literal)))) continue
            if (selected != null && selected != choices.valueAt(index)) return false
            selected = choices.valueAt(index)
        }
        val member = selected ?: return false
        for (index in 0 until choices.size) {
            val literal = choices.literalAt(index)
            if (choices.valueAt(index) == member && !Lit.evaluate(literal, point.boolValue(Lit.variable(literal)))) {
                return false
            }
        }
        return selects(checkNotNull(BigFraction.ofDouble(member)))
    }

    private fun power(factor: Power): Boolean? {
        val base = reals[(factor.base as Power.Coordinate.Real).id.index]
        val result = reals[(factor.result as Power.Coordinate.Real).id.index]
        return when (factor.exponent) {
            0 -> constant(result, BigFraction.ONE)
            1 -> residual(result, base)
            else -> powerResidual(result, base, factor.exponent)
        }
    }

    // `result = base^exponent` read as a residual row. A power wider than [POWER_WIDTH] bits is never formed: a
    // power far above the result, or far below the slack the result leaves, decides the row from bit lengths alone,
    // and one of comparable magnitude is bracketed by directed rounding, which decides every row but an exact tie
    // with the slack.
    private fun powerResidual(result: BigFraction, base: BigFraction, exponent: Int): Boolean? {
        val magnitude = base.magnitude()
        if (magnitude.isZero || magnitude == BigFraction.ONE || base.width() * exponent <= POWER_WIDTH) {
            return residual(result, rationalPower(base, exponent, cancellation) ?: return null)
        }
        val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
        val relative = BigFraction.of(policy.relative.numerator, policy.relative.denominator)
        if (relative >= BigFraction.ONE) return true
        val target = result.magnitude()
        // `2^low <= abs(base^exponent) < 2^high`.
        val low = exponent.toLong() * magnitude.log2Floor()
        val high = exponent.toLong() * (magnitude.log2Floor() + 2)
        val above = ((BigFraction.ONE + relative) * target + absolute) * (BigFraction.ONE - relative).reciprocal()
        if (above.isZero || low >= above.log2Floor() + 2) return false
        val alone = capturedSourceSlack(policy, BigFraction.ZERO, target)
        if (target < alone && high <= (alone - target).log2Floor()) return true
        if (target > alone && high + 1 <= (target - alone).log2Floor()) return false
        var bits = POWER_BRACKET_BITS
        while (bits <= POWER_WIDTH) {
            val bracket = powerBracket(magnitude, exponent, bits, cancellation) ?: return null
            val negative = base.signum() < 0 && exponent and 1 != 0
            val lowest = if (negative) bracket.second.negated() else bracket.first
            val highest = if (negative) bracket.first.negated() else bracket.second
            val toLowest = (result - lowest).magnitude()
            val toHighest = (result - highest).magnitude()
            val farthest = if (toLowest > toHighest) toLowest else toHighest
            if (farthest <= capturedSourceSlack(policy, BigFraction.ZERO, target + bracket.first)) return true
            val nearest = when {
                result >= lowest && result <= highest -> BigFraction.ZERO
                toLowest < toHighest -> toLowest
                else -> toHighest
            }
            if (nearest > capturedSourceSlack(policy, BigFraction.ZERO, target + bracket.second)) return false
            bits *= 2
        }
        return null
    }

    private fun slack(bound: BigFraction, exact: Boolean): BigFraction =
        if (exact) BigFraction.ZERO else capturedSourceSlack(policy, bound)
}

// `value^exponent` by repeated squaring, charging each product by its operand width; null when cancelled.
private fun rationalPower(value: BigFraction, exponent: Int, cancellation: ExecutionControl): BigFraction? {
    var remaining = exponent
    var base = value
    var result = BigFraction.ONE
    while (remaining > 0) {
        if (remaining and 1 != 0) {
            cancellation.charge(result.width() + base.width())
            if (cancellation()) return null
            result *= base
        }
        remaining = remaining ushr 1
        if (remaining > 0) {
            cancellation.charge(2 * base.width())
            if (cancellation()) return null
            base *= base
        }
    }
    return result
}

private fun BigFraction.width(): Long = num.magnitudeBitLength().toLong() + den.magnitudeBitLength()

// `[lower, upper]` around `magnitude^exponent` for a positive [magnitude], each product rounded outward to [bits]
// significant bits; null when cancelled or when the bracket leaves the range a fraction can hold.
private fun powerBracket(
    magnitude: BigFraction,
    exponent: Int,
    bits: Int,
    cancellation: ExecutionControl,
): Pair<BigFraction, BigFraction>? {
    fun round(value: Dyadic, up: Boolean): Dyadic {
        val excess = value.mantissa.magnitudeBitLength() - bits
        if (excess <= 0) return value
        val divisor = BIG_ONE shl excess
        val floor = value.mantissa / divisor
        return Dyadic(if (up) floor + BIG_ONE else floor, value.exponent + excess)
    }
    fun times(left: Dyadic, right: Dyadic, up: Boolean): Dyadic? {
        cancellation.charge(2L * bits)
        if (cancellation()) return null
        val product = Dyadic(left.mantissa * right.mantissa, left.exponent + right.exponent)
        return round(product, up).takeIf { kotlin.math.abs(it.exponent) <= POWER_EXPONENT_RANGE }
    }
    val shift = magnitude.num.magnitudeBitLength() - magnitude.den.magnitudeBitLength() - bits
    val floor = if (shift >= 0) magnitude.num / (magnitude.den shl shift)
        else (magnitude.num shl -shift) / magnitude.den
    var lowBase = Dyadic(floor, shift.toLong())
    var highBase = Dyadic(floor + BIG_ONE, shift.toLong())
    var low = Dyadic(BIG_ONE, 0L)
    var high = Dyadic(BIG_ONE, 0L)
    var remaining = exponent
    while (remaining > 0) {
        if (remaining and 1 != 0) {
            low = times(low, lowBase, up = false) ?: return null
            high = times(high, highBase, up = true) ?: return null
        }
        remaining = remaining ushr 1
        if (remaining > 0) {
            lowBase = times(lowBase, lowBase, up = false) ?: return null
            highBase = times(highBase, highBase, up = true) ?: return null
        }
    }
    return low.fraction() to high.fraction()
}

// `mantissa * 2^exponent`.
private class Dyadic(val mantissa: BigInt, val exponent: Long) {
    fun fraction(): BigFraction = if (exponent >= 0) BigFraction.of(mantissa shl exponent.toInt(), BIG_ONE)
        else BigFraction.of(mantissa, BIG_ONE shl (-exponent).toInt())
}

// A lower bound on `log2(this)` for a positive fraction, which lies below `2^(log2Floor() + 2)`.
private fun BigFraction.log2Floor(): Long = num.magnitudeBitLength().toLong() - den.magnitudeBitLength() - 1

// Powers up to this many bits are formed exactly; wider ones are bracketed from this many significant bits upward.
private const val POWER_WIDTH = 1L shl 16
private const val POWER_BRACKET_BITS = 128

// The binary exponents a bracket may reach: a power this far from one is decided from bit lengths before bracketing.
private const val POWER_EXPONENT_RANGE = 1L shl 20

private fun slackedRow(
    row: LinearRow,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: ExecutionControl,
): Boolean? {
    val constants = row.constants
    val bound = when (constants) {
        is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
        is RealConstants -> BigFraction.ofDouble(constants.bound) ?: return null
    }
    var activity = BigFraction.ZERO
    var magnitude = BigFraction.ZERO
    for (index in 0 until row.size) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coefficient = when (constants) {
            is IntegralConstants -> BigFraction.of(constants.exactCoeff(index), BIG_ONE)
            is RealConstants -> BigFraction.ofDouble(if (index < constants.intCoefficients.size) {
                constants.intCoefficients.at(index)
            } else constants.realCoefficients.at(index - constants.intCoefficients.size)) ?: return null
        }
        val reference = row.ref(index)
        val value = when {
            Term.isBool(reference) -> {
                val literal = Term.lit(reference)
                if (Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))) BigFraction.ONE
                else BigFraction.ZERO
            }
            Term.isInt(reference) -> BigFraction.of(sample.exactIntValue(Term.intVar(reference)), BIG_ONE)
            else -> requireNotNull(sample.exactReals)[Term.realVar(reference)]
        }
        val term = coefficient * value
        activity += term
        magnitude += term.magnitude()
    }
    val slack = capturedSourceSlack(policy, bound, magnitude)
    if (cancellation()) return null
    return when (row.relation) {
        LinearOp.LE -> activity <= bound + slack
        LinearOp.GE -> activity >= bound - slack
        LinearOp.EQ -> (activity - bound).magnitude() <= slack
        LinearOp.NE -> null
    }
}

private fun BigFraction.magnitude(): BigFraction = if (this < BigFraction.ZERO) negated() else this
