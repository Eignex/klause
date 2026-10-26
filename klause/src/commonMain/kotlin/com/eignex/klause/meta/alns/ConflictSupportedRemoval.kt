package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
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
    Unavailable, EmptySupport, Supported, Incompatible, Limited, Cancelled, Closed,
}

// Conflict attributions are ranking hints, including when their premises contain repair-local pins.
internal class ConflictSupportedRemoval(
    private val model: BakedProblem,
    private val objective: LinearObjective,
    private val callerAssumptions: Assumptions = Assumptions.None,
    private val maxCoordinates: Int = 4096,
    private val maxConflicts: Int = 32,
) : DestroyOperator, AutoCloseable {
    private var center: Sample? = null
    private var scores = IntArray(0)
    private var observing = false
    private var observed = 0
    private var closed = false
    private var cancellation: Cancellation = Cancellation.Never

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

    init {
        require(maxCoordinates > 0 && maxConflicts > 0)
    }

    fun observe(params: BacktrackParams): BacktrackParams {
        require(params.assumptions == callerAssumptions)
        return params.copy(variableSelector = observing(params.variableSelector))
    }

    fun recording(repair: RepairOperator): RepairOperator = RepairOperator { context ->
        begin(context.incumbent, context.pinAssumptions, context.bestObjective, context.params.cancellation)
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
        if (model.numRealVars != 0 || count > maxCoordinates) {
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
        scores = IntArray(count.toInt())
        observed = 0
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
            if (!callerAssumptions.isFrozenBool(b)) scores[b]++
        }
        for (i in ints) {
            visits++
            if (i !in 0 until model.numIntVars) {
                discard(ConflictGuidanceStatus.Incompatible)
                return
            }
            if (!callerAssumptions.isFrozenInt(i)) scores[model.numBoolVars + i]++
        }
        visits += scores.size
        status = if (scores.any { it > 0 }) ConflictGuidanceStatus.Supported else ConflictGuidanceStatus.EmptySupport
    }

    override fun destroy(
        rng: Random,
        problem: BakedProblem,
        incumbent: Sample,
        objective: LinearObjective,
        fraction: Double,
    ): FreedVars {
        if (closed) status = ConflictGuidanceStatus.Closed
        if (!closed && (problem !== model || objective !== this.objective ||
                (center != null && incumbent !== center))
        ) discard(ConflictGuidanceStatus.Incompatible)
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
        center = null
        scores = IntArray(0)
        originPins = null
        originCutoff = null
        status = reason
    }

    override fun close() {
        closed = true
        discard(ConflictGuidanceStatus.Closed)
    }
}
