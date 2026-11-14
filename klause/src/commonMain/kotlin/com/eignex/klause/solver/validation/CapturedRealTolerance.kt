package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
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
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntDisjointSet
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf

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

internal fun verifyCapturedLinearTolerance(
    source: Problem,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
): Verification<Sample, Unit> {
    val reals = sample.exactReals
        ?: return Verification.Indeterminate("captured tolerance requires rational source coordinates")
    for (id in reals.indices) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("captured tolerance bound check cancelled")
        val lower = BigFraction.ofDouble(source.realLower[id])
        val upper = BigFraction.ofDouble(source.realUpper[id])
        if ((lower != null && reals[id] < lower - capturedSourceSlack(policy, lower)) ||
            (upper != null && reals[id] > upper + capturedSourceSlack(policy, upper))
        ) return Verification.Rejected("captured source tolerance refutes a real declaration")
    }
    for (factor in source.factors) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("captured tolerance factor check cancelled")
        if (!factor.hasCapturedLinearTolerance()) {
            if (factor.variables.reals.isEmpty()) continue
            return Verification.Indeterminate("captured tolerance has no complete source relation adapter")
        }
        val form = if (factor is LinearIndicator) {
            if (sample.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L)) continue
            LinearForm.Conjunction(listOf(factor.row))
        } else factor.linearForm
        val rows = when (form) {
            is LinearForm.Conjunction -> form.rows
            is LinearForm.Disjunction -> form.rows
            else -> return Verification.Indeterminate("captured tolerance has no complete source relation adapter")
        }
        val checked = ArrayList<Boolean>(rows.size)
        for (row in rows) {
            checked += toleranceRow(row, sample, policy, cancellation)
                ?: return Verification.Indeterminate("captured tolerance row is unavailable or cancelled")
        }
        val valid = if (form is LinearForm.Disjunction) checked.any { it } else checked.all { it }
        if (cancellation()) return Verification.Indeterminate("captured tolerance row check cancelled")
        if (!valid) return Verification.Rejected("captured source tolerance refutes a real relation")
    }
    return if (cancellation()) Verification.Indeterminate("captured tolerance check cancelled")
    else Verification.Accepted(Candidate(sample, Unit))
}

private fun toleranceRow(
    row: LinearRow,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
): Boolean? {
    if (row.strict || row.activator != LinearRow.ALWAYS || row.relation == LinearOp.NE) return null
    return slackedRow(row, sample, policy, cancellation)
}

private fun slackedRow(
    row: LinearRow,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
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

// Acceptance of real completions within the tolerance the source declares. It is null when that tolerance is
// zero or absent, when there is no real coordinate, or when no real component reads the slack.
//
// Real coordinates that share a relation form a component, and each component is judged on its own. In a component
// whose relations are all linear rows and finite memberships, a non-strict active row reads the slack and a finite
// membership admits a value within the slack of a member. A strict row, a disequality or a row whose activator is
// false has no slack to read, so it must hold exactly. A component with any other real relation holds exactly,
// declarations included, so a nonlinear relation never reads the slack and never decides how an unrelated
// component is judged. Factors over integers and Booleans alone are left to the engine that enforces them.
internal fun capturedRealAcceptance(source: Problem, cancellation: Cancellation): FiniteSourceAcceptance? {
    val policy = source.semantics.effective.feasibilityTolerance
    if (policy == null || policy.isExact || source.numRealVars == 0) return null
    val relations = IntArrayList()
    val components = IntDisjointSet(source.numRealVars)
    for ((id, factor) in source.factors.withIndex()) {
        val reals = factor.variables.reals
        if (reals.isEmpty()) continue
        for (real in reals) components.union(reals[0], real)
        relations.add(id)
    }
    val checked = relations.toIntArray()
    val exactRoots = BooleanArray(source.numRealVars)
    for (id in checked) {
        val factor = source.factors[id]
        if (!factor.readsCapturedSlack()) exactRoots[components.find(factor.variables.reals[0])] = true
    }
    val exact = BooleanArray(source.numRealVars) { exactRoots[components.find(it)] }
    if (exact.all { it }) return null
    return FiniteSourceAcceptance(
        { sample -> acceptsWithinTolerance(source, checked, exact, sample, policy, cancellation) },
        permitsToleranceOptimum = true,
    )
}

private fun Factor.readsCapturedSlack(): Boolean = this is LinearIndicator || this is RealFiniteDomain ||
    linearForm.let { it is LinearForm.Conjunction || it is LinearForm.Disjunction }

private fun acceptsWithinTolerance(
    source: Problem,
    relations: IntArray,
    exact: BooleanArray,
    sample: Sample,
    policy: SourceTolerance,
    cancellation: Cancellation,
): Boolean {
    if (sample.numRealVars != source.numRealVars || sample.numIntVars != source.numIntVars ||
        sample.numBoolVars != source.numBoolVars
    ) return false
    val reals = sample.exactReals ?: List(source.numRealVars) {
        BigFraction.ofDouble(sample.approximateRealValue(it)) ?: return false
    }
    val point = if (sample.exactReals != null) sample else sample.copy(exactReals = reals)
    fun slack(id: Int, bound: BigFraction) = if (exact[id]) BigFraction.ZERO else capturedSourceSlack(policy, bound)
    for (id in reals.indices) {
        cancellation.charge(1)
        if (cancellation()) return false
        val lower = BigFraction.ofDouble(source.realLower[id])
        val upper = BigFraction.ofDouble(source.realUpper[id])
        if ((lower != null && reals[id] < lower - slack(id, lower)) ||
            (upper != null && reals[id] > upper + slack(id, upper))
        ) return false
    }
    val witness = object : ExactWitness {
        override fun truth(boolVar: Int): Boolean = point.boolValue(boolVar)
        override fun at(column: Int): BigFraction = if (column < source.numRealVars) reals[column] else {
            BigFraction.of(point.exactIntValue(column - source.numRealVars), BIG_ONE)
        }
    }
    fun holds(row: LinearRow): Boolean {
        cancellation.charge(row.size.toLong() + 1L)
        if (cancellation()) return false
        val active = row.activator == LinearRow.ALWAYS || witness.truth(row.activator)
        if (active && !row.strict && row.relation != LinearOp.NE) {
            slackedRow(row, point, policy, cancellation)?.let { return it }
        }
        return row.exactComparison(source.numRealVars, active, witness::truth).holdsAt(witness)
    }
    for (id in relations) {
        cancellation.charge(1)
        if (cancellation()) return false
        val factor = source.factors[id]
        val valid = if (factor is RealFiniteDomain) {
            (0 until factor.size).any { index ->
                val member = checkNotNull(BigFraction.ofDouble(factor.valueAt(index)))
                (reals[factor.variable] - member).magnitude() <= slack(factor.variable, member)
            }
        } else if (exact[factor.variables.reals[0]]) {
            checkExactRealRelation(id, factor, point, reals, cancellation) == null
        } else if (factor is LinearIndicator) {
            point.exactIntValue(factor.indicator) != bigIntOf(if (factor.whenOne) 1L else 0L) || holds(factor.row)
        } else when (val form = factor.linearForm) {
            is LinearForm.Conjunction -> form.rows.all(::holds)
            is LinearForm.Disjunction -> form.rows.any(::holds)
            else -> false
        }
        if (!valid) return false
    }
    return !cancellation()
}
