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

// The scale ALNS insertion noise is measured in; zero for a constant objective, so noise is inert there.
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

// A null repair could not reach feasibility; ALNS scores it as a rejected iteration.
internal fun interface RepairOperator {
    fun repair(context: RepairContext): Sample?

    companion object {
        val Defaults: List<RepairOperator> = listOf(
            InnerLsRepair(label = "standard"),
            InnerLsRepair(label = "quick", flipsOverride = 200L),
            InnerLsRepair(label = "deep", flipsOverride = 5_000L),
            GreedyConstructionRepair(),
            GreedyConstructionRepair(noise = 0.1),
        )
    }
}

// Operators delegating to the inner solver should route through session, when present, so cross-iteration
// state survives.
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
    // Reuses one session and LP across fragments, so it prunes against the monotone bestObjective cutoff.
    val repairSearch: RepairSearch? = null,
    val bestObjective: Double = Double.POSITIVE_INFINITY,
    val recordInnerWork: ((nodes: Long, moves: Long) -> Unit)? = null,
    val recordRepairResult: ((MinimizeResult) -> Unit)? = null,
)

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

// A bounded branch-and-bound over the freed neighbourhood under the pinned complement, pruning against the
// incumbent objective so a hopeless fragment is abandoned early and only a strictly improving completion returns.
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
        val Defaults: List<RepairOperator> = listOf(
            BacktrackRepair(label = "quick", maxDecisions = 500L),
            BacktrackRepair(label = "standard", maxDecisions = 2_000L),
            BacktrackRepair(label = "deep", maxDecisions = 10_000L),
        )
    }
}

// One myopic pass from the incumbent with no local search after it: cheap, and finds a clear per-variable choice
// directly where LS repair wins on neighbourhoods that need coordination. Scoring uses the caller's cost shaping,
// so under feasibility-first shaping greedy stays feasible whenever it can.
internal class GreedyConstructionRepair(
    val intDomainSampleCap: Int = 20,
    // ALNS insertion noise (Ropke and Pisinger): each score is perturbed by a uniform draw in
    // `±noise · maxObjectiveCoefficient`, so the fill occasionally takes a non-best value.
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

// Regret insertion after J.-Y. Potvin and J.-M. Rousseau, "A parallel route building algorithm for the vehicle
// routing and scheduling problem with time windows", European Journal of Operational Research 66(3), 1993,
// https://doi.org/10.1016/0377-2217(93)90221-8. An int variable's regret is the gap between its best and
// second-best sampled value; best values are applied in descending regret order. Booleans take a single
// best-flip pass, to which regret over two values reduces.
internal class RegretRepair(
    val intDomainSampleCap: Int = 20,
    // Insertion noise, as in GreedyConstructionRepair.
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

// Best-improvement hill climbing over the freed neighbourhood, with no tabu, noise or restart.
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
