package com.eignex.klause.solver.count

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.bool.Xor

/**
 * Outcome of counting the satisfying assignments inside one XOR-hash cell, up to a cap.
 *
 * [count] is the number of *distinct projections* (onto the context's sampling set) observed,
 * capped at `cap + 1`. [capped] is true when the count hit the cap (so the true cell is at least
 * this large). [representatives] holds one decoded (original-variable) [Sample] per distinct
 * projection — reused by [UniGen] to draw a uniform member of the cell.
 */
internal data class CellResult(val count: Int, val capped: Boolean, val representatives: List<Sample>)

/**
 * Count distinct projections of the cell carved out by [hashes] from [ctx]'s problem, up to
 * `cap + 1`. Delegates to [CellContext.countCell]; the counting strategy (DFS enumeration for a
 * Boolean projection, projected solve-under-assumptions for an integer projection) lives there.
 */
internal fun cellCount(ctx: CellContext, hashes: List<Xor>, cap: Int): CellResult = ctx.countCell(hashes, cap)

/** Per-cell decision budget; bounds the residual exhaustion-tail thrash (see [CellContext.countCell]). */
internal const val CELL_DECISION_BUDGET: Long = 500_000L

/** The default sampling set: every Boolean variable of the problem. */
internal fun Problem.allBoolVars(): IntArray = IntArray(numBoolVars) { it }
