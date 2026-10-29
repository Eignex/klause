package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.SharingMemoryObservation

internal class RootSimplificationPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0
    private var scanEnd = 0
    private var scanRoot = -1
    override val spending = InprocessingSpending(InprocessingKind.Root)
    private var dropped = IntHashSet()
    private var revision = -1L
    private var pendingSource: Pair<Int, IntArray>? = null
    private var pending: RootCandidate? = null

    override fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(dropped, dropped.allocatedBytes, protected)
        pendingSource?.let { source ->
            sample.step()
            sample.ownedReferences(source, 1L, protected)
            val literals = source.second
            sample.owned(literals, literals.size.toLong() * Int.SIZE_BYTES, protected)
        }
        pending?.let { candidate ->
            sample.step()
            sample.ownedReferences(candidate, 2L, protected)
            sample.owned(candidate.literals, candidate.literals.size.toLong() * Int.SIZE_BYTES, protected)
            val residual = candidate.residual.backingData
            sample.owned(residual, residual.size.toLong() * Int.SIZE_BYTES, protected)
        }
    }

    override fun reset() {
        cursor = 0
        scanEnd = 0
        scanRoot = -1
        dropped = IntHashSet()
        revision = -1L
        pendingSource = null
        pending = null
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot) return
        spending.run(session, params, stats) { budget ->
            simplify(session, params, budget)
            PrivateRebuildCapture.observe(session, budget, params.rootRebuildCaptureVisits)
        }
    }

    private fun simplify(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        val count = session.rootClauseCount
        if (revision != session.learnedClauseRevision) {
            dropped = IntHashSet()
            pending = null
            cursor = 0
            scanEnd = count
            scanRoot = session.explanationState.undoTop
        }
        revision = session.learnedClauseRevision
        if (count == 0) return
        pendingSource?.let { (factor, literals) ->
            if (!budget.visit(literals.size + 1)) return
            session.retireRootSource(factor, literals)
            budget.note("RetirementWork", literals.size + 1L)
            pendingSource = null
        }
        if (!dropped.isEmpty()) {
            if (budget.stopped()) return
            val before = session.work
            val beforeCount = session.learnedClauseCount
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
            dropped = IntHashSet()
            revision = session.learnedClauseRevision
            if (session.learnedClauseCount < beforeCount) {
                cursor = 0
                scanEnd = session.rootClauseCount
            }
            return
        }
        if (cursor >= scanEnd && pending == null) {
            if (scanRoot != session.explanationState.undoTop) {
                cursor = 0
                scanRoot = session.explanationState.undoTop
            }
            scanEnd = count
        }
        var examined = 0
        var literalsVisited = 0
        while ((pending != null || cursor < scanEnd) && examined++ < params.rootSimplifyBatch) {
            if (!session.canSimplifyRoot || literalsVisited >= ROOT_LITERAL_LIMIT || budget.stopped()) break
            var candidate = pending
            if (candidate == null) {
                if (!budget.visit()) break
                val factor = cursor++
                budget.note("Candidates")
                val literals = session.rootClauseLiterals(factor, ROOT_LITERAL_LIMIT - literalsVisited)
                if (literals == null) {
                    if (literalsVisited > 0) {
                        cursor--
                        break
                    }
                    continue
                }
                budget.charge(literals.size)
                literalsVisited += literals.size
                if (literals.size < 2) continue
                budget.note("Eligible")
                candidate = RootCandidate(factor, literals)
                pending = candidate
            }
            val factor = candidate.factor
            val literals = candidate.literals
            val residual = candidate.residual
            while (candidate.next < literals.size && !candidate.satisfied) {
                if (budget.stopped()) return
                when (budget.truth(literals[candidate.next++])) {
                    true -> candidate.satisfied = true
                    false -> Unit
                    null -> residual.add(literals[candidate.next - 1])
                }
            }
            val satisfied = candidate.satisfied
            if (!satisfied && (residual.isEmpty() || residual.size == literals.size)) {
                pending = null
                continue
            }
            val source = factor < session.problem.numFactors
            if (!satisfied) {
                val index = factor - session.problem.numFactors
                val origin = if (source) OWN_ORIGIN else session.learnedClauseOrigin(index)
                val lbd = if (source) residual.size else minOf(session.learnedClauseLbd(index), residual.size)
                if (!budget.visit(residual.size * 2 + 1)) break
                val before = session.work
                session.addRootDerivedClause(Clause(residual.toIntArray()), lbd, origin, source)
                pending = null
                if (source) pendingSource = factor to literals
                else dropped.add(index)
                budget.note("AttachmentWork", session.work - before + residual.size * 2 + 1L)
                budget.note("Shortened")
                if (!session.canSimplifyRoot) break
            }
            if (budget.stopped()) break
            if (source) {
                if (!budget.visit(literals.size + 1)) break
                session.retireRootSource(factor, literals)
                pendingSource = null
                budget.note("RetirementWork", literals.size + 1L)
            } else dropped.add(factor - session.problem.numFactors)
            pending = null
        }
        val beforeCompaction = session.learnedClauseCount
        if (!dropped.isEmpty() && !budget.stopped()) {
            val before = session.work
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
            dropped = IntHashSet()
            revision = session.learnedClauseRevision
        }
        if (session.learnedClauseCount < beforeCompaction) {
            cursor = 0
            scanEnd = session.rootClauseCount
        }
    }
}

private class RootCandidate(val factor: Int, val literals: IntArray) {
    val residual = IntArrayList(literals.size)
    var next = 0
    var satisfied = false
}

internal class RootProbingPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0
    override val spending = InprocessingSpending(InprocessingKind.Probe)

    override fun reset() {
        cursor = 0
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot || params.rootProbeWork <= 0L) return
        spending.run(session, params, stats) { budget -> probe(session, params, budget) }
    }

    private fun probe(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        val variables = session.problem.numBoolVars
        if (variables == 0) return
        var attempted = 0
        var examined = 0
        val priorCancelled = session.probeCancelled
        while (attempted < params.rootProbeBatch && examined < minOf(2L * variables, ROOT_LITERAL_LIMIT.toLong())) {
            if (!session.canSimplifyRoot || !budget.visit()) break
            val literal = cursor % (2 * variables)
            val variable = Lit.variable(literal)
            cursor = (literal + 1) % (2 * variables)
            examined++
            budget.note("Candidates")
            if (session.boolValue(variable) != null) continue
            attempted++
            budget.note("Eligible")
            budget.probe()
            val start = session.work
            val token = Cancellation { session.work - start >= params.rootProbeWork }
            val result = session.withCancellation(token) { session.probeBool(variable, Lit.isPositive(literal)) }
            when (result) {
                null -> if (!params.cancellation()) session.probeCancelled = priorCancelled
                is PropagationResult.Unsat -> {
                    if (!budget.visit(3)) break
                    val before = session.work
                    session.addRootDerivedClause(Clause(intArrayOf(Lit.negate(literal))), 1, OWN_ORIGIN, false)
                    budget.note("AttachmentWork", session.work - before + 3L)
                    budget.note("Units")
                }
                else -> session.popLast()
            }
        }
    }
}

internal const val ROOT_LITERAL_LIMIT = 8192
