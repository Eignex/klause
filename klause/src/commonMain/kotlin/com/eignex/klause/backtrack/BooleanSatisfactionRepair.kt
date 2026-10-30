package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ConditionedClauseExchange
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.RepairSearchStep
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

@Suppress("TooGenericExceptionCaught") // preserve engine failures while releasing retained query state
internal class BooleanSatisfactionRepair(
    solver: BacktrackSolver,
    private val params: BacktrackParams,
) : RepairSearch {
    init {
        require(solver.problem.numIntVars == 0 && solver.problem.numRealVars == 0 &&
            solver.problem.factors.all { it is Clause } && !solver.problem.hasSymmetryBreaking)
        require(params.componentFactory == null && params.lpConfig == null && params.lpPlan == LpPlan() &&
            !params.integerRowLearning)
        require(params.objectiveBoundSupplier == null && params.objectiveLowerBoundSupplier == null &&
            params.globalVarLowerSupplier == null && params.globalVarUpperSupplier == null &&
            params.toleranceCheck == null && params.cutExchange == null &&
            (params.clauseExchange == null || params.clauseExchange is ConditionedClauseExchange) &&
            params.improvedSolutionSink == null && params.pooledIncumbents == null)
    }

    private val handle = ResumableSatisfaction(solver, params.copy(assumptions = Assumptions.None), rebindable = true)
    private var started = false
    private var closed = false
    override val stats: SolveStats get() = handle.stats
    override val work: Long get() = handle.work

    override fun begin(
        assumptions: Assumptions,
        decisionBudget: Long,
        cutoff: Double,
        cancellation: Cancellation,
    ) {
        check(!closed)
        try {
            require(cutoff == Double.POSITIVE_INFINITY) { "Boolean satisfaction has no objective cutoff" }
            require(decisionBudget == params.maxDecisions) { "Boolean satisfaction retains its decision policy" }
            handle.rebind(assumptions, cancellation)
            started = true
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    override fun runSlice(cancellation: Cancellation, millis: Long, work: Long): RepairSearchStep {
        check(!closed && started)
        try {
            return when (val result = handle.runSlice(cancellation, millis, work)) {
                is SolveResult.Sat -> RepairSearchStep(result.assignment, TerminationReason.SearchExhausted)
                is SolveResult.Unsat -> RepairSearchStep(null, TerminationReason.SearchExhausted)
                is SolveResult.Unknown -> RepairSearchStep(null, result.reason)
                null -> RepairSearchStep(null, null)
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }

    private fun closeAfter(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }
}
