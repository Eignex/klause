package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
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
        @Suppress("UNUSED_PARAMETER") sharedIntOcc: SharedIntOccurrence? = null,
    ): PassDelta {
        if (problem.numIntVars < 2) return PassDelta()
        val n = problem.numIntVars
        // Iterate to the pass's own fixpoint: a chain of ≥ 3 duplicate columns folds one pair per
        // representative, and the next iteration re-signs the third against the widened aggregate. Doing
        // this internally instead of firing once per round and leaning on the round engine to re-invoke
        // collapses the chain in a single pass — so the other passes are not re-run over each intermediate
        // state. Column duplication is a structural (row-support + coefficient) property, so re-propagation
        // between the old rounds could not change which columns are duplicates; the fixpoint reached here
        // is the one the round engine reached across rounds, in the same pairwise order.
        var workFactors: Array<Factor> = problem.factors
        val domains = problem.intDomains.copyOf()
        val batches = ArrayList<List<ColumnMerge>>() // in application order; reconstruction undoes them last-first
        while (true) {
            val eligible = eligibleColumns(workFactors, n, domains, objectiveIntVars)
            val signatures = columnSignatures(workFactors, n, eligible)
            // Group eligible variables by column signature; equal signatures are duplicate columns. Fold
            // at most one duplicate into each representative this iteration (a third re-signs next iteration).
            val repBySignature = HashMap<List<Long>, Int>()
            val repConsumed = HashSet<Int>()
            val merges = ArrayList<ColumnMerge>()
            for (v in 0 until n) {
                if (!eligible[v]) continue
                val sig = signatures[v] ?: continue
                val rep = repBySignature[sig]
                if (rep == null) {
                    repBySignature[sig] = v
                } else if (rep !in repConsumed) {
                    merges.add(ColumnMerge(keep = rep, drop = v, keepDomain = domains[rep], dropDomain = domains[v]))
                    repConsumed.add(rep)
                }
            }
            if (merges.isEmpty()) break
            val keepOf = IntArray(n) { it } // drop → its aggregate representative
            for (m in merges) {
                keepOf[m.drop] = m.keep
                val keep = domains[m.keep]
                // Widen the aggregate to the Minkowski sum; the session must reseed on this widen, so it
                // flows through [PassDelta.domains].
                domains[m.keep] = IntDomain(keep.min + m.dropDomain.min, keep.max + m.dropDomain.max)
            }
            workFactors = Array(workFactors.size) { aggregateColumns(workFactors[it], keepOf) }
            batches.add(merges)
        }
        if (batches.isEmpty()) return PassDelta()
        // Delta by identity against the input: a slot whose final rewrite differs from the input factor is
        // a drop+add; an untouched slot contributes nothing.
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        problem.factors.forEachIndexed { i, f ->
            if (workFactors[i] !== f) {
                dropped.add(i)
                added.add(workFactors[i])
            }
        }
        return PassDelta(dropped.toIntArray(), added, domains, DuplicateColumnMerges(batches)::reconstruct)
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
     *  split must not land in a hole), and the variable is not read by the objective. The factor list is
     *  scanned once to mark every variable a non-[Linear] factor mentions. */
    private fun eligibleColumns(
        factors: Array<Factor>,
        numIntVars: Int,
        domains: Array<IntDomain>,
        objectiveIntVars: Set<Int>,
    ): BooleanArray {
        val eligible = BooleanArray(numIntVars) { v ->
            v !in objectiveIntVars && domains[v].isContiguous()
        }
        for (f in factors) {
            if (f is Linear) continue
            for (v in f.intVars) eligible[v] = false
        }
        return eligible
    }

    /** Canonical column signature of each eligible variable: every [Linear] factor it appears in paired
     *  with its coefficient there, sorted by factor id. Two eligible variables share a signature iff
     *  they occur in exactly the same factors with the same coefficient in each — duplicate columns.
     *  Ineligible variables, and variables in no factor, get a `null` signature and never match. */
    private fun columnSignatures(factors: Array<Factor>, numIntVars: Int, eligible: BooleanArray): Array<List<Long>?> {
        val entries = Array(numIntVars) { if (eligible[it]) ArrayList<Long>() else null }
        factors.forEachIndexed { fid, f ->
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
        return Array(numIntVars) { entries[it]?.takeIf { e -> e.isNotEmpty() } }
    }

    private fun IntDomain.isContiguous(): Boolean = size == max - min + 1
}

/** A single duplicate-column aggregation: the surviving aggregate [keep] absorbs [drop]. Both
 *  variables' *declared* domains ([keepDomain], [dropDomain]) are needed to split the aggregate value
 *  back into a feasible pair at reconstruction. */
internal class ColumnMerge(val keep: Int, val drop: Int, val keepDomain: IntDomain, val dropDomain: IntDomain)

/**
 * The duplicate-column aggregations [Presolve.mergeDuplicateColumns] made, holding the data to split
 * each aggregate variable back into its two originals. Pass a solution of the reduced problem through
 * [reconstruct] to recover a solution of the original.
 */
internal class DuplicateColumnMerge(private val merges: List<ColumnMerge>) {
    /** Recover the dropped variables in a solution [sample] of the reduced problem. The aggregate `keep` holds
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

/**
 * Reconstruction for a fixpoint run of [DuplicateColumns.mergeDuplicateColumns], which folds a chain of
 * duplicate columns over several internal iterations. Each iteration's aggregate is built on the previous
 * iteration's (possibly already-widened) representative domain, so the splits must be undone in reverse
 * iteration order — the same last-batch-first order the round engine produced when it re-invoked the pass
 * once per iteration and composed the per-round reconstructions with `foldRight`.
 */
internal class DuplicateColumnMerges(private val batches: List<List<ColumnMerge>>) {
    fun reconstruct(sample: Sample): Sample {
        var s = sample
        for (batch in batches.asReversed()) s = DuplicateColumnMerge(batch).reconstruct(s)
        return s
    }
}
