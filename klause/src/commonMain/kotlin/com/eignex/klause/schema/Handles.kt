package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolTerm
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntTerm
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.not
import kotlin.math.roundToInt

class BoolHandle(val name: String) : BoolTerm {
    override fun toExpr(): BoolExpr = BoolRef(name, negated = false)
}

class NominalHandle(val name: String, val labels: List<String>) {
    infix fun eq(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return NominalEq(name, label)
    }
    infix fun ne(label: String): BoolExpr = !eq(label)
}

class IntHandle(val name: String, val min: Int, val max: Int) : IntTerm {
    override fun toIntExpr(): IntExpr = IntRef(name)
}

/**
 * Float variable bucketed at the schema layer. The runtime represents it as an integer
 * domain `[0, buckets-1]`; comparisons and arithmetic against [Double] literals lower to
 * integer constraints on the bucket index at construction time.
 *
 * Direct comparisons against literals (`f le 0.5`) work via [FloatExpr], which is also the
 * type returned by arithmetic operators (`f + 0.1`, `2 * f`, etc.). Same-handle linear
 * combinations like `(2 * f - 0.3) le f + 0.1` collapse to a single bucket-int comparison.
 *
 * Cross-handle linear arithmetic (mixing two distinct float handles in one expression) is
 * not yet supported and throws at expression-build time.
 */
class FloatHandle(val name: String, val min: Double, val max: Double, val buckets: Int) {

    fun bucketOf(value: Double): Int {
        val clamped = value.coerceIn(min, max)
        val frac = (clamped - min) / (max - min)
        return (frac * (buckets - 1)).roundToInt().coerceIn(0, buckets - 1)
    }

    /** Identity expression `1·f + 0`. Use this when an API needs a [FloatExpr]. */
    fun toExpr(): FloatExpr = FloatExpr(this, coeff = 1.0, offset = 0.0)

    operator fun plus(d: Double): FloatExpr = FloatExpr(this, 1.0, d)
    operator fun minus(d: Double): FloatExpr = FloatExpr(this, 1.0, -d)
    operator fun times(c: Int): FloatExpr = FloatExpr(this, c.toDouble(), 0.0)
    operator fun times(c: Double): FloatExpr = FloatExpr(this, c, 0.0)
    operator fun unaryMinus(): FloatExpr = FloatExpr(this, -1.0, 0.0)

    infix fun le(c: Double): BoolExpr = toExpr() le c
    infix fun lt(c: Double): BoolExpr = toExpr() lt c
    infix fun ge(c: Double): BoolExpr = toExpr() ge c
    infix fun gt(c: Double): BoolExpr = toExpr() gt c
    infix fun eq(c: Double): BoolExpr = toExpr() eq c
    infix fun ne(c: Double): BoolExpr = toExpr() ne c

    infix fun le(other: FloatExpr): BoolExpr = toExpr() le other
    infix fun lt(other: FloatExpr): BoolExpr = toExpr() lt other
    infix fun ge(other: FloatExpr): BoolExpr = toExpr() ge other
    infix fun gt(other: FloatExpr): BoolExpr = toExpr() gt other
    infix fun eq(other: FloatExpr): BoolExpr = toExpr() eq other
    infix fun ne(other: FloatExpr): BoolExpr = toExpr() ne other
}

operator fun Int.times(handle: FloatHandle): FloatExpr = FloatExpr(handle, this.toDouble(), 0.0)
operator fun Double.times(handle: FloatHandle): FloatExpr = FloatExpr(handle, this, 0.0)
operator fun Int.times(expr: FloatExpr): FloatExpr = expr * this
operator fun Double.times(expr: FloatExpr): FloatExpr = expr * this

/**
 * Linear expression `coeff · handle + offset`, with both terms tracked in real (Double)
 * space. Arithmetic operators chain by folding the coefficient and offset; comparison
 * operators rearrange `coeff · h + offset OP threshold` to `h OP (threshold - offset) /
 * coeff`, then drop into the handle's bucket grid.
 *
 * All operations preserve the underlying [FloatHandle] reference. Combining two
 * `FloatExpr`s that name different handles requires the cross-variable linear arithmetic
 * path, which isn't yet implemented; doing so throws at expression-build time.
 */
