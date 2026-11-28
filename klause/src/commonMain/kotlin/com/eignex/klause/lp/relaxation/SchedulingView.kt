package com.eignex.klause.lp.relaxation

import com.eignex.klause.bound.CumulativeFlowBound
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.constructionLongArray

/**
 * A [Cumulative] with constant durations, minimum demands and the declared maximum capacity, which keeps
 * every derived constraint a relaxation. Only cases [CumulativeRelaxation], [buildCumulativeTimeIndexed]
 * and [CumulativeFlowBound] all handle soundly are surfaced: a variable duration is no two-variable
 * makespan link, and a presence literal makes every obligation conditional.
 */
internal class SchedulingView(
    val starts: IntArray,
    val durations: LongArray,
    val resources: LongArray,
    val capacity: Long,
)

internal fun schedulingViews(problem: Problem): List<SchedulingView> {
    var result: List<SchedulingView> = emptyList()
    sequence<Unit> { result = prepareSchedulingViews(problem) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareSchedulingViews(problem: Problem): List<SchedulingView> {
    var visits = 0
    val out = ArrayList<SchedulingView>()
    for (f in problem.factors) {
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        when (f) {
            is Cumulative -> {
                if (f.durationVars.isNotEmpty() || f.presents.isNotEmpty() || f.starts.isEmpty()) continue
                val cap = declaredCapacityOf(problem, f) ?: continue
                val res = prepareMinimumResources(problem, f) ?: continue
                out.add(SchedulingView(f.starts, f.durations, res, cap))
            }

            else -> Unit
        }
    }
    return out
}

// Null when the model states no capacity above 0. A capacity open above states no ceiling, so the factor is
// dropped rather than capped at a search-box endpoint the model never declared.
internal fun declaredCapacityOf(problem: Problem, f: Cumulative): Long? {
    if (f.capacityVar < 0) return f.capacity.takeIf { it > 0L }
    if (!problem.statesUpperBound(f.capacityVar)) return null
    return problem.rootDomainOf(f.capacityVar).max.takeIf { it > 0L }
}

// Null when a demand is open below: its minimum would be an invented search-box endpoint.
internal fun minimumResourcesOf(problem: Problem, f: Cumulative): LongArray? {
    if (f.resourceVars.isEmpty()) return LongArray(f.starts.size) { i -> f.resources[i] }
    if (f.resourceVars.any { !problem.statesLowerBound(it) }) return null
    return LongArray(f.starts.size) { i -> problem.rootDomainOf(f.resourceVars[i]).min }
}

internal suspend fun SequenceScope<Unit>.prepareMinimumResources(problem: Problem, f: Cumulative): LongArray? {
    if (f.resourceVars.isEmpty()) return constructionLongArray(f.starts.size) { f.resources[it] }
    for (i in f.resourceVars.indices) {
        if (!problem.statesLowerBound(f.resourceVars[i])) return null
        if ((i + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    return constructionLongArray(f.starts.size) { problem.rootDomainOf(f.resourceVars[it]).min }
}
