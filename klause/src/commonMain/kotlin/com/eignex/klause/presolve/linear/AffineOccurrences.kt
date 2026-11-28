package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.presolve.linear.AffineSingletons.AffineCandidate
import com.eignex.klause.presolve.linear.AffineSingletons.CANCEL_POLL_MASK
import com.eignex.klause.presolve.linear.AffineSingletons.FOLD_ABSORB_CAP
import com.eignex.klause.presolve.linear.AffineSingletons.FactorOcc
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

private fun isEqCand(f: Factor?): Boolean = f?.equivalentLinear()?.let { row ->
    row.integerConstants != null && row.op == LinearOp.EQ && row.vars.size >= 2
} == true

private fun aliasOverflowsLong(factor: Factor?, x: Int, replacement: Int): Boolean {
    for (row in factor?.linearRows.orEmpty()) {
        if (row.constants !is IntegerConstants) continue
        var sum = 0L
        for (i in 0 until row.size) {
            val ref = row.ref(i)
            if (!Term.isInt(ref) || (Term.intVar(ref) != x && Term.intVar(ref) != replacement)) continue
            val coefficient = row.coeff(i)
            val next = sum + coefficient
            if (((sum xor next) and (coefficient xor next)) < 0L) return true
            sum = next
        }
    }
    return false
}

private fun eqPivotIds(factors: Array<Factor>): IntArray {
    val ids = IntArrayList(0)
    for (id in factors.indices) if (isEqCand(factors[id])) ids.add(id)
    return ids.toIntArray()
}

private fun lowerBound(eqIds: IntArray, from: Int): Int {
    val index = eqIds.binarySearchInt(from)
    return if (index >= 0) index else -index - 1
}

// The session's occurrence CSR is in stable-id order, so its dense indices are the ids into [factors].
internal class AffineSeedOccurrences(
    private val factors: Array<Factor>,
    private val occ: SharedIntOccurrence,
    private val rewriteStructured: Boolean,
) : FactorOcc {
    override val size: Int get() = factors.size
    override fun factorAt(id: Int): Factor = factors[id]
    override fun degreeOf(x: Int): Int = occ.offsets[x + 1] - occ.offsets[x]

    // Lazy: the touched-variable re-scan never scans by id.
    private val eqIds by lazy { eqPivotIds(factors) }
    override fun nextEqId(from: Int): Int {
        val p = lowerBound(eqIds, from)
        return if (p < eqIds.size) eqIds[p] else factors.size
    }

    override fun occurrenceAt(x: Int, k: Int): Int = occ.flat[occ.offsets[x] + k]

    override fun isContained(defIdx: Int, x: Int): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) if (occ.flat[k] != defIdx) return false
        return true
    }

    override fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            val id = occ.flat[k]
            val f = factors[id]
            // The integer kernel cannot replace a factor whose complete declaration needs real arithmetic.
            if (id != defIdx && affineFoldRow(f, rewriteStructured) == null) return false
        }
        return true
    }

    override fun foldOverflowsLong(
        defIdx: Int,
        x: Int,
        termVars: IntArray,
        termCoeffs: LongArray,
        constTerm: Long,
        cancellation: ExecutionControl,
    ): Boolean {
        var polled = 0
        val termsSmall = termsFitHalfLong(constTerm, termCoeffs)
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            // A bail counts as "would overflow"; skipping the fold is sound.
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
            cancellation.charge(AFFINE_WORK_WEIGHT)
            val id = occ.flat[k]
            val f = affineFoldRow(factors[id], rewriteStructured)
            if (id != defIdx && f != null && foldRowOverflowsLong(
                    f,
                    x,
                    termVars,
                    termCoeffs,
                    constTerm,
                    termsSmall,
                )
            ) {
                return true
            }
        }
        return false
    }

    override fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            val id = occ.flat[k]
            if (id != defIdx && aliasOverflowsLong(factors[id], x, replacement)) return true
        }
        return false
    }

    override fun otherOccurrencesAffineSubstitutable(
        defIdx: Int,
        x: Int,
        scale: Int,
        offset: Int,
        replacement: Int,
        cancellation: ExecutionControl,
    ): Boolean {
        for (k in occ.offsets[x] until occ.offsets[x + 1]) {
            cancellation.charge(AFFINE_WORK_WEIGHT)
            if ((k and CANCEL_POLL_MASK) == 0 && cancellation()) return false
            val id = occ.flat[k]
            if (id == defIdx) continue
            val f = factors[id]
            // Every occurrence must support an exact replacement or its own affine substitution.
            if (!affineConsumerAccepts(f, rewriteStructured, x, scale, offset, replacement, cancellation)) {
                return false
            }
        }
        return !cancellation()
    }

    override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean = false

    override fun promotedCount(): Int = 0

    override fun promotedAt(k: Int): Int = error("the pristine seed promotes nothing")
}

