package com.eignex.klause.solver.localsearch.meta

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.LocalSearchParams

/**
 * The "repair" half of an ALNS iteration: fill in the freed variables under the pinned
 * assumptions. The standard implementation [InnerLsRepair] delegates to the inner LS
 * engine; custom operators can vary the flip budget, the restart cadence, or even use
 * a different solver entirely.
 *
 * Returning `null` signals "this repair couldn't reach feasibility" — ALNS treats it as
 * a rejected iteration and applies the bandit's reject reward.
 */
fun interface RepairOperator {
    fun repair(context: RepairContext): Sample?

    companion object {
        /** Default operator menu: three flip-budget profiles for the inner LS. The bandit
         *  learns which depth profile fits the current problem class — quick probes for
         *  problems where a few flips suffice, deep repair for harder pins. */
        val Defaults: List<RepairOperator> = listOf(
            InnerLsRepair(label = "standard"),
            InnerLsRepair(label = "quick", flipsOverride = 200L),
            InnerLsRepair(label = "deep", flipsOverride = 5_000L),
        )
    }
}

/** Bundle of everything a [RepairOperator] needs to fill a freed neighbourhood. */
data class RepairContext(
    val inner: Optimizer<LocalSearchParams>,
    val params: LocalSearchParams,
    val objective: Objective,
    val pinAssumptions: Assumptions,
    val incumbent: Sample,
    val freed: FreedVars,
)

/**
 * Repair via the inner LS engine: invoke `inner.minimize` with the pin assumptions
 * applied. [flipsOverride] optionally caps the inner search budget independent of
 * `params.maxFlips` — useful for differentiating "quick probe" from "deep investment"
 * variants the ALNS bandit can choose between.
 */
class InnerLsRepair(
    val label: String = "standard",
    val flipsOverride: Long? = null,
) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val params = if (flipsOverride != null) context.params.copy(maxFlips = flipsOverride) else context.params
        return context.inner.minimize(context.objective, params.withAssumptions(context.pinAssumptions))
    }

    override fun toString(): String = "InnerLsRepair($label${flipsOverride?.let { ", flips=$it" } ?: ""})"
}
