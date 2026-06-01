package com.eignex.klause.solver.localsearch.meta

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSession
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
internal fun interface RepairOperator {
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

/** Bundle of everything a [RepairOperator] needs to fill a freed neighbourhood. When
 *  [session] is provided, operators that delegate to the inner solver should route
 *  calls through it so cross-iteration state (DDFW weights, activity recency) survives;
 *  the [inner] reference remains for operators that need raw `Optimizer` access. */
internal data class RepairContext(
    val inner: Optimizer<LocalSearchParams>,
    val params: LocalSearchParams,
    val objective: Objective,
    val pinAssumptions: Assumptions,
    val incumbent: Sample,
    val freed: FreedVars,
    val rng: Random = Random.Default,
    val session: LocalSearchSession? = null,
)

/**
 * Repair via the inner LS engine: invoke `inner.minimize` with the pin assumptions
 * applied. [flipsOverride] optionally caps the inner search budget independent of
 * `params.maxFlips` — useful for differentiating "quick probe" from "deep investment"
 * variants the ALNS bandit can choose between.
 */
internal class InnerLsRepair(val label: String = "standard", val flipsOverride: Long? = null) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val params = if (flipsOverride != null) context.params.copy(maxFlips = flipsOverride) else context.params
        val merged = params.withAssumptions(context.pinAssumptions)
        // Prefer the session when present so weight learning + activity recency
        // accumulate across iterations; fall back to the bare inner Optimizer otherwise.
        val result = context.session?.minimize(context.objective, merged)
            ?: context.inner.minimize(context.objective, merged)
        return result.assignment
    }

    override fun toString(): String = "InnerLsRepair($label${flipsOverride?.let { ", flips=$it" }.orEmpty()})"
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
internal class GreedyConstructionRepair(val intDomainSampleCap: Int = 20) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.ints[i])
        state.recompute()

        val shaping = context.params.costShaping
        fun currentScore(): Double = shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))

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
                IntArray(d.size) { d.valueAt(it) }
            } else {
                IntArray(intDomainSampleCap) { d.valueAt(context.rng.nextInt(d.size)) }
            }
            for (v in candidates) {
                if (v == cur) continue
                state.apply(Move.IntSet(i, v))
                val s = currentScore()
                if (s < bestScore) {
                    bestScore = s
                    bestVal = v
                }
                state.apply(Move.IntSet(i, cur)) // revert
            }
            if (bestVal != cur) state.apply(Move.IntSet(i, bestVal))
        }

        return state.assignment.snapshot()
    }

    override fun toString(): String = "GreedyConstructionRepair(cap=$intDomainSampleCap)"
}

/**
 * Regret-based repair (Potvin & Rousseau 1993, adapted to CP/LS). For each freed integer
 * variable, compute the *regret* — the gap between the cost of its best feasible value and
 * its second-best. Assign variables in descending regret order: vars with large regret
 * are critical (the wrong choice costs a lot) and should be locked in first; small-regret
 * vars are flexible and slot in later. Booleans assign by best-flip score in arbitrary
 * order — regret is a continuous-domain heuristic.
 *
 * Compared to [GreedyConstructionRepair] (which orders freed vars by shuffle), regret
 * uses problem-aware ordering. On scheduling / packing problems where each var's value
 * has dramatically different costs, regret typically reaches a feasible incumbent in fewer
 * inner LS rounds.
 */
internal class RegretRepair(val intDomainSampleCap: Int = 20) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.ints[i])
        state.recompute()
        val shaping = context.params.costShaping
        fun currentScore(): Double = shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))
        // Booleans: greedy single pass (regret on a 2-value domain reduces to best-flip).
        for (b in context.freed.bools) {
            val baseline = currentScore()
            state.apply(Move.BoolFlip(b))
            if (currentScore() >= baseline) state.apply(Move.BoolFlip(b))
        }
        // Integers: compute regret per var, sort desc, assign best value in that order.
        data class Slot(val v: Int, val best: Int, val bestScore: Double, val regret: Double)
        val slots = ArrayList<Slot>(context.freed.ints.size)
        for (i in context.freed.ints) {
            val d = problem.intDomains[i]
            val cur = state.assignment.intValue(i)
            val cand: IntArray = if (d.size <= intDomainSampleCap) {
                IntArray(d.size) { d.valueAt(it) }
            } else {
                IntArray(intDomainSampleCap) { d.valueAt(context.rng.nextInt(d.size)) }
            }
            var best = cur
            var bestScore = currentScore()
            var second = Double.POSITIVE_INFINITY
            for (v in cand) {
                if (v == cur) continue
                state.apply(Move.IntSet(i, v))
                val s = currentScore()
                if (s < bestScore) {
                    second = bestScore
                    bestScore = s
                    best = v
                } else if (s < second) {
                    second = s
                }
                state.apply(Move.IntSet(i, cur))
            }
            val regret = if (second == Double.POSITIVE_INFINITY) 0.0 else second - bestScore
            slots.add(Slot(i, best, bestScore, regret))
        }
        slots.sortByDescending { it.regret }
        for (slot in slots) {
            val cur = state.assignment.intValue(slot.v)
            if (cur != slot.best) state.apply(Move.IntSet(slot.v, slot.best))
        }
        return state.assignment.snapshot()
    }

    override fun toString(): String = "RegretRepair(cap=$intDomainSampleCap)"
}

/**
 * Best-improving (hill-climbing) repair. Repeatedly scans every freed variable and every
 * candidate value (sampled to [intDomainSampleCap] for large int domains) and applies the
 * single move that *most* reduces the shaped score. Terminates when no improving move
 * exists — i.e. a strict local optimum over the freed neighbourhood under shaped scoring.
 *
 * Distinguished from [InnerLsRepair] by being purely deterministic-best-improvement
 * (no tabu, noise, or restart). Good when the freed neighbourhood is small and the
 * objective surface near the incumbent is smooth — the bandit learns when best-improving
 * outperforms stochastic LS.
 */
internal class BestImprovingRepair(val intDomainSampleCap: Int = 20, val maxIterations: Int = 100) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.bools[b])
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.ints[i])
        state.recompute()
        val shaping = context.params.costShaping
        fun currentScore(): Double = shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))
        var iter = 0
        while (iter < maxIterations) {
            val baseline = currentScore()
            var bestScore = baseline
            var bestMove: Move? = null
            for (b in context.freed.bools) {
                state.apply(Move.BoolFlip(b))
                val s = currentScore()
                if (s < bestScore) {
                    bestScore = s
                    bestMove = Move.BoolFlip(b)
                }
                state.apply(Move.BoolFlip(b))
            }
            for (i in context.freed.ints) {
                val d = problem.intDomains[i]
                val cur = state.assignment.intValue(i)
                val cand: IntArray = if (d.size <= intDomainSampleCap) {
                    IntArray(d.size) { d.valueAt(it) }
                } else {
                    IntArray(intDomainSampleCap) { d.valueAt(context.rng.nextInt(d.size)) }
                }
                for (v in cand) {
                    if (v == cur) continue
                    state.apply(Move.IntSet(i, v))
                    val s = currentScore()
                    if (s < bestScore) {
                        bestScore = s
                        bestMove = Move.IntSet(i, v)
                    }
                    state.apply(Move.IntSet(i, cur))
                }
            }
            if (bestMove == null || bestScore >= baseline) break
            state.apply(bestMove)
            iter++
        }
        return state.assignment.snapshot()
    }

    override fun toString(): String = "BestImprovingRepair(cap=$intDomainSampleCap, iters=$maxIterations)"
}
