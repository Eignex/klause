package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolTerm
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSum
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
 * Linear expression `Σ c_i · h_i + offset` over one or more [FloatHandle]s, all tracked
 * in real (Double) space. Arithmetic operators fold by merging coefficient maps;
 * comparisons against a Double or another [FloatExpr] lower to a single integer
 * comparison on the handles' bucket-int variables.
 *
 * Single-handle expressions take the precise per-handle quantization path
 * (folding offset/coeff into the threshold and using [FloatHandle.bucketOf]).
 * Multi-handle expressions take a rationalised path: each real coefficient is scaled by
 * a fixed precision factor and rounded to an integer, then the comparison is emitted
 * as `IntCompare(IntSum(IntScale(...)), op, IntLit(K))` for the existing affine
 * lowering in `Compiler` to turn into a `Linear` factor.
 */
class FloatExpr internal constructor(
    private val terms: Map<FloatHandle, Double>,
    private val offset: Double,
) {

    internal constructor(handle: FloatHandle, coeff: Double, offset: Double) :
        this(if (coeff == 0.0) emptyMap() else mapOf(handle to coeff), offset)

    operator fun plus(d: Double): FloatExpr = FloatExpr(terms, offset + d)
    operator fun minus(d: Double): FloatExpr = FloatExpr(terms, offset - d)
    operator fun times(c: Int): FloatExpr = times(c.toDouble())
    operator fun times(c: Double): FloatExpr {
        if (c == 0.0) return FloatExpr(emptyMap(), 0.0)
        return FloatExpr(terms.mapValues { it.value * c }, offset * c)
    }
    operator fun unaryMinus(): FloatExpr = times(-1.0)

    operator fun plus(other: FloatExpr): FloatExpr {
        val merged = LinkedHashMap<FloatHandle, Double>(terms)
        for ((h, c) in other.terms) {
            val sum = (merged[h] ?: 0.0) + c
            if (sum == 0.0) merged.remove(h) else merged[h] = sum
        }
        return FloatExpr(merged, offset + other.offset)
    }
    operator fun minus(other: FloatExpr): FloatExpr = this + (-other)

    infix fun le(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.LE, IntCmpOp.GE)
    infix fun lt(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.LT, IntCmpOp.GT)
    infix fun ge(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.GE, IntCmpOp.LE)
    infix fun gt(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.GT, IntCmpOp.LT)
    infix fun eq(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.EQ, IntCmpOp.EQ)
    infix fun ne(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.NE, IntCmpOp.NE)

    /** Expression-vs-expression comparison: rewrite `lhs OP rhs` as `(lhs - rhs) OP 0`. */
    infix fun le(other: FloatExpr): BoolExpr = (this - other) le 0.0
    infix fun lt(other: FloatExpr): BoolExpr = (this - other) lt 0.0
    infix fun ge(other: FloatExpr): BoolExpr = (this - other) ge 0.0
    infix fun gt(other: FloatExpr): BoolExpr = (this - other) gt 0.0
    infix fun eq(other: FloatExpr): BoolExpr = (this - other) eq 0.0
    infix fun ne(other: FloatExpr): BoolExpr = (this - other) ne 0.0

    private fun compare(threshold: Double, op: IntCmpOp, flippedOp: IntCmpOp): BoolExpr =
        when {
            terms.isEmpty() -> constantBool(evalConstant(op, threshold))
            terms.size == 1 -> {
                val entry = terms.entries.first()
                singleHandleBucketCompare(entry.key, entry.value, threshold, op, flippedOp)
            }
            else -> multiHandleBucketCompare(threshold, op)
        }

    private fun evalConstant(op: IntCmpOp, threshold: Double): Boolean = when (op) {
        IntCmpOp.LE -> offset <= threshold
        IntCmpOp.LT -> offset < threshold
        IntCmpOp.GE -> offset >= threshold
        IntCmpOp.GT -> offset > threshold
        IntCmpOp.EQ -> offset == threshold
        IntCmpOp.NE -> offset != threshold
    }

    /**
     * Single-handle path: fold coeff and offset into the threshold in float space, then
     * round to a bucket index via [FloatHandle.bucketOf]. Out-of-range thresholds collapse
     * to tautology / contradiction. [flippedOp] kicks in when [coeff] is negative because
     * dividing both sides by a negative reverses the comparison direction.
     */
    private fun singleHandleBucketCompare(
        handle: FloatHandle,
        coeff: Double,
        threshold: Double,
        op: IntCmpOp,
        flippedOp: IntCmpOp,
    ): BoolExpr {
        val ref = IntRef(handle.name)
        val effectiveThreshold = (threshold - offset) / coeff
        val finalOp = if (coeff < 0) flippedOp else op
        return when {
            effectiveThreshold < handle.min -> when (finalOp) {
                IntCmpOp.LE, IntCmpOp.LT, IntCmpOp.EQ -> constantBool(false)
                IntCmpOp.GE, IntCmpOp.GT, IntCmpOp.NE -> constantBool(true)
            }
            effectiveThreshold > handle.max -> when (finalOp) {
                IntCmpOp.LE, IntCmpOp.LT, IntCmpOp.NE -> constantBool(true)
                IntCmpOp.GE, IntCmpOp.GT, IntCmpOp.EQ -> constantBool(false)
            }
            else -> IntCompare(ref, finalOp, IntLit(handle.bucketOf(effectiveThreshold)))
        }
    }

    /**
     * Multi-handle path: substitute `real_i = h_i.min + (b_i / (h_i.buckets - 1)) ·
     * (h_i.max - h_i.min)` for each handle and rearrange so the comparison reads
     * `Σ s_i · b_i  OP  K` where `s_i = c_i · (h_i.max - h_i.min) / (h_i.buckets - 1)`
     * and `K = threshold - offset - Σ c_i · h_i.min`. Multiply both sides by [SCALE] and
     * round to integers; emit through the existing affine-lowering pipeline.
     *
     * Discretisation error is bounded by 1 / [SCALE] per term — orders of magnitude below
     * the per-handle bucket grid for realistic [SCALE] values.
     */
    private fun multiHandleBucketCompare(threshold: Double, op: IntCmpOp): BoolExpr {
        var constSum = offset
        val children = mutableListOf<IntExpr>()
        for ((h, c) in terms) {
            val realStep = c * (h.max - h.min) / (h.buckets - 1)
            val k = (SCALE * realStep).roundToInt()
            if (k != 0) children.add(IntScale(k, IntRef(h.name)))
            constSum += c * h.min
        }
        val K = (SCALE * (threshold - constSum)).roundToInt()
        val sum: IntExpr = when (children.size) {
            0 -> IntLit(0)
            1 -> children[0]
            else -> IntSum(children)
        }
        return IntCompare(sum, op, IntLit(K))
    }

    /**
     * `0 = 0` for true and `0 ≠ 0` for false: the compiler's affine pass folds these to
     * a no-op or a compile-time-false [IllegalStateException] respectively, matching how
     * user-written contradictions like `(x - x) eq 5` already surface.
     */
    private fun constantBool(value: Boolean): BoolExpr =
        if (value) IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(0))
        else IntCompare(IntLit(0), IntCmpOp.NE, IntLit(0))

    private companion object {
        const val SCALE: Double = 1_000_000.0
    }
}
