package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.ClauseTier
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.SharingMemoryObservation

/** Reduce the learned-clause database at a restart without discarding permanent or glue clauses. */
internal fun forgetIfOverCap(
    session: PropagationSession,
    params: BacktrackParams,
    protectedPublication: Boolean = false,
) {
    if (protectedPublication) return
    val cap = params.maxLearnedClauses ?: return
    val startWork = session.work
    if (session.activeLearnedClauseCount > cap) forgetWindow(session, params, cap, startWork)
    if (session.learnedClauseCount > session.activeLearnedClauseCount) {
        session.compactLearnedClauses(
            (params.learnedMemoryWork - (session.work - startWork)).coerceAtLeast(0L),
            params.learnedMemoryScratchBytes, { params.cancellation() }) { _, _ -> true }
    }
}

private fun forgetWindow(session: PropagationSession, params: BacktrackParams, cap: Int, startWork: Long) {
    val retired = session.maintainCapWindow(cap,
        (params.learnedMemoryWork - (session.work - startWork)).coerceAtLeast(0L),
        params.learnedMemoryScratchBytes, { params.cancellation() }, { index ->
            !session.learnedClausePermanent(index) && session.learnedClauseLbd(index) > params.lbdGlueThreshold &&
                (!params.tieredLearnedDb || (session.learnedClauseTier(index) == ClauseTier.LOCAL &&
                    !session.learnedClauseUsedSinceReduction(index)))
        }) { index ->
        val lbd = session.learnedClauseLbd(index)
        if (!params.tieredLearnedDb) {
            lbd <= params.lbdGlueThreshold
        } else {
            val used = session.learnedClauseUsedSinceReduction(index)
            val tier = session.learnedClauseTier(index).takeUnless { it == ClauseTier.UNSET } ?: when {
                lbd <= params.lbdGlueThreshold -> ClauseTier.CORE
                lbd <= params.midLbdThreshold -> ClauseTier.MID
                else -> ClauseTier.LOCAL
            }
            val next = when (tier) {
                ClauseTier.CORE -> ClauseTier.CORE
                ClauseTier.MID -> if (used) ClauseTier.MID else ClauseTier.LOCAL
                ClauseTier.LOCAL -> if (used) ClauseTier.MID else ClauseTier.LOCAL
                ClauseTier.UNSET -> error("tier was resolved")
            }
            session.setLearnedClauseTier(index, next)
            session.clearLearnedClauseUsed(index)
            tier != ClauseTier.LOCAL || used
        }
    }
    if (retired > 0) {
        params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = session.activeLearnedClauseCount, dropped = retired))
    }
}

// Retaining the batch prevents repeated attachments when retirement must wait for another slice.
internal class VivificationPass(startCursor: Int = 0) : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    override val spending = InprocessingSpending(InprocessingKind.Vivification)
    var cursor = startCursor
        private set
    private var revision = -1L
    private var rows = ArrayList<IntArray>()
    private var lbds = IntArrayList()
    private var origins = IntArrayList()
    private var dropped = IntHashSet()
    private var attached = 0
    private var preparing = true

    override fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.ownedReferences(rows, rows.size.toLong(), protected)
        for (row in rows) {
            sample.step()
            sample.owned(row, row.size.toLong() * Int.SIZE_BYTES, protected)
        }
        val lbdData = lbds.backingData
        val originData = origins.backingData
        sample.owned(lbdData, lbdData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(originData, originData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(dropped, dropped.allocatedBytes, protected)
    }

    override fun reset() {
        cursor = 0
        clear()
    }

    private fun clear() {
        rows = ArrayList()
        lbds = IntArrayList()
        origins = IntArrayList()
        dropped = IntHashSet()
        attached = 0
        preparing = true
        revision = -1L
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot) return
        if (revision != session.learnedClauseRevision) clear()
        revision = session.learnedClauseRevision
        spending.run(session, params, stats) { advance(session, params, it) }
    }

    private fun advance(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        val count = session.learnedClauseCount
        if (count == 0) return
        if (cursor !in 0 until count) cursor = 0
        if (preparing) {
            var examined = 0
            var literalsVisited = 0
            while (examined++ < minOf(params.vivifyBatch.coerceAtLeast(1), count)) {
                if (literalsVisited >= ROOT_LITERAL_LIMIT || !budget.visit()) break
                val index = cursor
                cursor = (cursor + 1) % count
                budget.note("Candidates")
                val literals = session.rootClauseLiterals(session.problem.numFactors + index,
                    ROOT_LITERAL_LIMIT - literalsVisited) ?: continue
                budget.charge(literals.size)
                literalsVisited += literals.size
                if (literals.size < 3) continue
                budget.note("Eligible")
                val strengthened = vivifyClause(session, literals, params, budget) ?: continue
                if (strengthened.size !in 2 until literals.size) continue
                rows.add(strengthened)
                lbds.add(minOf(session.learnedClauseLbd(index), strengthened.size))
                origins.add(session.learnedClauseOrigin(index))
                dropped.add(index)
            }
            preparing = false
        }
        while (attached < rows.size) {
            val row = rows[attached]
            if (!budget.visit(row.size * 2 + 1)) return
            val before = session.work
            session.addRootDerivedClause(Clause(row), lbds[attached], origins[attached], false)
            budget.note("AttachmentWork", session.work - before + row.size * 2 + 1L)
            budget.note("Shortened")
            attached++
        }
        if (!dropped.isEmpty()) {
            if (budget.stopped()) return
            val before = session.work
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
            cursor = 0
        }
        clear()
    }
}

internal fun vivify(
    session: PropagationSession,
    params: BacktrackParams,
    startCursor: Int,
    stats: SearchStatsSink? = null,
): Int = VivificationPass(startCursor).also { it.run(session, params, stats) }.cursor

private fun vivifyClause(
    session: PropagationSession,
    literals: IntArray,
    params: BacktrackParams,
    budget: InprocessingBudget,
): IntArray? {
    val kept = IntArrayList(literals.size)
    var pushes = 0
    var result: IntArray? = null
    val priorCancelled = session.probeCancelled
    try {
        for (literal in literals) {
            if (budget.stopped()) return null
            when (budget.truth(literal)) {
                true -> {
                    kept.add(literal)
                    result = kept.toIntArray()
                    break
                }

                false -> Unit

                null -> {
                    kept.add(literal)
                    budget.probe()
                    when (session.probeBool(Lit.variable(literal), !Lit.isPositive(literal))) {
                        null -> return null
                        is PropagationResult.Unsat -> {
                            result = kept.toIntArray()
                            break
                        }
                        else -> pushes++
                    }
                }
            }
        }
        return result ?: kept.toIntArray().takeIf { it.size < literals.size }
    } finally {
        repeat(pushes) { session.popLast() }
        if (!params.cancellation()) session.probeCancelled = priorCancelled
    }
}

internal fun snapshotAssignment(session: PropagationSession): Sample = Sample(
    BooleanArray(session.problem.numBoolVars) { session.boolValue(it) ?: false },
    LongArray(session.problem.numIntVars) { session.intDomain(it).min },
)

internal fun BacktrackSolver.farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean =
    minDistance <= 0 || window.all { candidate.hammingDistanceTo(it) >= minDistance }
