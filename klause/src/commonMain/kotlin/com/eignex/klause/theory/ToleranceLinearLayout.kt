package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
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
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.solver.validation.RealToleranceScope
import com.eignex.klause.solver.validation.capturedSourceSlack
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact

// The sample-independent part of the real completion model of [problem] in which every relation reads [policy] as
// [RealToleranceScope] defines it, with the slack its bound alone admits: `max(absolute, relative * abs(bound))`
// for a row, a declaration or a member, and the absolute slack for the residual row `r - f(x) = 0` of a nonlinear
// relation. Each such region lies inside the one the point check accepts, so an exact witness of [model] is a point
// within tolerance, and a refutation shows that no point holds every relation within that slack.
//
// A component that holds exactly keeps its exact encodings and declarations. A source with a real product without
// finite choices or a power above one, which no linear model states once the discrete coordinates are fixed, or with
// a kept relation no linear encoder enforces, is not [searchable].
internal class ToleranceLinearLayout(private val problem: Problem, policy: SourceTolerance) {
    private val scope = RealToleranceScope.of(problem)
    private val relaxed = ArrayList<Int>()
    private val kept = ArrayList<Int>()
    private val linear = problem.factors.all { it.variables.reals.isEmpty() || it.statesLinearly() }
    private val fixedForms = HashMap<Int, List<LinearForm>>()
    private val declarations = ArrayList<ExactSearchRow>()
    private val lower = DoubleArray(problem.numRealVars) { problem.realLower[it] }
    private val upper = DoubleArray(problem.numRealVars) { problem.realUpper[it] }
    private val rows = ToleranceRows(problem.numRealVars, policy)

    init {
        for ((index, factor) in problem.factors.withIndex()) {
            if (factor.variables.reals.isEmpty()) continue
            if (factor is Distinct || !relaxes(factor.variables.reals[0])) kept += index else {
                relaxed += index
                rows.fixedForms(factor)?.let { fixedForms[index] = it }
            }
        }
        for (id in 0 until problem.numRealVars) {
            if (!relaxes(id)) continue
            BigFraction.ofDouble(problem.realLower[id])?.let { bound ->
                lower[id] = Double.NEGATIVE_INFINITY
                declarations += rows.row(listOf(id to BigFraction.ONE), LinearOp.GE, bound - rows.slack(bound))
            }
            BigFraction.ofDouble(problem.realUpper[id])?.let { bound ->
                upper[id] = Double.POSITIVE_INFINITY
                declarations += rows.row(listOf(id to BigFraction.ONE), LinearOp.LE, bound + rows.slack(bound))
            }
        }
    }

    private fun relaxes(id: Int): Boolean = !scope.isExact(id)

    // Whether [model] states every relation: each relaxed one linearly, and each kept one through an encoder that
    // enforces it.
    fun searchable(encoders: PreparedFactorProjections): Boolean = linear && kept.all { index ->
        val encoder = encoders.encoder(index, EncodingTarget.LINEAR)
        encoder.enforcesSource || encoder.support == EncoderSupport.CONDITIONED
    }

    // The completion model at [sample]'s discrete coordinates. Null when an encoder declines or [cancellation]
    // stops the encoding.
    fun model(encoders: PreparedFactorProjections, sample: Sample, cancellation: Cancellation): Problem? {
        require(encoders.problem === problem) { "tolerance layout belongs to a different source" }
        val exact = encoders.linearTheoryModel(EncodingTarget.LINEAR, kept,
            (0 until problem.numIntVars).associate { SourceIntId(it) to sample.exactIntValue(it) }, cancellation,
            (0 until problem.numBoolVars).associate { SourceBoolId(it) to sample.boolValue(it) }) ?: return null
        val factors = ArrayList<Factor>(exact.factors.asList())
        for (index in relaxed) {
            cancellation.charge(1)
            if (cancellation()) return null
            val factor = problem.factors[index]
            val attribution = EncodingAttribution(index, factor.boolVars.map(::SourceBoolId),
                factor.intVars.map(::SourceIntId), factor.variables.reals.map(::SourceRealId))
            val forms = fixedForms[index] ?: rows.sampledForms(factor, sample) ?: return null
            for (form in forms) factors += EncodedTheoryFactor(factor, form, attribution)
        }
        factors += declarations
        if (cancellation()) return null
        val realCount = exact.numRealVars
        return EncodedTheoryModel(problem, factors.toTypedArray(), null, realCount,
            lower = DoubleArray(realCount) { if (it < lower.size) lower[it] else exact.realLower[it] },
            upper = DoubleArray(realCount) { if (it < upper.size) upper[it] else exact.realUpper[it] })
    }
}

