package com.eignex.klause.schema

import com.eignex.klause.ast.And
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolTerm
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntIfThenElse
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntTerm
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or

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
    val name: String,
    val present: BoolHandle,
    val value: IntHandle,
) {
    val min: Int get() = value.min
    val max: Int get() = value.max

    /** Yields an [IntExpr] equal to [value] when [present], otherwise [default]. Safe in any
     *  arithmetic context. */
    fun valueOr(default: Int): IntExpr =
        IntIfThenElse(present.toExpr(), value.toIntExpr(), IntLit(default))

    fun valueOr(default: IntTerm): IntExpr =
        IntIfThenElse(present.toExpr(), value.toIntExpr(), default.toIntExpr())

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

    infix fun le(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.LE)
    infix fun lt(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.LT)
    infix fun ge(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.GE)
    infix fun gt(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.GT)
    infix fun eq(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.EQ)
    infix fun ne(other: IntTerm): BoolExpr = cmpRhsInt(other, IntCmpOp.NE)

    infix fun le(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.LE)
    infix fun lt(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.LT)
    infix fun ge(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.GE)
    infix fun gt(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.GT)
    infix fun eq(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.EQ)
    infix fun ne(c: Int): BoolExpr = cmpRhsConst(c, IntCmpOp.NE)

    infix fun le(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.LE)
    infix fun lt(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.LT)
    infix fun ge(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.GE)
    infix fun gt(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.GT)
    infix fun eq(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.EQ)
    infix fun ne(other: OptIntHandle): BoolExpr = cmpRhsOpt(other, IntCmpOp.NE)
}

/**
 * Optional Boolean variable: a `(present, value)` pair. Like [OptIntHandle], the value is
 * meaningful only when [present] is true; in a Boolean context the opt-bool itself evaluates
 * to `present ∧ value` so absent operands silently become false.
 */
class OptBoolHandle(
    val name: String,
    val present: BoolHandle,
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
 * Optional nominal variable: a `(present, value)` pair. Comparisons against a label or another
 * nominal follow MiniZinc opt semantics — absent operands make the comparison false.
 */
class OptNominalHandle(
    val name: String,
    val present: BoolHandle,
    val value: NominalHandle,
) {
    val labels: List<String> get() = value.labels

    infix fun eq(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return And(listOf(present.toExpr(), NominalEq(value.name, label)))
    }

    infix fun ne(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return And(listOf(present.toExpr(), Not(NominalEq(value.name, label))))
    }
}
