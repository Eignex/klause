package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.snapshotAssignment
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpRounding
import com.eignex.klause.lp.bounding.RootComponentRounding
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

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
