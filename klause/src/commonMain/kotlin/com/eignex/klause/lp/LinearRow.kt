package com.eignex.klause.lp

import com.eignex.klause.ir.LinearOp

/**
 * One linear constraint `Σ coeff(k) · value(ref(k)) ⟨op⟩ bound`, as exposed by `Factor.linearRows`.
 *
 * An **interface**, not a data holder: a factor implements it directly (or hands back a lightweight
 * view) so a clause-heavy model does not materialise a coefficient array per constraint. Each term
 * references either an integer variable or a Boolean literal through the single tagged [ref] space (see
 * [Term]): `value` is the variable's integer value for an integer term and the literal's 0/1 truth for a
 * Boolean term (a negative literal counts `1 − x`). A consumer iterates `k in 0 until size`, reading
 * `ref(k)` / `coeff(k)` **uniformly**, and only decodes the term kind ([Term.isBool]) when it must —
 * e.g. to look up an integer domain versus a `[0, 1]` literal. Integer-variable and Boolean-literal refs
 * occupy disjoint tag ranges, so a key over the refs keeps an integer constraint and a Boolean one in
 * separate buckets with no extra discriminator.
 *
 * A read-only structural view for presolve. Distinct from a `Factor.linearize` relaxation row: a
 * [LinearRow] is **solution-set exact** for its originating factor; a relaxation row may be a superset.
 */
interface LinearRow {
    /** The number of terms. */
    val size: Int

    /** The tagged reference (see [Term]) of term [k], `0 ≤ k < size`. */
    fun ref(k: Int): Int

    /** The coefficient of term [k], `0 ≤ k < size`. */
    fun coeff(k: Int): Long

    /** The relational operator comparing the weighted sum against [bound]. Named [relation] rather than
     *  `op` so a factor whose native operator is a different type (a pseudo-Boolean's `PbOp`) can still
     *  implement this interface directly. */
    val relation: LinearOp

    /** The right-hand side the weighted sum is compared against. */
    val bound: Long

    /** Whether every term references an integer variable (no Boolean literal). */
    val isIntegerOnly: Boolean
        get() {
            for (k in 0 until size) if (Term.isBool(ref(k))) return false
            return true
        }

    /** Views over a factor's own arrays, for a factor whose linear form is not itself a [LinearRow]. */
    companion object {
        /** A view of `Σ coeffs·vars ⟨op⟩ bound` over integer variables (raw ids); the arrays are not copied. */
        fun ofInts(vars: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            IntLinearRow(vars, coeffs, op, bound)

        /** A view of `Σ coeffs·value(lits) ⟨op⟩ bound` over Boolean literals (Lit-encoded); not copied. */
        fun ofBools(lits: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            BoolLinearRow(lits, coeffs, op, bound)

        /** A view of `Σ value(lits) ⟨op⟩ bound` over Boolean literals with unit coefficients (no array). */
        fun ofBools(lits: IntArray, op: LinearOp, bound: Long): LinearRow = BoolLinearRow(lits, null, op, bound)
    }
}

/** A [LinearRow] view over parallel integer-variable / coefficient arrays, without copying them. */
internal class IntLinearRow(
    val vars: IntArray,
    val coeffs: LongArray,
    override val relation: LinearOp,
    override val bound: Long,
) : LinearRow {
    override val size: Int get() = vars.size
    override fun ref(k: Int): Int = Term.ofIntVar(vars[k])
    override fun coeff(k: Int): Long = coeffs[k]
    override val isIntegerOnly: Boolean get() = true
}

/** A [LinearRow] view over a Boolean-literal array without copying it; `null` [weights] means unit. */
internal class BoolLinearRow(
    val lits: IntArray,
    private val weights: LongArray?,
    override val relation: LinearOp,
    override val bound: Long,
) : LinearRow {
    override val size: Int get() = lits.size
    override fun ref(k: Int): Int = Term.ofLit(lits[k])
    override fun coeff(k: Int): Long = weights?.get(k) ?: 1L
    override val isIntegerOnly: Boolean get() = false
}

/**
 * The tagged term-reference space of a [LinearRow]. An integer-variable reference and a Boolean-literal
 * reference are packed into one [Int] with disjoint low-bit tags, so a single pass over a row's terms is
 * kind-agnostic and references of the two kinds never collide in a key.
 */
object Term {
    /** Reference to integer variable [v]. */
    fun ofIntVar(v: Int): Int = v shl 1

    /** Reference to Boolean literal [lit] (Lit-encoded). */
    fun ofLit(lit: Int): Int = (lit shl 1) or 1

    /** True iff [ref] denotes a Boolean literal (else an integer variable). */
    fun isBool(ref: Int): Boolean = (ref and 1) == 1

    /** The integer variable id of [ref]; valid only when [isBool] is false. */
    fun intVar(ref: Int): Int = ref shr 1

    /** The Boolean literal (Lit-encoded) of [ref]; valid only when [isBool] is true. */
    fun lit(ref: Int): Int = ref shr 1
}
