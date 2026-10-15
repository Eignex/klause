package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb

internal fun Compiler.Builder.selectionTerm(condition: Int, whenTrue: Int, whenFalse: Int): IntComb {
    val index = litTo01(condition)
    val result = newAuxVar(minOf(domains[whenTrue].min, domains[whenFalse].min),
        maxOf(domains[whenTrue].max, domains[whenFalse].max))
    factors.add(Element(index, result, longArrayOf(whenFalse.toLong(), whenTrue.toLong()),
        arrIsVars = true, indexOffset = 0))
    definedVars.add(result)
    return IntComb.Narrow(LinComb(mapOf(result to 1L), 0L))
}

internal fun selectionOffset(element: XmlElement): Int {
    val standard = element.child("list")?.attr("startIndex").orEmpty()
    val legacy = element.attr("startIndex")
    val value = standard.ifBlank { legacy }.ifBlank { "0" }.toIntOrNull()
        ?: throw UnsupportedXcsp3Exception("selection: invalid startIndex")
    if (standard.isNotBlank() && legacy.isNotBlank() && legacy.toIntOrNull() != value) {
        throw UnsupportedXcsp3Exception("selection: conflicting startIndex attributes")
    }
    return value
}

internal fun requireAnySelectionRank(element: XmlElement) {
    val rank = element.child("index")?.attr("rank").orEmpty()
    if (rank.isNotBlank() && rank != "any") throw UnsupportedXcsp3Exception("selection: unsupported rank '$rank'")
}

internal fun Compiler.Builder.elementConstantMatrix(
    rows: List<IntArray>,
    row: Int,
    column: Int,
    result: Int,
    rowOffset: Int,
    columnOffset: Int,
) {
    val cells = rows.sumOf { it.size.toLong() }
    require(cells <= Int.MAX_VALUE) { "element: matrix is too large" }
    if (cells == 0L) {
        factors.add(Clause(intArrayOf()))
        return
    }
    var offset = 0L
    val starts = LongArray(rows.size) { position -> offset.also { offset += rows[position].size } }
    val lengths = LongArray(rows.size) { rows[it].size.toLong() }
    val start = newAuxVar(starts.min(), starts.max())
    val length = newAuxVar(lengths.min(), lengths.max())
    val index = newAuxVar(0, cells - 1)
    definedVars.add(start)
    definedVars.add(length)
    definedVars.add(index)
    definedVars.add(result)
    factors.add(Element(row, start, starts, arrIsVars = false, indexOffset = rowOffset))
    factors.add(Element(row, length, lengths, arrIsVars = false, indexOffset = rowOffset))
    factors.add(Linear(longArrayOf(1), intArrayOf(column), LinearOp.GE, columnOffset.toLong()))
    factors.add(Linear(longArrayOf(1, -1), intArrayOf(column, length), LinearOp.LE, columnOffset.toLong() - 1))
    factors.add(Linear(longArrayOf(1, 1, -1), intArrayOf(start, column, index), LinearOp.EQ,
        columnOffset.toLong()))
    factors.add(Element(index, result, rows.flatMap { it.map(Int::toLong) }.toLongArray(),
        arrIsVars = false, indexOffset = 0))
}
