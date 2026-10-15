package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import kotlin.math.abs
import kotlin.math.round

internal fun FlatZincCompiler.emitFloatSelection(c: FznConstraint) {
    expectArity(c, 3)
    val idx = resolveIntVar(c.args[0])
    val arr = evalFloatConstArray(c.args[1])
    val xRef = resolveFloatVarOrConst(c.args[2])
    val x = when (xRef) {
        is FloatRef.Var -> xRef.bk
        is FloatRef.Const -> resolveFloatElement(c.args[2], "__float_element_${floatVars.size}", exactFloats)
    }
    if (x.lpOnly) {
        factors.add(RealElement(idx, x.varId, arr))
        val domain = intDomains[idx]
        if (domain.min == domain.max && domain.min in 1L..arr.size.toLong()) {
            factors.add(Clause(intArrayOf(exactFloatValueLiteral(x.varId, arr[(domain.min - 1).toInt()]))))
        }
        return
    }
    val stepX = if (x.buckets > 1) (x.hi - x.lo) / (x.buckets - 1) else 0.0
    val dom = intDomains[idx]
    val tolerance = 0.5
    var reachable = 0
    val buckets = LongArray(arr.size) { position ->
        if (position.toLong() + 1 !in dom) return@LongArray 0L
        reachable++
        val cv = arr[position]
        if (cv < x.lo - stepX * tolerance || cv > x.hi + stepX * tolerance) {
            failHere("array_float_element: value $cv not representable under the current float bucketing")
        }
        val ix = if (stepX == 0.0) {
            0
        } else {
            ((cv - x.lo) / stepX).let {
                val rounded = round(it).toInt()
                if (abs(it - rounded) > tolerance) {
                    failHere("array_float_element: value $cv not representable under the current float bucketing")
                }
                rounded
            }
        }
        if (ix < 0 || ix >= x.buckets) {
            failHere("array_float_element: value $cv not representable under the current float bucketing")
        }
        ix.toLong()
    }
    if (reachable == 0) failHere("array_float_element: no valid index value in the domain")
    factors.add(Element(idx, x.varId, buckets, arrIsVars = false))
}

internal fun FlatZincCompiler.emitRealMinMax(c: FznConstraint, max: Boolean): Boolean {
    val variables = c.args.map(::resolveFloatVarOrConst).filterIsInstance<FloatRef.Var>()
    if (variables.any { !it.bk.lpOnly }) return false
    val operands = c.args.map { resolveFloatElement(it, "__real_extremum_${floatVars.size}", lpOnly = true).varId }
    factors.add(RealArrayMinMax(operands[2], intArrayOf(operands[0], operands[1]), max))
    return true
}
