package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal fun verifyRealCoordinates(model: Problem, sample: Sample): Verification<Sample, Unit> =
    verifyRealCoordinates(model, sample, Cancellation.Never)

internal fun verifyRealCoordinates(
    model: Problem,
    sample: Sample,
    cancellation: Cancellation,
): Verification<Sample, Unit> {
    cancellation.charge(1L)
    if (cancellation()) return Verification.Indeterminate("source coordinate check cancelled")
    if (model.factors.any { it is Distinct || it is AllDifferent } &&
        (sample.numIntVars != model.numIntVars || sample.numBoolVars != model.numBoolVars)
    ) return Verification.Rejected("assignment does not cover distinctness coordinates")
    for ((id, factor) in model.factors.withIndex()) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source coordinate check cancelled")
        if (factor !is Abs && factor !is AffineAbs && factor !is AllDifferent &&
            !(factor is Distinct && factor.variables.reals.isEmpty())
        ) continue
        if (sample.numIntVars != model.numIntVars) {
            return Verification.Rejected("assignment does not cover the integer variables")
        }
        val valid = when (factor) {
            is Abs -> sample.exactIntValue(factor.operand).abs() == sample.exactIntValue(factor.result)
            is AffineAbs -> factor.isSatisfied(sample::exactIntValue)
            is AllDifferent -> factor.isSatisfied(sample)
            is Distinct -> factor.isSatisfied(sample::exactIntValue, sample::boolValue) {
                error("integer distinct operand has a real coordinate")
            }
            else -> false
        }
        if (cancellation()) return Verification.Indeterminate("real witness check cancelled")
        if (!valid) {
            return Verification.Rejected("integer factor $id refutes the assignment")
        }
    }
    if (model.numRealVars == 0) return Verification.Accepted(Candidate(sample, Unit))
    val reals = sample.exactReals ?: return Verification.Indeterminate("continuous values are not certified")
    if (reals.size != model.numRealVars) return Verification.Rejected("assignment does not cover the real variables")
    for (id in reals.indices) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source coordinate check cancelled")
        val lower = BigFraction.ofDouble(model.realLower[id])
        val upper = BigFraction.ofDouble(model.realUpper[id])
        if ((lower != null && reals[id] < lower) || (upper != null && reals[id] > upper)) {
            return Verification.Rejected("real $id is outside its declared range")
        }
    }
    val witness = object : ExactWitness {
        override fun truth(boolVar: Int): Boolean = sample.boolValue(boolVar)
        override fun at(column: Int): BigFraction = if (column < model.numRealVars) reals[column] else {
            BigFraction.of(sample.exactIntValue(column - model.numRealVars), BIG_ONE)
        }
    }
    fun holds(row: LinearRow): Boolean {
        cancellation.charge(row.size.toLong() + 1L)
        if (cancellation()) return false
        return row.exactComparison(
            model.numRealVars, row.activator == LinearRow.ALWAYS || witness.truth(row.activator), witness::truth,
        ).holdsAt(witness)
    }
    for ((id, factor) in model.factors.withIndex()) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source coordinate check cancelled")
        if (factor.variables.reals.isEmpty()) continue
        if (factor is Distinct) {
            if (!factor.isSatisfied(sample::exactIntValue, sample::boolValue, reals::get)) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is Power && factor.base is Power.Coordinate.Real) {
            val matches = rationalPowerEquals(reals[factor.base.id.index], factor.exponent,
                reals[(factor.result as Power.Coordinate.Real).id.index], cancellation)
                ?: return Verification.Indeterminate("source power check unavailable or cancelled")
            if (cancellation()) return Verification.Indeterminate("source power check cancelled")
            if (!matches) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is ContinuousProduct) {
            if (reals[factor.a] * reals[factor.b] != reals[factor.result]) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            factor.finiteChoices?.let { choices ->
                var admitted = false
                for (index in 0 until choices.size) {
                    cancellation.charge(1)
                    if (cancellation()) return Verification.Indeterminate("source choice check cancelled")
                    val matches = reals[choices.operand] == BigFraction.ofDouble(choices.valueAt(index))
                    admitted = admitted || matches
                    if (choices.hasGuards) {
                        val literal = choices.literalAt(index)
                        if (Lit.evaluate(literal, sample.boolValue(Lit.variable(literal))) != matches) {
                            return Verification.Rejected("real factor $id refutes its choice guards")
                        }
                    }
                }
                if (!admitted) return Verification.Rejected("real factor $id refutes its operand domain")
            }
            continue
        }
        if (factor is RealAbs) {
            val operand = reals[factor.operand]
            val magnitude = if (operand < BigFraction.ZERO) operand.negated() else operand
            if (magnitude != reals[factor.result]) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is RealProduct) {
            val integer = BigFraction.of(sample.exactIntValue(factor.intOperand), BIG_ONE)
            if (integer * reals[factor.realOperand] != reals[factor.result]) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        if (factor is RealArrayMinMax) {
            var selected = reals[factor.xs[0]]
            for (operand in factor.xs) {
                cancellation.charge(1)
                if (cancellation()) return Verification.Indeterminate("source extrema check cancelled")
                val value = reals[operand]
                if (if (factor.max) value > selected else value < selected) selected = value
            }
            if (selected != reals[factor.result]) return Verification.Rejected("real factor $id refutes the assignment")
            continue
        }
        if (factor is RealElement) {
            val index = sample.exactIntValue(factor.idx)
            val position = if (index.fitsLong()) factor.position(index.toLongExact()) else null
            if (position == null) return Verification.Rejected("real factor $id has an invalid index")
            val selected = if (factor.arrIsVars) reals[factor.entries[position]]
                else checkNotNull(BigFraction.ofDouble(factor.constants[position]))
            if (selected != reals[factor.result]) return Verification.Rejected("real factor $id refutes the assignment")
            continue
        }
        if (factor is LinearIndicator) {
            val trigger = bigIntOf(if (factor.whenOne) 1L else 0L)
            val valid = sample.exactIntValue(factor.indicator) != trigger || holds(factor.row)
            if (cancellation()) return Verification.Indeterminate("real witness check cancelled")
            if (!valid) {
                return Verification.Rejected("real factor $id refutes the assignment")
            }
            continue
        }
        val form = factor.linearForm
        if (form == null || form is LinearForm.Relaxation) {
            return Verification.Indeterminate("real factor $id has no complete witness checker")
        }
        val valid = when (form) {
            is LinearForm.Conjunction -> form.rows.all(::holds)
            is LinearForm.Disjunction -> form.rows.any(::holds)
            is LinearForm.Relaxation -> false
        }
        if (cancellation()) return Verification.Indeterminate("source row check cancelled")
        if (!valid) return Verification.Rejected("real factor $id refutes the assignment")
    }
    return if (cancellation()) Verification.Indeterminate("source coordinate check cancelled")
    else Verification.Accepted(Candidate(sample, Unit))
}

