package com.eignex.klause.compile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lowering.extremeBounds
import com.eignex.klause.lowering.newBooleanInt
import com.eignex.klause.lowering.newIntExtreme
import com.eignex.klause.lowering.newIntSelection
import com.eignex.klause.lowering.selectionBounds
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntRef

internal fun Lowering.liftElement(index: IntExpr, items: List<IntExpr>): IntExpr {
    val idx = intVarOf(materializeIntVar(lift(index)).name)
    val entries = IntArray(items.size) { intVarOf(materializeIntVar(lift(items[it])).name) }
    val result = problemBuilder.newIntSelection(idx, LongArray(entries.size) { entries[it].toLong() },
        selectionBounds(entries, intDomains), freshInt = { intVarOf(newAuxIntVar(it)) })
    return IntRef(idToIntName.getValue(result))
}

internal fun Lowering.liftIfThenElse(cond: BoolExpr, thenE: IntExpr, elseE: IntExpr): IntExpr {
    val thenId = intVarOf(materializeIntVar(lift(thenE)).name)
    val elseId = intVarOf(materializeIntVar(lift(elseE)).name)
    val literal = lowerToLit(cond)
    val index = problemBuilder.newBooleanInt(literal) { intVarOf(newAuxIntVar(it)) }
    val domain = IntDomain(minOf(intDomains[thenId].min, intDomains[elseId].min),
        maxOf(intDomains[thenId].max, intDomains[elseId].max))
    val result = problemBuilder.newIntSelection(index, longArrayOf(elseId.toLong(), thenId.toLong()),
        domain, freshInt = { intVarOf(newAuxIntVar(it)) })
    return IntRef(idToIntName.getValue(result))
}

internal fun Lowering.liftMinMax(children: List<IntExpr>, isMin: Boolean): IntExpr {
    val entries = IntArray(children.size) { intVarOf(materializeIntVar(lift(children[it])).name) }
    val result = problemBuilder.newIntExtreme(entries, max = !isMin,
        domain = extremeBounds(entries, intDomains, max = !isMin), freshInt = { intVarOf(newAuxIntVar(it)) })
    return IntRef(idToIntName.getValue(result))
}
