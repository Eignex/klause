package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.LinearRow
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.bool.internals.CoalescedTerms
import com.eignex.klause.solver.factor.bool.internals.coalesceLinearTerms
import com.eignex.klause.solver.factor.remapVars

/**
 * `Σ coeffs(i) * intVars(i) ⟨op⟩ bound`. Payload at `intPayload(factorId)` is the current
 * weighted sum, kept in sync incrementally by [Invariant.applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of `bound`,
 * clamped to the variable's domain. Terms pair [coeffs] with [vars]; the sum is compared by [op]
 * against [bound].
 */
class Linear private constructor(terms: CoalescedTerms, val op: LinearOp, val bound: Int) : Factor {

    val vars: IntArray = terms.vars
    val coeffs: IntArray = terms.coeffs

    init {
        require(coeffs.isNotEmpty()) { "linear sum must have at least one term" }
    }

    override val intVars: IntArray = vars

    /**
     * `Σ coeffs(i) * vars(i) ⟨op⟩ bound`. Duplicate variables are coalesced (their coefficients
     * summed) so the local-search payload stays consistent regardless of caller.
     */
    constructor(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int) :
        this(coalesceLinearTerms(vars, coeffs), op, bound)

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) {
        enum(op)
        int(bound)
        pairsByKey(vars) { coeffs[it].toLong() }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Linear(coeffs, vars.remapVars(intMap), op, bound)

    // Folds `Linear(coeffs, vars.remapVars(intMap), op, bound).structuralKey().hashCode()` without
    // allocating the remapped Linear or its key. The key coalesces coefficients of variables sharing
    // an image (matching `coalesceLinearTerms`) and sorts the (image, coeff) pairs, so pack each pair
    // into a `Long` (image high, coeff low), sort, and fold the key's `LongArray` content-hash over the
    // merged runs. Symmetry refinement runs this once per incident variable each round.
    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int {
        val n = vars.size
        val packed = LongArray(n)
        for (k in 0 until n) {
            packed[k] = (intMap[vars[k]].toLong() shl Int.SIZE_BITS) or (coeffs[k].toLong() and LOW_WORD)
        }
        packed.sort()
        var distinct = 0
        var i = 0
        while (i < n) {
            val img = packed[i] ushr Int.SIZE_BITS
            var j = i + 1
            while (j < n && packed[j] ushr Int.SIZE_BITS == img) j++
            distinct++
            i = j
        }
        // Payload order: op.ordinal, bound, pair count, then each (image, summed coeff) ascending.
        var h = 1
        h = 31 * h + longHashWord(op.ordinal.toLong())
        h = 31 * h + longHashWord(bound.toLong())
        h = 31 * h + longHashWord(distinct.toLong())
        i = 0
        while (i < n) {
            val img = (packed[i] ushr Int.SIZE_BITS).toInt()
            var sum = 0L
            var j = i
            while (j < n && (packed[j] ushr Int.SIZE_BITS).toInt() == img) {
                sum += (packed[j] and LOW_WORD).toInt().toLong()
                j++
            }
            h = 31 * h + longHashWord(img.toLong())
            h = 31 * h + longHashWord(sum.toInt().toLong())
            i = j
        }
        return 31 * FactorKind.LINEAR.ordinal + h
    }

    /**
     * A pure binary value relation `c·x ⟨=|≠⟩ c·y` — two terms with opposite-equal coefficients and a
     * zero bound, comparing for equality or distinctness. Its allowed-tuple set (`{x = y}` / `{x ≠ y}`)
     * is invariant under *any* uniform relabeling of values, so it is value-anonymous. Every
     * other linear is value-meaningful: an ordering (`≤`/`≥`) is not relabeling-invariant, and a
     * nonzero bound or non-opposite coefficients tie the variables to specific magnitudes.
     */
    private fun isBinaryValueRelation(): Boolean = (op == LinearOp.EQ || op == LinearOp.NE) && bound == 0 &&
        vars.size == 2 && coeffs[0] != 0 && coeffs[0] == -coeffs[1]

    override fun isValueAnonymous(): Boolean = isBinaryValueRelation()

    // A value-anonymous factor names no value as a constant, so a relabeling maps it to itself (#501).
    override fun remapValues(valueMap: (Int) -> Int): Factor? = if (isBinaryValueRelation()) this else null

    override val boolVars: IntArray = EmptyIntArray

    override fun asPropagator(): Propagator = LinearPropagator(boolVars, intVars, coeffs, vars, op, bound)

    override fun asInvariant(): Invariant = LinearInvariant(coeffs, vars, op, bound)

    override fun asLinearizer(): Linearizer = LinearLinearizer(op, vars, coeffs, bound)

    // A Linear *is* a single exact linear row — its own inequality, no relaxation.
    override fun linearRows(): List<LinearRow> = listOf(LinearRow(coeffs, vars, op, bound.toLong()))
}

/** Low 32 bits mask for packing/unpacking a `(image, coeff)` pair in [Linear.remapStructuralHash]. */
private const val LOW_WORD = 0xFFFFFFFFL

/** `Long.hashCode()` (the per-word step of `LongArray.contentHashCode`), so the folded hash matches
 *  the one [StructuralKey] computes from its payload. */
private fun longHashWord(w: Long): Int = (w xor (w ushr Int.SIZE_BITS)).toInt()
