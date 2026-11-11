package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.math.abs

internal class LpNumericalResult(
    val bound: LpNumericalBound?,
    val conflict: LpNumericalConflict?,
    val point: FloatLpResult?,
    val optimumWithinTolerance: Boolean,
    val work: Long,
    val stop: LpFloatingBoundStop?,
)

@Suppress("ReturnCount")
internal fun numericalLpResult(
    model: LpModel,
    solver: LpSolver,
    result: FloatLpResult?,
    workLimit: Long,
    cancellation: Cancellation,
    observer: LpCertificationObserver?,
    floatAccept: ((FloatLpResult) -> Boolean)? = null,
    floatOffset: Double = 0.0,
): CertifiedLpResult {
    require(workLimit >= 0L)
    if (cancellation()) return numericalResult(result, stop = LpFloatingBoundStop.CANCELLED)
    if (result == null) {
        return numericalConflictResult(model, solver, workLimit, cancellation, observer)
    }
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_BOUND)
    val checked = floatingObjectiveLowerBound(model, result.duals, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: if (checked.bound != null) "ACCEPTED" else "REFUSED", checked.work)
    if (cancellation()) return numericalResult(result, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val point = result.acceptedNumericalPoint(model, checked.stop, floatAccept)
    if (cancellation()) return numericalResult(result, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val bound = checked.bound?.let { LpNumericalBound(model, it) }
    val sourceObjective = point?.objective?.plus(floatOffset)
    val optimum = point != null && bound != null && sourceObjective?.isFinite() == true &&
        abs(point.objective - bound.value) <=
        FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(point.objective + floatOffset))
    return numericalResult(result, bound, point = point, optimum = optimum, work = checked.work, stop = checked.stop)
}

private fun numericalConflictResult(
    model: LpModel,
    solver: LpSolver,
    workLimit: Long,
    cancellation: Cancellation,
    observer: LpCertificationObserver?,
): CertifiedLpResult {
    val ray = solver.infeasibleRay ?: return numericalResult(null)
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_CONFLICT)
    val checked = floatingInfeasibilityMargin(model, ray, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: if (checked.infeasible) "ACCEPTED" else "REFUSED", checked.work)
    val cancelled = cancellation()
    val conflict = checked.margin?.takeIf { checked.infeasible && !cancelled }
        ?.let { LpNumericalConflict(model, it) }
    return numericalResult(null, conflict = conflict, work = checked.work,
        stop = if (cancelled) LpFloatingBoundStop.CANCELLED else checked.stop)
}

private fun FloatLpResult.acceptedNumericalPoint(
    model: LpModel,
    stop: LpFloatingBoundStop?,
    accept: ((FloatLpResult) -> Boolean)?,
): FloatLpResult? = takeIf {
    stop != LpFloatingBoundStop.WORK && optimal && objective.isFinite() &&
        primal.size == model.n && primal.all(Double::isFinite) && accept?.invoke(it) == true
}

private fun numericalResult(
    result: FloatLpResult?,
    bound: LpNumericalBound? = null,
    conflict: LpNumericalConflict? = null,
    point: FloatLpResult? = null,
    optimum: Boolean = false,
    work: Long = 0L,
    stop: LpFloatingBoundStop? = null,
): CertifiedLpResult = CertifiedLpResult(
    result, null, null, null, null, false, { null },
    numerical = LpNumericalResult(bound, conflict, point, optimum, work, stop),
)
