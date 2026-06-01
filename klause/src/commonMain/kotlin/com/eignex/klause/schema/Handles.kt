package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolTerm
import com.eignex.klause.ast.FloatLinearConstraint
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntTerm
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.not

/** DSL handle for a declared Boolean variable; usable directly as a [BoolTerm]. */
class BoolHandle(
    /** Name of the underlying Boolean variable. */
    val name: String,
) : BoolTerm {
    override fun toExpr(): BoolExpr = BoolRef(name, negated = false)
}

/** DSL handle for a declared nominal variable, exposing label equality tests. */
class NominalHandle(
    /** Name of the underlying nominal variable. */
    val name: String,
    /** The valid category labels. */
    val labels: List<String>,
) {
    /** `this == label`; the label must be one of [labels]. */
    infix fun eq(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return NominalEq(name, label)
    }

    /** `this != label`. */
    infix fun ne(label: String): BoolExpr = !eq(label)
}

/** DSL handle for a declared integer variable; usable directly as an [IntTerm]. */
class IntHandle(
    /** Name of the underlying integer variable. */
    val name: String,
    /** Inclusive lower bound of the domain. */
    val min: Int,
    /** Inclusive upper bound of the domain. */
    val max: Int,
) : IntTerm {
    override fun toIntExpr(): IntExpr = IntRef(name)
}

/**
 * Float variable represented as a native real-valued solver variable with bounds
 * `[min, max]`. Arithmetic and comparison operators build a [FloatExpr] that lowers
 * to a [FloatLinearConstraint] AST node at compile-time; the compiler converts that
 * into a [com.eignex.klause.solver.factor.FloatLinear] factor in the [com.eignex.klause.solver.Problem].
 *
 * The historical `buckets` parameter is preserved for source compatibility but is no
 * longer used at the schema layer — bucketing is now a per-backend concern handled by
 * [com.eignex.klause.solver.FloatLowering] at solve-time. Backends with native float
 * support (Z3) ignore the lowering entirely.
 */
class FloatHandle(
    /** Name of the underlying float variable. */
    val name: String,
    /** Inclusive lower real bound. */
    val min: Double,
    /** Inclusive upper real bound. */
    val max: Double,
    /** Deprecated, ignored bucket count kept for source compatibility. */
    @Deprecated("Bucketing is now a per-backend solve-time concern; this parameter is ignored.")
    val buckets: Int = 0,
) {

    /** Identity expression `1·f + 0`. Use this when an API needs a [FloatExpr]. */
    fun toExpr(): FloatExpr = FloatExpr(this, coeff = 1.0, offset = 0.0)

    /** `this + d`. */
    operator fun plus(d: Double): FloatExpr = FloatExpr(this, 1.0, d)

    /** `this - d`. */
    operator fun minus(d: Double): FloatExpr = FloatExpr(this, 1.0, -d)

    /** `c · this`. */
    operator fun times(c: Int): FloatExpr = FloatExpr(this, c.toDouble(), 0.0)

    /** `c · this`. */
    operator fun times(c: Double): FloatExpr = FloatExpr(this, c, 0.0)

    /** `-this`. */
    operator fun unaryMinus(): FloatExpr = FloatExpr(this, -1.0, 0.0)

    /** `this ≤ c`. */
    infix fun le(c: Double): BoolExpr = toExpr() le c

    /** `this < c`. */
    infix fun lt(c: Double): BoolExpr = toExpr() lt c

    /** `this ≥ c`. */
    infix fun ge(c: Double): BoolExpr = toExpr() ge c

    /** `this > c`. */
    infix fun gt(c: Double): BoolExpr = toExpr() gt c

    /** `this = c`. */
    infix fun eq(c: Double): BoolExpr = toExpr() eq c

    /** `this ≠ c`. */
    infix fun ne(c: Double): BoolExpr = toExpr() ne c

    /** `this ≤ other`. */
    infix fun le(other: FloatExpr): BoolExpr = toExpr() le other

    /** `this < other`. */
    infix fun lt(other: FloatExpr): BoolExpr = toExpr() lt other

    /** `this ≥ other`. */
    infix fun ge(other: FloatExpr): BoolExpr = toExpr() ge other

    /** `this > other`. */
    infix fun gt(other: FloatExpr): BoolExpr = toExpr() gt other

    /** `this = other`. */
    infix fun eq(other: FloatExpr): BoolExpr = toExpr() eq other

    /** `this ≠ other`. */
    infix fun ne(other: FloatExpr): BoolExpr = toExpr() ne other
}

/** `this · handle`. */
operator fun Int.times(handle: FloatHandle): FloatExpr = FloatExpr(handle, this.toDouble(), 0.0)

/** `this · handle`. */
operator fun Double.times(handle: FloatHandle): FloatExpr = FloatExpr(handle, this, 0.0)

/** `this · expr`. */
operator fun Int.times(expr: FloatExpr): FloatExpr = expr * this

