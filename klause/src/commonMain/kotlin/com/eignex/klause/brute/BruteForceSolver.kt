package com.eignex.klause.brute

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.values
import com.eignex.kpermute.LongPermutation
import com.eignex.kpermute.longPermutation
import kotlin.random.Random

/**
 * Per-call params for [BruteForceSolver]. The brute-force engine is exact within its
 * step budget; [maxSteps] caps the number of assignments the walker will check before
 * giving up. The default [Long.MAX_VALUE] means "exhaust the entire space", but for
 * problems with billions of assignments and few satisfying ones, callers should set a
 * finite budget.
 *
 *  - [randomSeed] — controls the iteration order via kpermute.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity filter on
 *    [BruteForceSolver.enumerate]. Default `0 / 0` means no filter; the walker already
 *    visits each assignment at most once.
 *  - [maxSteps] — global cap on assignments visited per call. When the walker hits the
 *    cap, the sequence ends; for [BruteForceSolver.minimize] this means the returned
 *    sample is the best seen so far rather than guaranteed-optimal.
 */
data class BruteForceParams(
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    val maxSteps: Long = Long.MAX_VALUE,
    /**
     * Wall-clock-independent operation budget across all backends. For [BruteForceSolver]
     * one instruction = one assignment visited (the same unit [maxSteps] counts). When
     * both are set, the smaller wins. `null` = no instruction-budget cap; use [maxSteps]
     * alone. See the matching field on `BacktrackParams` / `LocalSearchParams` for the
     * cross-backend rationale.
     */
    val maxInstructions: Long? = null,
) : SolverParams {
    /** Effective per-call assignment cap honouring both [maxSteps] and [maxInstructions]. */
    internal val effectiveStepCap: Long get() = minOf(maxSteps, maxInstructions ?: Long.MAX_VALUE)
}

/**
 * Ground-truth [Solver] / [Optimizer] for testing. Walks the entire assignment space in a
 * permuted order and yields every assignment that satisfies the problem.
 *
 * Spaces larger than [Long.MAX_VALUE] are supported by splitting variables into "chunks"
 * whose per-chunk product fits in a `Long`. Each chunk gets its own [LongPermutation]; the
 * walker traverses the cartesian product of chunk permutations as a nested loop. Within a
 * chunk the iteration is shuffled (the kpermute output); across chunks it's lexicographic.
 * For problems with many variables but few constraints, the satisfaction density is high
 * enough that a satisfying assignment falls out within a handful of inner iterations
 * regardless.
 *
 * As an [Optimizer] the implementation is the obvious "walk every assignment, return the
 * one with the lowest objective". Trivially correct and useful as a ground-truth oracle
 * for stochastic optimisation backends on small problems.
 */
