package com.eignex.klause.solver.count

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.GaussianXor
import com.eignex.klause.solver.factor.Xor

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
 * `cap + 1`.
 *
 * The XOR hashes are propagated jointly by Gauss-Jordan elimination (see [GaussianXor], wired in by
 * [withHashes]), so the backtrack solver finds every model in the hashed cell quickly — early
 * parity conflict/forcing keeps the search off infeasible branches. A [CELL_DECISION_BUDGET] cap
 * bounds the rare residual thrash (e.g. when a clause's variables are parity-determined, the
 * exhaustion proof can still wander): because Gaussian finds all models *before* that tail, cutting
 * it leaves the count correct. The cap fires cleanly because Gaussian conflicts are asserting (the
 * decision counter advances), unlike the native [Xor] factor's non-asserting parity conflicts.
 */
internal fun cellCount(ctx: CellContext, hashes: List<Xor>, cap: Int): CellResult {
    val params = BacktrackParams(maxDecisions = CELL_DECISION_BUDGET)
    val enumeration = BacktrackSolver(ctx.problem.withHashes(hashes)).enumerate(params)
    // For hashed cells, draw at most cap+1 models: that is exactly enough to decide ">cap" and it
    // keeps us out of the exhaustion tail that can thrash. The un-hashed base has no parity slices
    // and is safe (and necessary, for exact projected counts) to enumerate fully.
    val models = if (hashes.isEmpty()) enumeration else enumeration.take(cap + 1)
    val reps = LinkedHashMap<List<Int>, Sample>()
    for (model in models) {
        val key = ctx.projectionKey(model)
        if (key !in reps) {
            reps[key] = ctx.decode(model)
            if (reps.size > cap) break
        }
    }
    return CellResult(count = reps.size, capped = reps.size > cap, representatives = reps.values.toList())
}

/** Per-cell decision budget; bounds the residual exhaustion-tail thrash (see [cellCount]). */
internal const val CELL_DECISION_BUDGET: Long = 500_000L

/** The default sampling set: every Boolean variable of the problem. */
internal fun Problem.allBoolVars(): IntArray = IntArray(numBoolVars) { it }
