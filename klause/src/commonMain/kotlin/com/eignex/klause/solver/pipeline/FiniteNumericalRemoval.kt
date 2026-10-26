package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength
import kotlin.random.Random

internal class FiniteNumericalRemoval(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val source: SourceProofContext,
    private val funding: SourceLpFunding,
    val signal: Signal,
    private val callerPins: Assumptions = Assumptions.None,
) : DestroyOperator, AutoCloseable {
    enum class Signal { Disagreement, Refutation }
    enum class Status { Unavailable, NoPoint, Agreement, EmptySupport, Supported, Incompatible,
        Unsupported, Limited, Cancelled, Funding, Closed }

    private var center: Sample? = null
    private var scores = intArrayOf()
    private var token = Cancellation.Never
    private var closed = false
    var status = Status.Unavailable
        private set
    var originScope: SourceScope? = null
        private set
    var originCutoff: SourceNumericalCutoff? = null
        private set
    var unavailableReason: String? = null
        private set
    var rankedInputs = 0
        private set

    fun accept(
        incumbent: Sample,
        pins: Assumptions,
        query: SourceProofContext,
        cutoff: SourceNumericalCutoff?,
        feedback: SourceNumericalFeedbackResult,
        support: SourceNumericalSupportResult? = null,
        cancellation: Cancellation = Cancellation.Never,
    ) {
        check(!closed)
        retire(Status.Unavailable)
        token = cancellation
        if (blocked()) return
        val count = model.numBoolVars.toLong() + model.numIntVars
        if (model.numRealVars != 0 || !callerPins.deductions.isEmpty || !pins.deductions.isEmpty) {
            retire(Status.Unsupported)
            return
        }
        if (count > 4096) {
            retire(Status.Limited)
            return
        }
        funding.charge(1L + count, 64L + count * 4L)
        if (blocked()) return
        val validScope = incumbent.numBoolVars == model.numBoolVars && incumbent.numIntVars == model.numIntVars &&
            incumbent.numRealVars == 0 && compatible(query, pins, cutoff)
        if (blocked()) return
        if (!validScope) {
            retire(Status.Incompatible)
            return
        }
        if (feedback !is SourceNumericalFeedbackResult.Available) {
            retire(Status.Unavailable)
            unavailableReason = (feedback as SourceNumericalFeedbackResult.Unavailable).reason.name
            return
        }
        if (!feedback.compatible(query, cutoff)) {
            retire(Status.Incompatible)
            return
        }
        scores = IntArray(count.toInt())
        when (signal) {
            Signal.Disagreement -> {
                val point = feedback.query.point
                if (point == null) {
                    retire(Status.NoPoint)
                    return
                }
                funding.charge(point.size.toLong())
                if (blocked()) return
                if (point.keys.any { index(it) == null }) {
                    retire(Status.Incompatible)
                    return
                }
                for (index in scores.indices) {
                    funding.charge(1)
                    if (blocked()) return
                    val coordinate = coordinate(index)
                    val relaxed = point[coordinate] ?: if (coordinate.kind == SourceCoordinate.Kind.Boolean) {
                        query.scope.condition.bools[coordinate.id]?.let {
                            if (it) BigFraction.ONE else BigFraction.ZERO
                        }
                    } else null
                    if (relaxed == null) {
                        retire(Status.Unavailable)
                        unavailableReason = "missing-coordinate"
                        return
                    }
                    if (relaxed.num.magnitudeBitLength() > 4096 || relaxed.den.magnitudeBitLength() > 4096) {
                        retire(Status.Limited)
                        return
                    }
                    if (!frozen(index) && relaxed != value(incumbent, index)) scores[index] = 1
                }
                status = if (scores.any { it != 0 }) Status.Supported else Status.Agreement
            }
            Signal.Refutation -> {
                if (support !is SourceNumericalSupportResult.Available) {
                    retire(Status.Unavailable)
                    unavailableReason = (support as? SourceNumericalSupportResult.Unavailable)?.reason?.name
                        ?: "missing-support"
                    return
                }
                if (support.feedback !== feedback) {
                    retire(Status.Incompatible)
                    return
                }
                for (coordinate in support.coordinates) {
                    funding.charge(1)
                    if (blocked()) return
                    val index = index(coordinate)
                    if (index == null) {
                        retire(Status.Incompatible)
                        return
                    }
                    if (!frozen(index)) scores[index] = 1
                }
                status = if (scores.any { it != 0 }) Status.Supported else Status.EmptySupport
            }
        }
        if (blocked()) return
        center = incumbent
        originScope = query.scope
        originCutoff = cutoff
        rankedInputs = scores.count { it != 0 }
    }

    private fun compatible(query: SourceProofContext, pins: Assumptions, cutoff: SourceNumericalCutoff?): Boolean {
        if (!objectiveMatches() || source.scope.condition != callerPins.sourceCondition() ||
            query.scope != source.scope.copy(condition = pins.sourceCondition()) ||
            query.coordinates != source.coordinates || query.objective != source.objective ||
            query.objectiveConstant != source.objectiveConstant || query.semantics != source.semantics ||
            query.semanticsChecker !== source.semanticsChecker) return false
        if (callerPins.bools.any { (id, value) -> pins.boolValueOrNull(id) != value } ||
            callerPins.ints.any { (id, value) -> pins.intValueOrNull(id) != value }) return false
        val original = source.premises
        val conditioned = query.premises
        funding.charge(1L + original.size + conditioned.size)
        if (token() || funding.paused) return false
        if (original.any { (id, premise) -> conditioned[id] != premise }) return false
        val extra = if (cutoff != null && cutoff.premise !in original) 1 else 0
        return conditioned.size == original.size + extra && (cutoff == null || cutoff.enforcedBy(query)) &&
            source.coordinates.size == model.numBoolVars + model.numIntVars &&
            source.coordinates.all { index(it) != null }
    }

    private fun objectiveMatches(): Boolean {
        val direction = source.scope.objective?.sense ?: return false
        if (!source.affineObjectiveAvailable || objective.boolWeightValues.size > model.numBoolVars ||
            objective.intCoefficientValues.size > model.numIntVars ||
            objective.realCoefficientValues.any { it != 0.0 }) {
            return false
        }
        val coefficients = source.objective
        fun original(value: Long): BigFraction = BigFraction.ofLong(value).let {
            if (direction == ObjectiveSense.MINIMIZE) it else it.negated()
        }
        if (source.objectiveConstant != original(objective.constant)) return false
        for (index in 0 until model.numBoolVars + model.numIntVars) {
            val coefficient = if (index < model.numBoolVars) objective.boolWeightValues.getOrElse(index) { 0L }
                else objective.intCoefficientValues.getOrElse(index - model.numBoolVars) { 0L }
            if ((coefficients[coordinate(index)] ?: BigFraction.ZERO) != original(coefficient)) return false
        }
        return true
    }

    private fun coordinate(index: Int) = if (index < model.numBoolVars) {
        SourceCoordinate(SourceCoordinate.Kind.Boolean, index)
    } else SourceCoordinate(SourceCoordinate.Kind.Integer, index - model.numBoolVars)

    private fun index(coordinate: SourceCoordinate): Int? = when (coordinate.kind) {
        SourceCoordinate.Kind.Boolean -> coordinate.id.takeIf { it in 0 until model.numBoolVars }
        SourceCoordinate.Kind.Integer -> coordinate.id.takeIf { it in 0 until model.numIntVars }
            ?.let { model.numBoolVars + it }
        SourceCoordinate.Kind.Real -> null
    }

    private fun frozen(index: Int): Boolean = if (index < model.numBoolVars) callerPins.isFrozenBool(index)
        else callerPins.isFrozenInt(index - model.numBoolVars)

    private fun value(sample: Sample, index: Int): BigFraction = if (index < model.numBoolVars) {
        if (sample.boolValue(index)) BigFraction.ONE else BigFraction.ZERO
    } else BigFraction.ofLong(sample.intValue(index - model.numBoolVars))

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample, objective: LinearObjective,
        fraction: Double, assumptions: Assumptions, cancellation: Cancellation,
    ): FreedVars {
        if (!closed && assumptions != callerPins) retire(Status.Incompatible)
        if (!closed && cancellation()) {
            retire(Status.Cancelled)
            return FreedVars(intArrayOf(), intArrayOf())
        }
        return destroy(rng, problem, incumbent, objective, fraction)
    }

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample, objective: LinearObjective,
        fraction: Double,
    ): FreedVars {
        if (closed) status = Status.Closed
        if (blocked()) return FreedVars(intArrayOf(), intArrayOf())
        if (!closed && (problem !== model || objective !== this.objective ||
                (center != null && center !== incumbent))) retire(Status.Incompatible)
        if (status != Status.Supported) {
            return DestroyOperator.Random.destroy(rng, problem, incumbent, objective, fraction)
        }
        funding.charge(scores.size.toLong(), scores.size * 16L)
        if (blocked()) return FreedVars(intArrayOf(), intArrayOf())
        val order = scores.indices.shuffled(rng)
        val count = (fraction * scores.size).toInt().coerceIn(1, scores.size)
        val selected = ArrayList<Int>(count)
        for (weight in 1 downTo 0) {
            for (index in order) {
                funding.charge(1)
                if (blocked()) return FreedVars(intArrayOf(), intArrayOf())
                if (!frozen(index) && scores[index] == weight) selected += index
                if (selected.size == count) break
            }
            if (selected.size == count) break
        }
        return FreedVars(selected.filter { it < model.numBoolVars }.toIntArray(),
            selected.filter { it >= model.numBoolVars }.map { it - model.numBoolVars }.toIntArray())
    }

    private fun blocked(): Boolean {
        if (closed) return false
        val reason = when {
            token() -> Status.Cancelled
            funding.paused -> Status.Funding
            else -> return false
        }
        retire(reason)
        return true
    }

    private fun retire(reason: Status) {
        center = null
        scores = intArrayOf()
        token = Cancellation.Never
        originScope = null
        originCutoff = null
        rankedInputs = 0
        unavailableReason = null
        status = reason
    }

    override fun close() {
        closed = true
        retire(Status.Closed)
    }
}
