package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.meta.alns.RepairBlockClosure
import com.eignex.klause.meta.alns.RepairBlockInterrupted
import com.eignex.klause.meta.alns.RepairBlockWork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.magnitudeBitLength
import kotlin.random.Random

internal class FiniteNumericalRemoval(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val source: SourceProofContext,
    private val funding: SourceLpFunding,
    val signal: Signal,
    private val callerPins: Assumptions = Assumptions.None,
    private val definitions: InvariantNetwork? = null,
    val completion: Completion = Completion.None,
) : DestroyOperator, AutoCloseable {
    enum class Signal { Disagreement, Refutation }
    enum class Completion { None, SourceRows }
    enum class Status { Unavailable, NoPoint, Agreement, EmptySupport, Supported, Incomplete, Incompatible,
        Unsupported, Limited, Cancelled, Funding, ClosureDeclined, Closed }

    private var center: Sample? = null
    private var scores = intArrayOf()
    private var directions = intArrayOf()
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
    var packets: List<SourceRowCompletion.Packet> = emptyList()
        private set
    var lastDecline: String? = null
        private set
    var lastCompletedInputs = intArrayOf()
        private set
    var lastFilledInputs = 0
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
        directions = IntArray(count.toInt())
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
                    if (!frozen(index) && relaxed != value(incumbent, index)) {
                        scores[index] = 1
                        directions[index] = (relaxed - value(incumbent, index)).signum()
                    }
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
        if (completion == Completion.SourceRows && status == Status.Supported) {
            val seeds = ArrayList<Pair<Int, Int>>()
            for (index in scores.indices) {
                if (scores[index] == 0) continue
                if (directions[index] != 0) {
                    seeds += index to directions[index]
                } else {
                    seeds += index to 1
                    seeds += index to -1
                }
            }
            val completed = SourceRowCompletion(model, source, funding, ::frozen) { token() || funding.paused }
                .complete(incumbent, seeds) { scores[it] != 0 }
            if (blocked()) return
            packets = checkNotNull(completed)
            if (packets.none { it.outcome == SourceRowCompletion.Outcome.Complete }) status = Status.Incomplete
        }
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

    private fun rootFixed(index: Int): Boolean = if (index < model.numBoolVars) {
        (model.baked as? PropagationResult.Implied)?.boolValueOrNull(index) != null
    } else model.rootIntDomain(index - model.numBoolVars).isFixed

    private fun value(sample: Sample, index: Int): BigFraction = if (index < model.numBoolVars) {
        if (sample.boolValue(index)) BigFraction.ONE else BigFraction.ZERO
    } else BigFraction.ofLong(sample.intValue(index - model.numBoolVars))

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample, objective: LinearObjective,
        fraction: Double, assumptions: Assumptions, cancellation: Cancellation,
    ): FreedVars {
        if (!closed && assumptions != callerPins) {
            retire(Status.Incompatible)
            return FreedVars(intArrayOf(), intArrayOf())
        }
        return select(rng, problem, incumbent, objective, fraction, cancellation)
    }

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample, objective: LinearObjective,
        fraction: Double,
    ): FreedVars = select(rng, problem, incumbent, objective, fraction, Cancellation.Never)

    private fun select(rng: Random, problem: BakedProblem, incumbent: Sample, objective: LinearObjective,
        fraction: Double, cancellation: Cancellation,
    ): FreedVars {
        if (closed) status = Status.Closed
        if (blocked(cancellation)) return FreedVars(intArrayOf(), intArrayOf())
        if (!closed && (problem !== model || objective !== this.objective ||
                (center != null && center !== incumbent))) retire(Status.Incompatible)
        lastDecline = null
        lastCompletedInputs = intArrayOf()
        lastFilledInputs = 0
        if (status == Status.Incomplete) {
            lastDecline = "incomplete-packets"
            return FreedVars(intArrayOf(), intArrayOf())
        }
        if (status != Status.Supported) {
            return closeSelection(problem,
                DestroyOperator.Random.destroy(rng, problem, incumbent, objective, fraction), cancellation)
        }
        funding.charge(scores.size.toLong(), scores.size * 16L)
        if (blocked(cancellation)) return FreedVars(intArrayOf(), intArrayOf())
        val order = scores.indices.shuffled(rng)
        val count = (fraction * scores.size).toInt().coerceIn(1, scores.size)
        val selected = LinkedHashSet<Int>(count)
        if (completion == Completion.SourceRows) {
            val complete = packets.filter { it.outcome == SourceRowCompletion.Outcome.Complete }
                .map { packet -> packet.members.map { checkNotNull(index(it.coordinate)) } }
            val ranked = complete.indices.shuffled(rng).sortedByDescending { packet ->
                complete[packet].count { scores[it] != 0 }
            }
            for (packet in ranked) {
                funding.charge(1L + complete[packet].size)
                if (blocked(cancellation)) return FreedVars(intArrayOf(), intArrayOf())
                val added = complete[packet].filter { it !in selected }
                if (selected.size + added.size <= count) selected += added
            }
            if (selected.isEmpty()) {
                lastDecline = "packet-exceeds-request"
                return FreedVars(intArrayOf(), intArrayOf())
            }
            lastCompletedInputs = selected.toIntArray()
        }
        for (weight in 1 downTo 0) {
            if (selected.size == count) break
            for (index in order) {
                funding.charge(1)
                if (blocked(cancellation)) return FreedVars(intArrayOf(), intArrayOf())
                if (!frozen(index) && scores[index] == weight && index !in selected &&
                    (completion == Completion.None || !rootFixed(index))) selected += index
                if (selected.size == count) break
            }
        }
        lastFilledInputs = selected.size - lastCompletedInputs.size
        return closeSelection(problem, FreedVars(selected.filter { it < model.numBoolVars }.toIntArray(),
            selected.filter { it >= model.numBoolVars }.map { it - model.numBoolVars }.toIntArray()), cancellation)
    }

    private fun closeSelection(problem: BakedProblem, requested: FreedVars, cancellation: Cancellation): FreedVars {
        if (definitions == null) return requested
        if (problem !== model) return FreedVars(intArrayOf(), intArrayOf())
        funding.charge(1, (model.numBoolVars.toLong() + model.numIntVars) * 96L)
        if (blocked(cancellation)) return FreedVars(intArrayOf(), intArrayOf())
        val meter = WorkMeter { funding.charge(it) }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = token() || cancellation() || funding.paused
            override fun workMeter(): WorkMeter = meter
        }
        val work = RepairBlockWork(stop, 65_536)
        return try {
            val closedBlock = RepairBlockClosure.close(model, requested, definitions, callerPins,
                4096, 4096, work)
            if (blocked(cancellation)) FreedVars(intArrayOf(), intArrayOf()) else closedBlock.freed
        } catch (_: RepairBlockInterrupted) {
            if (!blocked(cancellation)) {
                retire(Status.ClosureDeclined)
                unavailableReason = work.decline?.name
            }
            FreedVars(intArrayOf(), intArrayOf())
        }
    }

    private fun blocked(cancellation: Cancellation = Cancellation.Never): Boolean {
        if (closed) return false
        val reason = when {
            token() || cancellation() -> Status.Cancelled
            funding.paused -> Status.Funding
            else -> return false
        }
        retire(reason)
        return true
    }

    private fun retire(reason: Status) {
        center = null
        scores = intArrayOf()
        directions = intArrayOf()
        packets = emptyList()
        lastDecline = null
        lastCompletedInputs = intArrayOf()
        lastFilledInputs = 0
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
