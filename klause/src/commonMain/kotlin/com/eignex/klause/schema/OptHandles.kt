package com.eignex.klause.schema

import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolTerm
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntIfThenElse
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.NominalEq
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or

/**
 * Optional integer variable: a `(present, value)` pair. The presence bool exists as an ordinary
 * Boolean variable in the underlying problem; the value variable has the usual integer domain
 * but is meaningful only when [present] is true. Comparisons follow MiniZinc opt semantics —
 * "undefined in a Boolean context is false" — so any comparison involving an absent operand
 * evaluates to false rather than dropping out of the constraint.
 *
 * Use [valueOr] to materialise the value into an [IntExpr] that's safe to plug into arithmetic;
 * arithmetic on the bare [value] handle would ignore [present] and is therefore not exposed.
 */
class OptIntHandle(
    /** Name of the optional variable. */
    val name: String,
    /** Presence literal: true iff the variable is present. */
    val present: BoolHandle,
    /** The value handle, meaningful only when [present]. */
    val value: IntHandle,
) {
    /** Inclusive lower bound of the value's domain. */
    val min: Int get() = value.min

    /** Inclusive upper bound of the value's domain. */
    val max: Int get() = value.max

    /** Yields an [IntExpr] equal to [value] when [present], otherwise [default]. Safe in any
     *  arithmetic context. */
    fun valueOr(default: Int): IntExpr = IntIfThenElse(present.toExpr(), value.toIntExpr(), IntLit(default))

    /** Yields an [IntExpr] equal to [value] when [present], otherwise [default]. */
    fun valueOr(default: IntTerm): IntExpr = IntIfThenElse(present.toExpr(), value.toIntExpr(), default.toIntExpr())

    private fun cmpRhsInt(rhs: IntTerm, op: IntCmpOp): BoolExpr =
        And(listOf(present.toExpr(), IntCompare(value.toIntExpr(), op, rhs.toIntExpr())))

    private fun cmpRhsConst(c: Int, op: IntCmpOp): BoolExpr =
        And(listOf(present.toExpr(), IntCompare(value.toIntExpr(), op, IntLit(c))))

    private fun cmpRhsOpt(other: OptIntHandle, op: IntCmpOp): BoolExpr = And(
        listOf(
            present.toExpr(),
            other.present.toExpr(),
            IntCompare(value.toIntExpr(), op, other.value.toIntExpr()),
        ),
    )

    /** `this ≤ other`, and present (false if absent). */
    infix fun le(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.LE)

    /** `this < other`, and present (false if absent). */
    infix fun lt(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.LT)

    /** `this ≥ other`, and present (false if absent). */
    infix fun ge(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.GE)

    /** `this > other`, and present (false if absent). */
    infix fun gt(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.GT)

    /** `this = other`, and present (false if absent). */
    infix fun eq(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.EQ)

    /** `this ≠ other`, and present (false if absent). */
    infix fun ne(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.NE)

    /** `this ≤ c`, and present (false if absent). */
    infix fun le(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.LE)

    /** `this < c`, and present (false if absent). */
    infix fun lt(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.LT)

    /** `this ≥ c`, and present (false if absent). */
    infix fun ge(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.GE)

    /** `this > c`, and present (false if absent). */
    infix fun gt(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.GT)

    /** `this = c`, and present (false if absent). */
    infix fun eq(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.EQ)

    /** `this ≠ c`, and present (false if absent). */
    infix fun ne(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.NE)

    /** `this ≤ other`, and present (false if absent). */
    infix fun le(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.LE)

    /** `this < other`, and present (false if absent). */
    infix fun lt(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.LT)

    /** `this ≥ other`, and present (false if absent). */
    infix fun ge(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.GE)

    /** `this > other`, and present (false if absent). */
    infix fun gt(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.GT)

    /** `this = other`, and present (false if absent). */
    infix fun eq(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.EQ)

    /** `this ≠ other`, and present (false if absent). */
    infix fun ne(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.NE)
}

/**
 * Optional Boolean variable: a `(present, value)` pair. Like [OptIntHandle], the value is
 * meaningful only when [present] is true; in a Boolean context the opt-bool itself evaluates
 * to `present ∧ value` so absent operands silently become false.
 */
class OptBoolHandle(
    /** Name of the optional variable. */
    val name: String,
    /** Presence literal: true iff the variable is present. */
    val present: BoolHandle,
    /** The value handle, meaningful only when [present]. */
    val value: BoolHandle,
) : BoolTerm {
    /** Coerces to `present ∧ value` — false whenever the variable is absent. */
    override fun toExpr(): BoolExpr = And(listOf(present.toExpr(), value.toExpr()))

    /** Yields a [BoolExpr] equal to [value] when [present], otherwise [default]. Useful when
     *  the caller wants a non-false fallback (e.g. `valueOr(true)`). */
    fun valueOr(default: Boolean): BoolExpr =
        if (default) Or(listOf(Not(present.toExpr()), value.toExpr())) else toExpr()
}

