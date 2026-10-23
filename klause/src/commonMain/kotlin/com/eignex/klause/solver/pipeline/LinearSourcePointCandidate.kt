package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.search.AssembledSearchModel
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExactRealValue
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength

internal class LinearSourcePointCandidate private constructor(
    private val source: Problem,
    selection: PreparedFactorProjections,
    private val learnClauseCubes: Boolean,
    private val observe: (String) -> Unit,
) {
    private val projection = PropagationProblem(source, selection)
    private var previous: List<BigFraction>? = null
    private var attempts = 0

    fun check(point: List<BigFraction>, owner: SearchSession): AssembledSearchModel? {
        if (owner.executionControls.exhausted || point === previous || attempts == MAX_ATTEMPTS) return null
        previous = point
        attempts++
        observe("SOURCE_POINT_ATTEMPT")
        val parent = owner.executionControls.cancellation
        val operation = owner.executionControls.operationAllowance(parent)
        var work = 0L
        var stopped = false
        val meter = WorkMeter { units ->
            work += units.coerceAtMost(MAX_WORK + 1L)
            parent.charge(units)
        }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (!stopped) stopped = work > MAX_WORK || parent() || operation()
                return stopped
            }
            override fun deadline() = operation.deadline()
            override fun workMeter(): WorkMeter = meter
        }
        val sample = complete(point, owner, stop) ?: run {
            observe(if (stop()) "SOURCE_POINT_STOPPED" else "SOURCE_POINT_INCOMPLETE")
            return null
        }
        observe("SOURCE_POINT_BOOLEAN_COMPLETE")
        if (stop() || !owner.consumeCheck()) return null
        val checker = FixedSourcePoint(source, sample, projection, stop)
        // The containing check was reserved above; this private query creates no native allowance.
        val query = SearchSession(listOf(checker), maxChecks = 1, cancellation = stop)
        if (query.initialize() != ComponentResult.Consistent || stop()) return null
        observe("SOURCE_POINT_SHARED_CHECK")
        if (query.check() != ComponentCheck.Feasible || stop()) return null
        stop.charge(source.numBoolVars.toLong() + source.numRealVars)
        if (stop()) return null
        return query.model().also { observe("SOURCE_POINT_ACCEPTED") }
    }

    private fun complete(point: List<BigFraction>, owner: SearchSession, stop: Cancellation): Sample? {
        if (point.size != source.numRealVars || stop()) return null
        stop.charge(source.numBoolVars * (if (learnClauseCubes) 2L else 1L) + source.numRealVars)
        if (stop()) return null
        val values = arrayOfNulls<Boolean>(source.numBoolVars)
        val reasons = if (learnClauseCubes) IntArray(source.numBoolVars) { -1 } else null
        for (id in values.indices) {
            stop.charge(1)
            if (stop()) return null
            val pinned = owner.boolValue(id)
            if (pinned != null && owner.levelOf(Lit.make(id, pinned)) == 0) values[id] = pinned
        }
        for (coordinate in point) if (!scalar(coordinate, stop)) return null
        for (factor in source.factors) {
            stop.charge(1)
            if (stop()) return null
            if (factor !is ExactLinear) continue
            val truth = truth(factor, point, stop) ?: return null
            if (factor.activator == LinearRow.ALWAYS) {
                if (!truth) return null
            } else {
                val old = values[factor.activator]
                if (old != null && old != truth) return null
                values[factor.activator] = truth
            }
        }
        observe("SOURCE_POINT_ROWS_COMPLETE")
        var changed: Boolean
        do {
            changed = false
            for ((factorIndex, factor) in source.factors.withIndex()) {
                stop.charge(1)
                if (stop()) return null
                if (factor !is Clause || factor.tautological) continue
                var satisfied = false
                var unknown = 0
                var unit = 0
                for (literal in factor.literals) {
                    stop.charge(1)
                    if (stop()) return null
                    val value = values[Lit.variable(literal)]
                    if (value == null) {
                        unknown++
                        unit = literal
                    } else if (Lit.evaluate(literal, value)) {
                        satisfied = true
                        break
                    }
                }
                if (satisfied) continue
                if (unknown == 0) {
                    if (reasons != null) learnConflict(factor, values, reasons, owner, stop)
                    return null
                }
                if (unknown == 1) {
                    val variable = Lit.variable(unit)
                    values[variable] = Lit.isPositive(unit)
                    reasons?.set(variable, factorIndex)
                    changed = true
                }
            }
        } while (changed)
        observe("SOURCE_POINT_CLAUSES_COMPLETE")
        stop.charge(source.numBoolVars.toLong() + 2L * source.numRealVars)
        if (stop()) return null
        return Sample.fromOwned(BooleanArray(values.size) { values[it] ?: false }, longArrayOf(),
            DoubleArray(point.size) { point[it].toDouble() }, point)
    }

    private fun learnConflict(
        conflict: Clause,
        values: Array<Boolean?>,
        reasons: IntArray,
        owner: SearchSession,
        stop: Cancellation,
    ) {
        observe("SOURCE_POINT_CUBE_ATTEMPT")
        stop.charge(3L * values.size + 1L)
        if (stop()) return
        val seen = BooleanArray(values.size)
        val pending = IntArray(values.size)
        val clause = IntArray(minOf(values.size, MAX_CUBE_LITERALS))
        var head = 0
        var tail = 0
        var size = 0
        var entries = 0
        fun enqueueFalse(literal: Int): Boolean {
            stop.charge(1)
            if (stop() || entries++ == MAX_CUBE_ENTRIES) return false
            val variable = Lit.variable(literal)
            val value = values.getOrNull(variable) ?: return false
            if (Lit.evaluate(literal, value)) return false
            if (!seen[variable]) {
                seen[variable] = true
                pending[tail++] = variable
            }
            return true
        }
        for (literal in conflict.literals) if (!enqueueFalse(literal)) return
        while (head < tail) {
            stop.charge(1)
            if (stop()) return
            val variable = pending[head++]
            val value = values[variable] ?: return
            val reason = reasons[variable]
            if (reason < 0) {
                if (size == clause.size) return
                clause[size++] = Lit.make(variable, !value)
            } else {
                val row = source.factors[reason] as? Clause ?: return
                val implied = Lit.make(variable, value)
                var found = false
                for (literal in row.literals) {
                    if (literal == implied) {
                        stop.charge(1)
                        if (stop() || entries++ == MAX_CUBE_ENTRIES) return
                        found = true
                    } else if (!enqueueFalse(literal)) return
                }
                if (!found) return
            }
        }
        if (size == 0) return
        stop.charge(8L * size + 1L)
        if (stop()) return
        // Only source-clause resolution proves this lemma; point truths remain assumptions in it.
        owner.learn(SearchExplanation(clause.copyOf(size)))
        observe("SOURCE_POINT_CUBE_OFFERED")
        observe("SOURCE_POINT_CUBE_L_$size")
    }

    private fun truth(row: ExactLinear, point: List<BigFraction>, stop: Cancellation): Boolean? {
        var activity = BigFraction.ZERO
        for (index in 0 until row.size) {
            stop.charge(1)
            if (stop() || !Term.isReal(row.ref(index))) return null
            val coefficient = BigFraction.of(row.constants.exactCoeff(index), BIG_ONE)
            val coordinate = point[Term.realVar(row.ref(index))]
            if (!scalar(coefficient, stop) || !preflight(coefficient, coordinate, stop)) return null
            val product = coefficient * coordinate
            if (!scalar(product, stop) || !preflight(activity, product, stop)) return null
            activity += product
            if (!scalar(activity, stop)) return null
        }
        val bound = BigFraction.of(row.constants.exactBound, BIG_ONE)
        if (!scalar(bound, stop) || !preflight(activity, bound, stop)) return null
        return when (row.relation) {
            LinearOp.LE -> if (row.strict) activity < bound else activity <= bound
            LinearOp.GE -> if (row.strict) activity > bound else activity >= bound
            LinearOp.EQ -> activity == bound
            LinearOp.NE -> activity != bound
        }
    }

    private fun scalar(value: BigFraction, stop: Cancellation): Boolean {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        stop.charge(1L + bits / Long.SIZE_BITS)
        return bits <= MAX_BITS && !stop()
    }

    private fun preflight(left: BigFraction, right: BigFraction, stop: Cancellation): Boolean {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        if (bits > MAX_BITS * 2L + 2L) return false
        val words = bits / Long.SIZE_BITS + 1L
        stop.charge(bits + words * words)
        return !stop()
    }

    companion object {
        private const val MAX_COORDINATES = 65_536
        private const val MAX_ATTEMPTS = 64
        private const val MAX_WORK = 16_777_216L
        private const val MAX_BITS = 4096
        private const val MAX_CUBE_ENTRIES = 65_536
        private const val MAX_CUBE_LITERALS = 4096

        fun prepare(
            source: Problem,
            selection: PreparedFactorProjections,
            stop: Cancellation,
            observe: (String) -> Unit = {},
        ): LinearSourcePointCandidate? {
            if (source.numIntVars != 0 || source.numRealVars == 0 || source.numBoolVars == 0 ||
                source.numBoolVars.toLong() + source.numRealVars > MAX_COORDINATES ||
                selection.providers !== FactorProjectionProviders.Default || selection.problem !== source
            ) return null
            var hasCardinality = false
            for (factor in source.factors) {
                stop.charge(1)
                if (stop() || (factor !is ExactLinear && factor !is Clause && factor !is Cardinality)) return null
                if (factor is Cardinality) hasCardinality = true
            }
            return LinearSourcePointCandidate(source, selection, hasCardinality, observe)
        }
    }
}

private class FixedSourcePoint(
    private val source: Problem,
    private val sample: Sample,
    private val projection: PropagationProblem,
    private val stop: Cancellation,
) : SearchComponent {
    private var accepted = false

    override fun initialize(context: SearchContext): ComponentResult {
        for (id in 0 until sample.numBoolVars) {
            stop.charge(1)
            if (stop()) return ComponentResult.Indeterminate
            val result = context.publish(Lit.make(id, sample.boolValue(id)))
            if (result != ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck {
        if (stop() || !context.consumeCheck()) return ComponentCheck.Indeterminate
        accepted = verifyOpenWitness(source, sample, stop, projection) is Verification.Accepted
        return if (accepted && !stop()) ComponentCheck.Feasible else ComponentCheck.Indeterminate
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        check(accepted)
        for (id in 0 until sample.numRealVars) {
            model.contribute(SearchExactRealValue(SourceRealId(id)), sample.exactReals!![id])
        }
    }
}
