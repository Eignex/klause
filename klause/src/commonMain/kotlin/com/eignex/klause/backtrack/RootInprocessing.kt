package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet

internal class RootSimplificationPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0

    override fun reset() {
        cursor = 0
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot) return
        val count = session.rootClauseCount
        if (count == 0) return
        val dropped = IntHashSet()
        var visits = 0L
        var changed = false
        if (cursor !in 0 until count) cursor = 0
        var examined = 0
        while (examined++ < minOf(params.rootSimplifyBatch, count)) {
            if (session.cancelRequested || !session.canSimplifyRoot || visits >= ROOT_LITERAL_LIMIT) break
            visits++
            val factor = cursor
            cursor = (cursor + 1) % count
            val literals = session.rootClauseLiterals(factor, ROOT_LITERAL_LIMIT - visits.toInt()) ?: continue
            visits += literals.size
            if (literals.size < 2) continue
            val satisfied = literals.any { session.litTruth(it) == true }
            val residual = if (satisfied) null else literals.filter { session.litTruth(it) != false }.toIntArray()
            if (!satisfied && (residual == null || residual.isEmpty() || residual.size == literals.size)) continue
            val source = factor < session.problem.numFactors
            if (residual != null) {
                val index = factor - session.problem.numFactors
                val origin = if (source) OWN_ORIGIN else session.learnedClauseOrigin(index)
                val lbd = if (source) residual.size else minOf(session.learnedClauseLbd(index), residual.size)
                session.addRootDerivedClause(Clause(residual), lbd, origin, source)
                if (!session.canSimplifyRoot) continue
            }
            if (source) session.retireRootSource(factor, literals)
            else dropped.add(factor - session.problem.numFactors)
            changed = true
        }
        stats?.observeInprocessing(probes = 0L, visits = visits)
        if (!dropped.isEmpty()) session.forgetLearnedClauses { index, _ -> index !in dropped }
        // Learned indices belong to this snapshot; compaction and imports may change them before the next run.
        if (changed) cursor = 0
    }
}

internal class RootProbingPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0

    override fun reset() {
        cursor = 0
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot || params.rootProbeWork <= 0L) return
        val variables = session.problem.numBoolVars
        if (variables == 0) return
        var attempted = 0
        var examined = 0
        val priorCancelled = session.probeCancelled
        while (attempted < params.rootProbeBatch && examined < minOf(2 * variables, ROOT_LITERAL_LIMIT)) {
            if (session.cancelRequested || !session.canSimplifyRoot) break
            val literal = cursor % (2 * variables)
            val variable = Lit.variable(literal)
            cursor = (literal + 1) % (2 * variables)
            examined++
            if (session.boolValue(variable) != null) continue
            attempted++
            stats?.observeInprocessing(probes = 1L, visits = 0L)
            val start = session.work
            val token = Cancellation { session.work - start >= params.rootProbeWork }
            val result = session.withCancellation(token) { session.probeBool(variable, Lit.isPositive(literal)) }
            when (result) {
                null -> {
                    // A local probe limit retracts its entire partial fixpoint; it is not a search cancellation.
                    if (!session.cancelRequested) session.probeCancelled = priorCancelled
                }
                is PropagationResult.Unsat -> {
                    // At an unconditional root the failed decision is the complete premise.
                    session.addRootDerivedClause(Clause(intArrayOf(Lit.negate(literal))), 1, OWN_ORIGIN, false)
                }
                else -> session.popLast()
            }
        }
        stats?.observeInprocessing(probes = 0L, visits = examined.toLong())
    }
}

private const val ROOT_LITERAL_LIMIT = 8192