// Factors keyed by stable id: ids `[0, base.size)` are the inputs, appends take the next id, and a dropped id is
// tombstoned and never reused, so id order matches a fresh compacted list. The per-variable occurrence index
// holds one entry per occurrence; its per-list order is irrelevant because every reader tests membership.
internal class AffineWorkingSet(
    base: Array<Factor>,
    nVars: Int,
    seed: SharedIntOccurrence?,
    private val rewriteStructured: Boolean,
) : FactorOcc {
    private val slots = ArrayList<Factor?>(base.size + 1).apply { addAll(base) }
    private val intOcc = Array(nVars) { IntArrayList(0) }

    // A fold can promote a non-candidate into a candidate (`c·x = b` becomes an equality over the definition's
    // partners); those few ids are tracked apart from the pristine [baseEqIds]. Dropped or degraded ids stay and
    // are rejected by the candidate gate. Appended rows are inequalities, so never candidates.
    private val baseEqIds = eqPivotIds(base)
    private val promoted = IntArrayList(0)
    private val promotedSet = IntHashSet()

    private fun inBaseEq(id: Int): Boolean {
        val p = lowerBound(baseEqIds, id)
        return p < baseEqIds.size && baseEqIds[p] == id
    }

    override fun nextEqId(from: Int): Int {
        val p = lowerBound(baseEqIds, from)
        var best = if (p < baseEqIds.size) baseEqIds[p] else slots.size
        for (k in 0 until promoted.size) {
            val id = promoted[k]
            if (id in from until best) best = id
        }
        return best
    }

    // Folds absorbed per stable id, parallel to [slots].
    private val absorbed = IntArrayList(base.size + 1).apply { repeat(base.size) { add(0) } }

    // Per-variable count of at-cap foldable rows mentioning it, so [anyOtherLinearAtAbsorbCap] answers in O(1).
    private val atCapCount = IntArray(nVars)

    private fun isCappable(f: Factor?): Boolean = affineFoldRow(f, rewriteStructured) != null

    init {
        if (seed != null) {
            for (v in 0 until nVars) {
                val occ = intOcc[v]
                for (k in seed.offsets[v] until seed.offsets[v + 1]) occ.add(seed.flat[k])
            }
        } else {
            for (id in base.indices) for (v in base[id].intVars) intOcc[v].add(id)
        }
    }

    override val size: Int get() = slots.size

    override fun factorAt(id: Int): Factor? = slots[id]

    fun liveFactors(): List<Factor> = slots.filterNotNull()

    fun drop(id: Int) {
        val f = slots[id] ?: return
        if (absorbed[id] >= FOLD_ABSORB_CAP && isCappable(f)) for (v in f.intVars) atCapCount[v]--
        for (v in f.intVars) intOcc[v].removeValueSwap(id)
        slots[id] = null
    }

    private fun replace(id: Int, prev: Factor, next: Factor) {
        val was = absorbed[id]
        if (was >= FOLD_ABSORB_CAP && isCappable(prev)) for (v in prev.intVars) atCapCount[v]--
        absorbed[id] = was + 1
        for (v in prev.intVars) intOcc[v].removeValueSwap(id)
        slots[id] = next
        for (v in next.intVars) intOcc[v].add(id)
        if (was + 1 >= FOLD_ABSORB_CAP && isCappable(next)) for (v in next.intVars) atCapCount[v]++
        if (isEqCand(next) && !isEqCand(prev) && !inBaseEq(id) && promotedSet.add(id)) promoted.add(id)
    }

    private fun append(next: Factor) {
        val id = slots.size
        slots.add(next)
        absorbed.add(0)
        for (v in next.intVars) intOcc[v].add(id)
    }

    override fun degreeOf(x: Int): Int = intOcc[x].size

    override fun occurrenceAt(x: Int, k: Int): Int = intOcc[x][k]

    override fun isContained(defIdx: Int, x: Int): Boolean {
        val occ = intOcc[x]
        for (k in 0 until occ.size) if (occ[k] != defIdx) return false
        return true
    }

    override fun otherOccurrencesAllLinear(defIdx: Int, x: Int): Boolean {
        val occ = intOcc[x]
        for (k in 0 until occ.size) {
            val id = occ[k]
            val f = slots[id]
            if (id != defIdx && affineFoldRow(f, rewriteStructured) == null) return false
        }
        return true
    }

    override fun foldOverflowsLong(
        defIdx: Int,
        x: Int,
        termVars: IntArray,
        termCoeffs: LongArray,
        constTerm: Long,
        cancellation: ExecutionControl,
    ): Boolean {
        val occ = intOcc[x]
        var polled = 0
        val termsSmall = termsFitHalfLong(constTerm, termCoeffs)
        for (k in 0 until occ.size) {
            // A bail counts as "would overflow"; skipping the fold is sound.
            if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return true
            cancellation.charge(AFFINE_WORK_WEIGHT)
            val id = occ[k]
            val f = affineFoldRow(slots[id], rewriteStructured)
            if (id != defIdx && f != null && foldRowOverflowsLong(
                    f,
                    x,
                    termVars,
                    termCoeffs,
                    constTerm,
                    termsSmall,
                )
            ) {
                return true
            }
        }
        return false
    }

    override fun aliasOverflowsLong(defIdx: Int, x: Int, replacement: Int): Boolean {
        val occ = intOcc[x]
        for (k in 0 until occ.size) {
            val id = occ[k]
            if (id != defIdx && aliasOverflowsLong(slots[id], x, replacement)) return true
        }
        return false
    }

    override fun otherOccurrencesAffineSubstitutable(
        defIdx: Int,
        x: Int,
        scale: Int,
        offset: Int,
        replacement: Int,
        cancellation: ExecutionControl,
    ): Boolean {
        val occ = intOcc[x]
        for (k in 0 until occ.size) {
            cancellation.charge(AFFINE_WORK_WEIGHT)
            if ((k and CANCEL_POLL_MASK) == 0 && cancellation()) return false
            val id = occ[k]
            if (id == defIdx) continue
            val f = slots[id] ?: continue
            if (!affineConsumerAccepts(f, rewriteStructured, x, scale, offset, replacement, cancellation)) {
                return false
            }
        }
        return !cancellation()
    }

    override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean {
        val total = atCapCount[x]
        if (total == 0) return false
        // `defIdx` mentions `x`, so subtract its own contribution to leave any other at-cap row.
        val self = if (absorbed[defIdx] >= FOLD_ABSORB_CAP && isCappable(slots[defIdx])) 1 else 0
        return total - self > 0
    }

    override fun promotedCount(): Int = promoted.size

    override fun promotedAt(k: Int): Int = promoted[k]

    fun fold(ranges: ColumnRanges, c: AffineCandidate): Int {
        val singlePartner = c.termVars.size == 1
        // Snapshot: [replace] rewrites x's occurrence list as it goes.
        val occ = intOcc[c.x]
        val toRewrite = IntArray(occ.size) { occ[it] }
        var minTouched = c.defIdx
        for (id in toRewrite) if (id < minTouched) minTouched = id
        for (id in toRewrite) {
            if (id == c.defIdx) continue
            val f = slots[id] ?: continue
            val linear = affineFoldRow(f, rewriteStructured)
            val next = when {
                linear != null -> if (linear.intIndexOf(c.x) >= 0) foldAffineIntoRow(linear, c) else f

                // The gate admits this path only for an Int-range scale and offset, so the narrowing is safe.
                singlePartner ->
                    requireNotNull(
                        f.substituteAffine(c.x, c.termCoeffs[0].toInt(), c.constTerm.toInt(), c.termVars[0]),
                    ) {
                        "substituteAffine returned null for a factor accepted by the candidate gate"
                    }

                else -> f
            }
            if (next !== f) replace(id, f, next)
        }
        drop(c.defIdx)
        for (bound in domainBoundsOnTerms(ranges, c)) append(bound)
        // Only the pivot's occurrences change content, so no factor below their minimum can become a candidate.
        return minTouched
    }

    fun alias(problem: Problem, ranges: ColumnRanges, c: AffineCandidate): Int {
        val boolMap = IntArray(problem.numBoolVars) { it }
        val intMap = IntArray(problem.numIntVars) { it }
        val y = c.termVars[0]
        intMap[c.x] = y
        val mapping = VarRemap(boolMap, intMap)
        // A factor holding both `x` and `y` coalesces to one `y`, so `y`'s entries are re-derived after remapping.
        val occX = intOcc[c.x]
        val touched = IntArray(occX.size) { occX[it] }
        var minTouched = c.defIdx
        for (id in touched) if (id < minTouched) minTouched = id
        for (id in touched) {
            if (id == c.defIdx) continue
            val f = slots[id] ?: continue
            // An alias absorbs no fold, but an at-cap row's count moves from x to y with its [intOcc] entry.
            val atCap = absorbed[id] >= FOLD_ABSORB_CAP && isCappable(f)
            val remapped = f.remap(mapping)
            slots[id] = remapped
            intOcc[y].removeValueSwap(id)
            val hasY = remapped.intVars.contains(y)
            if (hasY) intOcc[y].add(id)
            if (atCap) {
                if (f.intVars.contains(y)) atCapCount[y]--
                if (hasY) atCapCount[y]++
            }
        }
        intOcc[c.x].clear()
        // Drop first: it removes the x=y row's own at-cap y-contribution.
        drop(c.defIdx)
        atCapCount[c.x] = 0
        for (bound in domainBoundsOnTerms(ranges, c)) append(bound)
        // As in [fold]; relies on the remap above being occurrence-scoped.
        return minTouched
    }
}

