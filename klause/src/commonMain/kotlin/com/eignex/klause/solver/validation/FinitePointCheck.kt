@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SelectedProjectionChecks
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.fitsLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/*
 * Checks points against one immutable [problem]: each point becomes the private root of a disposable propagation
 * state, so incompatible caller pins still refute it, and the selected original-source relations are checked on
 * the composed fixpoint. A wipeout refutes the point; a fixpoint cut short by cancellation decides nothing.
 *
 * Propagators and occurrence indexes are built once per checker when every selected route is built in, since
 * built-in projections keep their mutable work in the state. A custom factory may open a private mechanism for
 * one live state, so those problems project afresh for each point. The last decided point is retained: the
 * problem is immutable and restrictions are checked on the point itself, so the same discrete coordinates get the
 * same propagated verdict under any restrictions. An interrupted check is never retained.
 */
internal class FinitePointCheck(private val problem: Problem, private val enforcingPeers: Set<Int> = emptySet()) {
    private val finite = problem as? BakedProblem
    private val shared: PropagationProblem? by lazy {
        // Without a prepared selection the projection uses built-in propagators only.
        if (finite?.factorProjections?.nativePropagation != false) PropagationProblem(problem) else null
    }
    private val checks: SelectedProjectionChecks? by lazy {
        finite?.factorProjections?.let {
            if (enforcingPeers.isEmpty()) it.originalChecks else SelectedProjectionChecks(it, enforcingPeers)
        }
    }

    private val decided = AtomicReference<DecidedPoint?>(null)

    // Whether a verdict covers every selected relation of [model], so a caller may key its result on [model].
    fun checksAllOf(model: Problem): Boolean = model === problem && enforcingPeers.isEmpty()

    private fun projection(): PropagationProblem = shared ?: PropagationProblem(problem)

    fun <V> check(
        candidate: Candidate<Sample, V>,
        cancellation: Cancellation,
        assumptions: Assumptions = Assumptions.None,
        onWork: (Long) -> Unit = {},
        liveWork: ((() -> Long) -> Unit)? = null,
    ): Verification<Sample, V> {
        val sample = candidate.assignment
        val unchecked = when {
            sample.numBoolVars < problem.numBoolVars || sample.numIntVars < problem.numIntVars ->
                Verification.Rejected("assignment does not cover the model's discrete variables")

            sample.exactInts?.any { !it.fitsLong() } == true ->
                Verification.Indeterminate("a finite checker cannot verify wide integer coordinates")

            // Unary pins and restrictions add nothing a point covering every discrete coordinate does not
            // already fix, so they are checked on the point and the propagated verdict holds under any of them.
            else -> checkPointRestrictions(sample, assumptions, cancellation)
        }
        if (unchecked != null) {
            onWork(0L)
            return unchecked
        }
        decided.load()?.takeIf { it.sample.sameDiscreteCoordinates(sample) }?.let { previous ->
            onWork(0L)
            return previous.reason?.let { Verification.Rejected(it) } ?: Verification.Accepted(candidate)
        }
        return propagate(candidate, cancellation, onWork, liveWork).also { verdict ->
            when (verdict) {
                is Verification.Accepted -> decided.store(DecidedPoint(sample, null))
                is Verification.Rejected -> decided.store(DecidedPoint(sample, verdict.reason))
                is Verification.Indeterminate -> Unit
            }
        }
    }

    private class DecidedPoint(val sample: Sample, val reason: String?)

