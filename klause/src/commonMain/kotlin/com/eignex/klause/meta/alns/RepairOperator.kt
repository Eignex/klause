package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.ir.randomValue
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.math.abs
import kotlin.random.Random

/** The objective's largest-magnitude coefficient — the scale ALNS insertion noise is measured in. Zero
 *  for an empty (constant) objective, so noise is inert where there is nothing to perturb. */
private fun objectiveScale(objective: LinearObjective): Double {
    var m = 0L
    for (w in objective.boolWeightValues) {
        val a = abs(w)
        if (a > m) m = a
    }
    for (c in objective.intCoefficientValues) {
        val a = abs(c)
        if (a > m) m = a
    }
    return m.toDouble()
}

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
        /** Default operator menu: three flip-budget profiles for the inner LS plus a pure and a noisy
         *  greedy-construction filler. The bandit learns which fits the current problem. */
        val Defaults: List<RepairOperator> = listOf(
            InnerLsRepair(label = "standard"),
            InnerLsRepair(label = "quick", flipsOverride = 200L),
            InnerLsRepair(label = "deep", flipsOverride = 5_000L),
            GreedyConstructionRepair(),
            GreedyConstructionRepair(noise = 0.1),
        )
    }
}

/** Bundle of everything a [RepairOperator] needs to fill a freed neighbourhood. When
 *  [session] is provided, operators that delegate to the inner solver should route
 *  calls through it so cross-iteration state (DDFW weights, activity recency) survives;
 *  the [inner] reference remains for operators that need raw `Optimizer` access.
 *
 *  [backtrack] / [backtrackParams] are present when the caller supplies a backtrack LCG+LP engine for
 *  CP repair ([BacktrackRepair]); null on a pure-LS ALNS. */
internal data class RepairContext(
    val inner: Optimizer<LocalSearchParams>,
    val params: LocalSearchParams,
    val objective: LinearObjective,
    val pinAssumptions: Assumptions,
    val incumbent: Sample,
    val freed: FreedVars,
    val rng: Random = Random.Default,
    val session: LocalSearchSession? = null,
    val backtrack: Optimizer<BacktrackParams>? = null,
    val backtrackParams: BacktrackParams? = null,
    /** A persistent repair handle reusing one session + LP across fragments; when present,
     *  [BacktrackRepair] uses it instead of a fresh solve per repair. */
    val repairSearch: RepairSearch? = null,
    /** The monotone (non-increasing) best-so-far objective — the cutoff [BacktrackRepair] prunes the
     *  fragment against when using [repairSearch] (the reused session needs a monotone cutoff). */
    val bestObjective: Double = Double.POSITIVE_INFINITY,
    val recordInnerWork: ((nodes: Long, moves: Long) -> Unit)? = null,
    val recordRepairResult: ((MinimizeResult) -> Unit)? = null,
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
        // The session carries weight learning and activity recency across iterations.
        val result = context.session?.minimize(context.objective, merged)
            ?: context.inner.minimize(context.objective, merged)
        context.recordInnerWork?.invoke(
            result.stats.search.nodes.sum.toLong(), result.stats.ls.moves.sum.toLong(),
        )
        context.recordRepairResult?.invoke(result)
        return result.assignment
    }

    override fun toString(): String = "InnerLsRepair($label${flipsOverride?.let { ", flips=$it" }.orEmpty()})"
}

/**
 * CP repair via the backtrack LCG+LP engine — the hybrid LS+CP move. Pins the complement of the
 * freed set as root assumptions and runs a small bounded branch-and-bound over the freed neighbourhood,
 * so the fragment gets full propagation, clause learning, and LP bounding, unlike the LS/greedy
 * repairs. The incumbent objective is wired as [BacktrackParams.objectiveBoundSupplier] so the search
 * prunes against best-known and abandons a hopeless fragment early; only a strictly-improving completion
 * comes back (else null, which ALNS treats as a rejected iteration). [maxDecisions] is the repair budget
 * the ALNS bandit picks between — a quick probe vs a deep investment. A no-op (null) when the context
 * carries no backtrack engine.
 */
