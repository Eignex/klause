package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bound.CumulativeFlowBound
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound

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
                val cap = declaredCapacityOf(problem, f) ?: continue
                val res = minimumResourcesOf(problem, f) ?: continue
                out.add(SchedulingView(f.starts, f.durations, res, cap))
            }

            else -> Unit
        }
    }
    return out
}

/**
 * The declared (maximum) capacity of [f], or null when the model states none above 0.
 *
 * A capacity variable the model leaves open above states no ceiling at all, so every obligation an
 * energetic relaxation would derive from it vanishes. The factor is dropped rather than capped at the
 * search box, whose endpoint would state a resource limit the model never declared.
 */
internal fun declaredCapacityOf(problem: Problem, f: Cumulative): Long? {
    if (f.capacityVar < 0) return f.capacity.takeIf { it > 0L }
    if (!problem.statesUpperBound(f.capacityVar)) return null
    return problem.rootDomainOf(f.capacityVar).max.takeIf { it > 0L }
}

/** Per-task minimum resource demand of [f], or null when a demand variable is open below — its minimum
 *  is then the search box's invented endpoint rather than a demand the model states. */
internal fun minimumResourcesOf(problem: Problem, f: Cumulative): LongArray? {
    if (f.resourceVars.isEmpty()) return LongArray(f.starts.size) { i -> f.resources[i] }
    if (f.resourceVars.any { !problem.statesLowerBound(it) }) return null
    return LongArray(f.starts.size) { i -> problem.rootDomainOf(f.resourceVars[i]).min }
}