class FloatExpr internal constructor(
    private val handle: FloatHandle,
    private val coeff: Double,
    private val offset: Double,
) {

    operator fun plus(d: Double): FloatExpr = FloatExpr(handle, coeff, offset + d)
    operator fun minus(d: Double): FloatExpr = FloatExpr(handle, coeff, offset - d)
    operator fun times(c: Int): FloatExpr = FloatExpr(handle, coeff * c, offset * c)
    operator fun times(c: Double): FloatExpr = FloatExpr(handle, coeff * c, offset * c)
    operator fun unaryMinus(): FloatExpr = FloatExpr(handle, -coeff, -offset)

    operator fun plus(other: FloatExpr): FloatExpr {
        requireSameHandle(other)
        return FloatExpr(handle, coeff + other.coeff, offset + other.offset)
    }
    operator fun minus(other: FloatExpr): FloatExpr {
        requireSameHandle(other)
        return FloatExpr(handle, coeff - other.coeff, offset - other.offset)
    }

    infix fun le(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.LE, IntCmpOp.GE)
    infix fun lt(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.LT, IntCmpOp.GT)
    infix fun ge(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.GE, IntCmpOp.LE)
    infix fun gt(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.GT, IntCmpOp.LT)
    infix fun eq(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.EQ, IntCmpOp.EQ)
    infix fun ne(threshold: Double): BoolExpr = bucketCompare(threshold, IntCmpOp.NE, IntCmpOp.NE)

    /** Same-handle expression comparison: rewrite `lhs OP rhs` as `(lhs - rhs) OP 0`. */
    infix fun le(other: FloatExpr): BoolExpr = (this - other) le 0.0
    infix fun lt(other: FloatExpr): BoolExpr = (this - other) lt 0.0
    infix fun ge(other: FloatExpr): BoolExpr = (this - other) ge 0.0
    infix fun gt(other: FloatExpr): BoolExpr = (this - other) gt 0.0
    infix fun eq(other: FloatExpr): BoolExpr = (this - other) eq 0.0
    infix fun ne(other: FloatExpr): BoolExpr = (this - other) ne 0.0

    private fun requireSameHandle(other: FloatExpr) {
        require(handle === other.handle) {
            "Cross-variable float arithmetic between '${handle.name}' and '${other.handle.name}' " +
                "is not yet supported"
        }
    }

    /**
     * Reduce `coeff · h + offset OP threshold` to a bucket-index integer comparison.
     * [op] is the operator the original expression carries; [flippedOp] is its
     * counterpart for when [coeff] is negative (the comparison flips when both sides are
     * divided by a negative).
     *
     * Out-of-handle-range thresholds become tautologies / contradictions encoded as
     * comparisons against `IntLit(0)` or `IntLit(-1)` — bucket values can never be
     * negative, so `bucket < 0` is reliably unsatisfiable and `bucket >= 0` reliably true.
     */
    private fun bucketCompare(threshold: Double, op: IntCmpOp, flippedOp: IntCmpOp): BoolExpr {
        val ref = IntRef(handle.name)
        if (coeff == 0.0) {
            val holds = when (op) {
                IntCmpOp.LE -> offset <= threshold
                IntCmpOp.LT -> offset < threshold
                IntCmpOp.GE -> offset >= threshold
                IntCmpOp.GT -> offset > threshold
                IntCmpOp.EQ -> offset == threshold
                IntCmpOp.NE -> offset != threshold
            }
            return constantBool(ref, holds)
        }
        val effectiveThreshold = (threshold - offset) / coeff
        val finalOp = if (coeff < 0) flippedOp else op
        return when {
            effectiveThreshold < handle.min -> when (finalOp) {
                IntCmpOp.LE, IntCmpOp.LT, IntCmpOp.EQ -> constantBool(ref, false)
                IntCmpOp.GE, IntCmpOp.GT, IntCmpOp.NE -> constantBool(ref, true)
            }
            effectiveThreshold > handle.max -> when (finalOp) {
                IntCmpOp.LE, IntCmpOp.LT, IntCmpOp.NE -> constantBool(ref, true)
                IntCmpOp.GE, IntCmpOp.GT, IntCmpOp.EQ -> constantBool(ref, false)
            }
            else -> IntCompare(ref, finalOp, IntLit(handle.bucketOf(effectiveThreshold)))
        }
    }

    private fun constantBool(ref: IntRef, value: Boolean): BoolExpr =
        // `x = x` reduces to a no-op in the compiler (coeffs cancel, constant 0 == 0).
        // `x != x` reduces to a constant-false IllegalStateException at compile time —
        // matching how the compiler already surfaces user-written contradictions like
        // `(x - x) eq 5`. Either path keeps the AST integer-only and avoids needing a
        // dedicated boolean-literal node.
        if (value) IntCompare(ref, IntCmpOp.EQ, ref)
        else IntCompare(ref, IntCmpOp.NE, ref)
}
