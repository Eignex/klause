package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap

internal object DuplicateColumns {

    // Work charged per factor scanned, scaled by its arity: the source form reads each row twice.
    private const val DUPLICATE_COLUMNS_WORK_WEIGHT = 10L

    private const val OPEN_PATTERNS = 4

    private const val SPLIT_BOUND_LIMIT = 1L shl 61

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
     *    variable into another would silently rewrite the objective. Mirrors affine elimination.
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
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        sharedIntOcc: SharedIntOccurrence? = null,
        incrementalTouchedVars: IntArray? = null,
    ): PassDelta {
        if (problem.numIntVars < 2) return PassDelta()
        val n = problem.numIntVars
        // Inter-round fast-bail. On a re-run the engine supplies the variables whose factors changed since
        // dup-columns last ran. A duplicate-column class collapses fully when the pass fires (every member
        // but the representative is folded away, leaving pairwise-distinct signatures), so a *new* class can
        // only form on a column whose factor membership or a coefficient changed — i.e. a touched variable.
        // If no touched variable is column-eligible the pass is fruitless this firing, byte-identical to the
        // full scan below (which would re-derive the same all-singleton classes). Only a re-run passes a
        // non-null [incrementalTouchedVars]; the first firing (and the fresh path) scans in full.
        if (incrementalTouchedVars != null && sharedIntOcc != null &&
            !anyTouchedColumnEligible(incrementalTouchedVars, sharedIntOcc, problem, objectiveIntVars)
        ) {
            return PassDelta()
        }
        // Column duplication is a structural (row-support + coefficient) property, and folding one duplicate
        // into its representative removes only the dropped column's own term — every surviving column stays
        // in the same factors with the same coefficient, so no column's signature or eligibility changes.
        // Eligibility and signatures are therefore computed once over the input rather than re-derived after
        // each fold: a class of K identical columns collapses in a single O(n) scan instead of K of them,
        // which is what let numbrix (a ~3950-column class) spend seconds re-scanning 8505 variables per pair.
        val domains = problem.rootIntDomains()
        val eligible = eligibleColumns(problem.factors, n, domains, objectiveIntVars)
        val signatures = columnSignatures(problem.factors, n, eligible)
        // Group eligible columns by signature in ascending-id order; the first is the representative.
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            if (!eligible[v]) continue
            val sig = signatures[v] ?: continue
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val maxClassSize = classes.values.maxOfOrNull { it.size } ?: 0
        if (maxClassSize < 2) return PassDelta()
        // Fold in per-round batches: round k folds the k-th duplicate of every class into its representative
        // (one fold per representative per round), widening the aggregate's domain to the running Minkowski
        // sum. Batches undo last-first, which is what makes the reconstruction split exact.
        val keepOf = IntArray(n) { it } // drop → its aggregate representative
        val batches = ArrayList<List<ColumnMerge>>() // in application order; reconstruction undoes them last-first
        for (k in 1 until maxClassSize) {
            val merges = ArrayList<ColumnMerge>()
            for (members in classes.values) {
                if (members.size <= k) continue
                val rep = members[0]
                val drop = members[k]
                val keep = domains[rep]
                // The Minkowski sum must be representable: two columns pinned at the open-domain clamp
                // (2^62 each) sum to 2^63, which wraps to Long.MIN_VALUE — a well-formed aggregate at the
                // wrong value, so the model is declared UNSAT with nothing to show the bound was lost.
                // Saturating instead would admit a `z` no `(x, y)` can realise, which reconstruction
                // could not split, so the pair is left unmerged.
                val lo = addExactOrNull(keep.min, domains[drop].min) ?: continue
                val hi = addExactOrNull(keep.max, domains[drop].max) ?: continue
                merges.add(ColumnMerge(keep = rep, drop = drop, keepDomain = keep, dropDomain = domains[drop]))
                keepOf[drop] = rep
                // Widen the aggregate to the Minkowski sum; the session reseeds on this widen via [PassDelta.domains].
                domains[rep] = IntDomain(lo, hi)
            }
            batches.add(merges)
        }
        val workFactors = Array(problem.factors.size) { aggregateColumns(problem.factors[it], keepOf) }
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

    /**
     * The source form, over declared ranges, merging only into a representative whose range already is
     * the aggregate's.
     *
     * A source delta can narrow a range but never widen one, so the aggregate `z = r + y` has to fit the
     * representative `r` as declared. A side of the Minkowski sum is open when either column is open
     * there and `r`'s bound plus `y`'s otherwise, so it equals `r`'s side exactly when `r` is open there
     * or `y` is closed at `0`. Which members a representative absorbs depends only on which of its sides
     * are open, so the pattern absorbing the most members of a class picks it.
     *
     * A column declaring holes stays out, as in the finite form: a split could land in one. Because each
     * fold leaves `r`'s range unchanged, every split reads the declared bounds, and folds into the same
     * representative are undone last first.
     */
    fun mergeSourceDuplicateColumns(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceDelta {
        val n = problem.numIntVars
        if (n < 2) return SourceDelta()
        val bounds = problem.intBounds
        val eligible = BooleanArray(n) { v ->
            v !in objectiveIntVars &&
                problem.declaredIntDomains.declaredOrNull(v)?.isContiguous() != false &&
                (bounds.isOpenLower(v) || fitsSplit(bounds.lower(v))) &&
                (bounds.isOpenUpper(v) || fitsSplit(bounds.upper(v)))
        }
        val factors = problem.factors
        for (f in factors) {
            cancellation.charge(DUPLICATE_COLUMNS_WORK_WEIGHT * (1L + f.intVars.size))
            if (f.equivalentLinear()?.integerConstants != null) continue
            for (v in f.intVars) eligible[v] = false
        }
        val signatures = columnSignatures(factors, n, eligible)
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            val sig = signatures[v] ?: continue
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val keepOf = IntArray(n) { it }
        val steps = ArrayList<RebuildStep>()
        for (members in classes.values) {
            if (members.size < 2) continue
            val rep = sourceRepresentative(members, bounds) ?: continue
            val folded = IntArrayList()
            for (k in 0 until members.size) {
                val y = members[k]
                if (y != rep && absorbs(rep, y, bounds)) folded.add(y)
            }
            for (k in folded.size - 1 downTo 0) {
                val y = folded[k]
                keepOf[y] = rep
                steps.add(splitStep(rep, y, bounds))
                steps.add(RebuildStep.AffineValue(rep, 0L, intArrayOf(rep, y), longArrayOf(1L, -1L), 1L))
            }
        }
        if (steps.isEmpty()) return SourceDelta()
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        factors.forEachIndexed { i, f ->
            val rewritten = aggregateColumns(f, keepOf)
            if (rewritten !== f) {
                dropped.add(i)
                added.add(rewritten)
            }
        }
        return SourceDelta(dropped.toIntArray(), added, rebuild = SourceRebuilds(steps))
    }

    /** The member of [members] whose open-side pattern absorbs the most others, or null when none absorbs one. */
    private fun sourceRepresentative(members: IntArrayList, bounds: IntBounds): Int? {
        var best: Int? = null
        var bestCount = 0
        val tried = BooleanArray(OPEN_PATTERNS)
        for (k in 0 until members.size) {
            val r = members[k]
            val pattern = (if (bounds.isOpenLower(r)) 1 else 0) + (if (bounds.isOpenUpper(r)) 2 else 0)
            if (tried[pattern]) continue
            tried[pattern] = true
            var count = 0
            for (j in 0 until members.size) if (members[j] != r && absorbs(r, members[j], bounds)) count++
            if (count > bestCount) {
                best = r
                bestCount = count
            }
        }
        return best
    }

    /** Whether `rep + y` spans exactly [rep]'s declared range: each side open on [rep], or closed at `0` on [y]. */
    private fun absorbs(rep: Int, y: Int, bounds: IntBounds): Boolean =
        (bounds.isOpenLower(rep) || (bounds.hasLower(y) && bounds.lower(y) == 0L)) &&
            (bounds.isOpenUpper(rep) || (bounds.hasUpper(y) && bounds.upper(y) == 0L))

    /**
     * The step recovering [y] from the aggregate `z` held in [rep]'s slot, before [rep] itself is.
     *
     * A split needs `y ∈ [max(lo(y), z − hi(r)), min(hi(y), z − lo(r))]`, non-empty for every `z` in the
     * Minkowski sum; its upper end is taken. With both of those sides open, [absorbs] has left `r` open
     * above too, so `r` takes whatever `y` does and `y` sits at its lower bound, or `0` when it has none.
     */
    private fun splitStep(rep: Int, y: Int, bounds: IntBounds): RebuildStep = when {
        bounds.hasLower(rep) -> RebuildStep.QuotientValue(
            y,
            -bounds.lower(rep),
            intArrayOf(rep),
            longArrayOf(1L),
            1L,
            roundDown = true,
            clamp = if (bounds.hasUpper(y)) bounds.upper(y) else null,
        )

        bounds.hasUpper(y) -> RebuildStep.AffineValue(y, bounds.upper(y), IntArray(0), LongArray(0), 1L)

        else -> {
            val atLower = if (bounds.hasLower(y)) bounds.lower(y) else 0L
            RebuildStep.AffineValue(y, atLower, IntArray(0), LongArray(0), 1L)
        }
    }

    /** Whether a declared bound leaves room for the `Long` evaluator to subtract it from an aggregate value. */
    private fun fitsSplit(bound: Long): Boolean = bound > -SPLIT_BOUND_LIMIT && bound < SPLIT_BOUND_LIMIT

    /** [factor] with every dropped duplicate column folded into its representative: in a [Linear] row,
     *  remove each dropped variable's term and keep the representative's coefficient standing for the
     *  aggregate (sound because a duplicate column carries the *same* coefficient as its representative
     *  in that row). A row mentioning no dropped variable, and every non-[Linear] factor (none mention
     *  a dropped variable — they were ineligible), is returned unchanged. */
    private fun aggregateColumns(factor: Factor, keepOf: IntArray): Factor {
        val linear = factor.equivalentLinear() ?: return factor
        val row = linear.integerConstants ?: return factor
        if (linear.vars.none { keepOf[it] != it }) return factor
        val keptVars = IntArrayList(linear.vars.size)
        val keptCoeffs = LongArrayList(linear.vars.size)
        for (i in linear.vars.indices) {
            val v = linear.vars[i]
            if (keepOf[v] != v) continue // a dropped duplicate: its term is absorbed by the representative's
            keptVars.add(v)
            keptCoeffs.add(row.coeff(i))
        }
        return Linear(keptCoeffs.toLongArray(), keptVars.toIntArray(), linear.op, row.bound)
    }

    /** Whether any variable in [touched] is column-eligible with at least one [Linear] occurrence — the
     *  cheap re-run gate. Eligibility is the same local test [eligibleColumns] applies (not an objective
     *  variable, a contiguous domain, and no non-[Linear] occurrence), read here per variable off the
     *  session's occurrence index so a barren re-run costs O(occurrences of touched) rather than the
     *  O(all occurrences) full signature build. A variable in no [Linear] factor gets a `null` signature
     *  in the full scan and can never match, so it is not counted here. */
    private fun anyTouchedColumnEligible(
        touched: IntArray,
        occ: SharedIntOccurrence,
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
    ): Boolean {
        for (v in touched) {
            if (v in objectiveIntVars || !problem.rootIntDomain(v).isContiguous()) continue
            val start = occ.offsets[v]
            val end = occ.offsets[v + 1]
            var onlyLinear = true
            for (k in start until end) {
                val g = problem.factors[occ.flat[k]]
                if (g.equivalentLinear()?.integerConstants == null) {
                    onlyLinear = false
                    break
                }
            }
            if (onlyLinear && end > start) return true
        }
        return false
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
            // Only integer [Linear] columns are aggregatable; a variable in any other factor — including a
            // continuous (real-bearing) Linear, whose reals the integer rewrite would drop — is ineligible.
            if (f.equivalentLinear()?.integerConstants != null) continue
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
        factors.forEachIndexed { fid, factor ->
            val f = factor.equivalentLinear() ?: return@forEachIndexed
            val row = f.integerConstants ?: return@forEachIndexed
            val coeffByVar = MutableIntLongMap(f.vars.size)
            for (i in f.vars.indices) coeffByVar.put(f.vars[i], row.coeff(i))
            for (v in f.intVars) {
                entries[v]?.apply {
                    add(fid.toLong())
                    add(coeffByVar.getOrDefault(v, 0L))
                }
            }
        }
        return Array(numIntVars) { entries[it]?.takeIf { e -> e.isNotEmpty() } }
    }

    /** `a + b`, or null when it overflows [Long]. Overflow iff the operands share a sign the sum does not. */
    private fun addExactOrNull(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0L) null else r
    }

    private fun IntDomain.isContiguous(): Boolean = holeCount == 0L
}

/** A single duplicate-column aggregation: the surviving aggregate [keep] absorbs [drop]. Both
 *  variables' *declared* domains ([keepDomain], [dropDomain]) are needed to split the aggregate value
 *  back into a feasible pair at reconstruction. */
internal class ColumnMerge(val keep: Int, val drop: Int, val keepDomain: IntDomain, val dropDomain: IntDomain)

/**
 * The duplicate-column aggregations [Presolve] made, holding the data to split
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
        return sample.copy(ints = ints)
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
