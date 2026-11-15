package com.eignex.klause.localsearch

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.solver.objective.BooleanObjectiveDelta
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.ExactIntegerSum
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.StopPolling
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.pollStop
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.toDouble

private const val MAX_PRESERVED_ELEMENT_SELECTIONS: Int = 32

internal class LocalSearchProbe(
    private val state: LocalSearchState,
    private val touched: BooleanArray,
    private val wasViolated: BooleanArray,
) {
    private enum class Phase { IDLE, PREPARING, APPLYING, READING, RESTORING }

    private var phase = Phase.IDLE
    val active: Boolean get() = phase != Phase.IDLE
    private val cancellable: Boolean get() = phase == Phase.APPLYING || phase == Phase.READING

    private val problem get() = state.problem
    private val assignment get() = state.assignment
    private val projection get() = state.projection
    private val factorDegree get() = state.factorDegree
    private val weights get() = state.weights
    private val cost get() = state.cost
    private val assignmentRevision get() = state.assignmentRevision

    // Only first degree changes are saved; weighted scoring visits them in factor-id order.
    private var savedDegrees: IntArray? = null
    private val touchedFactors = IntArrayList()

    // Definition outputs are saved even when the inverse definition cannot write a value.
    private val slots = IntArrayList()
    private val slotSet = IntHashSet()
    private var savedValues: LongArray = emptyLongArray()

    private val cumulativeFactors: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        problem.factors.indices.filter { problem.factors[it] is Cumulative }.toIntArray()
    }
    val hasCumulativeFactors: Boolean get() = cumulativeFactors.isNotEmpty()
    private var cumulativeFractions: DoubleArray = emptyDoubleArray()
    val hasVariableElements: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        problem.factors.any { it is Element && it.arrIsVars }
    }

    private var scoringCancellation: ExecutionControl = ExecutionControl.Never
    private var scoringCountdown = 0
    private var scoringPolling: StopPolling? = null
    private var scoringWork: WorkMeter? = null

    fun scoringPick(cancellation: ExecutionControl, pick: () -> Move?): Move? {
        scoringCancellation = cancellation
        scoringPolling = cancellation.stopPolling
        scoringWork = state.inspectionWork
        scoringCountdown = 0
        return try {
            val timer = state.phaseTimer
            if (cancellation.pollStop()) return null
            val selected = if (timer == null) pick() else timer.measureScoringPick(pick)
            if (cancellation.pollStop()) null else selected
        } catch (_: LocalSearchScoringInterrupted) {
            null
        } finally {
            scoringCancellation = ExecutionControl.Never
            scoringPolling = null
            scoringWork = null
        }
    }

    fun checkScoringWork() {
        scoringWork?.charge(1L)
        if (scoringCancellation === ExecutionControl.Never) return
        val polling = scoringPolling
        if (polling != null) {
            if (!polling.due(1)) return
        } else if (scoringCountdown-- > 0) {
            return
        }
        scoringCountdown = LOCAL_SEARCH_OPERATION_BATCH - 1
        if (scoringCancellation()) throw LocalSearchScoringInterrupted
    }

    fun checkWork() {
        if (cancellable) {
            scoringWork?.charge(1L)
            if (scoringCancellation === ExecutionControl.Never) return
            if (scoringPolling?.due(1) == false) return
            if (scoringCancellation()) throw LocalSearchScoringInterrupted
        }
    }

    private fun checkObjectiveProbeWork() {
        if (!cancellable) return
        scoringWork?.charge(1L)
        if (scoringCancellation === ExecutionControl.Never) return
        val polling = scoringPolling
        if (polling != null) {
            if (!polling.due(1)) return
        } else if (scoringCountdown-- > 0) {
            return
        }
        scoringCountdown = LOCAL_SEARCH_OPERATION_BATCH - 1
        if (scoringCancellation()) throw LocalSearchScoringInterrupted
    }

    // Pre-probe value of a functional objective the network does not maintain, valid while the assignment is
    // unchanged since [committedObjectiveRevision]; every candidate of one pick shares it.
    private var committedObjectiveOf: FunctionalObjective? = null
    private var committedObjective: BigInt = BIG_ZERO
    private var committedObjectiveRevision = -1L

    private fun committedValue(objective: FunctionalObjective): BigInt {
        if (committedObjectiveOf !== objective || committedObjectiveRevision != assignmentRevision) {
            committedObjective = objective.evaluateExact(assignment)
            committedObjectiveOf = objective
            committedObjectiveRevision = assignmentRevision
        }
        return committedObjective
    }

    fun linearObjectiveDelta(move: Move, obj: LinearObjective): Double {
        objectiveDeltaSum.reset()
        var realDelta = 0.0
        if (move !is Move.Compound) {
            realDelta = addLinearObjectivePart(move, obj)
            return objectiveDeltaSum.toDouble() + realDelta
        }
        slotSet.clear()
        for (part in move.parts) {
            if (!slotSet.add(state.slotOf(part))) return evaluate(move, obj).objectiveDelta
            realDelta += addLinearObjectivePart(part, obj)
        }
        return objectiveDeltaSum.toDouble() + realDelta
    }

    private fun addLinearObjectivePart(part: Move, obj: LinearObjective): Double {
        when (part) {
            is Move.BoolFlip -> {
                val v = part.varId
                if (v < obj.boolWeightValues.size) {
                    val before = if (assignment.boolValue(v)) 1L else 0L
                    objectiveDeltaSum.addDelta(obj.boolWeightValues[v], before, 1L - before)
                }
            }

            is Move.IntSet -> {
                val v = part.varId
                if (v < obj.intCoefficientValues.size) {
                    objectiveDeltaSum.addDelta(obj.intCoefficientValues[v], assignment.intValue(v), part.newValue)
                }
            }

            is Move.RealSet -> {
                val v = part.varId
                if (v < obj.realCoefficientValues.size) {
                    return obj.realCoefficientValues[v] * (part.newValue - assignment.realValue(v))
                }
            }

            is Move.Compound -> error("nested compound move")
        }
        return 0.0
    }

    private val objectiveDeltaSum = ExactIntegerSum()

    private fun saveProbeCoordinates(move: Move): IntArrayList? {
        slots.clear()
        slotSet.clear()
        fun saveSlot(slot: Int) {
            if (slotSet.add(slot)) slots.add(slot)
        }
        if (move is Move.Compound) {
            for (part in move.parts) saveSlot(state.slotOf(part))
        } else {
            saveSlot(state.slotOf(move))
        }
        val net = state.invariants
        val affected = state.probeDefinitions(move)
        if (net != null && affected != null) {
            for (position in 0 until affected.size) {
                val node = net.node(affected[position])
                saveSlot(if (node.outIsBool) node.out else problem.numBoolVars + node.out)
            }
        }
        if (savedValues.size < slots.size) savedValues = LongArray(slots.size)
        for (i in 0 until slots.size) savedValues[i] = valueAt(slots[i])
        return affected
    }

    private fun valueAt(slot: Int): Long = when {
        slot < problem.numBoolVars -> if (assignment.boolValue(slot)) 1L else 0L
        slot < problem.numBoolVars + problem.numIntVars -> assignment.intValue(slot - problem.numBoolVars)
        else -> assignment.realValue(slot - problem.numBoolVars - problem.numIntVars).toRawBits()
    }

    private fun restoreProbeCoordinates() {
        for (i in slots.size - 1 downTo 0) {
            val slot = slots[i]
            val value = savedValues[i]
            if (valueAt(slot) == value) continue
            state.restoreProbeCoordinate(slot, value)
        }
    }

    @Suppress("LongParameterList")
    fun evaluate(
        move: Move,
        objective: Objective? = null,
        indexed: BooleanObjectiveDelta? = null,
        refineCumulative: Boolean = false,
        weightedResidual: Boolean = false,
        elementEligibility: MoveSink? = null,
        feasibleObjective: Boolean = false,
        capture: (() -> Unit)? = null,
    ): Evaluation {
        check(!active) { "local-search probes cannot be nested" }
        phase = Phase.PREPARING
        try {
            val oldCost = cost
            val oldExactObjective = if (indexed == null) {
                (objective as? FunctionalObjective)?.let(::committedValue)
            } else {
                null
            }
            val oldObjective = if (
                objective != null && objective !is LinearObjective && objective !is FunctionalObjective
            ) {
                objective.evaluate(assignment)
            } else {
                0.0
            }
            val degBefore = if (weights.allocated || move !is Move.Compound) {
                (savedDegrees ?: IntArray(factorDegree.size)).also { savedDegrees = it }
            } else {
                null
            }
            if (refineCumulative) {
                if (cumulativeFractions.size < cumulativeFactors.size) {
                    cumulativeFractions = DoubleArray(cumulativeFactors.size)
                }
                for (i in cumulativeFactors.indices) cumulativeFractions[i] = cumulativeFraction(cumulativeFactors[i])
            }
            val affected = saveProbeCoordinates(move)
            touchedFactors.clear()
            phase = Phase.APPLYING
            try {
                state.applyWithDefinitions(move, affected)
                capture?.invoke()
                phase = Phase.READING
                var breakCount = 0
                var makeCount = 0
                var newlyViolated = 0
                for (i in 0 until touchedFactors.size) {
                    val fid = touchedFactors[i]
                    val degree = factorDegree[fid]
                    if (degree > 0 && !wasViolated[fid]) newlyViolated++
                    if (degBefore != null) {
                        val before = degBefore[fid]
                        if (degree > before) breakCount++
                        if (degree < before) makeCount++
                    }
                }
                val delta = cost - oldCost
                val weighted = if (weights.allocated) weightedDegreeDelta(checkNotNull(degBefore)) else delta.toDouble()
                val objectiveDelta = when {
                    feasibleObjective && cost > 0L -> Double.POSITIVE_INFINITY

                    objective is LinearObjective -> probeLinearObjectiveDelta(objective)

                    indexed != null -> indexed.difference(
                        assignment,
                        slots,
                        savedValues,
                        ::checkObjectiveProbeWork,
                    ).also { state.phaseTimer?.recordObjectiveScoring("indexed") }

                    objective is FunctionalObjective ->
                        (objective.evaluateExact(assignment) - checkNotNull(oldExactObjective)).toDouble().also {
                            state.phaseTimer?.recordObjectiveScoring("generic")
                        }

                    objective == null -> 0.0

                    else -> objective.evaluate(assignment) - oldObjective
                }
                val breaks = if (move is Move.Compound) newlyViolated else breakCount
                val cumulativeDelta = if (refineCumulative) cumulativeFractionDelta(weightedResidual) else 0.0
                val elementRepairs = elementEligibility?.let { elementPreservingRepairs(it) }
                return Evaluation(breaks, makeCount, delta, weighted, objectiveDelta, cumulativeDelta, elementRepairs)
            } finally {
                phase = Phase.RESTORING
                restoreProbeCoordinates()
                // Restoration returns every probed coordinate to its committed value, so the cached value stands.
                if (oldExactObjective != null) committedObjectiveRevision = assignmentRevision
                for (i in 0 until touchedFactors.size) touched[touchedFactors[i]] = false
            }
        } finally {
            phase = Phase.IDLE
        }
    }

    private fun probeLinearObjectiveDelta(objective: LinearObjective): Double {
        objectiveDeltaSum.reset()
        var realDelta = 0.0
        for (i in 0 until slots.size) {
            val slot = slots[i]
            val old = savedValues[i]
            if (slot < problem.numBoolVars) {
                val coefficient = objective.boolWeightValues.getOrElse(slot) { 0L }
                objectiveDeltaSum.addDelta(coefficient, old, valueAt(slot))
            } else if (slot < problem.numBoolVars + problem.numIntVars) {
                val coefficient = objective.intCoefficientValues.getOrElse(slot - problem.numBoolVars) { 0L }
                objectiveDeltaSum.addDelta(coefficient, old, valueAt(slot))
            } else {
                val real = slot - problem.numBoolVars - problem.numIntVars
                val coefficient = objective.realCoefficientValues.getOrElse(real) { 0.0 }
                realDelta += coefficient * (assignment.realValue(real) - Double.fromBits(old))
            }
        }
        return objectiveDeltaSum.toDouble() + realDelta
    }

    private fun weightedDegreeDelta(degBefore: IntArray): Double {
        val w = weights.factorWeights
        var delta = 0.0
        // Keep factor-id order so floating-point accumulation matches a full degree scan.
        touchedFactors.backingData.sort(0, touchedFactors.size)
        for (p in 0 until touchedFactors.size) {
            val i = touchedFactors[p]
            val d = factorDegree[i] - degBefore[i]
            if (d != 0) delta += w[i] * d
        }
        return delta
    }

    data class Evaluation(
        val breakScore: Int,
        val makeScore: Int,
        val netDelta: Long,
        val weightedNetDelta: Double,
        val objectiveDelta: Double,
        val cumulativeDelta: Double,
        val elementRepairs: List<Move>?,
    )

    fun recordDegreeChange(factorId: Int, oldDegree: Int) {
        if (phase != Phase.APPLYING || touched[factorId]) return
        touched[factorId] = true
        wasViolated[factorId] = oldDegree > 0
        savedDegrees?.set(factorId, oldDegree)
        touchedFactors.add(factorId)
    }

    fun cumulativeRepairFraction(): Double {
        if (cost == 0L) return 0.0
        var fraction = 0.0
        for (fid in cumulativeFactors) fraction += cumulativeFraction(fid)
        return fraction
    }

    private fun elementPreservingRepairs(eligibility: MoveSink): List<Move>? {
        val targets = LinkedHashMap<Int, Long>()
        val considered = IntHashSet()
        for (i in 0 until slots.size) {
            val slot = slots[i]
            if (slot < problem.numBoolVars || slot >= problem.numBoolVars + problem.numIntVars) continue
            if (valueAt(slot) == savedValues[i]) continue
            val variable = slot - problem.numBoolVars
            for (fid in projection.intOccurrences[variable]) {
                val element = problem.factors[fid] as? Element ?: continue
                if (!element.arrIsVars || element.idx != variable || !considered.add(fid)) continue
                if (considered.size > MAX_PRESERVED_ELEMENT_SELECTIONS) return null
                val previouslyViolated = if (touched[fid]) wasViolated[fid] else factorDegree[fid] > 0
                if (previouslyViolated) continue
                if (!preserveElementTarget(element, eligibility, targets)) return null
            }
        }
        return targets.filter { (variable, value) -> assignment.intValue(variable) != value }
            .map { (variable, value) -> Move.IntSet(variable, value) }
    }

    private fun preserveElementTarget(
        element: Element,
        eligibility: MoveSink,
        targets: MutableMap<Int, Long>,
    ): Boolean {
        val index = assignment.intValue(element.idx)
        if (index < element.indexOffset || index >= element.indexOffset.toLong() + element.arr.size) return false
        val cell = element.arr[(index - element.indexOffset).toInt()].toInt()
        val value = savedIntValue(element.result)
        val previous = targets[cell]
        if (previous != null && previous != value) return false
        targets[cell] = value
        if (assignment.intValue(cell) == value) return true
        if (value !in state.rootDomains[cell] || !eligibility.allowsInt(cell) ||
            problem.numBoolVars + cell in slotSet
        ) {
            return false
        }
        return true
    }

    private fun savedIntValue(variable: Int): Long {
        val slot = problem.numBoolVars + variable
        for (i in 0 until slots.size) if (slots[i] == slot) return savedValues[i]
        return assignment.intValue(variable)
    }

    private fun cumulativeFraction(factorId: Int): Double {
        val extra = state.intPayload[factorId].toLong() - state.violationSoftCap
        if (extra <= 0L) return 0.0
        val lower = 1L shl (Long.SIZE_BITS - extra.countLeadingZeroBits() - 1)
        return (extra - lower).toDouble() / lower.toDouble()
    }

    private fun cumulativeFractionDelta(weighted: Boolean): Double {
        val w = if (weighted && weights.allocated) weights.factorWeights else null
        var delta = 0.0
        for (i in cumulativeFactors.indices) {
            val fid = cumulativeFactors[i]
            delta += (w?.get(fid) ?: 1.0) * (cumulativeFraction(fid) - cumulativeFractions[i])
        }
        return delta
    }
}
