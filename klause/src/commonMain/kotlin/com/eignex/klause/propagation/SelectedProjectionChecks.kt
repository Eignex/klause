package com.eignex.klause.propagation

import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.defaultExecutionCapabilities
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.util.Cancellation

internal class SelectedProjectionChecks(
    selection: PreparedFactorProjections,
    enforcingPeers: Set<Int> = emptySet(),
) {
    private val source = selection.problem
    private val factors = source.factors.indices.filter { id ->
        id !in enforcingPeers && selection.requiresOriginalCheck(id) && source.factors[id].variables.reals.isEmpty() &&
            source.factors[id] !is ObjectiveBoundFactor
    }
    private val available = factors.all {
        source.factors[it].defaultExecutionCapabilities().propagationCheck == AssignmentCheckCapability.EXACT
    }
    private val original = if (factors.isEmpty() || !available) null else
        source.withFactors(Array(factors.size) { source.factors[factors[it]] })

    fun check(session: PropagationSession, cancellation: Cancellation): ComponentCheck {
        if (!available) return ComponentCheck.Indeterminate
        val model = original ?: return ComponentCheck.Feasible
        if (cancellation()) return ComponentCheck.Indeterminate
        val point = model.withIntDomains(Array(model.numIntVars) {
            val value = session.intDomain(it).min
            IntDomain(value, value)
        })
        val checker = PropagationSession(point, cancellation)
        val result = try {
            checkPoint(model.numBoolVars, session, checker)
        } finally {
            session.recordProjectionCheck(checker)
        }
        return if (result == ComponentCheck.Feasible && (checker.fixpointCancelled || cancellation())) {
            ComponentCheck.Indeterminate
        } else result
    }

    private fun checkPoint(
        booleans: Int,
        session: PropagationSession,
        checker: PropagationSession,
    ): ComponentCheck {
        if (checker.isUnsatAtRoot) return ComponentCheck.Infeasible()
        for (id in 0 until booleans) {
            if (checker.pinBool(id, session.boolValue(id) ?: false) is PropagationResult.Unsat) {
                return ComponentCheck.Infeasible()
            }
        }
        return ComponentCheck.Feasible
    }
}
