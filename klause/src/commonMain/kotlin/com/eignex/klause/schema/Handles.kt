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
 * Float variable bucketed at the schema layer. All comparisons resolve at construction time
 * to integer comparisons against the bucket index, so the AST stays integer-only beyond the
 * schema definition itself. Float arithmetic is intentionally not provided; combine floats
 * via integer expressions on bucket indices if you need it.
 */
class FloatHandle(val name: String, val min: Double, val max: Double, val buckets: Int) {
    private val ref: IntRef = IntRef(name)

    fun bucketOf(value: Double): Int {
        val clamped = value.coerceIn(min, max)
        val frac = (clamped - min) / (max - min)
        return (frac * (buckets - 1)).roundToInt().coerceIn(0, buckets - 1)
    }

    infix fun le(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.LE, IntLit(bucketOf(c)))
    infix fun lt(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.LT, IntLit(bucketOf(c)))
    infix fun ge(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.GE, IntLit(bucketOf(c)))
    infix fun gt(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.GT, IntLit(bucketOf(c)))
    infix fun eq(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.EQ, IntLit(bucketOf(c)))
    infix fun ne(c: Double): BoolExpr = IntCompare(ref, IntCmpOp.NE, IntLit(bucketOf(c)))
}
