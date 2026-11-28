package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap

/**
 * `result = arr(idx − indexOffset)`, kept whole because a per-index decomposition gives local search no gradient.
 *
 * [arr] holds constants, or int-var ids when [arrIsVars]. With the default [indexOffset] of `1`, position 0 is
 * selected by `idx = 1`. Local search grades `|result − arr(idx)|`, and an out-of-range `idx` by its distance
 * back into range.
 */
class Element private constructor(
    /** Index variable id. */
    val idx: Int,
    /** Result variable id. */
    val result: Int,
    /** Variable ids when [arrIsVars], else constant values. */
    val arr: LongArray,
    /** Whether [arr] holds variable ids. */
    val arrIsVars: Boolean,
    /** Integer representing index 0 of [arr]. */
    val indexOffset: Int,
    cachedArrKey: LongArray?,
) : Factor {

    constructor(idx: Int, result: Int, arr: LongArray, arrIsVars: Boolean, indexOffset: Int = 1) :
        this(idx, result, arr, arrIsVars, indexOffset, null)

    init {
        require(arr.isNotEmpty()) { "element: empty array" }
    }

    // A constant-array remap leaves [arr] untouched, so its key fragment is carried across remaps, keeping the
    // O(|arr|) encode out of symmetry refinement's per-round hot path.
    private var cachedArrKey: LongArray? = cachedArrKey

    private fun arrKey(): LongArray = cachedArrKey ?: StructuralKey.words {
        long(arr.size.toLong())
        for (x in arr) long(x)
    }.also { cachedArrKey = it }

    override fun remap(mapping: VarRemap): Factor = if (arrIsVars) {
        Element(
            mapping.int(idx),
            mapping.int(result),
            LongArray(arr.size) { mapping.int(arr[it].toInt()).toLong() },
            arrIsVars,
            indexOffset,
            null,
        )
    } else {
        Element(mapping.int(idx), mapping.int(result), arr, arrIsVars, indexOffset, arrKey())
    }

    // A unit-scale shift of the index folds into [indexOffset]. A scaled index would reindex the array, and `x`
    // must appear only as the index so the rewrite removes every occurrence.
    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? =
        if (x == idx && scale == 1 && x != result && (!arrIsVars || x.toLong() !in arr) &&
            indexOffset.toLong() - offset in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        ) {
            Element(replacement, result, arr, arrIsVars, indexOffset - offset)
        } else {
            null
        }

    // Keeps array order and every distinguishing field, since a coarser key would let symmetry verification
    // accept a false swap. Builder form because the cached [arrKey] holds variable ids the KeySink cannot remap.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.ELEMENT) {
        bool(arrIsVars)
        int(indexOffset)
        int(idx)
        int(result)
        words(arrKey())
    }

    // No remapValues override: `idx`'s value selects a position, so a swap of two idx values leaves the constant
    // array unchanged and the value-symmetry verifier would wrongly accept it.

    override val variables: VarList = MixedVars(
        spanInts = intArrayOf(idx),
        // The result and the entries are read through their bounds, and enumerated only when they happen
        // to be narrow enough to walk. When arrIsVars the entries are var ids stored as Long.
        boundInts = if (arrIsVars) intArrayOf(result) + IntArray(arr.size) { arr[it].toInt() } else intArrayOf(result),
    )

    // A constant array is embedded in the key but is not part of [intVars], so its size must be added
    // explicitly; a variable array is already counted via [intVars].
    override val structuralKeyWeight: Int get() = intVars.size + if (arrIsVars) 0 else arr.size

    // A fixed index or a single-valued constant array reduces to an equality; the latter keeps the implicit
    // idx-in-range constraint by narrowing idx. An out-of-range index is left to the propagator to refute.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val idxDom = domains[idx]
        if (idxDom.min == idxDom.max) {
            val pos = idxDom.min - indexOffset
            if (pos < 0 || pos >= arr.size) return Unchanged
            val p = pos.toInt()
            if (!arrIsVars) return Rewrite(listOf(resultEquals(arr[p])))
            val v = arr[p].toInt()
            return if (v == result) Rewrite(emptyList()) else Rewrite(listOf(equate(v)))
        }
        if (!arrIsVars && arr.all { it == arr[0] }) {
            val lo = indexOffset
            val hi = indexOffset.toLong() + arr.size - 1
            // Structural bound rewrites carry IntRange endpoints.
            if (hi > Int.MAX_VALUE) return Unchanged
            if (maxOf(idxDom.min, lo.toLong()) > minOf(idxDom.max, hi)) return Unchanged
            return Rewrite(listOf(resultEquals(arr[0])), mapOf(idx to lo..hi.toInt()))
        }
        return Unchanged
    }

    private fun resultEquals(value: Long): Linear = Linear(longArrayOf(1L), intArrayOf(result), LinearOp.EQ, value)

    private fun equate(v: Int): Linear = Linear(intArrayOf(1, -1), intArrayOf(result, v), LinearOp.EQ, 0)
}
