package com.eignex.klause.solver

import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear

/**
 * The topologically-ordered functional definitions of a FlatZinc model — every
 * `:: defines_var(V)`-annotated constraint whose shape a [SweepNode] can mirror exactly, over
 * the *whole* model rather than just the objective cone. Definitions cover both value spaces:
 * int DAGs (abs / min / linear aux chains) via [SweepNode.IntDef], and the bool-shaped
 * definitions decompositions lean on — comparison reifications, `bool2int` channels, literal
 * set membership, bool conjunction/disjunction — plus variable-index element access.
 *
 * Why this exists: MiniZinc decompositions routinely make most of a model definitional
 * (fast-food/ff1: 229 of 230 constraints; prize-collecting: 596 of 668). A local search that
 * treats those as ordinary hard constraints must hand-repair the DAG one move at a time:
 * measured on both instances, CBLS needs ~2M flips to walk a random assignment to within one
 * violated factor of feasible — far beyond a competition wall-clock budget. Sweeping instead
 * *evaluates* every defined var bottom-up from the free (decision) variables, so a freshly
 * randomized assignment starts at the "only real constraints violated" frontier at the cost of
 * one pass.
 *
 * Soundness: computed int values are clamped into the variable's domain, and an element access
 * with an out-of-range index leaves the output untouched. In both cases the affected
 * definitional factor simply remains violated and the search repairs it locally — the sweep
 * never fabricates feasibility, it only fast-forwards the part of the repair the definitions
 * determine.
 */
class DefinitionalSweep internal constructor(
    /** Defining nodes in topological order — every node's inputs are free vars or earlier nodes. */
    private val nodes: List<SweepNode>,
) {
    /** One evaluable definition; [apply] writes the defined var from current values. */
    sealed interface SweepNode {
        /** Write the defined var from the current values in [assignment]. */
        fun apply(assignment: Assignment, domains: Array<IntDomain>)

        /** An int definition from the functional-objective node algebra (abs / min / max /
         *  times / plus / lin_eq), domain-clamped. */
        class IntDef internal constructor(private val node: FunctionalObjective.Node) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                val v = node.compute { id -> assignment.intValue(id).toLong() }
                assignment.setInt(node.out, domains[node.out].clampLong(v))
            }
        }

        /** `out ↔ (Σ coeffs·vars ⟨op⟩ rhs)` — every `int_*_reif` / `int_lin_*_reif` shape as a
         *  linear comparison over int vars; strict forms are normalized into LE/GE at build. */
        class CmpReif(
            /** The defined output var id. */
            val out: Int,
            private val coeffs: LongArray,
            private val vars: IntArray,
            private val rhs: Long,
            private val op: LinearOp,
        ) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                var sum = 0L
                for (k in vars.indices) sum += coeffs[k] * assignment.intValue(vars[k])
                val holds = when (op) {
                    LinearOp.LE -> sum <= rhs
                    LinearOp.GE -> sum >= rhs
                    LinearOp.EQ -> sum == rhs
                    LinearOp.NE -> sum != rhs
                }
                assignment.setBool(out, holds)
            }
        }

        /** `out ↔ (x ∈ values)` for a constant literal set ([values] sorted ascending). */
        class SetInReif(
            /** The defined output var id. */
            val out: Int,
            private val x: Int,
            private val values: IntArray,
        ) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                val v = assignment.intValue(x)
                var lo = 0
                var hi = values.size - 1
                var found = false
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val m = values[mid]
                    if (m == v) {
                        found = true
                        break
                    } else if (m < v) {
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                assignment.setBool(out, found)
            }
        }

        /** `out = bool2int(b)`. */
        class Bool2Int(
            /** The defined output var id. */
            val out: Int,
            private val b: Int,
        ) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                assignment.setInt(out, domains[out].clampLong(if (assignment.boolValue(b)) 1L else 0L))
            }
        }

        /** `out ↔ ⋀ ins` / `out ↔ ⋁ ins` over bool *literals* (polarity-encoded via [Lit], the
         *  FlatZinc compiler's bool-array element form — negated members are legal). */
        class BoolFold(
            /** The defined output var id. */
            val out: Int,
            private val ins: IntArray,
            private val isAnd: Boolean,
        ) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                var acc = isAnd
                for (lit in ins) {
                    val raw = assignment.boolValue(Lit.variable(lit))
                    val v = if (Lit.isPositive(lit)) raw else !raw
                    acc = if (isAnd) acc && v else acc || v
                    if (acc != isAnd) break
                }
                assignment.setBool(out, acc)
            }
        }

        /** `out = arr[idx − offset]` over a var array ([arrVars]) or constant table ([arrConsts]).
         *  An out-of-range index leaves the output untouched (its factor stays violated). */
        class ElementDef(
            /** The defined output var id. */
            val out: Int,
            private val idx: Int,
            private val arrVars: IntArray?,
            private val arrConsts: IntArray?,
            private val offset: Int,
        ) : SweepNode {
            override fun apply(assignment: Assignment, domains: Array<IntDomain>) {
                val pos = assignment.intValue(idx) - offset
                val vars = arrVars
                val consts = arrConsts
                val v = when {
                    vars != null && pos in vars.indices -> assignment.intValue(vars[pos]).toLong()
                    consts != null && pos >= 0 && pos < consts.size -> consts[pos].toLong()
                    else -> return // out-of-range index: leave the output for the search
                }
                assignment.setInt(out, domains[out].clampLong(v))
            }
        }
    }

    /** Number of swept definitions. */
    val size: Int get() = nodes.size

    /**
     * Evaluate every defined var bottom-up from the current values in [assignment] — then
     * evaluate the remaining **reification aux bools**: klause's own lowering introduces
     * `aux ↔ (Σ c·x op b)` Tseitin factors outside the annotation graph, and the aux is just as
     * definitional, so each aux in [factors] is set to the actual truth of its linear (skipping
     * [frozenBool] vars; consistent with any [SweepNode.CmpReif] result since both evaluate the
     * same final int values). Pure evaluation on both halves; factors *reading* a defined var
     * stay subject to ordinary search. Callers must recompute incremental solver state
     * afterwards.
     */
    fun sweep(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor> = emptyArray(),
        frozenBool: (Int) -> Boolean = { false },
    ) {
        for (n in nodes) n.apply(assignment, domains)
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