// Whether this relation has a linear statement once the discrete coordinates are fixed.
private fun Factor.statesLinearly(): Boolean =
    !(this is ContinuousProduct && finiteChoices == null) &&
        !(this is Power && base is Power.Coordinate.Real && exponent > 1)

private class ToleranceRows(
    private val realColumns: Int,
    private val policy: SourceTolerance,
) {
    private val residual = capturedSourceSlack(policy, BigFraction.ZERO)

    fun slack(bound: BigFraction): BigFraction = capturedSourceSlack(policy, bound)

    // The forms of a relation that reads no discrete coordinate, or null for one that does.
    fun fixedForms(factor: Factor): List<LinearForm>? = when (factor) {
        is RealAbs -> listOf(
            conjunction(
                within(listOf(factor.result to BigFraction.ONE, factor.operand to BigFraction.MINUS_ONE),
                    LinearOp.GE, BigFraction.ZERO, residual) +
                    within(listOf(factor.result to BigFraction.ONE, factor.operand to BigFraction.ONE),
                        LinearOp.GE, BigFraction.ZERO, residual),
            ),
            disjunction(
                within(listOf(factor.result to BigFraction.ONE, factor.operand to BigFraction.MINUS_ONE),
                    LinearOp.LE, BigFraction.ZERO, residual) +
                    within(listOf(factor.result to BigFraction.ONE, factor.operand to BigFraction.ONE),
                        LinearOp.LE, BigFraction.ZERO, residual),
            ),
        )
        is RealArrayMinMax -> {
            val toward = if (factor.max) LinearOp.GE else LinearOp.LE
            val away = if (factor.max) LinearOp.LE else LinearOp.GE
            fun difference(operand: Int) = listOf(factor.result to BigFraction.ONE, operand to BigFraction.MINUS_ONE)
            listOf(
                conjunction(factor.xs.flatMap { within(difference(it), toward, BigFraction.ZERO, residual) }),
                disjunction(factor.xs.flatMap { within(difference(it), away, BigFraction.ZERO, residual) }),
            )
        }
        is Power -> {
            val base = (factor.base as Power.Coordinate.Real).id.index
            val result = (factor.result as Power.Coordinate.Real).id.index
            when (factor.exponent) {
                0 -> listOf(conjunction(within(listOf(result to BigFraction.ONE), LinearOp.EQ, BigFraction.ONE,
                    slack(BigFraction.ONE))))
                1 -> listOf(conjunction(within(listOf(result to BigFraction.ONE, base to BigFraction.MINUS_ONE),
                    LinearOp.EQ, BigFraction.ZERO, residual)))
                else -> null
            }
        }
        is RealFiniteDomain -> membership(factor.variable,
            (0 until factor.size).map { checkNotNull(BigFraction.ofDouble(factor.valueAt(it))) })
        is ContinuousProduct -> factor.finiteChoices?.takeUnless { it.hasGuards }?.let { unguardedProduct(factor) }
        else -> null
    }

    // The forms of a relation that reads [sample]'s discrete coordinates. Null when no linear statement exists.
    fun sampledForms(factor: Factor, sample: Sample): List<LinearForm>? = when (factor) {
        is RealElement -> {
            val index = sample.exactIntValue(factor.idx)
            val position = if (index.fitsLong()) factor.position(index.toLongExact()) else null
            listOf(conjunction(when {
                position == null -> listOf(contradiction())
                factor.arrIsVars -> within(listOf(factor.result to BigFraction.ONE,
                    factor.entries[position] to BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO, residual)
                else -> checkNotNull(BigFraction.ofDouble(factor.constants[position])).let { value ->
                    within(listOf(factor.result to BigFraction.ONE), LinearOp.EQ, value, slack(value))
                }
            }))
        }
        is RealProduct -> listOf(conjunction(within(listOf(factor.result to BigFraction.ONE,
            factor.realOperand to BigFraction.of(sample.exactIntValue(factor.intOperand), BIG_ONE).negated()),
            LinearOp.EQ, BigFraction.ZERO, residual)))
        is ContinuousProduct -> factor.finiteChoices?.let { guardedProduct(factor, sample) }
        is LinearIndicator -> if (sample.exactIntValue(factor.indicator) == bigIntOf(if (factor.whenOne) 1L else 0L)) {
            listOf(conjunction(statedRow(factor.row, sample)))
        } else emptyList()
        else -> when (val form = factor.linearForm) {
            is LinearForm.Conjunction -> listOf(conjunction(form.rows.flatMap { statedRow(it, sample) }))
            is LinearForm.Disjunction -> clauses(form.rows.map { statedRow(it, sample) })
            else -> null
        }
    }

    private fun productRows(factor: ContinuousProduct, value: BigFraction): List<LinearRow> {
        val other = if (checkNotNull(factor.finiteChoices).operand == factor.a) factor.b else factor.a
        return within(listOf(factor.result to BigFraction.ONE, other to value.negated()), LinearOp.EQ,
            BigFraction.ZERO, residual)
    }

    // A product with guarded finite operand choices: the true guards name one member, the operand lies within the
    // slack of it and the product row holds within the slack.
    private fun guardedProduct(factor: ContinuousProduct, sample: Sample): List<LinearForm> {
        val choices = checkNotNull(factor.finiteChoices)
        var selected: Double? = null
        var consistent = true
        for (index in 0 until choices.size) {
            val literal = choices.literalAt(index)
            if (!Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))) continue
            if (selected != null && selected != choices.valueAt(index)) consistent = false
            selected = choices.valueAt(index)
        }
        for (index in 0 until choices.size) {
            val literal = choices.literalAt(index)
            val truth = Lit.evaluate(literal, sample.boolValue(Lit.variable(literal)))
            if (choices.valueAt(index) == selected && !truth) consistent = false
        }
        val member = selected?.takeIf { consistent }?.let { checkNotNull(BigFraction.ofDouble(it)) }
            ?: return listOf(conjunction(listOf(contradiction())))
        return listOf(conjunction(within(listOf(choices.operand to BigFraction.ONE), LinearOp.EQ, member,
            slack(member)) + productRows(factor, member)))
    }

    // A product with unguarded finite operand choices selects the member its operand lies within the slack of; the
    // product row holds within the slack for every member whose slack interval contains the operand.
    private fun unguardedProduct(factor: ContinuousProduct): List<LinearForm> {
        val choices = checkNotNull(factor.finiteChoices)
        val members = (0 until choices.size).map { checkNotNull(BigFraction.ofDouble(choices.valueAt(it))) }
        val forms = ArrayList(membership(choices.operand, members))
        for (member in members) {
            val outside = listOf(
                row(listOf(choices.operand to BigFraction.ONE), LinearOp.LE, member - slack(member), strict = true),
                row(listOf(choices.operand to BigFraction.ONE), LinearOp.GE, member + slack(member), strict = true),
            )
            for (side in productRows(factor, member)) forms += disjunction(outside + side)
        }
        return forms
    }

    // A value within the slack of some member, as the hull of the member intervals without each gap between them.
    private fun membership(variable: Int, members: List<BigFraction>): List<LinearForm> {
        if (members.isEmpty()) return listOf(conjunction(listOf(contradiction())))
        val intervals = members.map { it - slack(it) to it + slack(it) }
            .sortedWith { left, right -> left.first.compareTo(right.first) }
        val merged = ArrayList<Pair<BigFraction, BigFraction>>()
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && interval.first <= last.second) {
                val upper = if (interval.second > last.second) interval.second else last.second
                merged[merged.lastIndex] = last.first to upper
            } else merged += interval
        }
        val unit = listOf(variable to BigFraction.ONE)
        val forms = arrayListOf<LinearForm>(conjunction(listOf(row(unit, LinearOp.GE, merged.first().first),
            row(unit, LinearOp.LE, merged.last().second))))
        for (gap in 1 until merged.size) {
            forms += disjunction(listOf(row(unit, LinearOp.LE, merged[gap - 1].second),
                row(unit, LinearOp.GE, merged[gap].first)))
        }
        return forms
    }

    // The rows one source row states at this point: an active non-strict row widened by the slack of its bound,
    // and a strict row, a disequality or the complement under a false activator exactly.
    private fun statedRow(row: LinearRow, sample: Sample): List<LinearRow> {
        val active = row.activator == LinearRow.ALWAYS || sample.boolValue(row.activator)
        val comparison = row.exactForm(realColumns).comparison(active) { sample.boolValue(it) }
        var bound = comparison.bound
        val terms = ArrayList<Pair<Int, BigFraction>>()
        for ((column, coefficient) in comparison.terms) {
            if (column < realColumns) terms += column to coefficient
            else bound -= coefficient * BigFraction.of(sample.exactIntValue(column - realColumns), BIG_ONE)
        }
        if (!active || row.strict || row.relation == LinearOp.NE) {
            return listOf(row(terms, comparison.op, bound, comparison.strict))
        }
        val source = when (val constants = row.constants) {
            is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
            is RealConstants -> checkNotNull(BigFraction.ofDouble(constants.bound))
        }
        return within(terms, comparison.op, bound, slack(source))
    }

    // `terms op bound` widened by [slack]: an equality becomes the two rows of its band.
    private fun within(
        terms: List<Pair<Int, BigFraction>>,
        op: LinearOp,
        bound: BigFraction,
        slack: BigFraction,
    ): List<LinearRow> = when (op) {
        LinearOp.LE -> listOf(row(terms, LinearOp.LE, bound + slack))
        LinearOp.GE -> listOf(row(terms, LinearOp.GE, bound - slack))
        LinearOp.EQ -> listOf(row(terms, LinearOp.LE, bound + slack), row(terms, LinearOp.GE, bound - slack))
        LinearOp.NE -> listOf(row(terms, LinearOp.NE, bound))
    }

    fun row(
        terms: List<Pair<Int, BigFraction>>,
        op: LinearOp,
        bound: BigFraction,
        strict: Boolean = false,
    ): ExactSearchRow {
        val merged = LinkedHashMap<Int, BigFraction>()
        for ((column, coefficient) in terms) merged[column] = (merged[column] ?: BigFraction.ZERO) + coefficient
        val nonzero = merged.entries.filter { !it.value.isZero }
        return ExactSearchRow.of(IntArray(nonzero.size) { Term.ofRealVar(nonzero[it].key) },
            nonzero.map { it.value }, op, bound, strict)
    }

    private fun contradiction(): ExactSearchRow = row(emptyList(), LinearOp.LE, BigFraction.MINUS_ONE)

    private fun conjunction(rows: List<LinearRow>): LinearForm = LinearForm.Conjunction(rows)

    private fun disjunction(rows: List<LinearRow>): LinearForm = LinearForm.Disjunction(rows)

    // A disjunction of alternatives of which at most one is a two-row band, stated as clauses without selectors.
    private fun clauses(alternatives: List<List<LinearRow>>): List<LinearForm> {
        val band = alternatives.indexOfFirst { it.size > 1 }
        if (band < 0) return listOf(disjunction(alternatives.map { it.single() }))
        val rest = alternatives.filterIndexed { index, _ -> index != band }.map { it.single() }
        return alternatives[band].map { side -> disjunction(rest + side) }
    }
}
