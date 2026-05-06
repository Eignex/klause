package com.eignex.klause.solver

/**
 * Literal encoding: `lit = (variable shl 1) or (if negated 1 else 0)`.
 * `lit xor 1` flips polarity, `lit ushr 1` recovers the variable.
 */
object Lit {
    fun make(variable: Int, positive: Boolean): Int =
        (variable shl 1) or if (positive) 0 else 1

    fun variable(lit: Int): Int = lit ushr 1
    fun isPositive(lit: Int): Boolean = (lit and 1) == 0
    fun negate(lit: Int): Int = lit xor 1
    fun evaluate(lit: Int, value: Boolean): Boolean = value xor !isPositive(lit)
}
