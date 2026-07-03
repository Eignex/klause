package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.SumResult

/**
 * Scheduling-specific bound prunes: nodes cut by the Lagrangian bound (#23) or by the Cumulative
 * energetic-reasoning check (#22/#23). Zero outside scheduling models. See [SolveStats].
 */
data class SchedulingStats(
    /** Nodes pruned by the Lagrangian bound (#23). */
    val lagrangianPruned: SumResult = ZERO_COUNT,
    /** Nodes pruned by the Cumulative energetic-reasoning check (#22/#23). */
    val energeticPruned: SumResult = ZERO_COUNT,
) {
    /** Combine two workers' scheduling prune counts (additive). */
    fun mergedWith(o: SchedulingStats): SchedulingStats = SchedulingStats(
        lagrangianPruned = SumResult(lagrangianPruned.sum + o.lagrangianPruned.sum),
        energeticPruned = SumResult(energeticPruned.sum + o.energeticPruned.sum),
    )
}

/** Mutable [SchedulingStats] accumulator. See [SolveStatsSink]. */
internal class SchedulingStatsSink {
    val lagrangianPruned: CountStat = CountStat()
    val energeticPruned: CountStat = CountStat()

    fun observeLagrangianPrune() = lagrangianPruned.update(1.0)
    fun observeEnergeticPrune() = energeticPruned.update(1.0)

    fun snapshot(): SchedulingStats = SchedulingStats(
        lagrangianPruned = lagrangianPruned.read(),
        energeticPruned = energeticPruned.read(),
    )
}