class BruteForceSolver(override val problem: BakedProblem) :
    Solver<BruteForceParams>,
    Optimizer<BruteForceParams> {

    private val chunks: List<Chunk> = buildChunks(problem)

    override fun describe(params: BruteForceParams): String =
        """
        brute-force
          enumeration: exhaustive
          chunks:      ${chunks.size}
        """.trimIndent()

    override fun solve(params: BruteForceParams): SolveResult {
        for (sample in walk(params)) {
            return SolveResult.Sat(sample)
        }
        return SolveResult.Unsat()
    }

    /**
     * Lowest-objective assignment found within `params.maxSteps` iterations of the walker.
     * If the cap is generous enough to exhaust the assignment space, the result is the
     * exact global minimum; otherwise it's the best-seen-so-far.
     */
    override fun minimize(objective: LinearObjective, params: BruteForceParams): MinimizeResult {
        var bestObj = Double.POSITIVE_INFINITY
        var best: Sample? = null
        val budget = StepBudget(params.effectiveStepCap)
        for (s in walkWithBudget(params, budget)) {
            val obj = objective.evaluate(s)
            if (obj < bestObj) {
                bestObj = obj
                best = s
            }
        }
        // budget.remaining > 0 means the walker exhausted the assignment space naturally
        // (the search proved no better solution exists). Otherwise we hit the step cap
        // and the result is only best-effort.
        val exhausted = budget.remaining > 0
        return when {
            best != null && exhausted -> MinimizeResult.Optimal(best, bestObj)
            best != null -> MinimizeResult.BestFound(best, bestObj, TerminationReason.BudgetExhausted)
            exhausted -> MinimizeResult.Infeasible()
            else -> MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
        }
    }

    /** With replacement: each yield is the first satisfying assignment of a freshly-seeded
     *  permutation walk. Different seeds give different "first hit" positions, so the
     *  sequence behaves like independent draws — duplicates can reappear, as the contract
     *  requires. */
    override fun samples(params: BruteForceParams): Sequence<Sample> = sequence {
        var seed = params.randomSeed ?: 0L
        while (true) {
            val first = walk(params.copy(randomSeed = seed)).firstOrNull() ?: return@sequence
            yield(first)
            seed += 0x9E3779B97F4A7C15uL.toLong()
        }
    }

    override fun enumerate(params: BruteForceParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (s in walk(params)) {
            if (farEnough(s, window, params.minHammingDistance)) {
                yield(s)
                if (params.recentWindow > 0) {
                    if (window.size >= params.recentWindow) window.removeFirst()
                    window.addLast(s)
                }
            }
        }
    }

    /**
     * Walk the assignment space, capped at `params.maxSteps` total assignments visited.
     * Yields satisfying assignments in kpermute-shuffled order across nested chunk
     * permutations. When `params.maxSteps` is [Long.MAX_VALUE] (the default) the walk is
     * effectively unbounded.
     */
    private fun walk(params: BruteForceParams): Sequence<Sample> =
        walkWithBudget(params, StepBudget(params.effectiveStepCap))

    /** Variant of [walk] that uses a caller-supplied [StepBudget], so the caller can
     *  read `remaining` after iteration to distinguish "space exhausted naturally"
     *  from "step budget hit." Used by [minimize]'s Optimal-vs-BestFound verdict. */
    private fun walkWithBudget(params: BruteForceParams, budget: StepBudget): Sequence<Sample> {
        val seed = params.randomSeed ?: 0L
        val state = LocalSearchState(problem, Random(seed))
        if (chunks.isEmpty()) {
            // No variables — there's exactly one assignment to test.
            return sequence {
                if (!budget.consume()) return@sequence
                state.recompute()
                if (state.cost == 0L) yield(state.assignment.snapshot())
            }
        }
        // Use a different sub-seed per chunk so two chunks with the same size don't walk
        // in lock-step. Overflow is irrelevant — kpermute accepts any Long seed.
        val perms = chunks.mapIndexed { i, c ->
            longPermutation(size = c.totalSize, seed = seed + i * 0x9E3779B97F4A7C15uL.toLong())
        }
        return walkChunks(perms, depth = 0, state = state, budget = budget)
    }

    /** Mutable counter so the recursive [walkChunks] decrements one shared budget across
     *  the cartesian-product walk rather than each depth holding its own. */
    private class StepBudget(var remaining: Long) {
        /** Decrement the budget; return `false` if it's exhausted (caller should stop). */
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    private fun walkChunks(
        perms: List<LongPermutation>,
        depth: Int,
        state: LocalSearchState,
        budget: StepBudget,
    ): Sequence<Sample> = sequence {
        if (depth == perms.size) {
            if (!budget.consume()) return@sequence
            state.recompute()
            if (state.cost == 0L) yield(state.assignment.snapshot())
            return@sequence
        }
        for (idx in perms[depth]) {
            if (budget.remaining <= 0) return@sequence
            applyChunkIndex(chunks[depth], idx, state)
            yieldAll(walkChunks(perms, depth + 1, state, budget))
        }
    }

    /** Decode [idx] into the variables held by [chunk] and write them into [state]'s
     *  assignment. Mixed-radix: each dim consumes its radix from the running quotient. */
    private fun applyChunkIndex(chunk: Chunk, idx: Long, state: LocalSearchState) {
        var remaining = idx
        for (dim in chunk.dims) {
            val digit = (remaining % dim.radix).toInt()
            remaining /= dim.radix
            when (dim.kind) {
                DimKind.BOOL -> state.assignment.setBool(dim.varId, digit == 1)

                // `digit` is an ordinal into the present values, not an offset from min:
                // holey domains (e.g. {0,2}) must enumerate real members, never the hole.
                DimKind.INT -> state.assignment.setInt(
                    dim.varId,
                    problem.requireFiniteIntDomains()[dim.varId].values.valueAt(digit),
                )
            }
        }
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }

    private enum class DimKind { BOOL, INT }
    private data class Dim(val kind: DimKind, val varId: Int, val radix: Long)
    private data class Chunk(val dims: List<Dim>, val totalSize: Long)

    /** Factory and shared constants for [BruteForceSolver]. */
    companion object {
        /** Largest per-chunk product. Stays well below [Long.MAX_VALUE] so adding one more
         *  dim's worth of multiplication can't overflow even for radix = Int.MAX_VALUE. */
        const val CHUNK_CAP: Long = 1L shl 60

        /** Heuristic cap used by callers gating brute-force inclusion. Brute-force walking
         *  is exact, but exhausting a 10¹²-assignment space takes longer than typical use
         *  cases want to wait. */
        const val DEFAULT_MAX_SPACE: Long = 1_000_000L

        /** True when the total assignment space fits the caller's heuristic cap. The
         *  sampler itself accepts any size; this is purely a gating decision for callers
         *  that want brute force only when it'll finish quickly. */
        fun fits(problem: Problem, cap: Long = DEFAULT_MAX_SPACE): Boolean {
            val size = computeSpaceSize(problem)
            return size in 1..cap
        }

        /** Total space size, or [Long.MAX_VALUE] if it overflows. */
        private fun computeSpaceSize(problem: Problem): Long {
            var size = 1L
            repeat(problem.numBoolVars) {
                if (size > Long.MAX_VALUE / 2) return Long.MAX_VALUE
                size *= 2
            }
            for (d in problem.requireFiniteIntDomains()) {
                val r = d.valueCount
                if (r <= 0) return Long.MAX_VALUE
                if (size > Long.MAX_VALUE / r) return Long.MAX_VALUE
                size *= r
            }
            return size
        }

        /** Build chunks sized so each per-chunk product stays under [CHUNK_CAP]. Bools
         *  come first, then ints — order doesn't affect correctness, only iteration
         *  shape. */
        private fun buildChunks(problem: Problem): List<Chunk> {
            val dims = ArrayList<Dim>(problem.numBoolVars + problem.numIntVars)
            for (b in 0 until problem.numBoolVars) dims.add(Dim(DimKind.BOOL, b, 2L))
            for (i in 0 until problem.numIntVars) {
                dims.add(Dim(DimKind.INT, i, problem.requireFiniteIntDomains()[i].valueCount))
            }
            val chunks = ArrayList<Chunk>()

            @Suppress("DoubleMutabilityForCollection") // reassigned each expansion step
            var current = ArrayList<Dim>()
            var currentSize = 1L
            for (dim in dims) {
                require(dim.radix in 1..CHUNK_CAP) {
                    "Variable radix ${dim.radix} (varId ${dim.varId}, kind ${dim.kind}) " +
                        "exceeds per-chunk cap $CHUNK_CAP"
                }
                if (currentSize > CHUNK_CAP / dim.radix) {
                    chunks.add(Chunk(current, currentSize))
                    current = ArrayList()
                    currentSize = 1L
                }
                current.add(dim)
                currentSize *= dim.radix
            }
            if (current.isNotEmpty()) chunks.add(Chunk(current, currentSize))
            return chunks
        }
    }
}
