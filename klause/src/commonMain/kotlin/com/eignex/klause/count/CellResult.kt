package com.eignex.klause.count

import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample

// count is the number of distinct projections observed, capped at cap + 1; capped means more than cap were found.
// complete means enumeration certified exhaustion; otherwise the count is only a lower bound. representatives
// holds one decoded sample per distinct projection, which UniGen draws from.
internal data class CellResult(
    val count: Int,
    val capped: Boolean,
    val representatives: List<Sample>,
    val complete: Boolean,
)

internal fun cellCount(ctx: CellContext, hashes: List<Xor>, cap: Int): CellResult = ctx.countCell(hashes, cap)

// An interrupted cell supplies only a lower bound.
internal const val CELL_DECISION_BUDGET: Long = 500_000L

internal fun Problem.allBoolVars(): IntArray = IntArray(numBoolVars) { it }
