package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.values
import com.eignex.klause.util.EmptyIntArray
import kotlin.math.ceil
import kotlin.math.min

/**
 * Anytime exact (projected) model counting by depth-first feasibility search over the projection
 * variables in a fixed order.
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
 * The reported interval is `lower = proven models`, `upper = lower + Σ (open + undecided subtree
 * sizes)`, where a subtree at depth `d` has at most `Π domain-sizes[d..]` projected completions.
 * `lower` only rises and `upper` only falls; when the frontier empties (and nothing was undecided)
 * the interval collapses to the exact count. The returned [Count]s are emitted roughly every
 * [ExactCountConfig.reportEvery] checks, so iterating the sequence tightens on demand.
 */
internal object AnytimeCounter {

    private class Node(val depth: Int, val bools: Map<Int, Boolean>, val ints: Map<Int, Long>)

    fun run(problem: Problem, config: ExactCountConfig): Sequence<Count> = sequence {
        val boolVars = config.samplingSet
            ?: if (config.intSamplingSet == null) problem.allBoolVars() else EmptyIntArray
        val intVars = config.intSamplingSet
            ?: if (config.samplingSet == null) IntArray(problem.numIntVars) { it } else EmptyIntArray
        val depth = boolVars.size + intVars.size

        // suffix[d] = number of possible value combinations of projection vars d..depth-1.
        val suffix = DoubleArray(depth + 1)
        suffix[depth] = 1.0
        for (d in depth - 1 downTo 0) {
            val sizeAtD = if (d <
                boolVars.size
            ) {
                2.0
            } else {
                problem.requireFiniteIntDomains()[intVars[d - boolVars.size]].valueCount.toDouble()
            }
            suffix[d] = sizeAtD * suffix[d + 1]
        }

        val solver = BacktrackSolver(problem.bake())
        var checks = 0L
        var lower = 0L
        var openMass = 0.0 // Σ suffix[node.depth] over feasible-but-unexpanded nodes
        var undecidedMass = 0.0 // Σ suffix over subtrees we couldn't decide (keeps result inexact)

        fun snapshot(): Count {
            val upperD = lower.toDouble() + openMass + undecidedMass
            val exact = openMass == 0.0 && undecidedMass == 0.0
            val upper = if (exact) lower else clampToLong(ceil(upperD))
            val estimate = if (exact) lower else lower + (upper - lower) / 2
            return Count(estimate = estimate, lower = lower, upper = upper, exact = exact, confidence = 1.0)
        }

        // Root feasibility (and the trivial empty-projection case).
        checks++
        when (solver.solve(checkParams(config, Assumptions.None))) {
            is SolveResult.Sat -> if (depth == 0) {
                lower = 1
            } else {
                openMass = suffix[0]
            }

            is SolveResult.Unsat -> { /* count 0 */ }

            is SolveResult.Unknown -> undecidedMass = suffix[0]
        }

        val stack = ArrayDeque<Node>()
        if (depth > 0 && openMass > 0.0) stack.addLast(Node(0, emptyMap(), emptyMap()))
        yield(snapshot())

        var sinceReport = 0L
        while (stack.isNotEmpty() && checks < config.maxChecks) {
            val node = stack.removeLast()
            openMass -= suffix[node.depth]
            val d = node.depth
            val isBool = d < boolVars.size
            val varId = if (isBool) boolVars[d] else intVars[d - boolVars.size]
            val values: List<Long> = if (isBool) listOf(0L, 1L) else valuesOf(problem, varId)

            for (raw in values) {
                if (checks >= config.maxChecks) break
                val childBools = if (isBool) node.bools + (varId to (raw == 1L)) else node.bools
                val childInts = if (isBool) node.ints else node.ints + (varId to raw)
                checks++
                sinceReport++
                val res = solver.solve(checkParams(config, Assumptions(bools = childBools, ints = childInts)))
                when (res) {
                    is SolveResult.Sat ->
                        if (d + 1 == depth) {
                            lower += 1
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

    private fun valuesOf(problem: Problem, intVar: Int): List<Long> {
        val dom = problem.requireFiniteIntDomains()[intVar]
        return List(dom.values.size) { dom.values.valueAt(it) }
    }

    private fun clampToLong(x: Double): Long =
        if (x >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else min(x, Long.MAX_VALUE.toDouble()).toLong()
}
