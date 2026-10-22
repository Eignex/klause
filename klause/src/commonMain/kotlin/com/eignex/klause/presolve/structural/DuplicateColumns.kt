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
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.toLongExact

internal object DuplicateColumns {

    // Work units per factor, column, term, signature entry or rewrite visit in either lane.
    private const val DUPLICATE_COLUMNS_WORK_WEIGHT = 10L

    private class Interrupted : RuntimeException()

    private fun visit(cancellation: Cancellation) {
        if (cancellation()) throw Interrupted()
        cancellation.charge(DUPLICATE_COLUMNS_WORK_WEIGHT)
        if (cancellation()) throw Interrupted()
    }

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
     * aggregate `z` back into a feasible `(x, y)`: with both domains contiguous, an `x ∈ [min(x), max(x)]`
     * with `y = z − x ∈ [min(y), max(y)]` exists for every admitted `z`, so a solution of the reduced
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
     * A class of K duplicates folds in K − 1 batches, one fold per representative per batch, each over the
     * representative's domain as widened by the batches before it; reconstruction undoes the batches
     * last-first, so every split is a two-variable split over contiguous domains.
     */
    fun mergeDuplicateColumns(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        sharedIntOcc: SharedIntOccurrence? = null,
        incrementalTouchedVars: IntArray? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta = try {
        mergeFinite(problem, objectiveIntVars, sharedIntOcc, incrementalTouchedVars, cancellation)
    } catch (_: Interrupted) {
        // Aggregation changes every occurrence together with its reconstruction and widened domain.
        PassDelta()
    }

    private fun mergeFinite(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        sharedIntOcc: SharedIntOccurrence?,
        incrementalTouchedVars: IntArray?,
        cancellation: Cancellation,
    ): PassDelta {
        if (problem.numIntVars < 2 || cancellation()) return PassDelta()
        val n = problem.numIntVars
        // Inter-round fast-bail. On a re-run the engine supplies the variables whose factors changed since
        // the pass last ran. A duplicate-column class collapses fully when the pass fires, so a new class can
        // only form on a column whose factor membership or a coefficient changed, i.e. a touched variable.
        // If no touched variable is column-eligible the full scan below would find only singleton classes.
        // A null [incrementalTouchedVars] means a full scan.
        if (incrementalTouchedVars != null && sharedIntOcc != null &&
            !anyTouchedColumnEligible(incrementalTouchedVars, sharedIntOcc, problem, objectiveIntVars, cancellation)
        ) {
            return PassDelta()
        }
        // Folding one duplicate into its representative removes only the dropped column's own term, so no
        // surviving column's signature or eligibility changes: both are computed once over the input.
        val domains = Array(n) {
            visit(cancellation)
            problem.rootIntDomain(it)
        }
        val eligible = eligibleColumns(problem.factors, n, domains, objectiveIntVars, cancellation)
        val signatures = columnSignatures(problem.factors, n, eligible, cancellation)
        // Ascending-id order: the first member of a class is its representative.
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            visit(cancellation)
            if (!eligible[v]) continue
            val sig = signatures[v] ?: continue
            for (entry in sig) visit(cancellation)
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val maxClassSize = classes.values.maxOfOrNull {
            visit(cancellation)
            it.size
        } ?: 0
        if (maxClassSize < 2) return PassDelta()
        // Batch k folds the k-th duplicate of every class into its representative, widening the aggregate's
        // domain to the running Minkowski sum. Batches undo last-first, which makes the split exact.
        val keepOf = IntArray(n) {
            visit(cancellation)
            it
        }
        val batches = ArrayList<List<ColumnMerge>>()
        for (k in 1 until maxClassSize) {
            val merges = ArrayList<ColumnMerge>()
            for (members in classes.values) {
                visit(cancellation)
                if (members.size <= k) continue
                val rep = members[0]
                val drop = members[k]
                val keep = domains[rep]
                // The Minkowski sum must be representable: two columns at the open-domain clamp (2^62 each)
                // sum to 2^63, which wraps to Long.MIN_VALUE and would wrongly refute the model. Saturating
                // instead would admit a `z` no `(x, y)` can realise, so the pair is left unmerged.
                val lo = addExactOrNull(keep.min, domains[drop].min) ?: continue
                val hi = addExactOrNull(keep.max, domains[drop].max) ?: continue
                merges.add(ColumnMerge(keep = rep, drop = drop, keepDomain = keep, dropDomain = domains[drop]))
                keepOf[drop] = rep
                // The session reseeds on this widen via [PassDelta.domains].
                domains[rep] = IntDomain(lo, hi)
            }
            batches.add(merges)
        }
        val workFactors = Array(problem.factors.size) {
            visit(cancellation)
            aggregateColumns(problem.factors[it], keepOf, cancellation)
        }
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        problem.factors.forEachIndexed { i, f ->
            visit(cancellation)
            if (workFactors[i] !== f) {
                dropped.add(i)
                added.add(workFactors[i])
            }
        }
        if (cancellation()) throw Interrupted()
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
    ): SourceDelta = try {
        mergeSource(problem, objectiveIntVars, cancellation)
    } catch (_: Interrupted) {
        SourceDelta()
    }

    private fun mergeSource(problem: Problem, objectiveIntVars: Set<Int>, cancellation: Cancellation): SourceDelta {
        val n = problem.numIntVars
        if (n < 2 || cancellation()) return SourceDelta()
        val bounds = problem.intBounds
        val eligible = BooleanArray(n) { v ->
            visit(cancellation)
            v !in objectiveIntVars &&
                problem.declaredIntDomains.declaredOrNull(v)?.isContiguous() != false &&
                (bounds.isOpenLower(v) || fitsSplit(bounds.lower(v))) &&
                (bounds.isOpenUpper(v) || fitsSplit(bounds.upper(v)))
        }
        val factors = problem.factors
        for (f in factors) {
            visit(cancellation)
            if (f.equivalentLinear { visit(cancellation) }?.integerConstants != null) continue
            for (v in f.intVars) {
                visit(cancellation)
                eligible[v] = false
            }
        }
        val signatures = columnSignatures(factors, n, eligible, cancellation)
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            visit(cancellation)
            val sig = signatures[v] ?: continue
            for (entry in sig) visit(cancellation)
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val keepOf = IntArray(n) {
            visit(cancellation)
            it
        }
        val steps = ArrayList<RebuildStep>()
        for (members in classes.values) {
            visit(cancellation)
            if (members.size < 2) continue
            val rep = sourceRepresentative(members, bounds, cancellation) ?: continue
            val folded = IntArrayList()
            for (k in 0 until members.size) {
                visit(cancellation)
                val y = members[k]
                if (y != rep && absorbs(rep, y, bounds)) folded.add(y)
            }
            for (k in folded.size - 1 downTo 0) {
                visit(cancellation)
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
            visit(cancellation)
            val rewritten = aggregateColumns(f, keepOf, cancellation)
            if (rewritten !== f) {
                dropped.add(i)
                added.add(rewritten)
            }
        }
        if (cancellation()) throw Interrupted()
        return SourceDelta(dropped.toIntArray(), added, rebuild = SourceRebuilds(steps))
    }

    /** The member of [members] whose open-side pattern absorbs the most others, or null when none absorbs one. */
    private fun sourceRepresentative(members: IntArrayList, bounds: IntBounds, cancellation: Cancellation): Int? {
        var best: Int? = null
        var bestCount = 0
        val tried = BooleanArray(OPEN_PATTERNS)
        for (k in 0 until members.size) {
            visit(cancellation)
            val r = members[k]
            val pattern = (if (bounds.isOpenLower(r)) 1 else 0) + (if (bounds.isOpenUpper(r)) 2 else 0)
            if (tried[pattern]) continue
            tried[pattern] = true
            var count = 0
            for (j in 0 until members.size) {
                visit(cancellation)
                if (members[j] != r && absorbs(r, members[j], bounds)) count++
            }
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
    private fun aggregateColumns(factor: Factor, keepOf: IntArray, cancellation: Cancellation): Factor {
        val linear = factor.equivalentLinear { visit(cancellation) } ?: return factor
        val row = linear.integerConstants ?: return factor
        if (linear.vars.none {
            visit(cancellation)
            keepOf[it] != it
        }) return factor
        val keptVars = IntArrayList(linear.vars.size)
        val keptCoeffs = LongArrayList(linear.vars.size)
        for (i in linear.vars.indices) {
            visit(cancellation)
            val v = linear.vars[i]
            if (keepOf[v] != v) continue
            keptVars.add(v)
            keptCoeffs.add(row.coeff(i))
        }
        return Linear(keptCoeffs.toLongArray(), keptVars.toIntArray(), linear.op, row.bound)
    }

    /** Whether any variable in [touched] is column-eligible with at least one [Linear] occurrence — the
     *  cheap re-run gate. Eligibility is the same local test [eligibleColumns] applies (not an objective
     *  variable, a contiguous domain, and no non-[Linear] occurrence), read per variable off the session's
     *  occurrence index so a barren re-run costs O(occurrences of touched). A variable in no [Linear]
     *  factor can never match, so it is not counted. */
    private fun anyTouchedColumnEligible(
        touched: IntArray,
        occ: SharedIntOccurrence,
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation,
    ): Boolean {
        for (v in touched) {
            visit(cancellation)
            if (v in objectiveIntVars || !problem.rootIntDomain(v).isContiguous()) continue
            val start = occ.offsets[v]
            val end = occ.offsets[v + 1]
            var onlyLinear = true
            for (k in start until end) {
                visit(cancellation)
                val g = problem.factors[occ.flat[k]]
                if (g.equivalentLinear { visit(cancellation) }?.integerConstants == null) {
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
     *  split must not land in a hole), and the variable is not read by the objective. */
    private fun eligibleColumns(
        factors: Array<Factor>,
        numIntVars: Int,
        domains: Array<IntDomain>,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation,
    ): BooleanArray {
        val eligible = BooleanArray(numIntVars) { v ->
            visit(cancellation)
            v !in objectiveIntVars && domains[v].isContiguous()
        }
        for (f in factors) {
            visit(cancellation)
            // Only integer [Linear] columns are aggregatable; a variable in any other factor — including a
            // continuous (real-bearing) Linear, whose reals the integer rewrite would drop — is ineligible.
            if (f.equivalentLinear { visit(cancellation) }?.integerConstants != null) continue
            for (v in f.intVars) {
                visit(cancellation)
                eligible[v] = false
            }
        }
        return eligible
    }

    /** Canonical column signature of each eligible variable: every [Linear] factor it appears in paired
     *  with its coefficient there, sorted by factor id. Two eligible variables share a signature iff
     *  they occur in exactly the same factors with the same coefficient in each — duplicate columns.
     *  Ineligible variables, and variables in no factor, get a `null` signature and never match. */
    private fun columnSignatures(
        factors: Array<Factor>,
        numIntVars: Int,
        eligible: BooleanArray,
        cancellation: Cancellation,
    ): Array<List<Long>?> {
        val entries = Array(numIntVars) {
            visit(cancellation)
            if (eligible[it]) ArrayList<Long>() else null
        }
        factors.forEachIndexed { fid, factor ->
            visit(cancellation)
            val f = factor.equivalentLinear { visit(cancellation) } ?: return@forEachIndexed
            val row = f.integerConstants ?: return@forEachIndexed
            val coeffByVar = MutableIntLongMap(f.vars.size)
            for (i in f.vars.indices) {
                visit(cancellation)
                coeffByVar.put(f.vars[i], row.coeff(i))
            }
            for (v in f.intVars) {
                visit(cancellation)
                entries[v]?.apply {
                    add(fid.toLong())
                    add(coeffByVar.getOrDefault(v, 0L))
                }
            }
        }
        return Array(numIntVars) {
            visit(cancellation)
            entries[it]?.takeIf { e -> e.isNotEmpty() }
        }
    }

    /** `a + b`, or null when it overflows [Long]. */
    private fun addExactOrNull(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0L) null else r
    }

    private fun IntDomain.isContiguous(): Boolean = holeCount == 0L
}

/** A single duplicate-column aggregation: the surviving aggregate [keep] absorbs [drop]. Both variables'
 *  domains at merge time ([keepDomain], [dropDomain]) split the aggregate value back into a feasible pair. */
internal class ColumnMerge(val keep: Int, val drop: Int, val keepDomain: IntDomain, val dropDomain: IntDomain)

/** One batch of the duplicate-column aggregations [Presolve] made, holding the data to split each
 *  aggregate back into its two originals. */
internal class DuplicateColumnMerge(private val merges: List<ColumnMerge>) {
    /** Recover the dropped variables in a solution [sample] of the reduced problem. The aggregate `keep` holds
     *  `z = x + y`; a feasible split needs `x ∈ [min(x), max(x)]` and `y = z − x ∈ [min(y), max(y)]`,
     *  i.e. `x ∈ [max(min(x), z − max(y)), min(max(x), z − min(y))]`, non-empty for every `z` the
     *  aggregate admits when both originals are contiguous. Take its lower end. Within a batch each merge
     *  has a distinct `keep` and `drop`, so the split order is immaterial. */
    fun reconstruct(sample: Sample): Sample {
        if (merges.isEmpty()) return sample
        val ints = sample.ints
        for (m in merges) {
            val z = bigIntOf(ints[m.keep])
            val lower = maxOf(bigIntOf(m.keepDomain.min), z - bigIntOf(m.dropDomain.max))
            val upper = minOf(bigIntOf(m.keepDomain.max), z - bigIntOf(m.dropDomain.min))
            check(lower <= upper) { "Aggregate has no feasible duplicate-column split" }
            val x = lower.toLongExact()
            val y = (z - lower).toLongExact()
            check(y in m.dropDomain.min..m.dropDomain.max) { "Duplicate-column split is outside its domain" }
            ints[m.keep] = x
            ints[m.drop] = y
        }
        return sample.copy(ints = ints)
    }
}

/**
 * Reconstruction for [DuplicateColumns.mergeDuplicateColumns], which folds a class of duplicate columns over
 * several batches. Each batch's aggregate is built on the previous batch's widened representative domain,
 * so the splits are undone in reverse batch order.
 */
internal class DuplicateColumnMerges(private val batches: List<List<ColumnMerge>>) {
    fun reconstruct(sample: Sample): Sample {
        var s = sample
        for (batch in batches.asReversed()) s = DuplicateColumnMerge(batch).reconstruct(s)
        return s
    }
}
