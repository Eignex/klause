package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.lp.bound.CumulativeFlowBound
import com.eignex.klause.solver.Problem

/**
 * A [Cumulative] normalized to the constant data the LP relaxations and the flow
 * bound consume: per-task constant duration, **minimum** resource demand, and the **maximum**
 * (declared) capacity. Using the min demand and max capacity keeps every derived constraint a sound
 * relaxation — it can only loosen, never cut off a feasible schedule.
 *
 * Only the cases all three scheduling relaxations ([CumulativeRelaxation], the time-indexed builder in
 * [CpToLpRelaxation], and [CumulativeFlowBound]) can soundly handle are surfaced: constant durations
 * (a variable duration is not a two-variable makespan link and changes the resource window) and
 * non-optional tasks (a presence literal makes every derived obligation conditional). Both are simply
 * dropped, which only weakens the bound.
 */
internal class SchedulingView(
    val starts: IntArray,
    /** Constant per-task durations (`durations[i] == 0` tasks consume no resource). */
    val durations: LongArray,
    /** Per-task minimum resource demand. */
    val resources: LongArray,
    /** Declared (maximum) capacity, `> 0`. */
    val capacity: Long,
)

/** Every [Cumulative] of [problem], normalized; factors the relaxations cannot soundly
 *  encode (variable durations, optional tasks, non-positive capacity, empty) are omitted. */
internal fun schedulingViews(problem: Problem): List<SchedulingView> {
    val out = ArrayList<SchedulingView>()
    for (f in problem.factors) {
        when (f) {
            is Cumulative -> {
                if (f.durationVars.isNotEmpty() || f.presents.isNotEmpty() || f.starts.isEmpty()) continue
                val cap = if (f.capacityVar >= 0) problem.requireFiniteIntDomains()[f.capacityVar].max else f.capacity
                if (cap <= 0L) continue
                val res = LongArray(f.starts.size) { i ->
                    if (f.resourceVars.isNotEmpty()) {
                        problem.requireFiniteIntDomains()[f.resourceVars[i]].min
                    } else {
                        f.resources[i]
                    }
                }
                out.add(SchedulingView(f.starts, f.durations, res, cap))
            }

            else -> Unit
        }
    }
    return out
}