// Construction re-coalesces overlapping terms and keeps a reifying output in both directions.
private fun foldAffineIntoRow(l: LinearRow, c: AffineCandidate): Factor {
    val row = l.constants as IntegerConstants
    val ix = l.intIndexOf(c.x)
    val cX = row.coeff(ix)
    val newVars = IntArray(l.size - 1 + c.termVars.size)
    // The candidate gate declined any pivot whose fold would overflow, so the products below are safe.
    val newCoeffs = LongArray(newVars.size)
    var w = 0
    for (j in 0 until l.size) {
        if (j == ix) continue
        newVars[w] = Term.intVar(l.ref(j))
        newCoeffs[w] = row.coeff(j)
        w++
    }
    for (k in c.termVars.indices) {
        newVars[w] = c.termVars[k]
        newCoeffs[w] = cX * c.termCoeffs[k]
        w++
    }
    val bound = row.bound - cX * c.constTerm
    return if (l.activator == LinearRow.ALWAYS) Linear(newCoeffs, newVars, l.relation, bound)
    else ReifiedLinear(l.activator, newCoeffs, newVars, l.relation, bound)
}

private fun domainBoundsOnTerms(ranges: ColumnRanges, c: AffineCandidate): List<Factor> {
    val rows = ArrayList<Factor>(2)
    // One row per side the model bounds, and only where the terms do not already imply it. An open side has no
    // bound to carry over, and inventing one would constrain the partners by an endpoint the model never gave.
    if (ranges.hasUpper(c.x)) {
        val bound = ranges.max(c.x) - c.constTerm
        if (termActivity(ranges, c, upper = true)?.let { it > bound } != false) {
            rows.add(Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.LE, bound))
        }
    }
    if (ranges.hasLower(c.x)) {
        val bound = ranges.min(c.x) - c.constTerm
        if (termActivity(ranges, c, upper = false)?.let { it < bound } != false) {
            rows.add(Linear(c.termCoeffs.copyOf(), c.termVars.copyOf(), LinearOp.GE, bound))
        }
    }
    return rows
}