internal class BacktrackRepair(val label: String = "standard", val maxDecisions: Long = 2_000L) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        // The reused session's accumulated objective bounds only tighten, so it needs the monotone cutoff.
        context.repairSearch?.let {
            val record = context.recordInnerWork
            val before = if (record != null) it.stats.search.nodes.sum.toLong() else 0L
            val step = it.repairStep(
                context.pinAssumptions, maxDecisions, context.bestObjective, context.params.cancellation,
            )
            record?.invoke(it.stats.search.nodes.sum.toLong() - before, 0L)
            context.recordRepairResult?.invoke(step.sample?.let { sample ->
                MinimizeResult.BestFound(sample, context.objective.evaluate(sample),
                    checkNotNull(step.reason), it.stats)
            } ?: MinimizeResult.Unknown(checkNotNull(step.reason), it.stats))
            return step.sample
        }
        val engine = context.backtrack ?: return null
        val base = context.backtrackParams ?: return null
        val incumbentObjective = context.objective.evaluate(context.incumbent)
        val pinned = base
            .withAssumptions(context.pinAssumptions)
            .copy(
                maxDecisions = maxDecisions,
                objectiveBoundSupplier = { incumbentObjective },
                cancellation = base.cancellation or context.params.cancellation,
            )
        val result = engine.minimize(context.objective, pinned)
        context.recordInnerWork?.invoke(
            result.stats.search.nodes.sum.toLong(), result.stats.ls.moves.sum.toLong(),
        )
        context.recordRepairResult?.invoke(result)
        return result.assignment
    }

    override fun toString(): String = "BacktrackRepair($label, maxDecisions=$maxDecisions)"

    companion object {
        /** Three repair-budget profiles for the ALNS bandit to choose between — quick probe, standard,
         *  and deep fragment solve. */
        val Defaults: List<RepairOperator> = listOf(
            BacktrackRepair(label = "quick", maxDecisions = 500L),
            BacktrackRepair(label = "standard", maxDecisions = 2_000L),
            BacktrackRepair(label = "deep", maxDecisions = 10_000L),
        )
    }
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
 * feasibility-vs-objective trade-off; with the default [com.eignex.klause.localsearch.CostShaping.FeasibilityFirst]
 * infeasible candidates are scored `+∞` and greedy stays inside the feasible region
 * whenever it can.
 */
internal class GreedyConstructionRepair(
    val intDomainSampleCap: Int = 20,
    /** ALNS insertion noise (Ropke and Pisinger, see [Alns]): each candidate's score is perturbed by a uniform
     *  draw in `±noise · maxObjectiveCoefficient`, so the greedy fill occasionally takes a non-locally-best
     *  value and diversifies. `0.0` (default) is pure greedy; `~0.1` is the usual diversifying setting. */
    val noise: Double = 0.0,
) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.boolValue(b))
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.intValue(i))
        state.recompute()

        val shaping = context.params.costShaping
        fun currentScore(): Double = shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))
        // Added to a finite score only: an infeasible +∞ stays worst, so noise never accepts infeasibility.
        val noiseScale = noise * objectiveScale(context.objective)
        fun jittered(score: Double): Double = if (noiseScale > 0.0 && score.isFinite()) {
            score + noiseScale * (2.0 * context.rng.nextDouble() - 1.0)
        } else {
            score
        }

        val boolOrder = context.freed.bools.copyOf().also { it.shuffle(context.rng) }
        for (b in boolOrder) {
            val baseline = currentScore()
            state.apply(Move.BoolFlip(b))
            val flipped = jittered(currentScore())
            if (flipped >= baseline) state.apply(Move.BoolFlip(b))
        }

        val intOrder = context.freed.ints.copyOf().also { it.shuffle(context.rng) }
        for (i in intOrder) {
            val d = state.rootDomains[i]
            val cur = state.assignment.intValue(i)
            val baseline = currentScore()
            var bestVal = cur
            var bestScore = baseline
            val few = d.spanOrNull(intDomainSampleCap.toLong())
            val candidates: LongArray = if (few != null) {
                LongArray(few.size) { few.valueAt(it) }
            } else {
                LongArray(intDomainSampleCap) { d.randomValue(context.rng) }
            }
            for (v in candidates) {
                if (v == cur) continue
                state.apply(Move.IntSet(i, v))
                val s = jittered(currentScore())
                if (s < bestScore) {
                    bestScore = s
                    bestVal = v
                }
                state.apply(Move.IntSet(i, cur))
            }
            if (bestVal != cur) state.apply(Move.IntSet(i, bestVal))
        }

        return state.assignment.snapshot()
    }

    override fun toString(): String = "GreedyConstructionRepair(cap=$intDomainSampleCap, noise=$noise)"
}

