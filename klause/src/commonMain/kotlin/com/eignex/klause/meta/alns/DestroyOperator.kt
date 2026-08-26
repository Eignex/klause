package com.eignex.klause.meta.alns

import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.math.abs
import kotlin.random.Random

/**
 * The "destroy" half of an ALNS / LNS iteration: returns the set of variables to *free*
 * for the repair phase. The complementary set stays pinned at the incumbent's values via
 * [com.eignex.klause.solver.Assumptions]. Operators are expected to be stateless and
 * cheap; they're invoked once per ALNS iteration.
 */
internal fun interface DestroyOperator {
    fun destroy(
        rng: Random,
        problem: Problem,
        incumbent: Sample,
        objective: LinearObjective,
        fraction: Double,
    ): FreedVars

    companion object {
        /** Free a uniformly-random subset of `fraction * totalVars` variables. The classic
         *  diversifying baseline; every destroy palette includes it. */
        val Random: DestroyOperator = DestroyOperator { rng, problem, _, _, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val ids = (0 until totalVars).shuffled(rng).take(k)
            split(ids, problem.numBoolVars)
        }

        /** Free the `fraction * totalVars` variables that contribute the most to the current
         *  objective, by `|coefficient * value|` — the variables most likely to move the
         *  objective if re-optimised. */
        val WorstObjective: DestroyOperator = DestroyOperator { _, problem, incumbent, objective, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val contribs = DoubleArray(totalVars)
            // A coefficient array need not cover the whole namespace: presolve can extend it (a `{0, 1}`
            // column substituted for a literal), leaving the tail weightless.
            for (b in 0 until problem.numBoolVars) {
                val w = objective.boolWeights.getOrElse(b) { 0L }
                contribs[b] = abs(w.toDouble()) * (if (incumbent.bools[b]) 1.0 else 0.0)
            }
            for (i in 0 until problem.numIntVars) {
                contribs[problem.numBoolVars + i] = abs(
                    (objective.intCoefficients.getOrElse(i) { 0L } * incumbent.ints[i]).toDouble(),
                )
            }
            val indexed = (0 until totalVars).sortedByDescending { contribs[it] }
            split(indexed.take(k), problem.numBoolVars)
        }

        /** Free a connected "blob" of variables via BFS through factor co-occurrence.
         *  Starts from a random seed variable, hops to factors that contain it, then to
         *  the other variables in those factors, until `fraction` of the variable pool
         *  is reached. Captures the problem's coupling structure: re-optimising a
         *  related cluster surfaces dependencies that a uniformly-random subset misses
         *  (the "Shaw-related" pattern from LNS 1998 / ALNS 2006).
         *
         *  Falls back gracefully on disconnected problems: when BFS exhausts the
         *  connected component before reaching the target size, it re-seeds from an
         *  unvisited variable. */
        val AdjacencyRelated: DestroyOperator = DestroyOperator { rng, problem, _, _, fraction ->
            val projection = LocalSearchProblem(problem)
            val totalVars = problem.numBoolVars + problem.numIntVars
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            if (totalVars == 0) return@DestroyOperator FreedVars(EmptyIntArray, EmptyIntArray)
            val freed = BooleanArray(totalVars)
            var freedCount = 0
            val queue = IntArrayDeque()
            while (freedCount < k) {
                if (queue.isEmpty()) {
                    val startCandidates = (0 until totalVars).filter { !freed[it] }
                    if (startCandidates.isEmpty()) break
                    val seed = startCandidates[rng.nextInt(startCandidates.size)]
                    queue.addLast(seed)
                }
                val v = queue.removeFirst()
                if (freed[v]) continue
                freed[v] = true
                freedCount++
                if (freedCount >= k) break
                val factorIds = if (v < problem.numBoolVars) {
                    projection.boolOccurrences[v]
                } else {
                    projection.intOccurrences[v - problem.numBoolVars]
                }
                for (fid in factorIds) {
                    val f = problem.factors[fid]
                    for (b in f.boolVars) if (!freed[b]) queue.addLast(b)
                    for (i in f.intVars) {
                        val idx = problem.numBoolVars + i
                        if (!freed[idx]) queue.addLast(idx)
                    }
                }
            }
            val freedIds = (0 until totalVars).filter { freed[it] }
            split(freedIds, problem.numBoolVars)
        }

        /** Free variables that participate in factors *currently violated by the incumbent*.
         *  Locally-feasible incumbents (cost = 0) leave nothing to free here, so the
         *  operator returns an empty `FreedVars` — useful only when the ALNS incumbent
         *  is genuinely partial / infeasible (e.g. mid-iteration after a destroyed phase,
         *  or in soft-constraint settings). Caller should fall back to another operator
         *  when this returns empty. */
        val CurrentlyViolated: DestroyOperator = DestroyOperator { rng, problem, incumbent, _, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            if (totalVars == 0) return@DestroyOperator FreedVars(EmptyIntArray, EmptyIntArray)
            // Build a scratch state seeded with the incumbent; ask each factor whether it
            // is violated under that assignment.
            val scratch = LocalSearchState(problem, rng)
            for (b in 0 until problem.numBoolVars) scratch.assignment.setBool(b, incumbent.bools[b])
            for (i in 0 until problem.numIntVars) scratch.assignment.setInt(i, incumbent.ints[i])
            scratch.recompute()
            val violatedFactors = scratch.violated.toIntArray()
            if (violatedFactors.isEmpty()) return@DestroyOperator FreedVars(EmptyIntArray, EmptyIntArray)
            val freedSlots = IntHashSet()
            for (fid in violatedFactors) {
                val f = problem.factors[fid]
                for (v in f.boolVars) freedSlots.add(v)
                for (v in f.intVars) freedSlots.add(problem.numBoolVars + v)
            }
            // Subsample down to the fraction-sized target if the violated set is larger.
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val pool = freedSlots.toIntArray().toList()
            val picked = if (pool.size <= k) pool else pool.shuffled(rng).take(k)
            split(picked, problem.numBoolVars)
        }

        /**
         * Activity-biased: free variables that were touched most often in the inner
         * solver's prior calls, read from a [LocalSearchSession]'s captured touch counts.
         * Variables with the highest cumulative touch count are picked first — they are
         * the ones the search has been working hardest on and most likely to benefit
         * from re-optimisation in isolation.
         *
         * Falls back to [Random] when the session has no activity capture yet (first
         * iteration) or when no session was provided.
         */
        fun activityBiased(session: LocalSearchSession?): DestroyOperator =
            DestroyOperator { rng, problem, incumbent, objective, fraction ->
                val touches = session?.warmStateView?.activityTouches() ?: EmptyIntArray
                if (touches.isEmpty()) {
                    return@DestroyOperator Random.destroy(
                        rng,
                        problem,
                        incumbent,
                        objective,
                        fraction,
                    )
                }
                val totalVars = problem.numBoolVars + problem.numIntVars
                val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
                val indexed = IntArray(touches.size) { it }
                val sorted = indexed.sortedByDescending { touches[it] }.take(k)
                split(sorted, problem.numBoolVars)
            }

        /** Default operator palette: random + worst-objective + adjacency-related. Three
         *  operators gives the bandit a non-trivial menu to learn from; callers can add
         *  problem-specific operators (e.g. [CurrentlyViolated] when the incumbent is
         *  expected to be partially-infeasible, or [activityBiased] when ALNS runs over
         *  a [LocalSearchSession] that accumulates recency). */
        val Defaults: List<DestroyOperator> = listOf(Random, WorstObjective, AdjacencyRelated)

        /** Free every int var whose current value lies in a randomly-chosen contiguous
         *  window `[t, t + windowSize)`. Targets scheduling-style problems (Cumulative,
         *  Disjunctive, Sequence): tasks sharing a temporal slice get re-optimised
         *  together, exposing peak-overlap repairs that uniform random destroy misses.
         *  The window is sampled across the union of all int-var value ranges; vars
         *  outside the window stay pinned. Bool vars in the incumbent are always pinned. */
        fun timeWindow(windowSize: Int): DestroyOperator = DestroyOperator { rng, problem, incumbent, _, fraction ->
            require(windowSize > 0) { "windowSize must be > 0, got $windowSize" }
            val n = problem.numIntVars
            if (n == 0) return@DestroyOperator FreedVars(EmptyIntArray, EmptyIntArray)
            // Pick the window start uniformly across the smallest..largest int var domain.
            var globalLo = Long.MAX_VALUE
            var globalHi = Long.MIN_VALUE
            for (i in 0 until n) {
                val d = problem.requireFiniteIntDomains()[i]
                if (d.min < globalLo) globalLo = d.min
                if (d.max > globalHi) globalHi = d.max
            }
            if (globalLo > globalHi) return@DestroyOperator FreedVars(EmptyIntArray, EmptyIntArray)
            val span = globalHi - globalLo + 1
            val start = if (span <= windowSize) {
                globalLo
            } else {
                globalLo + rng.nextLong(span - windowSize + 1)
            }
            val end = start + windowSize // exclusive
            val inWindow = IntArrayList()
            for (i in 0 until n) {
                val v = incumbent.ints[i]
                if (v in start until end) inWindow.add(i)
            }
            // If the window picked an empty slice, fall back to a uniform random pick over
            // int vars only (the time-window operator targets int-typed schedule positions).
            if (inWindow.size == 0) {
                val cap = ((problem.numBoolVars + n) * fraction).toInt().coerceIn(1, n)
                val all = IntArray(n) { it }.also { it.shuffle(rng) }
                val ints = IntArray(cap) { all[it] }
                return@DestroyOperator FreedVars(EmptyIntArray, ints)
            }
            // Honour `fraction` as a cap on how many of the in-window vars to free.
            val cap = ((problem.numBoolVars + n) * fraction).toInt().coerceAtLeast(1)
            val take = minOf(cap, inWindow.size)
            val idxs = IntArray(inWindow.size) { it }.also { it.shuffle(rng) }
            val ints = IntArray(take) { inWindow[idxs[it]] }
            FreedVars(EmptyIntArray, ints)
        }

        private fun split(ids: List<Int>, numBoolVars: Int): FreedVars {
            val bools = IntArrayList()
            val ints = IntArrayList()
            for (v in ids) {
                if (v < numBoolVars) bools.add(v) else ints.add(v - numBoolVars)
            }
            return FreedVars(bools.toIntArray(), ints.toIntArray())
        }
    }
}

/** Variables freed by a [DestroyOperator]. Indices are in the underlying problem's space:
 *  `bools[i] ∈ [0, problem.numBoolVars)`, `ints[i] ∈ [0, problem.numIntVars)`. */
internal data class FreedVars(val bools: IntArray, val ints: IntArray) {
    val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    override fun equals(other: Any?): Boolean = other is FreedVars &&
        bools.contentEquals(other.bools) && ints.contentEquals(other.ints)
    override fun hashCode(): Int = 31 * bools.contentHashCode() + ints.contentHashCode()
}
