package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.RootComponentRounding
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import kotlin.math.round
import kotlin.time.TimeSource

private const val FIRST_PENDING: Byte = 0
private const val FIRST_PINNED: Byte = 1
private const val SECOND_PENDING: Byte = 2
private const val SECOND_PINNED: Byte = 3

internal fun LpEngine.repairRootComponent(
    session: PropagationSession,
    pending: RootComponentRounding,
    cancellation: Cancellation,
    targetOf: (Int) -> Double?,
    charge: () -> Unit,
): Boolean? {
    val repair = checkNotNull(pending.repair)
    val order = pending.order
    val end = order.offsets[pending.group + 1]
    val width = end - order.offsets[pending.group]
    val started = TimeSource.Monotonic.markNow()
    val before = session.work
    var outcome = "PAUSED"
    try {
        while (true) {
            if (cancellation() || session.fixpointCancelled || session.probeCancelled) return null
            if (!rootComponentRepairWorkAvailable()) {
                outcome = "WORK_DECLINED"
                return false
            }
            session.chargeInprocessingWork(1L)
            charge()
            if (cancellation()) return null
            if (repair.retreat) {
                if (repair.depth == 0) {
                    outcome = "EXHAUSTED"
                    return false
                }
                val frame = repair.depth - 1
                session.popToLevel(repair.levels[frame])
                // Retraction spends the same allowance as the propagation it reverses.
                session.chargeInprocessingWork(repair.undoWork[frame].coerceAtLeast(1L))
                if (repair.states[frame] == FIRST_PINNED && repair.first[frame] != repair.second[frame]) {
                    repair.states[frame] = SECOND_PENDING
                    repair.position = repair.positions[frame]
                    repair.retreat = false
                } else repair.depth--
                charge()
                continue
            }
            if (repair.position == 2 * width) {
                outcome = "COMPLETED"
                return true
            }
            val variable = order.members[end - 1 - repair.position % width]
            var frame = repair.depth - 1
            val waiting = frame >= 0 && repair.positions[frame] == repair.position &&
                (repair.states[frame] == FIRST_PENDING || repair.states[frame] == SECOND_PENDING)
            if (!waiting) {
                val value = targetOf(variable)
                if ((value != null) != (repair.position < width) || session.intDomain(variable).isFixed) {
                    repair.position++
                    continue
                }
                val domain = session.intDomain(variable)
                val first = if (value == null) domain.min else round(value).toLong().coerceIn(domain.min, domain.max)
                val second = when {
                    value == null -> domain.max
                    value >= first && first < domain.max -> first + 1L
                    value < first && first > domain.min -> first - 1L
                    else -> first
                }
                frame = repair.depth++
                repair.positions[frame] = repair.position
                repair.levels[frame] = session.decisionLevel
                repair.first[frame] = first
                repair.second[frame] = second
                repair.states[frame] = FIRST_PENDING
            }
            val second = repair.states[frame] == SECOND_PENDING
            val work = session.work
            val result = try {
                session.probeInt(variable, if (second) repair.second[frame] else repair.first[frame])
            } finally {
                charge()
            }
            if (result == null || result is PropagationResult.Unsat) {
                session.chargeInprocessingWork((session.work - work).coerceAtLeast(1L))
                charge()
            }
            if (result == null) return null
            if (result is PropagationResult.Unsat) {
                if (!second && repair.first[frame] != repair.second[frame]) {
                    repair.states[frame] = SECOND_PENDING
                } else {
                    repair.depth--
                    repair.retreat = true
                }
            } else {
                repair.undoWork[frame] = (session.work - work).coerceAtLeast(1L)
                repair.states[frame] = if (second) SECOND_PINNED else FIRST_PINNED
                repair.position++
            }
        }
    } finally {
        charge()
        sink.lp.observePrimalPhase("PRIMAL_COMPONENT_REPAIR", outcome,
            started.elapsedNow().inWholeNanoseconds, session.work - before)
    }
}
