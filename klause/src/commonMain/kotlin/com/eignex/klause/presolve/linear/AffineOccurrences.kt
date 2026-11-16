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

/** Whether [f] could head an affine or residue pivot: a [Linear] equality of arity >= 2. */
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

/** Stable ids (ascending) of the pristine [factors] that could ever head an affine/residue pivot. */
private fun eqPivotIds(factors: Array<Factor>): IntArray {
    val ids = IntArrayList(0)
    for (id in factors.indices) if (isEqCand(factors[id])) ids.add(id)
    return ids.toIntArray()
}

/** Index of the first entry of the ascending [eqIds] that is `>= from` (`eqIds.size` if none). */
private fun lowerBound(eqIds: IntArray, from: Int): Int {
    val index = eqIds.binarySearchInt(from)
    return if (index >= 0) index else -index - 1
}

/**
 * A read-only [FactorOcc] over the pristine input [factors] and the session's [SharedIntOccurrence],
 * whose CSR is in stable-id order so its dense indices *are* the ids into [factors]. Before any fold
 * the working set equals this, so the first candidate scan reads it directly — a fruitless pass (no
 * candidate) returns without ever building the mutable [AffineWorkingSet]'s O(occurrences) index.
 */
internal class AffineSeedOccurrences(
    private val factors: Array<Factor>,
    private val occ: SharedIntOccurrence,
    private val rewriteStructured: Boolean,
) : FactorOcc {
    override val size: Int get() = factors.size
    override fun factorAt(id: Int): Factor = factors[id]
    override fun degreeOf(x: Int): Int = occ.offsets[x + 1] - occ.offsets[x]

    // Built lazily: the incremental touched-variable path never scans by id, so a fruitless re-run
    // that returns early over the seed pays nothing for it.
    private val eqIds by lazy { eqPivotIds(factors) }
    override fun nextEqId(from: Int): Int {
        val p = lowerBound(eqIds, from)
        return if (p < eqIds.size) eqIds[p] else factors.size
    }

    /** The stable id of the [k]-th factor mentioning [x] (`0 until degreeOf(x)`) — lets the
     *  touched-variable re-scan visit only the factors a changed variable appears in. */
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
            // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
            // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
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

    // The seed is the pristine input before any fold, so no row has absorbed one yet.
    override fun anyOtherLinearAtAbsorbCap(defIdx: Int, x: Int): Boolean = false

    // The seed is the pristine input, so nothing has been rewritten and nothing can have been promoted.
    override fun promotedCount(): Int = 0

    override fun promotedAt(k: Int): Int = error("the pristine seed promotes nothing")
}

/**
 * The working factor set for one [AffineSingletons.eliminateAffineSingletons] call, keyed by a stable id.
 * Ids `[0, base.size)` are the pristine inputs; each appended factor takes the next id. A dropped id is
 * tombstoned (its slot becomes `null`) and never reused, so a live factor keeps its id — and hence
 * its position in stable-id order — for the whole run. The candidate scan walks the slots in id
 * order, which is exactly the order a fresh compacted list would present.
 *
 * Alongside the slots it holds a per-variable occurrence index over the stable ids of the
 * live factors whose `Factor.intVars` contains a variable, one entry per occurrence (mirroring a CSR
 * rebuild's multiset). It is maintained across eliminations: a fold rewrites only the factors that
 * mention the pivot, so only those ids' occurrence entries move. Its per-list order is irrelevant —
 * every reader (`isContained`, `otherOccurrences*`) tests membership, not position.
 */
