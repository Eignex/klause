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
    val learnedSize = session.learnedClauseCount
    if (learnedSize <= cap) return
    val startWork = session.work
    val prepared = prepareCapReduction(session, params, learnedSize, params.tieredLearnedDb)
    if (prepared == null) {
        forgetWindow(session, params, cap, startWork)
        return
    }
    if (params.tieredLearnedDb) {
        forgetTiered(session, params, cap, learnedSize, prepared)
        return
    }
    val glueThreshold = params.lbdGlueThreshold
    val remaining = (cap - (learnedSize - prepared.count)).coerceAtLeast(0)
    if (prepared.count <= remaining) return
    if (!prepared.select(remaining)) return
    session.forgetLearnedClauses { index, lbd ->
        lbd <= glueThreshold || session.learnedClausePermanent(index) || prepared.kept[index]
    }
    val dropped = learnedSize - session.learnedClauseCount
    if (dropped > 0) params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped, dropped = dropped))
}

private fun forgetTiered(
    session: PropagationSession,
    params: BacktrackParams,
    cap: Int,
    learnedSize: Int,
    prepared: CapReductionPreparation,
) {
    val residual = (cap - (learnedSize - prepared.count)).coerceAtLeast(0)
    if (prepared.count > residual && !prepared.select(residual)) return
    if (!prepared.publishTiers()) return
    if (prepared.count > residual) {
        session.forgetLearnedClauses { index, _ -> prepared.kept[index] }
        val retired = learnedSize - session.learnedClauseCount
        if (retired > 0) {
            params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = session.learnedClauseCount, dropped = retired))
        }
    }
}

private fun forgetWindow(session: PropagationSession, params: BacktrackParams, cap: Int, startWork: Long) {
    val retired = session.maintainCapWindow(cap,
        (params.learnedMemoryWork - (session.work - startWork)).coerceAtLeast(0L),
        params.learnedMemoryScratchBytes, { params.cancellation() }) { index ->
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

private fun prepareCapReduction(
    session: PropagationSession,
    params: BacktrackParams,
    count: Int,
    tiered: Boolean,
): CapReductionPreparation? {
    val levels = Int.SIZE_BITS - count.countLeadingZeroBits()
    val work = count.toLong() * (levels * 4L + 16L)
    val scratch = count.toLong() * if (tiered) 13L else 9L
    if (work > params.learnedMemoryWork || scratch > params.learnedMemoryScratchBytes ||
        params.cancellation() || session.cancelRequested
    ) return null
    session.chargeSharingWork(count.toLong() * 3L)
    if (params.cancellation() || session.cancelRequested) return null
    val prepared = CapReductionPreparation(session, params, count, tiered)
    return prepared.takeIf { it.scan(tiered) }
}

private class CapReductionPreparation(
    private val session: PropagationSession,
    private val params: BacktrackParams,
    size: Int,
    tiered: Boolean,
) {
    private val candidates = LongArray(size)
    val kept = BooleanArray(size) { true }
    val tiers = IntArray(if (tiered) size else 0)
    var count = 0
        private set
    private var work = size.toLong() * 3L

    private fun step(): Boolean {
        if (work >= params.learnedMemoryWork || params.cancellation() || session.cancelRequested) return false
        session.chargeSharingWork(1L)
        work++
        return !params.cancellation() && !session.cancelRequested
    }

    fun scan(tiered: Boolean): Boolean {
        for (index in candidates.indices) {
            if (!step()) return false
            val lbd = session.learnedClauseLbd(index)
            if (!tiered) {
                if (lbd > params.lbdGlueThreshold && !session.learnedClausePermanent(index)) add(index, lbd)
                continue
            }
            val used = session.learnedClauseUsedSinceReduction(index)
            val tier = session.learnedClauseTier(index).takeUnless { it == ClauseTier.UNSET } ?: when {
                lbd <= params.lbdGlueThreshold -> ClauseTier.CORE
                lbd <= params.midLbdThreshold -> ClauseTier.MID
                else -> ClauseTier.LOCAL
            }
            val permanent = session.learnedClausePermanent(index)
            tiers[index] = when {
                permanent || tier == ClauseTier.CORE -> tier
                tier == ClauseTier.MID -> if (used) ClauseTier.MID else ClauseTier.LOCAL
                used -> ClauseTier.MID
                else -> {
                    add(index, lbd)
                    ClauseTier.LOCAL
                }
            }.ordinal
        }
        return true
    }

    private fun add(index: Int, lbd: Int) {
        candidates[count++] = (lbd.toLong() shl Int.SIZE_BITS) or index.toLong()
        kept[index] = false
    }

    fun select(remaining: Int): Boolean {
        fun sift(start: Int, end: Int): Boolean {
            var root = start
            while (root * 2 + 1 < end) {
                if (!step()) return false
                var child = root * 2 + 1
                if (child + 1 < end && candidates[child] < candidates[child + 1]) child++
                if (candidates[root] >= candidates[child]) break
                val value = candidates[root]
                candidates[root] = candidates[child]
                candidates[child] = value
                root = child
            }
            return true
        }
        for (index in count / 2 - 1 downTo 0) if (!sift(index, count)) return false
        for (end in count - 1 downTo 1) {
            if (!step()) return false
            val value = candidates[0]
            candidates[0] = candidates[end]
            candidates[end] = value
            if (!sift(0, end)) return false
        }
        for (index in 0 until remaining) {
            if (!step()) return false
            kept[candidates[index].toInt()] = true
        }
        return step()
    }

    fun publishTiers(): Boolean {
        if (!step()) return false
        session.chargeSharingWork(tiers.size.toLong() * 2L)
        if (params.cancellation() || session.cancelRequested) return false
        for (index in tiers.indices) {
            session.setLearnedClauseTier(index, ClauseTier.entries[tiers[index]])
            session.clearLearnedClauseUsed(index)
        }
        return true
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

    override fun run(
        session: PropagationSession,
        params: BacktrackParams,
        stats: SearchStatsSink?,
        containingWork: Long,
    ) {
        if (!session.canSimplifyRoot) return
        if (revision != session.learnedClauseRevision) clear()
        revision = session.learnedClauseRevision
        spending.run(session, params, stats, containingWork = containingWork) { advance(session, params, it) }
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
