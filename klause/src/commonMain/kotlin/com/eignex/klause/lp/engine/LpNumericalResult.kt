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
    val recession: LpNumericalRecession? = null,
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
        if (solver.lastTermination == LpFloatTermination.UNBOUNDED_CANDIDATE) {
            solver.recessionCandidate?.let {
                return numericalRecessionResult(model, it, workLimit, cancellation, observer, floatAccept)
            }
        }
        return numericalConflictResult(model, solver, workLimit, cancellation, observer)
    }
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_BOUND)
    val checked = floatingObjectiveLowerBound(model, result.duals, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: if (checked.bound != null) "ACCEPTED" else "REFUSED", checked.work)
    if (cancellation()) return numericalResult(result, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val acceptance = LpPhaseTimer(observer, LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE)
    val point = result.acceptedNumericalPoint(model, checked.stop, floatAccept)
    acceptance.finish(if (point == null) "REFUSED" else "ACCEPTED")
    if (cancellation()) return numericalResult(result, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val bound = checked.bound?.let { LpNumericalBound(model, it) }
    val sourceObjective = point?.objective?.plus(floatOffset)
    val optimum = point != null && bound != null && sourceObjective?.isFinite() == true &&
        abs(point.objective - bound.value) <=
        FLOAT_EVIDENCE_TOLERANCE * maxOf(1.0, abs(point.objective + floatOffset))
    return numericalResult(result, bound, point = point, optimum = optimum, work = checked.work, stop = checked.stop)
}

private fun numericalRecessionResult(
    model: LpModel,
    candidate: LpRecessionCandidate,
    workLimit: Long,
    cancellation: Cancellation,
    observer: LpCertificationObserver?,
    floatAccept: ((FloatLpResult) -> Boolean)?,
): CertifiedLpResult {
    val timer = LpPhaseTimer(observer, LpSolvePhase.NUMERICAL_RECESSION)
    val checked = floatingRecession(model, candidate.direction, workLimit, cancellation)
    timer.finish(checked.stop?.name ?: "ACCEPTED", checked.work)
    if (cancellation()) return numericalResult(null, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val acceptance = LpPhaseTimer(observer, LpSolvePhase.SOURCE_FLOAT_ACCEPTANCE)
    val point = candidate.point.acceptedNumericalPoint(model, checked.stop, floatAccept, primalCandidate = true)
    acceptance.finish(if (point == null) "REFUSED" else "ACCEPTED")
    if (cancellation()) return numericalResult(null, work = checked.work, stop = LpFloatingBoundStop.CANCELLED)
    val recession = if (point != null && checked.accepted) LpNumericalRecession(model, candidate.direction) else null
    return numericalResult(candidate.point, point = point, work = checked.work,
        stop = checked.stop, recession = recession)
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
    primalCandidate: Boolean = false,
): FloatLpResult? = takeIf {
    stop != LpFloatingBoundStop.WORK && stop != LpFloatingBoundStop.CANCELLED &&
        (optimal || primalCandidate) && objective.isFinite() &&
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
    recession: LpNumericalRecession? = null,
): CertifiedLpResult = CertifiedLpResult(
    result, null, null, null, null, false, { null },
    numerical = LpNumericalResult(bound, conflict, point, optimum, work, stop, recession),
)
