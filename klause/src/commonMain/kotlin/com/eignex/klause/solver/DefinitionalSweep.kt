package com.eignex.klause.solver

import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear

/**
 * The topologically-ordered functional definitions of a FlatZinc model — every
 * `:: defines_var(V)`-annotated constraint whose shape the [FunctionalObjective.Node]
 * evaluator can mirror exactly, over the *whole* model rather than just the objective cone.
 *
 * Why this exists: MiniZinc decompositions routinely make most of a model definitional
 * (fast-food/ff1: 229 of 230 constraints define aux vars — abs/min channels feeding a sum).
 * A local search that treats those as ordinary hard constraints must hand-repair the DAG one
 * move at a time: measured on ff1 and prize-collecting, CBLS needs ~2M flips to walk a random
 * assignment to within one violated factor of feasible — far beyond a competition wall-clock
 * budget. Sweeping instead *evaluates* every defined var bottom-up from the free (decision)
 * variables, so a freshly randomized assignment starts at the "only real constraints violated"
 * frontier at the cost of one pass.
 *
 * Soundness: a computed value is clamped into the variable's domain. When clamping changes the
 * value (the random inputs imply an out-of-domain intermediate), that definitional factor simply
 * remains violated and the search repairs it locally — the sweep never fabricates feasibility,
 * it only fast-forwards the part of the repair the definitions determine.
 */
class DefinitionalSweep internal constructor(
    /** Defining nodes in topological order — every node's inputs are free vars or earlier nodes. */
    private val nodes: List<FunctionalObjective.Node>,
) {
    /** Number of swept definitions. */
    val size: Int get() = nodes.size

    /** Var ids written by the sweep (the defined, non-decision vars). */
    val definedVars: IntArray = IntArray(nodes.size) { nodes[it].out }

    /**
     * Evaluate every defined var bottom-up from the current values in [assignment], clamping
     * each result into its domain — then evaluate the **reification aux bools**: decompositions
     * (abs / min / element channels) lower to `aux ↔ (Σ c·x op b)` Tseitin factors, and the aux
     * is just as definitional as the int DAG, so each aux in [factors] is set to the actual
     * truth of its linear (skipping [frozenBool] vars). Pure evaluation on both halves; factors
     * *reading* an aux stay subject to ordinary search. Callers must recompute incremental
     * solver state afterwards.
     */
    fun sweep(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor> = emptyArray(),
        frozenBool: (Int) -> Boolean = { false },
    ) {
        for (n in nodes) {
            val computed = n.compute { id -> assignment.intValue(id).toLong() }
            assignment.setInt(n.out, domains[n.out].clampLong(computed))
        }
        for (f in factors) {
            if (f !is ReifiedLinear) continue
            if (frozenBool(f.auxBoolVar)) continue
            var sum = 0L
            for (k in f.vars.indices) sum += f.coeffs[k].toLong() * assignment.intValue(f.vars[k])
            val holds = when (f.op) {
                LinearOp.LE -> sum <= f.bound
                LinearOp.GE -> sum >= f.bound
                LinearOp.EQ -> sum == f.bound.toLong()
                LinearOp.NE -> sum != f.bound.toLong()
            }
            assignment.setBool(f.auxBoolVar, holds)
        }
    }
}
