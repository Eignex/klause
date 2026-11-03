package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.SharingMemoryObservation

internal class RootProbeImplications {
    private var pending = 0L
    private var pendingPair = false
    private var reason = -1
    private var reasonGeneration = -1L
    private var reasonChecked = false
    private var lookup = 0
    private var history: LongArray? = null
    private var historySize = 0
    private var admissions = 0

    val hasContinuation: Boolean get() = pending != 0L

    fun canCapture(params: BacktrackParams): Boolean = admissions < ADMISSION_LIMIT && fundable(params)

    fun reserve(params: BacktrackParams, budget: InprocessingBudget): Boolean {
        if (admissions >= ADMISSION_LIMIT) return false
        if (!fundable(params)) {
            budget.note("ImplicationUnfundable")
            return false
        }
        // Rollback destroys the speculative trail, including after an atomic propagation overspend.
        return budget.remaining > CAPTURE_WORK && budget.visit(CAPTURE_WORK)
    }

    fun capture(session: PropagationSession, literal: Int, priorPins: Int, budget: InprocessingBudget) {
        val pins = session.explanationState.boolPinOrder
        budget.note("ImplicationTrailPins", (pins.size - priorPins - 1).coerceAtLeast(0).toLong())
        if (pins.size <= priorPins + 1) {
            budget.note("ImplicationEmpty")
            return
        }
        val variable = pins[pins.size - 1]
        if (variable >= session.problem.numBoolVars || variable == Lit.variable(literal)) {
            budget.note("ImplicationUnsupportedTail")
            return
        }
        val value = session.boolValue(variable) ?: return
        pending = key(Lit.negate(literal), Lit.make(variable, value))
        pendingPair = false
        reason = session.explanationState.boolReason[variable]
        reasonGeneration = session.learnedRetirementGeneration
        reasonChecked = false
        lookup = 0
        budget.note("ImplicationCandidates")
    }

    fun captureConflict(parent: Int, child: Int, budget: InprocessingBudget) {
        check(Lit.variable(parent) != Lit.variable(child))
        pending = key(Lit.negate(parent), Lit.negate(child))
        pendingPair = true
        reason = -1
        reasonGeneration = -1L
        reasonChecked = true
        lookup = 0
        budget.note("PairProofs")
    }

    fun advance(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget): Boolean {
        if (!hasContinuation) return true
        if (budget.stopped()) return false
        if (!fundable(params) || admissions >= ADMISSION_LIMIT) {
            if (!budget.visit()) return false
            budget.note("ImplicationUnfundable")
            clearPending()
            return true
        }
        if (!reasonChecked) {
            if (budget.remaining < REASON_WORK || !budget.visit()) return false
            val currentReason = reason < session.problem.numFactors ||
                reasonGeneration == session.learnedRetirementGeneration
            val literals = if (reason >= 0 && currentReason) {
                session.rootClauseLiterals(reason, 2, includePermanent = true)
            } else null
            reasonChecked = true
            if (literals != null && literals.size == 2 && key(literals[0], literals[1]) == pending) {
                budget.note("ImplicationDirect")
                clearPending()
                return true
            }
        }
        var keys = history
        if (keys == null) {
            if (budget.remaining < ADMISSION_LIMIT || !budget.visit(ADMISSION_LIMIT)) return false
            keys = LongArray(ADMISSION_LIMIT)
            history = keys
        }
        while (lookup < historySize) {
            if (!budget.visit()) return false
            if (keys[lookup++] == pending) {
                budget.note("ImplicationDuplicate")
                clearPending()
                return true
            }
        }
        if (budget.remaining < ADMISSION_WORK || !budget.visit(ADMISSION_WORK)) return false
        val pair = pending
        val fromPair = pendingPair
        keys[historySize++] = pair
        admissions++
        clearPending()
        val before = session.work
        session.addRootDerivedClause(Clause(intArrayOf((pair ushr Int.SIZE_BITS).toInt(), pair.toInt())),
            2, OWN_ORIGIN, false, ownedLiterals = true)
        budget.note("AttachmentWork", session.work - before + ADMISSION_WORK)
        budget.note("Binaries")
        if (fromPair) budget.note("PairBinaries")
        return true
    }

    fun releaseRetainedBuffers() {
        history = null
        historySize = 0
        lookup = 0
    }

    fun reset() {
        clearPending()
        releaseRetainedBuffers()
        admissions = 0
    }

    fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, BOOKKEEPING_BYTES, protected)
        history?.let {
            sample.ownedReferences(this, 1L, protected)
            sample.owned(it, HISTORY_BYTES, protected)
        }
    }

    private fun clearPending() {
        pending = 0L
        pendingPair = false
        reason = -1
        reasonGeneration = -1L
        reasonChecked = false
        lookup = 0
    }

    private fun fundable(params: BacktrackParams): Boolean =
        minOf(params.inprocessingWork, params.inprocessingProbeWork) >=
            ADMISSION_LIMIT + REASON_WORK + ADMISSION_WORK &&
            minOf(params.learnedMemoryScratchBytes, params.learnedMemoryBytes ?: Long.MAX_VALUE) >=
            HISTORY_BYTES + 2L * Int.SIZE_BYTES

    private fun key(a: Int, b: Int): Long =
        (minOf(a, b).toLong() shl Int.SIZE_BITS) or maxOf(a, b).toLong()

    companion object {
        private const val ADMISSION_LIMIT = 64
        private const val CAPTURE_WORK = 8
        private const val REASON_WORK = 5
        private const val ADMISSION_WORK = 8
        private const val HISTORY_BYTES = ADMISSION_LIMIT * Long.SIZE_BYTES * 1L
        private const val BOOKKEEPING_BYTES = 2L * Long.SIZE_BYTES + 4L * Int.SIZE_BYTES + 2L
    }
}