private fun rationalPowerEquals(
    value: BigFraction,
    exponent: Int,
    expected: BigFraction,
    cancellation: Cancellation,
): Boolean? {
    cancellation.charge(1)
    if (cancellation()) return null
    val direct = when {
        exponent == 0 || value == BigFraction.ONE -> expected == BigFraction.ONE
        value.isZero -> expected.isZero
        value == BigFraction.MINUS_ONE -> expected ==
            if (exponent and 1 == 0) BigFraction.ONE else BigFraction.MINUS_ONE
        expected.isZero || (expected.signum() < 0) != (value.signum() < 0 && exponent and 1 != 0) -> false
        else -> null
    }
    if (direct != null) return direct.takeUnless { cancellation() }
    val numerator = positivePowerEquals(value.num.abs(), exponent, expected.num.abs(), cancellation)
        ?: return null
    if (!numerator) return false.takeUnless { cancellation() }
    val denominator = positivePowerEquals(value.den, exponent, expected.den, cancellation)
    return denominator?.takeUnless { cancellation() }
}

private fun positivePowerEquals(
    value: BigInt,
    exponent: Int,
    expected: BigInt,
    cancellation: Cancellation,
): Boolean? {
    if (value == BIG_ONE) return (expected == BIG_ONE).takeUnless { cancellation() }
    val width = expected.magnitudeBitLength().toLong()
    if ((value.magnitudeBitLength() - 1L) * exponent + 1L > width) return false
    var remaining = exponent
    var base = value
    var result = BIG_ONE
    while (remaining > 0) {
        cancellation.charge(1)
        if (cancellation()) return null
        if (remaining and 1 != 0) {
            val bits = result.magnitudeBitLength().toLong() + base.magnitudeBitLength()
            if (bits - 1L > width) return false
            if (bits > Int.MAX_VALUE) return null
            result *= base
            if (cancellation()) return null
        }
        remaining = remaining ushr 1
        if (remaining > 0) {
            val bits = 2L * base.magnitudeBitLength()
            if (bits - 1L > width) return false
            if (bits > Int.MAX_VALUE) return null
            base *= base
            if (cancellation()) return null
        }
    }
    return if (cancellation()) null else result == expected
}

private fun AllDifferent.isSatisfied(sample: Sample): Boolean {
    val seen = HashSet<BigInt>()
    for (position in vars.indices) {
        if (presents.isNotEmpty()) {
            val literal = presents[position]
            if (!Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))) continue
        }
        val value = sample.exactIntValue(vars[position])
        if (value.fitsLong() && value.toLongExact() in exceptSet) continue
        if (!seen.add(value)) return false
    }
    return true
}