/** `this · expr`. */
operator fun Double.times(expr: FloatExpr): FloatExpr = expr * this

/**
 * Linear expression `Σ c_i · h_i + offset` over one or more [FloatHandle]s, all in real
 * (Double) space. Arithmetic operators fold by merging coefficient maps; comparisons
 * against a Double or another [FloatExpr] lower to a [FloatLinearConstraint] AST node,
 * which the compiler turns into a [com.eignex.klause.solver.factor.FloatLinear] factor.
 */
class FloatExpr internal constructor(private val terms: Map<FloatHandle, Double>, private val offset: Double) {

    internal constructor(handle: FloatHandle, coeff: Double, offset: Double) :
        this(if (coeff == 0.0) emptyMap() else mapOf(handle to coeff), offset)

    /** `this + d`. */
    operator fun plus(d: Double): FloatExpr = FloatExpr(terms, offset + d)

    /** `this - d`. */
    operator fun minus(d: Double): FloatExpr = FloatExpr(terms, offset - d)

    /** `c · this`. */
    operator fun times(c: Int): FloatExpr = times(c.toDouble())

    /** `c · this`. */
    operator fun times(c: Double): FloatExpr {
        if (c == 0.0) return FloatExpr(emptyMap(), 0.0)
        return FloatExpr(terms.mapValues { it.value * c }, offset * c)
    }

    /** `-this`. */
    operator fun unaryMinus(): FloatExpr = times(-1.0)

    /** `this + other`, merging coefficient maps. */
    operator fun plus(other: FloatExpr): FloatExpr {
        val merged = LinkedHashMap<FloatHandle, Double>(terms)
        for ((h, c) in other.terms) {
            val sum = (merged[h] ?: 0.0) + c
            if (sum == 0.0) merged.remove(h) else merged[h] = sum
        }
        return FloatExpr(merged, offset + other.offset)
    }

    /** `this - other`. */
    operator fun minus(other: FloatExpr): FloatExpr = this + (-other)

    /** `this ≤ threshold`. */
    infix fun le(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.LE)

    /** `this < threshold`. */
    infix fun lt(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.LT)

    /** `this ≥ threshold`. */
    infix fun ge(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.GE)

    /** `this > threshold`. */
    infix fun gt(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.GT)

    /** `this = threshold`. */
    infix fun eq(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.EQ)

    /** `this ≠ threshold`. */
    infix fun ne(threshold: Double): BoolExpr = compare(threshold, IntCmpOp.NE)

    /** `this ≤ other`, rewritten as `(this - other) ≤ 0`. */
    infix fun le(other: FloatExpr): BoolExpr = (this - other) le 0.0

    /** `this < other`, rewritten as `(this - other) < 0`. */
    infix fun lt(other: FloatExpr): BoolExpr = (this - other) lt 0.0

    /** `this ≥ other`, rewritten as `(this - other) ≥ 0`. */
    infix fun ge(other: FloatExpr): BoolExpr = (this - other) ge 0.0

    /** `this > other`, rewritten as `(this - other) > 0`. */
    infix fun gt(other: FloatExpr): BoolExpr = (this - other) gt 0.0

    /** `this = other`, rewritten as `(this - other) = 0`. */
    infix fun eq(other: FloatExpr): BoolExpr = (this - other) eq 0.0

    /** `this ≠ other`, rewritten as `(this - other) ≠ 0`. */
    infix fun ne(other: FloatExpr): BoolExpr = (this - other) ne 0.0

    private fun compare(threshold: Double, op: IntCmpOp): BoolExpr {
        if (terms.isEmpty()) return constantBool(evalConstant(op, threshold))
        // `Σ c_i · h_i + offset  ⟨op⟩  threshold`  →  `Σ c_i · h_i  ⟨op⟩  threshold - offset`.
        val coeffs = DoubleArray(terms.size)
        val names = ArrayList<String>(terms.size)
        for ((i, e) in terms.entries.withIndex()) {
            coeffs[i] = e.value
            names.add(e.key.name)
        }
        return FloatLinearConstraint(coeffs, names, op, threshold - offset)
    }

    private fun evalConstant(op: IntCmpOp, threshold: Double): Boolean = when (op) {
        IntCmpOp.LE -> offset <= threshold
        IntCmpOp.LT -> offset < threshold
        IntCmpOp.GE -> offset >= threshold
        IntCmpOp.GT -> offset > threshold
        IntCmpOp.EQ -> offset == threshold
        IntCmpOp.NE -> offset != threshold
    }

    /** `0 = 0` for true and `0 ≠ 0` for false; the compiler's affine pass folds these. */
    private fun constantBool(value: Boolean): BoolExpr = if (value) {
        IntCompare(IntLit(0), IntCmpOp.EQ, IntLit(0))
    } else {
        IntCompare(IntLit(0), IntCmpOp.NE, IntLit(0))
    }
}
