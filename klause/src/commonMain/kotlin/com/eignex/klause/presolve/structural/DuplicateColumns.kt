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
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.numbers.addExactOrNull
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.toLongExact

internal object DuplicateColumns {

    // Work units per factor, column, term, signature entry or rewrite visit in either lane.
    private const val DUPLICATE_COLUMNS_WORK_WEIGHT = 10L

    private class Interrupted : RuntimeException()

    private fun visit(scan: ScanMeter) {
        if (!scan.visit(DUPLICATE_COLUMNS_WORK_WEIGHT)) throw Interrupted()
    }

    private const val OPEN_PATTERNS = 4

    private const val SPLIT_BOUND_LIMIT = 1L shl 61

    // Integer columns `x` and `y` occurring in exactly the same [Linear] rows with the same coefficient in
    // each merge into `z = x + y` in `x`'s slot, whose domain widens to `[min(x) + min(y), max(x) + max(y)]`.
    // Equal coefficients make `a_f·x + a_f·y` and `a_f·z` the same form, and with both domains contiguous
    // every admitted `z` splits back into a feasible `(x, y)`, so verdict and optimum are preserved.
    //
    // Not merged: objective variables (the objective reads ids directly), holed domains (a split could land
    // in a hole), and variables in any non-[Linear] factor (read value-wise, not as a column coefficient).
    //
    // A class of K duplicates folds in K − 1 batches, each over the representative's domain as widened by the
    // batches before it; reconstruction undoes them last-first, so every split is two-variable and contiguous.
    fun mergeDuplicateColumns(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        sharedIntOcc: SharedIntOccurrence? = null,
        incrementalTouchedVars: IntArray? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta {
        val scan = ScanMeter(cancellation)
        return try {
            mergeFinite(problem, objectiveIntVars, sharedIntOcc, incrementalTouchedVars, scan)
        } catch (_: Interrupted) {
            // Aggregation changes every occurrence together with its reconstruction and widened domain.
            PassDelta()
        } finally {
            scan.flush()
        }
    }

    private fun mergeFinite(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        sharedIntOcc: SharedIntOccurrence?,
        incrementalTouchedVars: IntArray?,
        scan: ScanMeter,
    ): PassDelta {
        if (problem.numIntVars < 2 || !scan.check()) return PassDelta()
        val n = problem.numIntVars
        // A class collapses fully when the pass fires, so a new class can only form on a touched variable;
        // with none column-eligible the full scan would find only singletons.
        if (incrementalTouchedVars != null && sharedIntOcc != null &&
            !anyTouchedColumnEligible(incrementalTouchedVars, sharedIntOcc, problem, objectiveIntVars, scan)
        ) {
            return PassDelta()
        }
        // Folding one duplicate into its representative removes only the dropped column's own term, so no
        // surviving column's signature or eligibility changes: both are computed once over the input.
        val domains = Array(n) {
            visit(scan)
            problem.rootIntDomain(it)
        }
        val eligible = eligibleColumns(problem.factors, n, domains, objectiveIntVars, scan)
        val signatures = columnSignatures(problem.factors, n, eligible, scan)
        // Ascending-id order: the first member of a class is its representative.
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            visit(scan)
            if (!eligible[v]) continue
            val sig = signatures[v] ?: continue
            for (entry in sig) visit(scan)
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val maxClassSize = classes.values.maxOfOrNull {
            visit(scan)
            it.size
        } ?: 0
        if (maxClassSize < 2) return PassDelta()
        // Batch k folds the k-th duplicate of every class into its representative, widening the aggregate's
        // domain to the running Minkowski sum. Batches undo last-first, which makes the split exact.
        val keepOf = IntArray(n) {
            visit(scan)
            it
        }
        val batches = ArrayList<List<ColumnMerge>>()
        for (k in 1 until maxClassSize) {
            val merges = ArrayList<ColumnMerge>()
            for (members in classes.values) {
                visit(scan)
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
            if (merges.isNotEmpty()) batches.add(merges)
        }
        // A declined merge touches no factor, domain or column, so with none accepted the model stays as it is.
        if (batches.isEmpty()) return PassDelta()
        val workFactors = Array(problem.factors.size) {
            visit(scan)
            aggregateColumns(problem.factors[it], keepOf, scan)
        }
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        problem.factors.forEachIndexed { i, f ->
            visit(scan)
            if (workFactors[i] !== f) {
                dropped.add(i)
                added.add(workFactors[i])
            }
        }
        if (!scan.check()) throw Interrupted()
        return PassDelta(dropped.toIntArray(), added, domains, DuplicateColumnMerges(batches)::reconstruct)
    }

    // A source delta can narrow a range but never widen one, so `y` merges only into a representative `r`
    // whose declared range already is the aggregate's: each side open on `r`, or `y` closed at `0` there.
    // The open-side pattern absorbing the most members of a class picks `r`. Holed columns stay out.
    fun mergeSourceDuplicateColumns(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SourceDelta {
        val scan = ScanMeter(cancellation)
        return try {
            mergeSource(problem, objectiveIntVars, scan)
        } catch (_: Interrupted) {
            SourceDelta()
        } finally {
            scan.flush()
        }
    }

    private fun mergeSource(problem: Problem, objectiveIntVars: Set<Int>, scan: ScanMeter): SourceDelta {
        val n = problem.numIntVars
        if (n < 2 || !scan.check()) return SourceDelta()
        val bounds = problem.intBounds
        val eligible = BooleanArray(n) { v ->
            visit(scan)
            v !in objectiveIntVars &&
                problem.declaredIntDomains.declaredOrNull(v)?.isContiguous() != false &&
                (bounds.isOpenLower(v) || fitsSplit(bounds.lower(v))) &&
                (bounds.isOpenUpper(v) || fitsSplit(bounds.upper(v)))
        }
        val factors = problem.factors
        for (f in factors) {
            visit(scan)
            if (f.equivalentLinear { visit(scan) }?.integerConstants != null) continue
            for (v in f.intVars) {
                visit(scan)
                eligible[v] = false
            }
        }
        val signatures = columnSignatures(factors, n, eligible, scan)
        val classes = LinkedHashMap<List<Long>, IntArrayList>()
        for (v in 0 until n) {
            visit(scan)
            val sig = signatures[v] ?: continue
            for (entry in sig) visit(scan)
            classes.getOrPut(sig) { IntArrayList() }.add(v)
        }
        val keepOf = IntArray(n) {
            visit(scan)
            it
        }
        val steps = ArrayList<RebuildStep>()
        for (members in classes.values) {
            visit(scan)
            if (members.size < 2) continue
            val rep = sourceRepresentative(members, bounds, scan) ?: continue
            val folded = IntArrayList()
            for (k in 0 until members.size) {
                visit(scan)
                val y = members[k]
                if (y != rep && absorbs(rep, y, bounds)) folded.add(y)
            }
            for (k in folded.size - 1 downTo 0) {
                visit(scan)
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
            visit(scan)
            val rewritten = aggregateColumns(f, keepOf, scan)
            if (rewritten !== f) {
                dropped.add(i)
                added.add(rewritten)
            }
        }
        if (!scan.check()) throw Interrupted()
        return SourceDelta(dropped.toIntArray(), added, rebuild = SourceRebuilds(steps))
    }

    private fun sourceRepresentative(members: IntArrayList, bounds: IntBounds, scan: ScanMeter): Int? {
        var best: Int? = null
        var bestCount = 0
        val tried = BooleanArray(OPEN_PATTERNS)
        for (k in 0 until members.size) {
            visit(scan)
            val r = members[k]
            val pattern = (if (bounds.isOpenLower(r)) 1 else 0) + (if (bounds.isOpenUpper(r)) 2 else 0)
            if (tried[pattern]) continue
            tried[pattern] = true
            var count = 0
            for (j in 0 until members.size) {
                visit(scan)
                if (members[j] != r && absorbs(r, members[j], bounds)) count++
            }
            if (count > bestCount) {
                best = r
                bestCount = count
            }
        }
        return best
    }

    private fun absorbs(rep: Int, y: Int, bounds: IntBounds): Boolean =
        (bounds.isOpenLower(rep) || (bounds.hasLower(y) && bounds.lower(y) == 0L)) &&
            (bounds.isOpenUpper(rep) || (bounds.hasUpper(y) && bounds.upper(y) == 0L))

    // Recovers `y` before `rep` at the upper end of `[max(lo(y), z − hi(r)), min(hi(y), z − lo(r))]`. With both
    // of those sides open, [absorbs] left `r` open above, so `y` sits at its lower bound, or `0` without one.
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

    // Leaves room for the `Long` evaluator to subtract the bound from an aggregate value.
    private fun fitsSplit(bound: Long): Boolean = bound > -SPLIT_BOUND_LIMIT && bound < SPLIT_BOUND_LIMIT

    // Dropping a duplicate's term is sound because it carries the same coefficient as its representative.
    private fun aggregateColumns(factor: Factor, keepOf: IntArray, scan: ScanMeter): Factor {
        val linear = factor.equivalentLinear { visit(scan) } ?: return factor
        val row = linear.integerConstants ?: return factor
        if (linear.vars.none {
                visit(scan)
                keepOf[it] != it
            }) return factor
        val keptVars = IntArrayList(linear.vars.size)
        val keptCoeffs = LongArrayList(linear.vars.size)
        for (i in linear.vars.indices) {
            visit(scan)
            val v = linear.vars[i]
            if (keepOf[v] != v) continue
            keptVars.add(v)
            keptCoeffs.add(row.coeff(i))
        }
        return Linear(keptCoeffs.toLongArray(), keptVars.toIntArray(), linear.op, row.bound)
    }

    // Same test as [eligibleColumns], read off the occurrence index so a barren re-run costs only the
    // touched variables' occurrences.
    private fun anyTouchedColumnEligible(
        touched: IntArray,
        occ: SharedIntOccurrence,
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        scan: ScanMeter,
    ): Boolean {
        for (v in touched) {
            visit(scan)
            if (v in objectiveIntVars || !problem.rootIntDomain(v).isContiguous()) continue
            val start = occ.offsets[v]
            val end = occ.offsets[v + 1]
            var onlyLinear = true
            for (k in start until end) {
                visit(scan)
                val g = problem.factors[occ.flat[k]]
                if (g.equivalentLinear { visit(scan) }?.integerConstants == null) {
                    onlyLinear = false
                    break
                }
            }
            if (onlyLinear && end > start) return true
        }
        return false
    }

    private fun eligibleColumns(
        factors: Array<Factor>,
        numIntVars: Int,
        domains: Array<IntDomain>,
        objectiveIntVars: Set<Int>,
        scan: ScanMeter,
    ): BooleanArray {
        val eligible = BooleanArray(numIntVars) { v ->
            visit(scan)
            v !in objectiveIntVars && domains[v].isContiguous()
        }
        for (f in factors) {
            visit(scan)
            // Only integer [Linear] columns are aggregatable; a variable in any other factor — including a
            // continuous (real-bearing) Linear, whose reals the integer rewrite would drop — is ineligible.
            if (f.equivalentLinear { visit(scan) }?.integerConstants != null) continue
            for (v in f.intVars) {
                visit(scan)
                eligible[v] = false
            }
        }
        return eligible
    }

    // (factor id, coefficient) pairs sorted by factor id: equal signatures are duplicate columns.
    // Ineligible variables get a null signature and never match.
    private fun columnSignatures(
        factors: Array<Factor>,
        numIntVars: Int,
        eligible: BooleanArray,
        scan: ScanMeter,
    ): Array<List<Long>?> {
        val entries = Array(numIntVars) {
            visit(scan)
            if (eligible[it]) ArrayList<Long>() else null
        }
        factors.forEachIndexed { fid, factor ->
            visit(scan)
            val f = factor.equivalentLinear { visit(scan) } ?: return@forEachIndexed
            val row = f.integerConstants ?: return@forEachIndexed
            val coeffByVar = MutableIntLongMap(f.vars.size)
            for (i in f.vars.indices) {
                visit(scan)
                coeffByVar.put(f.vars[i], row.coeff(i))
            }
            for (v in f.intVars) {
                visit(scan)
                entries[v]?.apply {
                    add(fid.toLong())
                    add(coeffByVar.getOrDefault(v, 0L))
                }
            }
        }
        return Array(numIntVars) {
            visit(scan)
            entries[it]?.takeIf { e -> e.isNotEmpty() }
        }
    }

    private fun IntDomain.isContiguous(): Boolean = holeCount == 0L
}

internal class ColumnMerge(val keep: Int, val drop: Int, val keepDomain: IntDomain, val dropDomain: IntDomain)

/** One batch of the duplicate-column aggregations [Presolve] made, holding the data to split each
 *  aggregate back into its two originals. */
internal class DuplicateColumnMerge(private val merges: List<ColumnMerge>) {
    // Takes the lower end of `x ∈ [max(min(x), z − max(y)), min(max(x), z − min(y))]`, non-empty when both
    // originals are contiguous. Merges in a batch have distinct keep and drop, so their order is immaterial.
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

// Each batch builds on the previous batch's widened representative domain, so splits undo in reverse order.
internal class DuplicateColumnMerges(private val batches: List<List<ColumnMerge>>) {
    fun reconstruct(sample: Sample): Sample {
        var s = sample
        for (batch in batches.asReversed()) s = DuplicateColumnMerge(batch).reconstruct(s)
        return s
    }
}
