package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.MutableIntIntMap

internal object DuplicateColumns {

    /**
     * Duplicate-column aggregation. The row side already deduplicates identical constraints
     * ([RedundantConstraints]); this is its column-side mirror. Two integer variables `x` and `y`
     * are *duplicate columns* when they occur in exactly the same [Linear] factors with the **same
     * coefficient** in each — `a_f·x` and `a_f·y` for the same `a_f` in every row `f`, and neither
     * elsewhere. Then every row sees `a_f·x + a_f·y = a_f·(x + y)`, so the pair can be replaced by a
     * single aggregate `z = x + y`: in each such row, drop `y`'s term and leave `x`'s coefficient
     * `a_f` standing for `a_f·z`, then widen `x`'s domain to the Minkowski sum
     * `[min(x) + min(y), max(x) + max(y)]`. The model loses one variable and each coupled term pair
     * collapses to one.
     *
     * **Soundness.** Equal coefficients in every shared row make `a_f·x + a_f·y` and `a_f·z` the same
     * linear form, so dropping `y`'s term while keeping `x`'s coefficient yields a row with identical
     * activity to the original pair whenever `z = x + y`. Widening to the Minkowski sum admits exactly
     * the reachable aggregate values. Reconstruction ([DuplicateColumnMerge.reconstruct]) splits the
     * aggregate `z` back into a feasible `(x, y)`: with both declared domains contiguous, an
     * `x ∈ [min(x), max(x)]` with
     * `y = z − x ∈ [min(y), max(y)]` exists for every admitted `z`, so a solution of the reduced
     * problem maps to a solution of the original — the SAT verdict and the optimum are preserved.
     *
     * **What is *not* merged** (each would break the argument above):
     *  - A variable the objective reads ([objectiveIntVars]); the objective names variable ids
     *    directly and the engine optimises over the presolved problem, so folding one objective
     *    variable into another would silently rewrite the objective. Mirrors [AffineSingletons].
     *  - A non-contiguous (holed) domain on either side: the split could land in a hole, so the
     *    aggregate would admit a `z` neither original pair can realise.
     *  - A variable occurring in any non-[Linear] factor: a global / reified row needs it as a genuine
     *    variable and reads it value-wise, not as a column coefficient.
     *  - A pair whose coefficient differs in any row, or whose row support differs (a row holding `x`
     *    but not `y` makes `y`'s coefficient there `0 ≠ a_f`): the columns are then not equal and the
     *    aggregate would not reproduce the rows.
     *
     * At most one duplicate is folded into each representative per pass; a third duplicate column
     * re-signs against the widened aggregate and is picked up on a later round, keeping every merge a
     * two-variable aggregate over *declared* domains that the contiguous split reconstructs exactly.
     */
    fun mergeDuplicateColumns(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        bakeConfig: BakeConfig = BakeConfig.NONE,
    ): DuplicateColumnMerge {
        if (problem.numIntVars < 2) return DuplicateColumnMerge(problem, emptyList())
        val eligible = eligibleColumns(problem, objectiveIntVars)
        val signatures = columnSignatures(problem, eligible)
        // Group eligible variables by column signature; equal signatures are duplicate columns. Fold
        // at most one duplicate into each representative this pass (a third re-signs next round).
        val repBySignature = HashMap<List<Long>, Int>()
        val repConsumed = HashSet<Int>()
        val merges = ArrayList<ColumnMerge>()
        for (v in 0 until problem.numIntVars) {
            if (!eligible[v]) continue
            val sig = signatures[v] ?: continue
            val rep = repBySignature[sig]
            if (rep == null) {
                repBySignature[sig] = v
            } else if (rep !in repConsumed) {
                merges.add(
                    ColumnMerge(
                        keep = rep,
                        drop = v,
                        keepDomain = problem.intDomains[rep],
                        dropDomain = problem.intDomains[v],
                    ),
                )
                repConsumed.add(rep)
            }
        }
        if (merges.isEmpty()) return DuplicateColumnMerge(problem, emptyList())

        val keepOf = IntArray(problem.numIntVars) { it } // drop → its aggregate representative
        val domains = problem.intDomains.copyOf()
        for (m in merges) {
            keepOf[m.drop] = m.keep
            val keep = domains[m.keep]
            domains[m.keep] = IntDomain(keep.min + m.dropDomain.min, keep.max + m.dropDomain.max)
        }
        val factors = problem.factors.map { aggregateColumns(it, keepOf) }
        return DuplicateColumnMerge(
            PresolveShared.rebuildProblem(problem, factors, domains, bakeConfig = bakeConfig),
            merges,
        )
    }

