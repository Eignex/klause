package com.eignex.klause.solver.localsearch.meta

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random

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
        /** Default operator menu: three flip-budget profiles for the inner LS plus a
         *  greedy-construction filler. The bandit learns which fits the current problem
         *  class — quick LS probes for problems where a few flips suffice, deep LS repair
         *  for harder pins, greedy construction for problems with clear local choices. */
        val Defaults: List<RepairOperator> = listOf(
            InnerLsRepair(label = "standard"),
            InnerLsRepair(label = "quick", flipsOverride = 200L),
            InnerLsRepair(label = "deep", flipsOverride = 5_000L),
            GreedyConstructionRepair(),
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
    val rng: Random = Random.Default,
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

/**
 * Myopic value-by-value fill of the freed variables, starting from the incumbent
 * assignment. For each freed bool, try flipping and keep if the shaped score
 * (`params.costShaping(violationCount, objective)`) strictly improves. For each freed
 * int, scan its domain (sampled to [intDomainSampleCap] for large domains) and pick
 * the value with the lowest shaped score.
 *
 * No local search runs after the greedy pass — that's the point. Compared to
 * [InnerLsRepair], greedy is cheap (one pass over freed vars, no flip budget), and
 * surfaces problem-specific local structure: when there's a clear local choice per
 * variable, greedy finds it directly; when the freed neighbourhood requires
 * coordination, LS-based repair wins. The ALNS bandit learns the split.
 *
 * Scoring uses [LocalSearchParams.costShaping] so the operator respects the caller's
 * feasibility-vs-objective trade-off; with the default [com.eignex.klause.solver.localsearch.CostShaping.FeasibilityFirst]
 * infeasible candidates are scored `+∞` and greedy stays inside the feasible region
 * whenever it can.
 */
class GreedyConstructionRepair(
    val intDomainSampleCap: Int = 20,
) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.ints[i])
        state.recompute()

        val shaping = context.params.costShaping
        fun currentScore(): Double {
            return shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))
        }

        val boolOrder = context.freed.bools.copyOf().also { it.shuffle(context.rng) }
        for (b in boolOrder) {
            val baseline = currentScore()
            state.apply(Move.BoolFlip(b))
            val flipped = currentScore()
            if (flipped >= baseline) state.apply(Move.BoolFlip(b)) // revert
        }

        val intOrder = context.freed.ints.copyOf().also { it.shuffle(context.rng) }
        for (i in intOrder) {
            val d = problem.intDomains[i]
            val cur = state.assignment.intValue(i)
            val baseline = currentScore()
            var bestVal = cur
            var bestScore = baseline
            val candidates: IntArray = if (d.size <= intDomainSampleCap) {
                IntArray(d.size) { d.min + it }
            } else {
                IntArray(intDomainSampleCap) { d.min + context.rng.nextInt(d.size) }
            }
            for (v in candidates) {
                if (v == cur) continue
                state.apply(Move.IntSet(i, v))
                val s = currentScore()
                if (s < bestScore) { bestScore = s; bestVal = v }
                state.apply(Move.IntSet(i, cur)) // revert
            }
            if (bestVal != cur) state.apply(Move.IntSet(i, bestVal))
        }

        return state.assignment.snapshot()
    }

    override fun toString(): String = "GreedyConstructionRepair(cap=$intDomainSampleCap)"
}
