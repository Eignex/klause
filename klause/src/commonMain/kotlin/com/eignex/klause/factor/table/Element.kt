package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray

/**
 * `result = arr(idx)` — the element constraint, native to local search rather than a
 * per-index reified-linear + indicator-clause decomposition (which would explode one
 * constraint into ~5·len factors and 2·len aux bools and give CBLS no gradient).
 *
 * [arr] is either a constant table ([arrIsVars] = false; entries are literal values) or an
 * array of int-var ids ([arrIsVars] = true; the element value is the *current value* of the
 * selected var). [idx] is `[indexOffset]`-based — `1` for MiniZinc's default, so position 0
 * of [arr] is selected by `idx = 1`.
 *
 * Stateless (no payload): every query reads the live assignment, O(1) for the selected
 * element. Graded violation `|result − arr(idx)|` (run through `compressViolation`) gives a
 * descent gradient that pushes `result` toward the selected element (or the element toward
 * `result`); an out-of-range `idx` is graded by its distance back into range. Repair moves
 * snap `result` to the selected element, snap the selected element to `result`, or re-point
 * `idx` at a position whose value already equals `result`.
 */
class Element private constructor(
    /** Index variable id. */
    val idx: Int,
    /** Result variable id (`result = arr(idx - indexOffset)`). */
    val result: Int,
    /** The indexed array: variable ids (stored as [Long], each fits [Int]) when [arrIsVars], else
     *  constant values (may span the full [Long] range). */
    val arr: LongArray,
    /** Whether [arr] holds variable ids (true) or constants (false). */
    val arrIsVars: Boolean,
    /** Integer representing index 0 of [arr]. */
    val indexOffset: Int,
    /** Cached array-key fragment carried from a factor over the *same* [arr] (a constant-array
     *  [remap], which leaves [arr] untouched); `null` forces a fresh computation when [arr] differs. */
    cachedArrKey: LongArray?,
) : Factor {

    constructor(idx: Int, result: Int, arr: LongArray, arrIsVars: Boolean, indexOffset: Int = 1) :
        this(idx, result, arr, arrIsVars, indexOffset, null)

    init {
        require(arr.isNotEmpty()) { "element: empty array" }
    }

    // The array part of the key is invariant under a *constant*-array remap (only idx/result move), so
    // it is computed once and carried across those remaps — keeping the O(|arr|) encode of a large
    // constant table (a crossword dictionary) out of symmetry refinement's per-round hot path. A
    // variable-array remap rewrites [arr], so its key is recomputed (cache not forwarded).
    private var cachedArrKey: LongArray? = cachedArrKey

    private fun arrKey(): LongArray = cachedArrKey ?: StructuralKey.words {
        long(arr.size.toLong())
        for (x in arr) long(x)
    }.also { cachedArrKey = it }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = if (arrIsVars) {
        // Entries are var ids stored as Long; remap through the Int var map and restore to Long.
        Element(
            intMap[idx],
            intMap[result],
            LongArray(arr.size) { intMap[arr[it].toInt()].toLong() },
            arrIsVars,
            indexOffset,
            null,
        )
    } else {
        Element(intMap[idx], intMap[result], arr, arrIsVars, indexOffset, arrKey())
    }

    // Affine substitution `idx = replacement + offset` (a pure shift, scale 1) folds into [indexOffset]:
    // reading `arr(idx − indexOffset)` becomes `arr(replacement − (indexOffset − offset))`. Only the
    // index in this shift form is representable; a scaled index would reindex the array, and the result
    // / array-variable roles read a value directly, so those decline. Requires `x` to appear solely as
    // the index (no double role) so the rewrite removes every occurrence.
    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? =
        if (x == idx && scale == 1 && x != result && (!arrIsVars || x.toLong() !in arr)) {
            Element(replacement, result, arr, arrIsVars, indexOffset - offset)
        } else {
            null
        }

    // Positional: the array is ordered (idx selects by position), so the key keeps array order
    // rather than sorting. Encodes every distinguishing field — array kind, offset, idx, result,
    // and the ordered array (var ids when [arrIsVars], else constant values) — so two non-equivalent
    // Elements never collide (a coarser key would let symmetry verification accept a false swap).
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.ELEMENT) {
        bool(arrIsVars)
        int(indexOffset)
        int(idx)
        int(result)
        words(arrKey())
    }

    // No remapValues override (value symmetry stays blocked when an Element is present, #536): the
    // value-symmetry verifier relabels a factor's value *constants* and compares keys, but `idx` is a
    // *variable* whose value selects which constant is read. A swap of two idx positions leaves the
    // constant array unchanged, so the verifier would (wrongly) accept it as a value symmetry. Since
    // remapValues only sees the factor, not idx's domain, it can't tell positions from values — so it
    // must conservatively return `null` (the default). Regular/Mdd are sound because their seq values
    // *are* the relabelable symbols, with no such positional coupling.

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        // When arrIsVars the entries are var ids stored as Long; narrow each back to an Int var id.
        if (arrIsVars) intArrayOf(idx, result) + IntArray(arr.size) { arr[it].toInt() } else intArrayOf(idx, result)

    // A constant array is embedded in the key but is not part of [intVars], so its size must be added
    // explicitly; a variable array is already counted via [intVars].
    override val structuralKeyWeight: Int get() = intVars.size + if (arrIsVars) 0 else arr.size

    // Structural reduction to a plain equality when the selection is pinned (propagation only filters
    // domains, it never removes the global). Both cases are solution-set exact:
    //  - a fixed index p selects arr[p], so result = arr[p] (a constant, or the selected variable; a
    //    self-reference result = result is vacuous and drops);
    //  - a constant array of one value c fixes result = c, and the implicit idx-in-range constraint is
    //    kept by narrowing idx to its valid positions.
    // An out-of-range fixed index, or a disjoint index range, is left to the propagator (an
    // infeasibility it already reports).
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val idxDom = domains[idx]
        if (idxDom.min == idxDom.max) {
            val pos = idxDom.min - indexOffset
            if (pos < 0 || pos >= arr.size) return FactorReduction.Unchanged
            val p = pos.toInt()
            if (!arrIsVars) return FactorReduction.Rewrite(listOf(resultEquals(arr[p])))
            val v = arr[p].toInt() // entry is a var id when arrIsVars
            return if (v == result) FactorReduction.Rewrite(emptyList()) else FactorReduction.Rewrite(listOf(equate(v)))
        }
        if (!arrIsVars && arr.all { it == arr[0] }) {
            val lo = indexOffset
            val hi = indexOffset + arr.size - 1
            if (maxOf(idxDom.min, lo.toLong()) > minOf(idxDom.max, hi.toLong())) return FactorReduction.Unchanged
            return FactorReduction.Rewrite(listOf(resultEquals(arr[0])), mapOf(idx to lo..hi))
        }
        return FactorReduction.Unchanged
    }

    /** The equality `result = [value]`. */
    private fun resultEquals(value: Long): Linear = Linear(longArrayOf(1L), intArrayOf(result), LinearOp.EQ, value)

    /** The equality `result = [v]` between the result and an array variable. */
    private fun equate(v: Int): Linear = Linear(intArrayOf(1, -1), intArrayOf(result, v), LinearOp.EQ, 0)

    override fun asPropagator(): Propagator = ElementPropagator(
        boolVars,
        intVars,
        idx,
        result,
        arr,
        arrIsVars,
        indexOffset,
    )

    override fun asInvariant(): Invariant = ElementInvariant(
        idx,
        result,
        arr,
        arrIsVars,
        indexOffset,
    )

    override fun asLinearizer(): Linearizer = ElementLinearizer(idx, result, arr, arrIsVars, indexOffset)
}
