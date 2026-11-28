package com.eignex.klause.meta.alns

import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import kotlin.math.abs
import kotlin.random.Random

// Operators should be stateless and cheap; they run once per ALNS iteration.
internal fun interface DestroyOperator {
    fun destroy(
        rng: Random,
        problem: BakedProblem,
        incumbent: Sample,
        objective: LinearObjective,
        fraction: Double,
    ): FreedVars

    fun destroy(
        rng: Random,
        problem: BakedProblem,
        incumbent: Sample,
        objective: LinearObjective,
        fraction: Double,
        assumptions: Assumptions,
        cancellation: ExecutionControl,
    ): FreedVars = destroy(rng, problem, incumbent, objective, fraction)

    companion object {
        val Random: DestroyOperator = DestroyOperator { rng, problem, _, _, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            if (totalVars == 0) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val ids = (0 until totalVars).shuffled(rng).take(k)
            split(ids, problem.numBoolVars)
        }

        val WorstObjective: DestroyOperator = DestroyOperator { _, problem, incumbent, objective, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            if (totalVars == 0) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val contribs = DoubleArray(totalVars)
            // A coefficient array need not cover the whole namespace: presolve can extend it (a `{0, 1}`
            // column substituted for a literal), leaving the tail weightless.
            for (b in 0 until problem.numBoolVars) {
                val w = objective.boolWeightValues.getOrElse(b) { 0L }
                contribs[b] = abs(w.toDouble()) * (if (incumbent.boolValue(b)) 1.0 else 0.0)
            }
            for (i in 0 until problem.numIntVars) {
                contribs[problem.numBoolVars + i] = abs(
                    (objective.intCoefficientValues.getOrElse(i) { 0L } * incumbent.intValue(i)).toDouble(),
                )
            }
            val indexed = (0 until totalVars).sortedByDescending { contribs[it] }
            split(indexed.take(k), problem.numBoolVars)
        }

        // Frees a connected cluster by BFS through factor co-occurrence, re-seeding when a component is
        // exhausted, so coupled variables are re-optimised together (relatedness removal from P. Shaw, "Using
        // Constraint Programming and Local Search Methods to Solve Vehicle Routing Problems", CP 1998,
        // https://doi.org/10.1007/3-540-49481-2_30).
        val AdjacencyRelated: DestroyOperator = DestroyOperator { rng, problem, _, _, fraction ->
            val projection = LocalSearchProblem(problem)
            val totalVars = problem.numBoolVars + problem.numIntVars
            if (totalVars == 0) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val freed = BooleanArray(totalVars)
            val queued = BooleanArray(totalVars)
            val visitedFactors = BooleanArray(problem.numFactors)
            var freedCount = 0
            val queue = IntArrayDeque()
            fun enqueue(variable: Int) {
                if (queued[variable]) return
                queued[variable] = true
                queue.addLast(variable)
            }
            while (freedCount < k) {
                if (queue.isEmpty()) {
                    val startCandidates = (0 until totalVars).filter { !freed[it] }
                    if (startCandidates.isEmpty()) break
                    val seed = startCandidates[rng.nextInt(startCandidates.size)]
                    enqueue(seed)
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
                    if (visitedFactors[fid]) continue
                    visitedFactors[fid] = true
                    val f = problem.factors[fid]
                    for (b in f.boolVars) enqueue(b)
                    for (i in f.intVars) {
                        val idx = problem.numBoolVars + i
                        enqueue(idx)
                    }
                }
            }
            val freedIds = (0 until totalVars).filter { freed[it] }
            split(freedIds, problem.numBoolVars)
        }

        // A feasible incumbent frees nothing, which Alns scores as a rejected iteration.
        val CurrentlyViolated: DestroyOperator = DestroyOperator { rng, problem, incumbent, _, fraction ->
            val totalVars = problem.numBoolVars + problem.numIntVars
            if (totalVars == 0) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val scratch = LocalSearchState(problem, rng)
            for (b in 0 until problem.numBoolVars) scratch.assignment.setBool(b, incumbent.boolValue(b))
            for (i in 0 until problem.numIntVars) scratch.assignment.setInt(i, incumbent.intValue(i))
            scratch.recompute()
            val violatedFactors = scratch.violated.toIntArray()
            if (violatedFactors.isEmpty()) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val freedSlots = IntHashSet()
            for (fid in violatedFactors) {
                val f = problem.factors[fid]
                for (v in f.boolVars) freedSlots.add(v)
                for (v in f.intVars) freedSlots.add(problem.numBoolVars + v)
            }
            val k = (fraction * totalVars).toInt().coerceIn(1, totalVars)
            val pool = freedSlots.toIntArray().toList()
            val picked = if (pool.size <= k) pool else pool.shuffled(rng).take(k)
            split(picked, problem.numBoolVars)
        }

        // Frees the variables the inner search touched most; falls back to Random until the session has captured
        // activity.
        fun activityBiased(session: LocalSearchSession?): DestroyOperator =
            DestroyOperator { rng, problem, incumbent, objective, fraction ->
                val touches = session?.warmStateView?.activityTouches() ?: emptyIntArray()
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

        val Defaults: List<DestroyOperator> = listOf(Random, WorstObjective, AdjacencyRelated)

        // Frees int vars whose value lies in a random window, so scheduled tasks sharing a temporal slice are
        // re-optimised together. Bool vars stay pinned.
        fun timeWindow(windowSize: Int): DestroyOperator = DestroyOperator { rng, problem, incumbent, _, fraction ->
            require(windowSize > 0) { "windowSize must be > 0, got $windowSize" }
            val n = problem.numIntVars
            if (n == 0) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            var globalLo = Long.MAX_VALUE
            var globalHi = Long.MIN_VALUE
            for (i in 0 until n) {
                val d = problem.rootIntDomain(i)
                if (d.min < globalLo) globalLo = d.min
                if (d.max > globalHi) globalHi = d.max
            }
            if (globalLo > globalHi) return@DestroyOperator FreedVars(emptyIntArray(), emptyIntArray())
            val span = globalHi - globalLo + 1
            val start = if (span <= windowSize) {
                globalLo
            } else {
                globalLo + rng.nextLong(span - windowSize + 1)
            }
            val end = start + windowSize // exclusive
            val inWindow = IntArrayList()
            for (i in 0 until n) {
                val v = incumbent.intValue(i)
                if (v in start until end) inWindow.add(i)
            }
            // An empty window falls back to a uniform random pick over int vars.
            if (inWindow.size == 0) {
                val cap = ((problem.numBoolVars + n) * fraction).toInt().coerceIn(1, n)
                val all = IntArray(n) { it }.also { it.shuffle(rng) }
                val ints = IntArray(cap) { all[it] }
                return@DestroyOperator FreedVars(emptyIntArray(), ints)
            }
            val cap = ((problem.numBoolVars + n) * fraction).toInt().coerceAtLeast(1)
            val take = minOf(cap, inWindow.size)
            val idxs = IntArray(inWindow.size) { it }.also { it.shuffle(rng) }
            val ints = IntArray(take) { inWindow[idxs[it]] }
            FreedVars(emptyIntArray(), ints)
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

internal data class FreedVars(val bools: IntArray, val ints: IntArray) {
    val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    override fun equals(other: Any?): Boolean = other is FreedVars &&
        bools.contentEquals(other.bools) && ints.contentEquals(other.ints)
    override fun hashCode(): Int = 31 * bools.contentHashCode() + ints.contentHashCode()
}
