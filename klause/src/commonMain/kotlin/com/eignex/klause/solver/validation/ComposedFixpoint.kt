package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SelectedProjectionChecks
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.util.Cancellation

/*
 * Pin the whole assignment into a fresh session, under [assumptions], and read the composed verdict off
 * the fixpoint. A wipeout refutes the assignment; a fixpoint cut short by [cancellation] decides nothing.
 */
internal fun <V> composedFixpoint(
    problem: Problem,
    candidate: Candidate<Sample, V>,
    cancellation: Cancellation,
    assumptions: Assumptions = Assumptions.None,
    onWork: (Long) -> Unit = {},
): Verification<Sample, V> = composedFixpoint(problem, candidate, cancellation, assumptions,
    enforcingPeers = emptySet(), onWork = onWork)

internal fun <V> composedFixpoint(
    problem: Problem,
    candidate: Candidate<Sample, V>,
    cancellation: Cancellation,
    assumptions: Assumptions = Assumptions.None,
    enforcingPeers: Set<Int>,
    onWork: (Long) -> Unit = {},
): Verification<Sample, V> {
    val sample = candidate.assignment
    val finite = problem as? BakedProblem
    val point = if (finite != null && problem.numRealVars == 0 && sample.exactInts == null &&
        sample.numBoolVars == problem.numBoolVars && sample.numIntVars == problem.numIntVars
    ) {
        pointPins(sample, cancellation) ?: run {
            onWork(0L)
            return Verification.Indeterminate("point preparation cancelled")
        }
    } else null
    val session = if (point != null) PropagationSession.forPointCheck(checkNotNull(finite), point, cancellation)
    else PropagationSession(problem, cancellation)
    try {
        if (session.isUnsatAtRoot) return Verification.Rejected("root propagation refutes the assignment")
        if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
        if ((!assumptions.isEmpty || !assumptions.deductions.isEmpty) &&
            session.seed(assumptions) is PropagationResult.Unsat
        ) {
            return Verification.Rejected("the assumptions refute the assignment")
        }
        if (point == null) pinPoint(session, sample, cancellation)?.let { return it }
        // A cut fixpoint leaves the state under-propagated, so a surviving point proves nothing.
        if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
        val checks = session.projection.selection?.let { selection ->
            if (enforcingPeers.isEmpty()) selection.originalChecks
            else SelectedProjectionChecks(selection, enforcingPeers)
        }
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
