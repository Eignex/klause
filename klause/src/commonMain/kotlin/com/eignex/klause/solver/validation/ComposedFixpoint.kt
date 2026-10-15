package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
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
    val session = PropagationSession(problem, cancellation)
    try {
        if (session.isUnsatAtRoot) return Verification.Rejected("root propagation refutes the assignment")
        if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
        if ((!assumptions.isEmpty || !assumptions.deductions.isEmpty) && session.seed(assumptions) is PropagationResult.Unsat) {
            return Verification.Rejected("the assumptions refute the assignment")
        }
        for (b in 0 until problem.numBoolVars) {
            if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
            if (session.pinBool(b, sample.boolValue(b)) is PropagationResult.Unsat) {
                return Verification.Rejected("bool $b = ${sample.boolValue(b)} conflicts")
            }
        }
        for (v in 0 until problem.numIntVars) {
            if (session.fixpointCancelled || cancellation()) return Verification.Indeterminate("propagation cancelled")
            if (session.pinInt(v, sample.intValue(v)) is PropagationResult.Unsat) {
                return Verification.Rejected("int $v = ${sample.intValue(v)} conflicts")
            }
        }
        // Sticky across every fixpoint above: a cut one leaves the state under-propagated, so a
        // surviving assignment proves nothing.
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
