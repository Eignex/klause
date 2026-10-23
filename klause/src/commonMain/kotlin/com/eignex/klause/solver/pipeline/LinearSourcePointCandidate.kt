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
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength

internal class LinearSourcePointCandidate private constructor(
    private val source: Problem,
    selection: PreparedFactorProjections,
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
        stop.charge(source.numBoolVars.toLong() + source.numRealVars)
        if (stop()) return null
        val values = arrayOfNulls<Boolean>(source.numBoolVars)
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
        var changed: Boolean
        do {
            changed = false
            for (factor in source.factors) {
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
                if (unknown == 0) return null
                if (unknown == 1) {
                    values[Lit.variable(unit)] = Lit.isPositive(unit)
                    changed = true
                }
            }
        } while (changed)
        stop.charge(source.numBoolVars.toLong() + 2L * source.numRealVars)
        if (stop()) return null
        return Sample.fromOwned(BooleanArray(values.size) { values[it] ?: false }, longArrayOf(),
            DoubleArray(point.size) { point[it].toDouble() }, point)
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
            for (factor in source.factors) {
                stop.charge(1)
                if (stop() || (factor !is ExactLinear && factor !is Clause && factor !is Cardinality)) return null
            }
            return LinearSourcePointCandidate(source, selection, observe)
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