/**
 * Regret-based repair, after the regret insertion of J.-Y. Potvin and J.-M. Rousseau, "A parallel route
 * building algorithm for the vehicle routing and scheduling problem with time windows", European Journal
 * of Operational Research 66(3), 1993, https://doi.org/10.1016/0377-2217(93)90221-8. For each freed
 * integer variable, the *regret* is the gap between the score of its best sampled value and its
 * second-best, both measured against the starting assignment; the best values are then applied in
 * descending regret order. Booleans take a single best-flip pass, as regret over a two-value domain
 * reduces to that.
 */
internal class RegretRepair(
    val intDomainSampleCap: Int = 20,
    /** Insertion noise, as in [GreedyConstructionRepair]: candidate scores are perturbed by `±noise ·
     *  maxObjectiveCoefficient` before the regret ordering, diversifying the assignment. `0.0` disables it. */
    val noise: Double = 0.0,
) : RepairOperator {
    override fun repair(context: RepairContext): Sample? {
        val problem = context.inner.problem
        val state = LocalSearchState(problem, context.rng, context.pinAssumptions)
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.boolValue(b))
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.intValue(i))
        state.recompute()
        val shaping = context.params.costShaping
        fun currentScore(): Double = shaping.shape(state.cost, context.objective.evaluate(state.assignment.snapshot()))
        val noiseScale = noise * objectiveScale(context.objective)
        fun jittered(score: Double): Double = if (noiseScale > 0.0 && score.isFinite()) {
            score + noiseScale * (2.0 * context.rng.nextDouble() - 1.0)
        } else {
            score
        }
        for (b in context.freed.bools) {
            val baseline = currentScore()
            state.apply(Move.BoolFlip(b))
            if (jittered(currentScore()) >= baseline) state.apply(Move.BoolFlip(b))
        }
        data class Slot(val v: Int, val best: Long, val bestScore: Double, val regret: Double)
        val slots = ArrayList<Slot>(context.freed.ints.size)
        for (i in context.freed.ints) {
            val d = state.rootDomains[i]
            val cur = state.assignment.intValue(i)
            val few = d.spanOrNull(intDomainSampleCap.toLong())
            val cand: LongArray = if (few != null) {
                LongArray(few.size) { few.valueAt(it) }
            } else {
                LongArray(intDomainSampleCap) { d.randomValue(context.rng) }
            }
            var best = cur
            var bestScore = currentScore()
            var second = Double.POSITIVE_INFINITY
            for (v in cand) {
                if (v == cur) continue
                state.apply(Move.IntSet(i, v))
                val s = jittered(currentScore())
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

    override fun toString(): String = "RegretRepair(cap=$intDomainSampleCap, noise=$noise)"
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
        for (b in 0 until problem.numBoolVars) state.assignment.setBool(b, context.incumbent.boolValue(b))
        for (i in 0 until problem.numIntVars) state.assignment.setInt(i, context.incumbent.intValue(i))
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
                val d = state.rootDomains[i]
                val cur = state.assignment.intValue(i)
                val few = d.spanOrNull(intDomainSampleCap.toLong())
                val cand: LongArray = if (few != null) {
                    LongArray(few.size) { few.valueAt(it) }
                } else {
                    LongArray(intDomainSampleCap) { d.randomValue(context.rng) }
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
