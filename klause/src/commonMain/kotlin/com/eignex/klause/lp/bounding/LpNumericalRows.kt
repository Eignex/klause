package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.engine.FLOAT_EVIDENCE_TOLERANCE
import com.eignex.klause.lp.engine.LpNumericalRowBound
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.floatingRowBounds
import com.eignex.klause.lp.engine.floorLong
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.math.nextDown
import kotlin.math.nextUp

internal fun LpEngine.applyNumericalRows(
    relaxer: CpToLpRelaxation,
    relaxation: LpRelaxation,
    session: PropagationSession,
    learn: Boolean,
    cancellation: Cancellation,
    onChange: () -> Unit,
): Boolean {
    if (problem.numRealVars == 0 || (problem.numIntVars == 0 && problem.numBoolVars == 0)) return false
    var current = relaxation
    repeat(2) { pass ->
        if (cancellation()) return false
        val remaining = numericalNodeWorkRemaining()
        if (remaining < 128L) return false
        val timer = LpPhaseTimer(sink.lp.certificationObserver(LpRoute.NODE), LpSolvePhase.NUMERICAL_ROWS)
        val projected = floatingRowBounds(current.model, remaining / 2L, numericalCheckingAllocation(), cancellation)
        noteNumericalWork(projected.work)
        timer.finish(projected.stop?.name ?: "COMPLETE", projected.work)
        var changed = false
        for (bound in projected.bounds) {
            if (cancellation()) return false
            if (applyNumericalRow(bound, current, session, learn, cancellation) { changed = true; onChange() }) {
                return true
            }
        }
        if (!changed || pass == 1 || cancellation()) return false
        val refreshWork = 16L * current.model.numVars + 128L
        if (refreshWork > numericalNodeWorkRemaining()) return false
        noteNumericalWork(refreshWork)
        current = nodeRelaxation(relaxer, session) ?: return false
    }
    return false
}

@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList")
private fun LpEngine.applyNumericalRow(
    bound: LpNumericalRowBound,
    relaxation: LpRelaxation,
    session: PropagationSession,
    learn: Boolean,
    cancellation: Cancellation,
    onChange: () -> Unit,
): Boolean {
    if (!bound.belongsTo(relaxation.model) || !bound.shiftedValue.isFinite() || cancellation()) return false
    val column = bound.column
    val variable = relaxation.colVarId.getOrNull(column) ?: return false
    if (variable < 0 || relaxation.model.colContinuous[column]) return false
    if (numericalNodeWorkRemaining() < 64L) return false
    noteNumericalWork(64L)
    val tolerance = FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(bound.shiftedValue))
    val shifted = if (bound.upper) (bound.shiftedValue + tolerance).nextUp()
        else (bound.shiftedValue - tolerance).nextDown()
    val source = BigFraction.ofDouble(shifted)?.plus(relaxation.model.exactShift(column)) ?: return false
    val threshold = (if (bound.upper) source.floorLong() else source.ceilLong()) ?: return false
    val bool = relaxation.colIsBool[column]
    val pinned = if (bool) session.boolValue(variable) else null
    val minimum = if (bool) { if (pinned == true) 1L else 0L } else session.intDomain(variable).min
    val maximum = if (bool) { if (pinned == false) 0L else 1L } else session.intDomain(variable).max
    if ((bound.upper && threshold >= maximum) || (!bound.upper && threshold <= minimum)) return false
    val reason = if (learn) numericalPremiseClause(relaxation, session, cancellation) else null
    if (cancellation()) return false
    if ((bound.upper && threshold < minimum) || (!bound.upper && threshold > maximum)) {
        sink.lp.observePrune()
        observeNumericalConsumption("ROW_CONFLICT")
        return true
    }
    val result = when {
        bool -> session.implyBool(variable, !bound.upper)
        bound.upper && reason != null -> session.implyIntAtMostWithReason(variable, threshold, reason)
        bound.upper -> session.implyIntAtMost(variable, threshold)
        reason != null -> session.implyIntAtLeastWithReason(variable, threshold, reason)
        else -> session.implyIntAtLeast(variable, threshold)
    }
    if (cancellation()) return false
    observeNumericalConsumption("ROW_FIXING")
    if (result is PropagationResult.Unsat) {
        sink.lp.observePrune()
        return true
    }
    if (learn && reason != null) lpNogoods?.let { pool ->
        val units = reason.size.toLong() + 1L
        if (units <= numericalNodeWorkRemaining()) {
            noteNumericalWork(units)
            val conclusion = when {
                bool -> Lit.make(variable, !bound.upper)
                bound.upper -> session.boundLeLit(variable, threshold, positive = true)
                else -> session.boundGeLit(variable, threshold, positive = true)
            }
            val clause = intArrayOf(conclusion) + reason
            if (pool.add(clause, registered = true) &&
                session.addLearnedClause(Clause(clause), lbd = clause.size, permanent = true) is PropagationResult.Unsat
            ) {
                sink.lp.observePrune()
                return true
            }
        }
    }
    sink.lp.observeFix()
    onChange()
    return false
}
