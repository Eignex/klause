package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.SourceObjectiveScore
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random

internal enum class ConflictGuidanceStatus {
    Unavailable,
    EmptySupport,
    Supported,
    Incompatible,
    Unsupported,
    Limited,
    Cancelled,
    Closed,
}

internal class ScopedConflictAttribution(
    val bools: IntArray,
    val ints: IntArray,
    val cutoff: Double,
    val localIncumbent: Sample?,
)

// Conflict attributions are ranking hints, including when their premises contain repair-local pins.
internal class ConflictSupportedRemoval(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val callerAssumptions: Assumptions = Assumptions.None,
    private val maxCoordinates: Int = 4096,
    private val maxConflicts: Int = 32,
) : DestroyOperator,
    AutoCloseable {
    private var center: Sample? = null
    private var scores = IntArray(0)
    private var seen = IntArray(0)
    private var observing = false
    private var hasSupport = false
    private var observed = 0
    private var closed = false
    private var cancellation: Cancellation = Cancellation.Never
    private var externalCutoff = Double.POSITIVE_INFINITY
    private var localCutoff = Double.POSITIVE_INFINITY
    private var localIncumbent: Sample? = null
    private val captured = mutableListOf<ScopedConflictAttribution>()

    var status = ConflictGuidanceStatus.Unavailable
        private set
    var originPins: Assumptions? = null
        private set
    var originCutoff: Double? = null
        private set
    var visits = 0L
        private set
    var conflicts = 0
        private set
    var capped = false
        private set

    fun weights(): IntArray {
        visits += scores.size
        return scores.copyOf()
    }

    fun attributions(): List<ScopedConflictAttribution> = captured.toList()

    init {
        require(maxCoordinates in 1..4096 && maxConflicts in 1..32)
    }

    fun observe(params: BacktrackParams): BacktrackParams {
        require(params.assumptions == callerAssumptions)
        val supplier = params.objectiveBoundSupplier
        return params.copy(
            variableSelector = observing(params.variableSelector),
            objectiveBoundSupplier = supplier?.let {
                {
                    val cutoff = it()
                    if (observing) {
                        if (cutoff.isNaN() || cutoff > externalCutoff) {
                            discard(ConflictGuidanceStatus.Incompatible)
                        } else {
                            externalCutoff = cutoff
                        }
                    }
                    cutoff
                }
            },
        )
    }

    fun recording(repair: BacktrackRepair): RepairOperator = RepairOperator { context ->
        val cutoff = if (context.repairSearch == null) objective.evaluate(context.incumbent) else context.bestObjective
        begin(context.incumbent, context.pinAssumptions, cutoff, context.params.cancellation)
        try {
            repair.repair(context)
        } finally {
            end()
        }
    }

    fun end() {
        observing = false
        if (!closed && cancellation()) discard(ConflictGuidanceStatus.Cancelled)
    }

    fun begin(incumbent: Sample, pins: Assumptions, cutoff: Double, token: Cancellation) {
        check(!closed)
        discard(ConflictGuidanceStatus.Unavailable)
        cancellation = token
        val count = model.numBoolVars.toLong() + model.numIntVars
        if (model.numRealVars != 0) {
            status = ConflictGuidanceStatus.Unsupported
            return
        }
        if (count > maxCoordinates) {
            status = ConflictGuidanceStatus.Limited
            return
        }
        if (token()) {
            status = ConflictGuidanceStatus.Cancelled
            return
        }
        require(incumbent.numBoolVars == model.numBoolVars && incumbent.numIntVars == model.numIntVars)
        require(!cutoff.isNaN())
        center = incumbent
        originPins = pins
        originCutoff = cutoff
        externalCutoff = cutoff
        scores = IntArray(count.toInt())
        seen = IntArray(count.toInt())
        visits += 2L * count
        observed = 0
        hasSupport = false
        capped = false
        observing = true
    }

    private fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
        override fun fresh(): VariableSelector = observing(delegate.fresh())

        override fun pick(session: PropagationSession, rng: Random): VarRef? {
            if (session.problem !== model) discard(ConflictGuidanceStatus.Incompatible)
            return delegate.pick(session, rng)
        }

        override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
            capture(unsat.conflictBools, unsat.conflictInts)
            delegate.onConflict(varRef, unsat)
        }

        override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
            capture(conflict.bools, conflict.ints)
            delegate.onConflict(varRef, conflict)
        }

        override fun onSolution(snapshot: Sample) {
            if (observing) {
                val value = SourceObjectiveScore(objective.evaluate(snapshot), objective.evaluateExact(snapshot))
                    .pruningBound
                if (value <= localCutoff) {
                    localCutoff = value
                    localIncumbent = snapshot
                }
            }
            delegate.onSolution(snapshot)
        }
    }

    private fun capture(bools: IntArray, ints: IntArray) {
        if (!observing || closed) return
        if (observed >= maxConflicts) {
            capped = true
            return
        }
        if (cancellation()) {
            discard(ConflictGuidanceStatus.Cancelled)
            return
        }
        if (bools.size.toLong() + ints.size > maxCoordinates) {
            discard(ConflictGuidanceStatus.Limited)
            return
        }
        observed++
        conflicts++
        for (b in bools) {
            visits++
            if (b !in 0 until model.numBoolVars) {
                discard(ConflictGuidanceStatus.Incompatible)
                return
            }
            if (seen[b] != observed && !callerAssumptions.isFrozenBool(b)) {
                seen[b] = observed
                scores[b]++
                hasSupport = true
            }
        }
        for (i in ints) {
            visits++
            if (i !in 0 until model.numIntVars) {
                discard(ConflictGuidanceStatus.Incompatible)
                return
            }
            val coordinate = model.numBoolVars + i
            if (seen[coordinate] != observed && !callerAssumptions.isFrozenInt(i)) {
                seen[coordinate] = observed
                scores[coordinate]++
                hasSupport = true
            }
        }
        captured.add(
            ScopedConflictAttribution(
                bools.copyOf(),
                ints.copyOf(),
                minOf(externalCutoff, localCutoff),
                localIncumbent,
            ),
        )
        visits += bools.size + ints.size
        status = if (hasSupport) ConflictGuidanceStatus.Supported else ConflictGuidanceStatus.EmptySupport
    }

    override fun destroy(
        rng: Random,
        problem: BakedProblem,
        incumbent: Sample,
        objective: LinearObjective,
        fraction: Double,
    ): FreedVars {
        if (closed) status = ConflictGuidanceStatus.Closed
        if (!closed && (
                problem !== model || objective !== this.objective ||
                    (center != null && incumbent !== center)
                )
        ) {
            discard(ConflictGuidanceStatus.Incompatible)
        }
        if (status != ConflictGuidanceStatus.Supported) {
            return DestroyOperator.Random.destroy(rng, problem, incumbent, objective, fraction)
        }
        val count = scores.size
        if (count == 0) return FreedVars(IntArray(0), IntArray(0))
        val k = (fraction * count).toInt().coerceIn(1, count)
        val order = (0 until count).shuffled(rng).sortedWith { left, right ->
            visits++
            scores[right].compareTo(scores[left])
        }.take(k)
        visits += count
        return FreedVars(
            order.filter { it < model.numBoolVars }.toIntArray(),
            order.filter { it >= model.numBoolVars }.map { it - model.numBoolVars }.toIntArray(),
        )
    }

    private fun discard(reason: ConflictGuidanceStatus) {
        observing = false
        observed = 0
        capped = false
        hasSupport = false
        center = null
        localIncumbent = null
        externalCutoff = Double.POSITIVE_INFINITY
        localCutoff = Double.POSITIVE_INFINITY
        captured.clear()
        scores = IntArray(0)
        seen = IntArray(0)
        originPins = null
        originCutoff = null
        status = reason
    }

    override fun close() {
        closed = true
        discard(ConflictGuidanceStatus.Closed)
    }
}
