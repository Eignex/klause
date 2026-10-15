package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntRef

internal fun Lowering.liftElement(index: IntExpr, items: List<IntExpr>): IntExpr {
    val idx = intVarOf(materializeIntVar(lift(index)).name)
    val entries = items.map { intVarOf(materializeIntVar(lift(it)).name) }
    val result = newAuxIntVar(IntDomain(entries.minOf { intDomains[it].min }, entries.maxOf { intDomains[it].max }))
    factors += Element(idx, intVarOf(result), entries.map { it.toLong() }.toLongArray(),
        arrIsVars = true, indexOffset = 0)
    return IntRef(result)
}

internal fun Lowering.liftIfThenElse(cond: BoolExpr, thenE: IntExpr, elseE: IntExpr): IntExpr {
    val thenId = intVarOf(materializeIntVar(lift(thenE)).name)
    val elseId = intVarOf(materializeIntVar(lift(elseE)).name)
    val literal = lowerToLit(cond)
    val index = intVarOf(newAuxIntVar(IntDomain(0, 1)))
    factors += ReifiedLinear(Lit.variable(literal), intArrayOf(1), intArrayOf(index), LinearOp.EQ,
        if (Lit.isPositive(literal)) 1 else 0)
    val result = newAuxIntVar(IntDomain(minOf(intDomains[thenId].min, intDomains[elseId].min),
        maxOf(intDomains[thenId].max, intDomains[elseId].max)))
    factors += Element(index, intVarOf(result), longArrayOf(elseId.toLong(), thenId.toLong()),
        arrIsVars = true, indexOffset = 0)
    return IntRef(result)
}

internal fun Lowering.liftMinMax(children: List<IntExpr>, isMin: Boolean): IntExpr {
    val entries = children.map { intVarOf(materializeIntVar(lift(it)).name) }.toIntArray()
    val domain = if (isMin) {
        IntDomain(entries.minOf { intDomains[it].min }, entries.minOf { intDomains[it].max })
    } else {
        IntDomain(entries.maxOf { intDomains[it].min }, entries.maxOf { intDomains[it].max })
    }
    val result = newAuxIntVar(domain)
    factors += ArrayMinMax(intVarOf(result), entries, max = !isMin)
    return IntRef(result)
}