// Extreme activity of the terms over their own ranges; null when unbounded or past Long. A redundant bound row
// makes a fold that removes one equality leave two rows behind, so rows are skipped when provably implied.
private fun termActivity(ranges: ColumnRanges, c: AffineCandidate, upper: Boolean): Long? {
    var total = 0L
    try {
        for (k in c.termVars.indices) {
            val v = c.termVars[k]
            val a = c.termCoeffs[k]
            if (a == 0L) continue
            val takeUpper = (a > 0L) == upper
            if (if (takeUpper) !ranges.hasUpper(v) else !ranges.hasLower(v)) return null
            total = addExact(total, mulExact(a, if (takeUpper) ranges.max(v) else ranges.min(v)))
        }
    } catch (_: CheckedLongOverflowException) {
        return null
    }
    return total
}
private fun affineFoldRow(factor: Factor?, reified: Boolean): LinearRow? {
    if (reified && factor is ReifiedLinear) {
        return if (factor.integerConstants != null) factor.linearRows.single() else null
    }
    return factor?.equivalentLinear()?.takeIf { it.integerConstants != null }
}

private fun affineConsumerAccepts(
    factor: Factor,
    rewriteStructured: Boolean,
    x: Int,
    scale: Int,
    offset: Int,
    replacement: Int,
    cancellation: ExecutionControl,
): Boolean {
    if (affineFoldRow(factor, rewriteStructured) != null) return true
    if (factor is ComparisonClause) {
        return rewriteStructured && factor.canSubstituteAffine(x, scale, offset, cancellation)
    }
    return factor.canSubstituteAffine(x, scale, offset, replacement)
}

