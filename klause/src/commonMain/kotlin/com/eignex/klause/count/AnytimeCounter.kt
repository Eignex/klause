package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * Anytime exact projected counting. Independent Boolean clause components multiply their
 * intervals; other models use depth-first feasibility search over the whole projection.
 *
 * Each node is a partial assignment of a prefix of the projection variables that the solver has
 * proven feasible (extensible to a full model). Expanding a node pins the next projection variable
 * to each of its domain values and asks the solver whether the extended prefix is still feasible:
 *  - feasible and last variable → a distinct projected model, `lower += 1`;
 *  - feasible and more to go → a child node to expand later;
 *  - infeasible → pruned (its whole subtree leaves the upper bound);
 *  - undecided within the per-check budget → kept as "possibly feasible" (stays in the upper bound,
 *    so the result can't claim exactness).
 *
 * Each traversal reports `lower = proven models`, `upper = lower + Σ (open + undecided subtree
 * sizes)`, where a subtree at depth `d` has at most `Π domain-sizes[d..]` projected completions.
 * `lower` only rises and `upper` only falls; when the frontier empties (and nothing was undecided)
 * the interval collapses to the exact count. Mass accounting uses exact integers; an upper bound
 * beyond the [Long] range is reported as the unbounded sentinel [Long.MAX_VALUE]. The returned
 * [Count]s are emitted roughly every [ExactCountConfig.reportEvery] checks, so iterating the
 * sequence tightens on demand.
 */
internal object AnytimeCounter {

    private class Node(val depth: Int, val bools: Map<Int, Boolean>, val ints: Map<Int, Long>)

    fun run(problem: BakedProblem, config: ExactCountConfig): Sequence<Count> = sequence {
        val budget = CountCheckBudget(config.maxChecks)
        val components = BooleanCountComponents.partition(problem, config)
        val intervals = components?.run(config, budget) ?: traverse(problem, config, budget)
        for (interval in intervals) yield(interval.snapshot(problem))
    }

    internal fun monolithic(problem: BakedProblem, config: ExactCountConfig): Sequence<Count> =
        traverse(problem, config, CountCheckBudget(config.maxChecks)).map { it.snapshot(problem) }

    internal fun traverse(
        problem: BakedProblem,
        config: ExactCountConfig,
        budget: CountCheckBudget,
    ): Sequence<ExactCountInterval> = sequence {
        val boolVars = config.samplingSet
            ?: if (config.intSamplingSet == null) problem.allBoolVars() else EmptyIntArray
        val intVars = config.intSamplingSet
            ?: if (config.samplingSet == null) IntArray(problem.numIntVars) { it } else EmptyIntArray
        val depth = boolVars.size + intVars.size

        // suffix[d] = number of possible value combinations of projection vars d..depth-1.
        val suffix = Array(depth + 1) { BIG_ONE }
        for (d in depth - 1 downTo 0) {
            val sizeAtD = if (d < boolVars.size) {
                BIG_TWO
            } else {
                val domain = problem.rootIntDomain(intVars[d - boolVars.size])
                domain.spanOrNull()?.let { bigIntOf(it.size) }
                    ?: (bigIntOf(domain.max) - bigIntOf(domain.min) + BIG_ONE - bigIntOf(domain.holeCount))
            }
            suffix[d] = sizeAtD * suffix[d + 1]
        }

        if (!budget.take()) {
            yield(ExactCountInterval(BIG_ZERO, suffix[0]))
            return@sequence
        }
        val solver = BacktrackSolver(problem)
        var lower = BIG_ZERO
        var openMass = BIG_ZERO // Includes unchecked children of a partially expanded node.
        var undecidedMass = BIG_ZERO

        fun snapshot() = ExactCountInterval(lower, lower + openMass + undecidedMass)

        // Root feasibility (and the trivial empty-projection case).
        when (solver.solve(checkParams(config, Assumptions.None))) {
            is SolveResult.Sat -> if (depth == 0) {
                lower = BIG_ONE
            } else {
                openMass = suffix[0]
            }

            is SolveResult.Unsat -> { /* count 0 */ }

            is SolveResult.Unknown -> undecidedMass = suffix[0]
        }

        val stack = ArrayDeque<Node>()
        if (depth > 0 && !openMass.isZero()) stack.addLast(Node(0, emptyMap(), emptyMap()))
        yield(snapshot())

        var sinceReport = 0L
        while (stack.isNotEmpty() && budget.available) {
            val node = stack.removeLast()
            val d = node.depth
            val isBool = d < boolVars.size
            val varId = if (isBool) boolVars[d] else intVars[d - boolVars.size]
            val values = if (isBool) sequenceOf(0L, 1L) else valuesOf(problem, varId)

            for (raw in values) {
                if (!budget.take()) break
                val childBools = if (isBool) node.bools + (varId to (raw == 1L)) else node.bools
                val childInts = if (isBool) node.ints else node.ints + (varId to raw)
                sinceReport++
                val res = solver.solve(checkParams(config, Assumptions(bools = childBools, ints = childInts)))
                // A check replaces only this child's share; unchecked siblings retain their mass.
                openMass -= suffix[d + 1]
                when (res) {
                    is SolveResult.Sat ->
                        if (d + 1 == depth) {
                            lower += BIG_ONE
                        } else {
                            stack.addLast(Node(d + 1, childBools, childInts))
                            openMass += suffix[d + 1]
                        }

                    is SolveResult.Unsat -> { /* pruned: this value contributes nothing */ }

                    is SolveResult.Unknown -> undecidedMass += suffix[d + 1]
                }
            }

            if (sinceReport >= config.reportEvery) {
                sinceReport = 0
                yield(snapshot())
            }
        }
        yield(snapshot())
    }

    private fun checkParams(config: ExactCountConfig, assumptions: Assumptions) = BacktrackPresets.satOptimized().copy(
        assumptions = assumptions,
        maxDecisions = config.maxDecisionsPerCheck,
    )

    private fun valuesOf(problem: BakedProblem, intVar: Int): Sequence<Long> {
        val domain = problem.rootIntDomain(intVar)
        return generateSequence(domain.min) { value ->
            if (value == domain.max) null else domain.higher(value)
        }
    }
}

internal class CountCheckBudget(private val limit: Long) {
    private var used = 0L
    val available: Boolean get() = used < limit

    fun take(): Boolean {
        if (!available) return false
        used++
        return true
    }
}

internal data class ExactCountInterval(val lower: BigInt, val upper: BigInt) {
    operator fun times(other: ExactCountInterval) = ExactCountInterval(lower * other.lower, upper * other.upper)

    fun snapshot(problem: BakedProblem): Count {
        val lo = clampToLong(lower)
        val hi = clampToLong(upper)
        return Count(
            estimate = lo + (hi - lo) / 2,
            lower = lo,
            upper = hi,
            exact = lower == upper && lower.fitsLong(),
            confidence = 1.0,
            semantics = problem.semantics,
        )
    }

    private fun clampToLong(mass: BigInt): Long =
        if (mass.fitsLong()) mass.toLongExact() else Long.MAX_VALUE
}
