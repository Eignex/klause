package com.eignex.klause.ir

/**
 * Literal encoding: `lit = (variable shl 1) or (if negated 1 else 0)`.
 * `lit xor 1` flips polarity, `lit ushr 1` recovers the variable.
 */
object Lit {
    /** Encode a literal from its [variable] id and polarity ([positive] = non-negated). */
    fun make(variable: Int, positive: Boolean): Int = (variable shl 1) or if (positive) 0 else 1

    /** The variable id carried by [lit]. */
    fun variable(lit: Int): Int = lit ushr 1

    /** True iff [lit] is a positive (non-negated) literal. */
    fun isPositive(lit: Int): Boolean = (lit and 1) == 0

    /** The literal with flipped polarity. */
    fun negate(lit: Int): Int = lit xor 1

    /** Evaluate [lit] given its variable's [value]. */
    fun evaluate(lit: Int, value: Boolean): Boolean = value xor !isPositive(lit)
}
