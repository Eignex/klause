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
 * the interval collapses to the exact count. Mass accounting uses exact integers; an upper bound
 * beyond the [Long] range is reported as the unbounded sentinel [Long.MAX_VALUE]. The returned
 * [Count]s are emitted roughly every [ExactCountConfig.reportEvery] checks, so iterating the
 * sequence tightens on demand.
 */
internal object AnytimeCounter {

    private class Node(val depth: Int, val bools: Map<Int, Boolean>, val ints: Map<Int, Long>)

    fun run(problem: BakedProblem, config: ExactCountConfig): Sequence<Count> = sequence {
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
                bigIntOf(domain.max) - bigIntOf(domain.min) + BIG_ONE - bigIntOf(domain.holeCount)
            }
            suffix[d] = sizeAtD * suffix[d + 1]
        }

        val solver = BacktrackSolver(problem)
        var checks = 0L
        var lower = 0L
        var openMass = BIG_ZERO // Includes unchecked children of a partially expanded node.
        var undecidedMass = BIG_ZERO

        fun snapshot(): Count {
            val upperMass = bigIntOf(lower) + openMass + undecidedMass
            val exact = openMass.isZero() && undecidedMass.isZero()
            val upper = clampToLong(upperMass)
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
        if (depth > 0 && !openMass.isZero()) stack.addLast(Node(0, emptyMap(), emptyMap()))
        yield(snapshot())

        var sinceReport = 0L
        while (stack.isNotEmpty() && checks < config.maxChecks) {
            val node = stack.removeLast()
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
                // A check replaces only this child's share; unchecked siblings retain their mass.
                openMass -= suffix[d + 1]
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

    private fun valuesOf(problem: BakedProblem, intVar: Int): List<Long> {
        val span = problem.rootIntDomain(intVar).span()
        return List(span.size) { span.valueAt(it) }
    }

    private fun clampToLong(mass: BigInt): Long =
        if (mass.fitsLong()) mass.toLongExact() else Long.MAX_VALUE
}