    private fun <V> propagate(
        candidate: Candidate<Sample, V>,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
        liveWork: ((() -> Long) -> Unit)?,
    ): Verification<Sample, V> {
        val sample = candidate.assignment
        val root = finite != null && problem.numRealVars == 0 &&
            sample.numBoolVars == problem.numBoolVars && sample.numIntVars == problem.numIntVars
        val point = if (root) {
            pointPins(sample, cancellation) ?: run {
                onWork(0L)
                return Verification.Indeterminate("point preparation cancelled")
            }
        } else {
            null
        }
        val session = if (point != null) {
            PropagationSession.forPointCheck(projection(), point, cancellation, liveWork)
        } else {
            PropagationSession(projection(), cancellation, onPropagationWork = liveWork)
        }
        try {
            if (session.isUnsatAtRoot) return Verification.Rejected("root propagation refutes the assignment")
            if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
            if (point == null) pinPoint(session, sample, cancellation)?.let { return it }
            // A cut fixpoint leaves the state under-propagated, so a surviving point proves nothing.
            if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
            return when (session.checkSelectedProjection(checks = checks)) {
                ComponentCheck.Feasible -> Verification.Accepted(candidate)

                is ComponentCheck.Infeasible -> Verification.Rejected("original source check refutes the assignment")

                ComponentCheck.Indeterminate ->
                    Verification.Indeterminate("original source check is unavailable or cancelled")
            }
        } finally {
            onWork(session.work)
        }
    }
}

internal fun checkPointRestrictions(
    sample: Sample,
    assumptions: Assumptions,
    cancellation: Cancellation,
): Verification<Nothing, Nothing>? {
    val refuted = Verification.Rejected("the assumptions refute the assignment")
    val cancelled = Verification.Indeterminate("restriction check cancelled")
    fun int(id: Int) = id in 0 until sample.numIntVars
    for (i in assumptions.boolKeys.indices) {
        if (cancellation()) return cancelled
        val id = assumptions.boolKeys[i]
        if (id !in 0 until sample.numBoolVars || sample.boolValue(id) != assumptions.boolValues[i]) return refuted
    }
    for (i in assumptions.intKeys.indices) {
        if (cancellation()) return cancelled
        val id = assumptions.intKeys[i]
        if (!int(id) || sample.intValue(id) != assumptions.intValues[i]) return refuted
    }
    val restrictions = assumptions.deductions
    restrictions.forEachIntMin { id, lower ->
        if (cancellation()) return cancelled
        if (!int(id) || sample.intValue(id) < lower) return refuted
    }
    restrictions.forEachIntMax { id, upper ->
        if (cancellation()) return cancelled
        if (!int(id) || sample.intValue(id) > upper) return refuted
    }
    restrictions.forEachIntHole { id, excluded ->
        if (cancellation()) return cancelled
        if (!int(id) || sample.intValue(id) == excluded) return refuted
    }
    restrictions.forEachIntSet { id, survivors ->
        if (cancellation()) return cancelled
        if (!int(id) || sample.intValue(id) !in survivors) return refuted
    }
    return null
}

private fun pointPins(sample: Sample, cancellation: Cancellation): Assumptions? {
    val boolKeys = IntArray(sample.numBoolVars)
    val boolValues = BooleanArray(sample.numBoolVars)
    for (id in boolKeys.indices) {
        if (cancellation()) return null
        boolKeys[id] = id
        boolValues[id] = sample.boolValue(id)
    }
    val intKeys = IntArray(sample.numIntVars)
    val intValues = LongArray(sample.numIntVars)
    for (id in intKeys.indices) {
        if (cancellation()) return null
        intKeys[id] = id
        intValues[id] = sample.intValue(id)
    }
    return Assumptions(boolKeys, boolValues, intKeys, intValues)
}

private fun pinPoint(
    session: PropagationSession,
    sample: Sample,
    cancellation: Cancellation,
): Verification<Nothing, Nothing>? {
    for (id in 0 until session.problem.numBoolVars) {
        if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
        if (session.pinBool(id, sample.boolValue(id)) is PropagationResult.Unsat) {
            return Verification.Rejected("bool $id = ${sample.boolValue(id)} conflicts")
        }
    }
    for (id in 0 until session.problem.numIntVars) {
        if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
        if (session.pinInt(id, sample.intValue(id)) is PropagationResult.Unsat) {
            return Verification.Rejected("int $id = ${sample.intValue(id)} conflicts")
        }
    }
    return null
}
