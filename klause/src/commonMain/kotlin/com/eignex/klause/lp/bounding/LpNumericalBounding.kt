package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.FLOAT_EVIDENCE_TOLERANCE
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpNumericalBound
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.floatingInfeasibilityMargin
import com.eignex.klause.lp.engine.floatingObjectiveLowerBound
import com.eignex.klause.lp.engine.hasIntegralObjectiveDefinition
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.util.Cancellation
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import kotlin.math.abs
import kotlin.math.nextDown
import kotlin.math.nextUp

internal fun LpEngine.numericalObjectiveBound(
    model: LpModel,
    duals: DoubleArray,
    cancellation: Cancellation,
    root: Boolean = false,
): LpNumericalBound? {
    val observer = sink.lp.certificationObserver(if (root) LpRoute.ROOT else LpRoute.NODE)
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_BOUND)
    val remaining = if (root) retainedRootRemainingWork() else numericalNodeWorkRemaining()
    val checked = floatingObjectiveLowerBound(model, duals, remaining, cancellation)
    noteNumericalWork(checked.work, root)
    timer.finish(checked.stop?.name ?: if (checked.bound != null) "ACCEPTED" else "REFUSED", checked.work)
    return checked.bound?.takeUnless { cancellation() }?.let { LpNumericalBound(model, it, duals) }
}

internal fun LpEngine.numericalConflict(
    model: LpModel,
    ray: DoubleArray,
    cancellation: Cancellation,
    root: Boolean = false,
): Boolean {
    val timer = LpPhaseTimer(sink.lp.certificationObserver(if (root) LpRoute.ROOT else LpRoute.NODE),
        LpSolvePhase.NUMERICAL_CONFLICT)
    val remaining = if (root) retainedRootRemainingWork() else numericalNodeWorkRemaining()
    val checked = floatingInfeasibilityMargin(model, ray, remaining, cancellation)
    noteNumericalWork(checked.work, root)
    timer.finish(checked.stop?.name ?: if (checked.infeasible) "ACCEPTED" else "REFUSED", checked.work)
    return checked.infeasible && !cancellation()
}

@Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
internal fun LpEngine.applyNumericalBound(
    relaxation: LpRelaxation,
    lower: LpNumericalBound,
    bound: Double,
    session: PropagationSession,
    objectiveVar: Int,
    objectiveAscending: Boolean,
    cancellation: Cancellation,
    learn: Boolean,
): Boolean {
    if (!lower.belongsTo(relaxation.model) || !lower.value.isFinite() || cancellation()) return false
    val mappingWork = 8L * (relaxation.model.numVars.toLong() + relaxation.model.n) + 16L
    if (mappingWork > numericalNodeWorkRemaining()) return false
    noteNumericalWork(mappingWork)
    if (cancellation()) return false
    val magnitude = maxOf(1.0, abs(lower.value))
    val adjusted = (lower.value - FLOAT_EVIDENCE_TOLERANCE * magnitude).nextDown()
    val full = BigFraction.ofDouble(adjusted)?.plus(BigFraction.ofLong(relaxation.objectiveConstant)) ?: return false
    val range = sourceObjectiveRange(relaxation)
    if (range == null && relaxation.model.hasIntegralObjectiveDefinition()) return false
    val integral = if (range != null) full.ceilLong()?.let(BigFraction::ofLong) ?: full else full
    val cutoff = when {
        !bound.isFinite() -> null
        range != null -> sourceObjectiveCutoff(relaxation, bound, range)
        else -> BigFraction.ofDouble(bound.nextUp())
    }
    if (cancellation()) return false
    if (cutoff != null && integral >= cutoff) {
        sink.lp.observePrune()
        observeNumericalConsumption("BOUND_PRUNE")
        return true
    }
    if (objectiveVar < 0 || !objectiveAscending || range == null) return false
    val floor = relaxation.objectiveVariableLowerBound(objectiveVar, integral) ?: return false
    if (floor <= session.intDomain(objectiveVar).min) return false
    val reason = if (learn) numericalPremiseClause(relaxation, session, cancellation) else null
    if (cancellation()) return false
    val result = if (reason == null) session.implyIntAtLeast(objectiveVar, floor)
        else session.implyIntAtLeastWithReason(objectiveVar, floor, reason)
    observeNumericalConsumption("OBJECTIVE_PROPAGATION")
    if (result !is PropagationResult.Unsat) return false
    sink.lp.observePrune()
    return true
}

internal fun LpEngine.observeNumericalConsumption(outcome: String, root: Boolean = false) {
    sink.lp.certificationObserver(if (root) LpRoute.ROOT else LpRoute.NODE)
        .observePhase(LpPhaseMetrics(LpSolvePhase.NUMERICAL_CONSUMPTION, outcome, 0L))
}

@Suppress("ReturnCount")
internal fun LpEngine.numericalPremiseClause(
    relaxation: LpRelaxation,
    session: PropagationSession,
    cancellation: Cancellation,
): IntArray? {
    val model = relaxation.model
    var units = model.m.toLong() + 2L * model.n
    val maxUnits = numericalCheckingAllocation() / 256L
    if (units > maxUnits) return null
    for (row in 0 until model.m) {
        if (numericalNodeWorkRemaining() == 0L || cancellation()) return null
        noteNumericalWork(1L)
        units += relaxation.sourceMap?.parent(row)?.storageUnits ?: model.exactState?.model?.row(row)?.premises?.size
            ?: model.rowPremises[row]?.let { it.vars.size.toLong() * 3L + it.boolLits.size } ?: 0L
        if (units > maxUnits) return null
    }
    val work = (model.extent() + units) * LpNodeOverhead.FIXING
    if (work > numericalNodeWorkRemaining() || cancellation()) return null
    noteNumericalWork(work)
    val literals = IntArrayList()
    val seen = IntHashSet()
    val selectedRow = IntArray(1)
    for (row in 0 until model.m) {
        if (cancellation()) return null
        selectedRow[0] = row
        if (!LpExplanation.addRowPremiseLits(literals, seen, relaxation, selectedRow, session)) return null
    }
    for (column in relaxation.colVarId.indices) {
        if (cancellation()) return null
        for (lower in listOf(true, false)) {
            val premises = LpExplanation.boundPremiseLits(relaxation, session, column, lower) ?: return null
            for (literal in premises) if (seen.add(literal)) literals.add(literal)
        }
    }
    return literals.toIntArray().takeUnless { cancellation() }
}
