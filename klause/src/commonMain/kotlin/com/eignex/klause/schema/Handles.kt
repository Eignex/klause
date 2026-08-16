package com.eignex.klause.schema

import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolTerm
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.NominalEq

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
 * Anything usable as a real-valued term in the float DSL — a [FloatHandle] or a (possibly
 * multi-term) [FloatExpr]. The arithmetic and comparison operators below are defined once over
 * `FloatTerm`, so handles, expressions, and `Double` constants all combine uniformly (mirroring
 * [com.eignex.klause.model.IntTerm]).
 */
interface FloatTerm {
    /** Coerce to the canonical linear [FloatExpr] form. */
    fun toFloatExpr(): FloatExpr
}

/**
 * DSL handle for a declared float variable. Combines and compares via the shared [FloatTerm]
 * operators, lowering to a [FloatLinearConstraint] AST node (the compiler turns that into a
 * `com.eignex.klause.factor.FloatLinear` factor). The `buckets` parameter is accepted but
 * ignored — bucketing is a per-backend solve-time concern.
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
) : FloatTerm {
    override fun toFloatExpr(): FloatExpr = FloatExpr(this, coeff = 1.0, offset = 0.0)
}

/**
 * Linear expression `Σ c_i · h_i + offset` over [FloatHandle]s in real (Double) space. Build it with
 * the [FloatTerm] operators; comparisons lower to a [FloatLinearConstraint] AST node.
 */
class FloatExpr internal constructor(internal val terms: Map<FloatHandle, Double>, internal val offset: Double) :
    FloatTerm {

    internal constructor(handle: FloatHandle, coeff: Double, offset: Double) :
        this(if (coeff == 0.0) emptyMap() else mapOf(handle to coeff), offset)

    override fun toFloatExpr(): FloatExpr = this

    internal fun shifted(d: Double): FloatExpr = FloatExpr(terms, offset + d)

    internal fun scaled(c: Double): FloatExpr {
        if (c == 0.0) return FloatExpr(emptyMap(), 0.0)
        return FloatExpr(terms.mapValues { it.value * c }, offset * c)
    }

    internal fun mergedWith(other: FloatExpr): FloatExpr {
        val merged = LinkedHashMap<FloatHandle, Double>(terms)
        for ((h, c) in other.terms) {
            val sum = (merged[h] ?: 0.0) + c
            if (sum == 0.0) merged.remove(h) else merged[h] = sum
        }
        return FloatExpr(merged, offset + other.offset)
    }

    internal fun compareWith(threshold: Double, op: IntCmpOp): BoolExpr {
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

// FloatTerm operators: defined once so handles, exprs, and Double constants compose uniformly.

/** `this + other`. */
operator fun FloatTerm.plus(other: FloatTerm): FloatExpr = toFloatExpr().mergedWith(other.toFloatExpr())

/** `this + d`. */
operator fun FloatTerm.plus(d: Double): FloatExpr = toFloatExpr().shifted(d)

/** `d + this`. */
operator fun Double.plus(t: FloatTerm): FloatExpr = t + this

/** `this - other`. */
operator fun FloatTerm.minus(other: FloatTerm): FloatExpr = this + (-other)

/** `this - d`. */
operator fun FloatTerm.minus(d: Double): FloatExpr = toFloatExpr().shifted(-d)

/** `d - this`. */
operator fun Double.minus(t: FloatTerm): FloatExpr = (-t) + this

/** `c · this`. */
operator fun FloatTerm.times(c: Double): FloatExpr = toFloatExpr().scaled(c)

/** `c · this`. */
operator fun FloatTerm.times(c: Int): FloatExpr = this * c.toDouble()

/** `c · this`. */
operator fun Double.times(t: FloatTerm): FloatExpr = t * this

/** `c · this`. */
operator fun Int.times(t: FloatTerm): FloatExpr = t * this.toDouble()

/** `-this`. */
operator fun FloatTerm.unaryMinus(): FloatExpr = this * -1.0

/** `this ≤ threshold`. */
infix fun FloatTerm.le(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.LE)

/** `this < threshold`. */
infix fun FloatTerm.lt(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.LT)

/** `this ≥ threshold`. */
infix fun FloatTerm.ge(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.GE)

/** `this > threshold`. */
infix fun FloatTerm.gt(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.GT)

/** `this = threshold`. */
infix fun FloatTerm.eq(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.EQ)

/** `this ≠ threshold`. */
infix fun FloatTerm.ne(threshold: Double): BoolExpr = toFloatExpr().compareWith(threshold, IntCmpOp.NE)

/** `this ≤ other`. */
infix fun FloatTerm.le(other: FloatTerm): BoolExpr = (this - other) le 0.0

/** `this < other`. */
infix fun FloatTerm.lt(other: FloatTerm): BoolExpr = (this - other) lt 0.0

/** `this ≥ other`. */
infix fun FloatTerm.ge(other: FloatTerm): BoolExpr = (this - other) ge 0.0

/** `this > other`. */
infix fun FloatTerm.gt(other: FloatTerm): BoolExpr = (this - other) gt 0.0

/** `this = other`. */
infix fun FloatTerm.eq(other: FloatTerm): BoolExpr = (this - other) eq 0.0

/** `this ≠ other`. */
infix fun FloatTerm.ne(other: FloatTerm): BoolExpr = (this - other) ne 0.0
