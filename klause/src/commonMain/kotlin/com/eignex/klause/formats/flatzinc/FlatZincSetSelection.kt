package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.util.binarySearchInt

internal fun FlatZincCompiler.emitSetSelection(constraint: FznConstraint, variableArray: Boolean) {
    expectArity(constraint, 3)
    val index = resolveIntVar(constraint.args[0])
    val result = resolveSetVar(constraint.args[2])
    val variableRows = if (variableArray) resolveSetVarArray(constraint.args[1]) else null
    val constantRows = if (variableArray) null else {
        val name = (constraint.args[1] as? FznExpr.Ident)?.name
            ?: failHere("array_set_element: second arg must be an array identifier")
        (arrays[name] as? FlatZincArray.IntSetParam)?.values
            ?: failHere("array_set_element: expected array of set-of-int parameters")
    }
    val count = variableRows?.size ?: checkNotNull(constantRows).size
    if (count == 0) {
        factors.add(Clause(intArrayOf()))
        return
    }
    val universe = (result.elements.toList() + (variableRows?.flatMap { it.elements.toList() }
        ?: checkNotNull(constantRows).flatMap { it.toList() })).distinct().sorted()
    if (universe.isEmpty()) {
        val zero = allocInt("__set_selection_zero_${factors.size}", 0, 0)
        factors.add(Element(index, zero, LongArray(count), arrIsVars = false))
        return
    }
    val absent = Lit.negate(trueLit())
    for (value in universe) {
        val resultPosition = result.layout.indexOf(value)
        val resultLiteral = if (resultPosition < 0) absent else Lit.make(result.indicatorBoolIds[resultPosition], true)
        val resultColumn = channelBoolsToInts(intArrayOf(resultLiteral), "set_selection_result_${factors.size}")[0]
        val entries = if (variableRows != null) {
            val literals = IntArray(count) { row ->
                val position = variableRows[row].layout.indexOf(value)
                if (position < 0) absent else Lit.make(variableRows[row].indicatorBoolIds[position], true)
            }
            channelBoolsToInts(literals, "set_selection_entries_${factors.size}").map(Int::toLong).toLongArray()
        } else {
            LongArray(count) { row -> if (checkNotNull(constantRows)[row].binarySearchInt(value) >= 0) 1 else 0 }
        }
        factors.add(Element(index, resultColumn, entries, arrIsVars = variableArray))
    }
}
