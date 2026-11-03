package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.snapshotAssignment
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpRounding
import com.eignex.klause.lp.bounding.RootComponentRounding
import com.eignex.klause.lp.bounding.RootPrimalTargets
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.math.round
import kotlin.time.TimeSource

internal fun LpEngine.prepareRootComponentTrial(
    objective: LinearObjective,
    cancellation: Cancellation,
): Boolean {
    val guide = rootRoundingWarmStart ?: return false
    val targets = rootPrimalTargets ?: return false
    if (guide.primal == null || targets.coordinates !== guide.primal || guide.knownColumns != null ||
        guide.objective !== objective || guide.roundingComplete || problem.numBoolVars != 0 ||
        problem.numRealVars != 0 || problem.numIntVars < 4 || cancellation()
    ) return false
    if (guide.componentRounding != null && guide.pinning?.fixpointCancelled == false) return true
    if (guide.pinning != null) return false
    if (guide.componentRounding == null && !fractionalComponentTargets(targets, cancellation)) return false
    val bytes = if (guide.componentRounding != null) 0L else
        128L + if (targets.components == null) rootComponentPreparationBytes(problem.numIntVars) else 0L
    if (!rootComponentPreparationAllowed(targets, bytes, 8L) ||
        rootComponentPreparationWork(targets, bytes) < 8L
    ) {
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", "PRIVATE_ROOT_ALLOWANCE_DECLINED", 0L)
        return false
    }
    val started = TimeSource.Monotonic.markNow()
    val session = PropagationSession(problem, cancellation)
    primalPropagationWork += session.work
    var retained = false
    var outcome = "PRIVATE_ROOT_DECLINED"
    try {
        if (session.isUnsatAtRoot || session.fixpointCancelled || cancellation()) return false
        outcome = "PREPARATION_DECLINED"
        val pending = prepareComponentRounding(session, guide, cancellation) ?: return false
        if (cancellation()) return false
        pending.reset()
        guide.pinning = session
        retained = true
        outcome = "HANDOFF_TRIAL_PREPARED"
        return true
    } finally {
        if (!retained) session.releaseRetainedState()
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", outcome,
            started.elapsedNow().inWholeNanoseconds)
    }
}

private fun LpEngine.fractionalComponentTargets(
    targets: RootPrimalTargets,
    cancellation: Cancellation,
): Boolean {
    val maximumWork = 8L * targets.intColumns.size
    if (!rootComponentPreparationAllowed(targets, 0L, maximumWork) ||
        rootComponentPreparationWork(targets, 0L) < maximumWork
    ) {
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", "TARGET_SCAN_DECLINED", 0L)
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ADMISSION",
            rootComponentPreparationDecline(targets, 0L, maximumWork) ?: "SLICE_WORK", 0L)
        return false
    }
    val meter = LpProjectionMeter(maximumWork, 0L, cancellation)
    val started = TimeSource.Monotonic.markNow()
    var outcome = "INTEGRAL_HANDOFF"
    try {
        for (column in targets.intColumns) {
            meter.reserve(8L, 0L, matrix = false)
            val value = targets.coordinates.getOrNull(column) ?: continue
            if (value.isFinite() && value != round(value)) {
                outcome = "FRACTIONAL_TARGETS"
                return true
            }
        }
        return false
    } catch (_: LpProjectionStop) {
        outcome = "ADMISSION_STOPPED"
        return false
    } finally {
        noteNodeOverhead(meter.work)
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", outcome,
            started.elapsedNow().inWholeNanoseconds, meter.work)
    }
}

internal fun LpEngine.prepareComponentRounding(
    session: PropagationSession,
    guide: RootRoundingWarmStart,
    cancellation: Cancellation,
): RootComponentRounding? {
    guide.componentRounding?.let { return it }
    if (session.decisionLevel != 0) return null
    if (rootPrimalTargets?.coordinates !== guide.primal && !retainRootPrimalTargets(cancellation)) return null
    val targets = rootPrimalTargets ?: return null
    val order = prepareRootComponentOrder(session, cancellation) ?: return null
    return prepareRootComponentRounding(targets, order, cancellation)
}

internal fun LpEngine.pinRootComponents(
    session: PropagationSession,
    pending: RootComponentRounding,
    relaxation: LpRelaxation,
    primal: DoubleArray,
    knownColumns: BooleanArray?,
    cancellation: Cancellation,
    charge: () -> Unit,
): Sample? {
    val order = pending.order
    fun target(variable: Int): Double? {
        val column = relaxation.intColOf[variable]
        return primal.getOrNull(column)?.takeIf {
            it.isFinite() && (knownColumns == null || knownColumns.getOrNull(column) == true)
        }
    }
    while (pending.group < order.groups) {
        if (cancellation() || session.fixpointCancelled || session.probeCancelled) return null
        var complete = true
        try {
            // Reversing the prepared fractionality order visits confident targets first; unknowns follow them.
            for (pass in 0..1) {
                for (index in order.offsets[pending.group + 1] - 1 downTo order.offsets[pending.group]) {
                    session.chargeInprocessingWork(1L)
                    if (cancellation() || session.fixpointCancelled || session.probeCancelled) return null
                    val variable = order.members[index]
                    val value = target(variable)
                    if ((value != null) != (pass == 0)) continue
                    val domain = session.intDomain(variable)
                    if (domain.isFixed) continue
                    if (!pinIntTowardLp(session, variable, value, domain.min, domain.max, pending.rounding)) {
                        complete = false
                        break
                    }
                }
                if (!complete) break
            }
        } finally {
            charge()
        }
        if (session.fixpointCancelled || session.probeCancelled || cancellation()) return null
        if (!complete) {
            session.popToLevel(pending.level)
            if (pending.rounding == LpRounding.DOWN) {
                val targets = rootPrimalTargets?.takeIf { it.components === order }
                if (targets != null) for (group in 0 until pending.group) {
                    session.chargeInprocessingWork(1L)
                    charge()
                    if (cancellation()) break
                    retainRootComponentValues(targets, order, group, session, cancellation)
                }
                sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", "GROUP_DECLINED", 0L)
                return null
            }
            pending.rounding = if (pending.rounding == LpRounding.NEAREST) LpRounding.UP else LpRounding.DOWN
            sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", "DIRECTION_RETRIED", 0L)
        } else {
            pending.group++
            pending.level = session.decisionLevel
            pending.rounding = LpRounding.NEAREST
            sink.lp.observePrimalPhase("PRIMAL_COMPONENT_ROUNDING", "GROUP_COMPLETED", 0L)
        }
    }
    return snapshotAssignment(session)
}
