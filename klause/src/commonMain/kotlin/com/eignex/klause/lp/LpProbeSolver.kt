package com.eignex.klause.lp

import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.RetainedLpSolver
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.newRetainedLpSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException

internal class LpProbeSolver(
    model: LpModel,
    private val cancellation: Cancellation,
    private val observer: LpCertificationObserver?,
    private val context: LpSolveContext,
) : AutoCloseable {
    private val trail = model.authoritativeModel()?.let(::LpBoundTrail)
    private var owner: RetainedLpSolver? = null
    private var closed = false

    fun probe(column: Int, maximize: Boolean, negativeColumn: Int = -1): Pair<LpModel, FloatLpResult>? {
        check(!closed)
        val trail = trail ?: return null
        val source = trail.state.model
        val sign = if (maximize) -1L else 1L
        val zero = ExactLpNumber.of(0L)
        val costs = MutableList(source.numVars) { zero }
        costs[column] = ExactLpNumber.of(sign)
        if (negativeColumn >= 0) costs[negativeColumn] = ExactLpNumber.of(-sign)
        var constant = source.column(column).origin.value * BigFraction.ofLong(sign)
        if (negativeColumn >= 0) constant -= source.column(negativeColumn).origin.value * BigFraction.ofLong(sign)
        if (!trail.replaceObjective(ExactLpObjective(costs, ExactLpNumber.of(constant)), cancellation)) return null
        val state = trail.state
        val model = state.ownerWorkingModel() ?: return null
        val retained = owner
        val reused = retained?.adopt(state, cancellation) == true
        val solver = if (reused) {
            checkNotNull(retained)
        } else {
            owner = null
            retained?.close()
            if (cancellation()) return null
            newRetainedLpSolver(model, cancellation, factory = context.engineFactory).also { owner = it }
        }
        val result = try {
            if (reused) solver.resolveBounds() else solver.solvePrimal()
        } catch (_: CheckedLongOverflowException) {
            null
        } finally {
            observer?.observeSolve(solver.lastMetrics, solver is ComponentLpSolverCapability)
        } ?: return null
        return model to result
    }

    override fun close() {
        if (closed) return
        closed = true
        val previous = owner
        owner = null
        previous?.close()
    }
}
