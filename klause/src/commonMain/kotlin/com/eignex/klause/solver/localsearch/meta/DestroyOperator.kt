package com.eignex.klause.solver.localsearch.meta

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.math.abs
import kotlin.random.Random

/**
 * The "destroy" half of an ALNS / LNS iteration: returns the set of variables to *free*
 * for the repair phase. The complementary set stays pinned at the incumbent's values via
 * [com.eignex.klause.solver.Assumptions]. Operators are expected to be stateless and
 * cheap; they're invoked once per ALNS iteration.
 */
fun interface DestroyOperator {
    fun destroy(rng: Random, problem: Problem, incumbent: Sample, objective: Objective, fraction: Double): FreedVars

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
         *  objective (by `|coefficient * value|` for linear objectives, or by single-flip /
         *  single-set delta for arbitrary objectives). Targets the variables most likely to
         *  move the objective if re-optimised. Falls back to random for objectives whose
         *  contribution can't be cheaply estimated. */
        val WorstObjective: DestroyOperator = DestroyOperator { rng, problem, incumbent, objective, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val contribs = DoubleArray(totalVars)
            when (objective) {
                is LinearObjective -> {
                    for (b in 0 until problem.numBoolVars) {
                        contribs[b] = abs(objective.boolWeights[b]) * (if (incumbent.bools[b]) 1.0 else 0.0)
                    }
                    for (i in 0 until problem.numIntVars) {
                        contribs[problem.numBoolVars + i] = abs(objective.intCoefficients[i] * incumbent.ints[i])
                    }
                }
                else -> {
                    // Fall back to random for non-linear objectives — full single-flip evaluation
                    // would be O(totalVars) objective evaluations per destroy call.
                    return@DestroyOperator Random.destroy(rng, problem, incumbent, objective, fraction)
                }
            }
            val indexed = (0 until totalVars).sortedByDescending { contribs[it] }
            split(indexed.take(k), problem.numBoolVars)
        }

        /** Free a connected "blob" of variables via BFS through factor co-occurrence.
         *  Starts from a random seed variable, hops to factors that contain it, then to
         *  the other variables in those factors, until [fraction] of the variable pool
         *  is reached. Captures the problem's coupling structure: re-optimising a
         *  related cluster surfaces dependencies that a uniformly-random subset misses
         *  (the "Shaw-related" pattern from LNS 1998 / ALNS 2006).
         *
         *  Falls back gracefully on disconnected problems: when BFS exhausts the
         *  connected component before reaching the target size, it re-seeds from an
         *  unvisited variable. */
        val AdjacencyRelated: DestroyOperator = DestroyOperator { rng, problem, _, _, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            if (totalVars == 0) return@DestroyOperator FreedVars(IntArray(0), IntArray(0))
            val freed = BooleanArray(totalVars)
            var freedCount = 0
            val queue = ArrayDeque<Int>()
            while (freedCount < k) {
                if (queue.isEmpty()) {
                    // Seed from a random unvisited variable.
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
                // Walk factor neighbours: for each factor touching v, enqueue its other vars.
                val factorIds = if (v < problem.numBoolVars) {
                    problem.boolOccurrences[v]
                } else {
                    problem.intOccurrences[v - problem.numBoolVars]
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

        /** Default operator palette: random + worst-objective + adjacency-related. Three
         *  operators gives the bandit a non-trivial menu to learn from; callers can add
         *  problem-specific operators. */
        val Defaults: List<DestroyOperator> = listOf(Random, WorstObjective, AdjacencyRelated)

        private fun split(ids: List<Int>, numBoolVars: Int): FreedVars {
            val bools = mutableListOf<Int>()
            val ints = mutableListOf<Int>()
            for (v in ids) {
                if (v < numBoolVars) bools.add(v) else ints.add(v - numBoolVars)
            }
            return FreedVars(bools.toIntArray(), ints.toIntArray())
        }
    }
}

/** Variables freed by a [DestroyOperator]. Indices are in the underlying problem's space:
 *  `bools[i] ∈ [0, problem.numBoolVars)`, `ints[i] ∈ [0, problem.numIntVars)`. */
data class FreedVars(val bools: IntArray, val ints: IntArray) {
    val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    override fun equals(other: Any?): Boolean = other is FreedVars &&
        bools.contentEquals(other.bools) && ints.contentEquals(other.ints)
    override fun hashCode(): Int = 31 * bools.contentHashCode() + ints.contentHashCode()
}
