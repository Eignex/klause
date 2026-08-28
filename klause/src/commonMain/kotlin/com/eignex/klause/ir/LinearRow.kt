package com.eignex.klause.ir

/**
 * One exact linear constraint `Σ coeff(k) · value(ref(k)) ⟨op⟩ bound` from a [Factor].
 *
 * An interface, not a data holder: a factor implements it directly (or hands back a lightweight view)
 * so a clause-heavy model does not materialise a coefficient array per constraint. Each term references
 * either an integer variable or a Boolean literal through the single tagged [Term] space. A Boolean
 * literal has its 0/1 truth value, so a negative literal counts `1 − x`.
 *
 * A row is structural model data. Presolve can consume it directly, while an LP relaxation may project it
 * into columns; a relaxation row itself need not be exact.
 */
interface LinearRow {
    /** The number of terms. */
    val size: Int

    /** The tagged reference (see [Term]) of term [k], `0 ≤ k < size`. */
    fun ref(k: Int): Int

    /** The coefficient of term [k], `0 ≤ k < size`. */
    fun coeff(k: Int): Long

    /** The relational operator comparing the weighted sum against [bound]. */
    val relation: LinearOp

    /** The right-hand side the weighted sum is compared against. */
    val bound: Long

    /** Whether every term references an integer variable (no Boolean literal). */
    val isIntegerOnly: Boolean
        get() {
            for (k in 0 until size) if (Term.isBool(ref(k))) return false
            return true
        }

    /** Views over a factor's own arrays, without copying them. */
    companion object {
        /** A view of `Σ coeffs·vars ⟨op⟩ bound` over integer variables (raw ids). */
        fun ofInts(vars: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            IntLinearRow(vars, coeffs, op, bound)

        /** A view of `Σ coeffs·value(lits) ⟨op⟩ bound` over Boolean literals (Lit-encoded). */
        fun ofBools(lits: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            BoolLinearRow(lits, coeffs, op, bound)

        /** A view of `Σ value(lits) ⟨op⟩ bound` over Boolean literals with unit coefficients. */
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
 * The tagged term-reference space of a [LinearRow]. Integer-variable references and Boolean-literal
 * references occupy disjoint ranges, so consumers can retain a single structural key space.
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