internal class AffineWorkingSet(
    base: Array<Factor>,
    nVars: Int,
    seed: SharedIntOccurrence?,
    private val rewriteStructured: Boolean,
) : FactorOcc {
    private val slots = ArrayList<Factor?>(base.size + 1).apply { addAll(base) }
    private val intOcc = Array(nVars) { IntArrayList(0) }

    // The pivot-candidate ids (a live [Linear] equality of arity >= 2) the scan may visit, letting it
    // skip the inequality/global bulk. [baseEqIds] is the pristine set, ascending. A fold does not only
    // shrink the set: [foldAffineIntoLinear] substitutes a pivot's multi-term definition into a shorter
    // equality that mentions it (e.g. `c·x = b` becomes an equality over the definition's partners),
    // *promoting* an id that was not a candidate into one. Those promoted ids are tracked separately
    // (there are only ever a handful — bounded by the folds' fan-out); dropped or degraded ids are left
    // in place and re-rejected by the candidate gate. Appended rows are inequalities and can only be
    // rewritten into inequalities, so no appended id is ever a candidate.
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

    // Folds absorbed by each stable id (parallel to [slots]): incremented whenever [replace] rewrites it,
    // read by [anyOtherLinearAtAbsorbCap] to cap a row that has become a fold sink. Appended rows start at 0.
    private val absorbed = IntArrayList(base.size + 1).apply { repeat(base.size) { add(0) } }

    // Per-variable count of at-cap cappable rows mentioning it, maintained alongside [intOcc] in [replace]
    // and [drop]. Lets [anyOtherLinearAtAbsorbCap] answer in O(1) instead of rescanning the occurrence list
    // of every candidate pivot. Only admitted word-sized rows are counted, matching that predicate.
    private val atCapCount = IntArray(nVars)

    private fun isCappable(f: Factor?): Boolean = affineFoldRow(f, rewriteStructured) != null

    init {
        // The seed's CSR is over the pristine input in input (= stable-id) order, so its dense indices
        // are the stable ids; adopt them directly instead of re-scanning every factor's [intVars].
        if (seed != null) {
            for (v in 0 until nVars) {
                val occ = intOcc[v]
                for (k in seed.offsets[v] until seed.offsets[v + 1]) occ.add(seed.flat[k])
            }
        } else {
            for (id in base.indices) for (v in base[id].intVars) intOcc[v].add(id)
        }
    }

    /** Number of stable slots (live plus tombstoned); the upper bound of the candidate scan. */
    override val size: Int get() = slots.size

    /** The factor at stable id [id], or `null` if it was dropped. */
    override fun factorAt(id: Int): Factor? = slots[id]

    /** The live (non-tombstoned) factors in stable-id order — the working constraint set. */
    fun liveFactors(): List<Factor> = slots.filterNotNull()

    /** Tombstone stable id [id], removing its occurrence entries. */
    fun drop(id: Int) {
        val f = slots[id] ?: return
        if (absorbed[id] >= FOLD_ABSORB_CAP && isCappable(f)) for (v in f.intVars) atCapCount[v]--
        for (v in f.intVars) intOcc[v].removeValueSwap(id)
        slots[id] = null
    }

    /** Replace live stable id [id] — currently holding [prev] — with [next], moving its occurrence
     *  entries from [prev]'s variables to [next]'s and recording that it absorbed a fold. */
    private fun replace(id: Int, prev: Factor, next: Factor) {
        val was = absorbed[id]
        if (was >= FOLD_ABSORB_CAP && isCappable(prev)) for (v in prev.intVars) atCapCount[v]--
        absorbed[id] = was + 1
        for (v in prev.intVars) intOcc[v].removeValueSwap(id)
        slots[id] = next
        for (v in next.intVars) intOcc[v].add(id)
        if (was + 1 >= FOLD_ABSORB_CAP && isCappable(next)) for (v in next.intVars) atCapCount[v]++
        // A rewrite that turns a non-candidate row into a candidate equality promotes it into the scan;
        // record it (unless the pristine set already lists it, or it is already tracked).
        if (isEqCand(next) && !isEqCand(prev) && !inBaseEq(id) && promotedSet.add(id)) promoted.add(id)
    }

    /** Append [next] as a fresh stable id and record its occurrences. */
    private fun append(next: Factor) {
        val id = slots.size
        slots.add(next)
        absorbed.add(0)
        for (v in next.intVars) intOcc[v].add(id)
    }

    /** Number of live factors mentioning [x] — the fan-out a fold on [x] would rewrite. */
    override fun degreeOf(x: Int): Int = intOcc[x].size

    override fun occurrenceAt(x: Int, k: Int): Int = intOcc[x][k]

    /** Whether [x] occurs in no factor other than [defIdx]. */
    override fun isContained(defIdx: Int, x: Int): Boolean {
        val occ = intOcc[x]
        for (k in 0 until occ.size) if (occ[k] != defIdx) return false
        return true
    }

    /** Whether every factor other than [defIdx] that mentions [x] is a [Linear] (foldable). */
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
            // A high-degree pivot's overflow check walks every row it mentions; poll so one candidate's
            // check cannot outrun the budget. Treat a bail as "would overflow" — skipping the fold is sound.
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

    /** Whether every factor other than [defIdx] that mentions [x] can take the substitution
     *  `x = scale·replacement + offset`: a [Linear] folds it directly, any other factor must opt in
     *  via `Factor.substituteAffine` (a global that can represent the affine view). */
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

    /**
     * Fold `x = constTerm + Σ termCoeffs·termVars` out of the working set, dropping the defining
     * equality [c].`defIdx` and rewriting only the factors that mention `x` in place, then appending
     * the domain-bound rows. The def id is tombstoned last so the occurrence scan below still sees it.
     */
    fun fold(ranges: ColumnRanges, c: AffineCandidate): Int {
        val singlePartner = c.termVars.size == 1
        // Snapshot the occurrence ids of `x` before mutating them: [replace] rewrites x's occurrence
        // list as it goes, and the def id must be skipped rather than folded into itself.
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

                // Single-partner affine into a global the gate accepted (non-null substitute). The
                // gate only admits this path for an Int-range scale/offset, so the narrowing is safe.
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
        // The lowest stable id whose candidacy this fold can newly establish: only the rewritten
        // factors (the pivot's occurrences) change content, so no factor below their minimum can
        // become a candidate. The candidate scan resumes from here instead of restarting at 0.
        return minTouched
    }

    /**
     * Apply the alias rename `x → y` via `Factor.remap` (any factor type) to the live factors that
     * mention `x`, dropping the defining equality and appending the domain-bound rows. Only `x`'s
     * occurrences migrate to `y`, so the occurrence index is patched, not rebuilt.
     */
    fun alias(problem: Problem, ranges: ColumnRanges, c: AffineCandidate): Int {
        val boolMap = IntArray(problem.numBoolVars) { it }
        val intMap = IntArray(problem.numIntVars) { it }
        val y = c.termVars[0]
        intMap[c.x] = y
        val mapping = VarRemap(boolMap, intMap)
        // Snapshot the ids that mention `x` before remapping — their occurrence entries for `x` and
        // `y` are re-derived from the remapped factor's own `Factor.intVars` below (a factor holding
        // both `x` and `y` coalesces to a single `y`, matching what a fresh CSR rebuild would list).
        val occX = intOcc[c.x]
        val touched = IntArray(occX.size) { occX[it] }
        var minTouched = c.defIdx
        for (id in touched) if (id < minTouched) minTouched = id
        // Remap only the factors that mention `x`: the rename map is identity except `x → y`, so a
        // factor without `x` remaps to an equal object — leaving it untouched is content-identical and
        // keeps the pass O(occurrences of `x`) instead of O(live factors) per alias.
        for (id in touched) {
            if (id == c.defIdx) continue
            val f = slots[id] ?: continue
            // An alias is not a fold (absorbed is unchanged), but it renames x→y, so an at-cap row moves
            // its at-cap count from x to y in lockstep with its [intOcc] entry (below). x is eliminated,
            // so its count is cleared wholesale afterwards.
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
        // drop first (it removes the x=y row's own at-cap y-contribution), then zero x wholesale — x is
        // eliminated, so any remaining x count is dead.
        drop(c.defIdx)
        atCapCount[c.x] = 0
        for (bound in domainBoundsOnTerms(ranges, c)) append(bound)
        // Only the rewritten factors (`x`'s occurrences, renamed to `y`) change content, so — as in
        // [fold] — no factor below their minimum can newly become a candidate; the scan resumes from
        // here instead of restarting at 0. This relies on the remap above being occurrence-scoped.
        return minTouched
    }
}

/** [l] with `x` replaced by `constTerm + Σ A_j·y_j`: drop `x`'s term, add `coeff_x·A_j` to each
 *  term var `y_j`, shift the bound by `−coeff_x·constTerm`. Construction re-coalesces overlapping
 *  terms and preserves a reifying output in both directions. */
private fun foldAffineIntoRow(l: LinearRow, c: AffineCandidate): Factor {
    val row = l.constants as IntegerConstants
    val ix = l.intIndexOf(c.x)
    val cX = row.coeff(ix)
    val newVars = IntArray(l.size - 1 + c.termVars.size)
    // Long throughout: both [l]'s coefficients and the fold candidate's are wide-capable, and the
    // candidate gate declined any pivot whose fold would overflow, so the products below are safe.
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

/** Bounds on the term vars enforcing that `x = constTerm + Σ termCoeffs·termVars` stays within the
 *  extent [ranges] gives `x`, one row per side it bounds. */
private fun domainBoundsOnTerms(ranges: ColumnRanges, c: AffineCandidate): List<Factor> {
    val rows = ArrayList<Factor>(2)
    // One row per side the model actually bounds, and only where the terms do not already imply it.
    // A column open above states no upper row: there is no bound on `x` to carry over to its terms,
    // and inventing one would constrain the partners by an endpoint the model never gave.
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

/**
 * The largest ([upper]) or smallest value `Σ termCoeffs·termVars` can take over the term columns'
 * own ranges, or null when it is unbounded that way or the sum leaves `Long`.
 *
 * A bound row exists to keep `x` inside its extent once `x` itself is gone. Where the terms cannot
 * reach past that extent on their own the row states nothing, and emitting it anyway is how a fold
 * that removes one equality leaves two rows behind — the growth that makes elimination cost more
 * than it saves. Null is the safe answer: the row is emitted whenever redundancy is not provable.
 */
private fun termActivity(ranges: ColumnRanges, c: AffineCandidate, upper: Boolean): Long? {
    var total = 0L
    try {
        for (k in c.termVars.indices) {
            val v = c.termVars[k]
            val a = c.termCoeffs[k]
            if (a == 0L) continue
            // The endpoint that drives the sum the way this side asks for.
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

/** Whether folding `x = constTerm + Σ termCoeffs·termVars` into the Linear row [f] would overflow
 *  64-bit arithmetic in the shifted bound or any folded coefficient. Each product/sum runs through the
 *  checked helpers, so an overflow surfaces as [CheckedLongOverflowException]; a `true` result lets the caller
 *  leave `x` un-eliminated (sound) rather than wrap a coefficient. */
private fun foldRowOverflowsLong(
    f: LinearRow,
    x: Int,
    termVars: IntArray,
    termCoeffs: LongArray,
    constTerm: Long,
    termsSmall: Boolean,
): Boolean {
    // Fast path, no `indexOf`: when the pivot's own terms are all below 2^31 ([termsSmall], hoisted by the
    // caller) and this row's magnitudes are too, no product `cX·c` reaches 2^62 and no sum reaches 2^63 —
    // `|cX| ≤ maxAbsCoeff`, so `cX` itself is never needed. The exact path runs only when some magnitude
    // is huge.
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

/** True when `|v| < 2^31`, so a product of two such values stays below 2^62 and a sum below 2^63 — the
 *  bound the overflow fast-path in [foldRowOverflowsLong] relies on. */
private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

/** Whether the fold's constant and every partner coefficient fit half-Long — the pivot-global half of the
 *  [foldRowOverflowsLong] fast path, computed once per pivot rather than once per rewritten row. */
private fun termsFitHalfLong(constTerm: Long, termCoeffs: LongArray): Boolean {
    if (!fitsHalfLong(constTerm)) return false
    for (c in termCoeffs) if (!fitsHalfLong(c)) return false
    return true
}

// Work units per scanned factor, tested term or folded fill-in entry, relative to one simplex operation.
