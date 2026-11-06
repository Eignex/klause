package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.SharingMemoryObservation

// Retained preparation avoids buying the same occurrence index on each short slice.
internal class Subsumption : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    override val spending = InprocessingSpending(InprocessingKind.Subsumption)
    private var cursor = 0
    private var pending: SubsumptionSlice? = null

    override fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.step()
        pending?.retainedMemory(sample, protected)
    }

    override fun reset() {
        cursor = 0
        pending = null
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (pending?.revision != session.learnedClauseRevision) pending = null
        if (!session.canSimplifyRoot || session.learnedClauseCount < 2) return
        spending.run(session, params, stats) { budget ->
            val slice = pending?.takeIf { it.revision == session.learnedClauseRevision }
                ?: SubsumptionSlice(session, cursor, params.subsumeBatch).also { pending = it }
            if (slice.advance(session, budget)) {
                cursor = if (slice.revision == session.learnedClauseRevision) slice.nextCursor else 0
                pending = null
            }
        }
    }
}

internal fun subsume(
    session: PropagationSession,
    params: BacktrackParams,
    startCursor: Int,
    stats: SearchStatsSink? = null,
): Int {
    if (!session.canSimplifyRoot || session.learnedClauseCount < 2) return startCursor
    var next = startCursor
    InprocessingSpending(InprocessingKind.Subsumption).run(session, params, stats) {
        val slice = SubsumptionSlice(session, startCursor, params.subsumeBatch)
        slice.advance(session, it)
        next = if (slice.revision == session.learnedClauseRevision) slice.nextCursor else 0
    }
    return next
}

private class SubsumptionSlice(session: PropagationSession, start: Int, batch: Int) {
    val revision = session.learnedClauseRevision
    private val count = session.learnedClauseCount
    private val size = minOf(count, batch.coerceAtLeast(1), INDEX_CLAUSES)
    var nextCursor = start.takeIf { it in 0 until count } ?: 0
        private set
    private val ids = IntArray(size)
    private val literals = arrayOfNulls<IntArray>(size)
    private val signatures = LongArray(size)
    private val occurrences = HashMap<Int, IntArrayList>()
    private var built = 0
    private var indexed = 0
    private var literalCount = 0
    private var target = 0
    private var literal = 0
    private var polarity = 0
    private var occurrence = 0
    private var strengthened: IntArray? = null
    private val dropped = IntHashSet()
    private val replacements = ArrayList<IntArray>()
    private val lbds = IntArrayList()
    private val origins = IntArrayList()
    private var attached = 0

    init {
        session.chargeInprocessingWork(3L * size)
    }

    fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(ids, ids.size.toLong() * Int.SIZE_BYTES, protected)
        sample.ownedReferences(literals, literals.size.toLong(), protected)
        sample.owned(signatures, signatures.size.toLong() * Long.SIZE_BYTES, protected)
        for (row in literals) {
            sample.step()
            if (row != null) sample.owned(row, row.size.toLong() * Int.SIZE_BYTES, protected)
        }
        sample.ownedReferences(occurrences, 2L * occurrences.size, protected)
        for (list in occurrences.values) {
            sample.step()
            val data = list.backingData
            sample.owned(data, data.size.toLong() * Int.SIZE_BYTES, protected)
        }
        strengthened?.let { sample.owned(it, it.size.toLong() * Int.SIZE_BYTES, protected) }
        sample.owned(dropped, dropped.allocatedBytes, protected)
        sample.ownedReferences(replacements, replacements.size.toLong(), protected)
        for (row in replacements) {
            sample.step()
            sample.owned(row, row.size.toLong() * Int.SIZE_BYTES, protected)
        }
        val lbdData = lbds.backingData
        val originData = origins.backingData
        sample.owned(lbdData, lbdData.size.toLong() * Int.SIZE_BYTES, protected)
        sample.owned(originData, originData.size.toLong() * Int.SIZE_BYTES, protected)
    }

    fun advance(session: PropagationSession, budget: InprocessingBudget): Boolean {
        while (built < size) {
            if (!budget.visit()) return false
            val id = nextCursor
            nextCursor = (nextCursor + 1) % count
            ids[built] = id
            budget.note("Candidates")
            val length = session.learnedClauseLiteralCount(id)
            if (length > ROOT_LITERAL_LIMIT - literalCount) budget.note("Oversized")
            val row = session.rootClauseLiterals(session.problem.numFactors + id,
                ROOT_LITERAL_LIMIT - literalCount)?.takeIf { it.size >= 2 }
            if (row != null) {
                // Copying and sorting are bounded by the retained literal ceiling.
                budget.charge(row.size * (2 + ceilLog2(row.size)))
                literals[built] = row.copyOf().also { it.sort() }
                signatures[built] = signatureOf(row)
                literalCount += row.size
                budget.note("Eligible")
            }
            built++
        }
        while (indexed < size) {
            if (!budget.visit()) return false
            literals[indexed]?.let { occurrences.getOrPut(it[0]) { IntArrayList() }.add(indexed) }
            indexed++
        }
        while (target < size) {
            if (budget.stopped()) return false
            val c = literals[target]
            if (c == null || ids[target] in dropped || literal >= c.size) {
                finishTarget(session)
                continue
            }
            val key = if (polarity == 0) c[literal] else Lit.negate(c[literal])
            val list = occurrences[key]
            if (list == null || occurrence >= list.size) {
                if (polarity++ == 1) { literal++; polarity = 0 }
                occurrence = 0
                continue
            }
            if (!budget.visit()) return false
            val other = list[occurrence++]
            if (other == target || ids[other] in dropped) continue
            val d = literals[other] ?: continue
            if (d.size > c.size || (signatures[other] and signatures[target].inv()) != 0L) continue
            if (!budget.visit(d.size * (1 + ceilLog2(c.size)))) { occurrence--; return false }
            val flipped = subsetWithOneFlip(d, c) ?: continue
            if (flipped == NO_FLIP) {
                dropped.add(ids[target])
            } else if (strengthened == null && c.size > 2) {
                budget.charge(c.size)
                strengthened = c.filter { it != flipped }.toIntArray()
            }
        }
        while (attached < replacements.size) {
            val row = replacements[attached]
            if (!budget.visit(row.size * 2 + 1)) return false
            val before = session.work
            session.addRootDerivedClause(Clause(row), lbds[attached], origins[attached], false)
            budget.note("AttachmentWork", session.work - before + row.size * 2 + 1L)
            budget.note("Shortened")
            attached++
        }
        if (!dropped.isEmpty()) {
            if (budget.stopped()) return false
            val before = session.work
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
        }
        return true
    }

    private fun finishTarget(session: PropagationSession) {
        strengthened?.takeUnless { ids[target] in dropped }?.let {
            dropped.add(ids[target])
            replacements.add(it)
            lbds.add(minOf(session.learnedClauseLbd(ids[target]), it.size))
            origins.add(session.learnedClauseOrigin(ids[target]))
        }
        target++
        literal = 0
        polarity = 0
        occurrence = 0
        strengthened = null
    }
}

private const val INDEX_CLAUSES = 1024
private const val NO_FLIP = -1

private fun subsetWithOneFlip(d: IntArray, c: IntArray): Int? {
    var flipped = NO_FLIP
    for (lit in d) {
        if (binaryContains(c, lit)) continue
        val neg = Lit.negate(lit)
        if (flipped == NO_FLIP && binaryContains(c, neg)) { flipped = neg; continue }
        return null
    }
    return flipped
}

private fun binaryContains(sorted: IntArray, value: Int): Boolean {
    var lo = 0
    var hi = sorted.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            sorted[mid] < value -> lo = mid + 1
            sorted[mid] > value -> hi = mid - 1
            else -> return true
        }
    }
    return false
}

private fun signatureOf(literals: IntArray): Long {
    var sig = 0L
    for (lit in literals) sig = sig or (1L shl (Lit.variable(lit) * SIG_MIX ushr SIG_SHIFT))
    return sig
}

private fun ceilLog2(value: Int): Int = Int.SIZE_BITS - (value - 1).countLeadingZeroBits()
private const val SIG_MIX = 0x9E3779B1.toInt()
private const val SIG_SHIFT = 26
