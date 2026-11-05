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
                if (repair.retreatLimit >= 0 && frame > repair.retreatLimit) {
                    repair.depth--
                } else {
                    repair.retreatLimit = -1
                    if (repair.states[frame] == FIRST_PINNED && repair.first[frame] != repair.second[frame]) {
                        repair.states[frame] = SECOND_PENDING
                        repair.position = repair.positions[frame]
                        repair.retreat = false
                    } else repair.depth--
                }
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
                repair.conflictFrames[frame] = -1
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
                val target = repairConflictFrame(session, pending, result, frame, cancellation, charge)
                if (!second && repair.first[frame] != repair.second[frame]) {
                    repair.conflictFrames[frame] = target
                    repair.states[frame] = SECOND_PENDING
                } else {
                    repair.depth--
                    repair.retreat = true
                    // Reported levels guide only this private trial; ordinary search keeps every alternative.
                    val firstTarget = repair.conflictFrames[frame]
                    if (second && firstTarget >= 0 && target >= 0) {
                        val latest = maxOf(firstTarget, target)
                        if (latest < repair.depth - 1) {
                            repair.retreatLimit = latest
                            sink.lp.observePrimalPhase("PRIMAL_COMPONENT_REPAIR_RETREAT", "CONFLICT", 0L)
                        }
                    }
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

private fun LpEngine.repairConflictFrame(
    session: PropagationSession,
    pending: RootComponentRounding,
    conflict: PropagationResult.Unsat,
    frame: Int,
    cancellation: Cancellation,
    charge: () -> Unit,
): Int {
    val repair = checkNotNull(pending.repair)
    var latest = -1
    var current = false
    for (level in conflict.conflictLevels) {
        if (cancellation() || !rootComponentRepairWorkAvailable()) return -1
        session.chargeInprocessingWork(1L)
        charge()
        if (level < 0) return -1
        if (level <= pending.level) continue
        if (level == repair.levels[frame] + 1) {
            current = true
            continue
        }
        var matched = -1
        for (earlier in 0 until frame) {
            if (cancellation() || !rootComponentRepairWorkAvailable()) return -1
            session.chargeInprocessingWork(1L)
            charge()
            if (repair.levels[earlier] + 1 == level) {
                matched = earlier
                break
            }
        }
        if (matched < 0) return -1
        latest = maxOf(latest, matched)
    }
    return if (current) latest else -1
}