private fun LinearRow.intIndexOf(variable: Int): Int {
    for (index in 0 until size) if (Term.intVar(ref(index)) == variable) return index
    return -1
}

private fun foldRowOverflowsLong(
    f: LinearRow,
    x: Int,
    termVars: IntArray,
    termCoeffs: LongArray,
    constTerm: Long,
    termsSmall: Boolean,
): Boolean {
    // When the pivot's terms and this row's magnitudes are below 2^31, no product reaches 2^62 and no sum 2^63;
    // `|cX| ≤ maxAbsCoeff`, so the fast path needs no `indexOf`.
    val row = f.constants as? IntegerConstants ?: return true
    if (termsSmall && fitsHalfLong(row.bound) && fitsHalfLong(row.maxAbsCoeff)) return false
    val xi = f.intIndexOf(x)
    if (xi < 0) return false
    val cX = row.coeff(xi)
    return try {
        subExact(row.bound, mulExact(cX, constTerm))
        for (k in termVars.indices) {
            val prod = mulExact(cX, termCoeffs[k])
            val yi = f.intIndexOf(termVars[k])
            if (yi >= 0) addExact(row.coeff(yi), prod)
        }
        false
    } catch (_: CheckedLongOverflowException) {
        true
    }
}

private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

// Computed once per pivot rather than once per rewritten row.
private fun termsFitHalfLong(constTerm: Long, termCoeffs: LongArray): Boolean {
    if (!fitsHalfLong(constTerm)) return false
    for (c in termCoeffs) if (!fitsHalfLong(c)) return false
    return true
}