/**
 * Optional float variable: a `(present, value)` pair, mirroring [OptIntHandle] for the
 * bucketised-float kind. The presence bool is an ordinary Boolean; the value is a [FloatHandle]
 * meaningful only when [present]. Comparisons follow MiniZinc opt semantics — any comparison
 * involving an absent operand evaluates to false rather than dropping out of the constraint.
 *
 * Unlike [OptIntHandle] there is no `valueOr`: floats lower only to linear
 * ([com.eignex.klause.model.FloatLinearConstraint]) factors, and the AST has no float
 * if-then-else node to express "value when present, default otherwise" in an arithmetic
 * context. Use [present] / [value] directly when a conditional real value is needed.
 */
class OptFloatHandle(
    /** Name of the optional variable. */
    val name: String,
    /** Presence literal: true iff the variable is present. */
    val present: BoolHandle,
    /** The value handle, meaningful only when [present]. */
    val value: FloatHandle,
) {
    /** Inclusive lower bound of the value's real domain. */
    val min: Double get() = value.min

    /** Inclusive upper bound of the value's real domain. */
    val max: Double get() = value.max

    private fun guarded(cmp: BoolExpr): BoolExpr = And(listOf(present.toExpr(), cmp))

    /** `this ≤ c`, and present (false if absent). */
    infix fun le(c: Double): BoolExpr = guarded(value le c)

    /** `this < c`, and present (false if absent). */
    infix fun lt(c: Double): BoolExpr = guarded(value lt c)

    /** `this ≥ c`, and present (false if absent). */
    infix fun ge(c: Double): BoolExpr = guarded(value ge c)

    /** `this > c`, and present (false if absent). */
    infix fun gt(c: Double): BoolExpr = guarded(value gt c)

    /** `this = c`, and present (false if absent). */
    infix fun eq(c: Double): BoolExpr = guarded(value eq c)

    /** `this ≠ c`, and present (false if absent). */
    infix fun ne(c: Double): BoolExpr = guarded(value ne c)

    /** `this ≤ other`, and present (false if absent). */
    infix fun le(other: FloatExpr): BoolExpr = guarded(value le other)

    /** `this < other`, and present (false if absent). */
    infix fun lt(other: FloatExpr): BoolExpr = guarded(value lt other)

    /** `this ≥ other`, and present (false if absent). */
    infix fun ge(other: FloatExpr): BoolExpr = guarded(value ge other)

    /** `this > other`, and present (false if absent). */
    infix fun gt(other: FloatExpr): BoolExpr = guarded(value gt other)

    /** `this = other`, and present (false if absent). */
    infix fun eq(other: FloatExpr): BoolExpr = guarded(value eq other)

    /** `this ≠ other`, and present (false if absent). */
    infix fun ne(other: FloatExpr): BoolExpr = guarded(value ne other)

    private fun cmpOpt(other: OptFloatHandle, cmp: BoolExpr): BoolExpr =
        And(listOf(present.toExpr(), other.present.toExpr(), cmp))

    /** `this ≤ other`, and both present (false if either absent). */
    infix fun le(other: OptFloatHandle): BoolExpr = cmpOpt(other, value le other.value)

    /** `this < other`, and both present (false if either absent). */
    infix fun lt(other: OptFloatHandle): BoolExpr = cmpOpt(other, value lt other.value)

    /** `this ≥ other`, and both present (false if either absent). */
    infix fun ge(other: OptFloatHandle): BoolExpr = cmpOpt(other, value ge other.value)

    /** `this > other`, and both present (false if either absent). */
    infix fun gt(other: OptFloatHandle): BoolExpr = cmpOpt(other, value gt other.value)

    /** `this = other`, and both present (false if either absent). */
    infix fun eq(other: OptFloatHandle): BoolExpr = cmpOpt(other, value eq other.value)

    /** `this ≠ other`, and both present (false if either absent). */
    infix fun ne(other: OptFloatHandle): BoolExpr = cmpOpt(other, value ne other.value)
}

/**
 * Optional nominal variable: a `(present, value)` pair. Comparisons against a label or another
 * nominal follow MiniZinc opt semantics — absent operands make the comparison false.
 */
class OptNominalHandle(
    /** Name of the optional variable. */
    val name: String,
    /** Presence literal: true iff the variable is present. */
    val present: BoolHandle,
    /** The value handle, meaningful only when [present]. */
    val value: NominalHandle,
) {
    /** The valid category labels. */
    val labels: List<String> get() = value.labels

    /** `this == label` and present (false if absent); [label] must be one of [labels]. */
    infix fun eq(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return And(listOf(present.toExpr(), NominalEq(value.name, label)))
    }

    /** `this != label` and present (false if absent); [label] must be one of [labels]. */
    infix fun ne(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return And(listOf(present.toExpr(), Not(NominalEq(value.name, label))))
    }
}