    /** [factor] with every dropped duplicate column folded into its representative: in a [Linear] row,
     *  remove each dropped variable's term and keep the representative's coefficient standing for the
     *  aggregate (sound because a duplicate column carries the *same* coefficient as its representative
     *  in that row). A row mentioning no dropped variable, and every non-[Linear] factor (none mention
     *  a dropped variable — they were ineligible), is returned unchanged. */
    private fun aggregateColumns(factor: Factor, keepOf: IntArray): Factor {
        if (factor !is Linear) return factor
        if (factor.vars.none { keepOf[it] != it }) return factor
        val keptVars = ArrayList<Int>(factor.vars.size)
        val keptCoeffs = ArrayList<Int>(factor.vars.size)
        for (i in factor.vars.indices) {
            val v = factor.vars[i]
            if (keepOf[v] != v) continue // a dropped duplicate: its term is absorbed by the representative's
            keptVars.add(v)
            keptCoeffs.add(factor.coeffs[i])
        }
        return Linear(keptCoeffs.toIntArray(), keptVars.toIntArray(), factor.op, factor.bound)
    }

    /** Per-variable eligibility: every occurrence is a [Linear] factor (a global / reified row reads a
     *  variable value-wise, not as a column coefficient), the domain is contiguous (the reconstruction
     *  split must not land in a hole), and the variable is not read by the objective. */
    private fun eligibleColumns(problem: Problem, objectiveIntVars: Set<Int>): BooleanArray {
        val eligible = BooleanArray(problem.numIntVars) { v ->
            v !in objectiveIntVars && problem.intDomains[v].isContiguous()
        }
        for (f in problem.factors) {
            if (f is Linear) continue
            for (v in f.intVars) eligible[v] = false
        }
        return eligible
    }

    /** Canonical column signature of each eligible variable: every [Linear] factor it appears in paired
     *  with its coefficient there, sorted by factor id. Two eligible variables share a signature iff
     *  they occur in exactly the same factors with the same coefficient in each — duplicate columns.
     *  Ineligible variables, and variables in no factor, get a `null` signature and never match. */
    private fun columnSignatures(problem: Problem, eligible: BooleanArray): Array<List<Long>?> {
        val entries = Array(problem.numIntVars) { if (eligible[it]) ArrayList<Long>() else null }
        problem.factors.forEachIndexed { fid, f ->
            if (f !is Linear) return@forEachIndexed
            val coeffByVar = MutableIntIntMap(f.vars.size)
            for (i in f.vars.indices) coeffByVar.put(f.vars[i], f.coeffs[i])
            for (v in f.intVars) {
                entries[v]?.apply {
                    add(fid.toLong())
                    add(coeffByVar.getOrDefault(v, 0).toLong())
                }
            }
        }
        return Array(problem.numIntVars) { entries[it]?.takeIf { e -> e.isNotEmpty() } }
    }

    private fun IntDomain.isContiguous(): Boolean = size == max - min + 1
}

/** A single duplicate-column aggregation: the surviving aggregate [keep] absorbs [drop]. Both
 *  variables' *declared* domains ([keepDomain], [dropDomain]) are needed to split the aggregate value
 *  back into a feasible pair at reconstruction. */
internal class ColumnMerge(val keep: Int, val drop: Int, val keepDomain: IntDomain, val dropDomain: IntDomain)

/**
 * Reduced problem from [Presolve.mergeDuplicateColumns] plus the data to split each aggregate
 * variable back into its two originals. Solve [problem], then pass the solution through
 * [reconstruct] to recover a solution of the original problem.
 */
class DuplicateColumnMerge internal constructor(
    /** The problem with duplicate columns aggregated. */
    val problem: Problem,
    private val merges: List<ColumnMerge>,
) {
    /** Recover the dropped variables in a solution [sample] of [problem]. The aggregate `keep` holds
     *  `z = x + y`; a feasible split needs `x ∈ [min(x), max(x)]` and `y = z − x ∈ [min(y), max(y)]`,
     *  i.e. `x ∈ [max(min(x), z − max(y)), min(max(x), z − min(y))]`, non-empty for every `z` the
     *  aggregate admits when both originals are contiguous. Take its lower end. Each merge has a
     *  distinct `keep` and `drop` (no `keep` is another merge's `drop` this pass), so the split order
     *  is immaterial. */
    fun reconstruct(sample: Sample): Sample {
        if (merges.isEmpty()) return sample
        val ints = sample.ints.copyOf()
        for (m in merges) {
            val z = ints[m.keep]
            val x = maxOf(m.keepDomain.min, z - m.dropDomain.max)
            ints[m.keep] = x
            ints[m.drop] = z - x
        }
        return Sample(sample.bools, ints)
    }
}
