package com.eignex.klause.backtrack

import com.eignex.klause.propagation.LearnedRetirementHandle
import com.eignex.klause.propagation.LearnedRetirementStatus
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.SharingMemoryObservation

internal class RootLearnedRetirement {
    private var captured: LearnedRetirementHandle? = null
    private var selected = false
    private var published = false
    private var abandoned = false
    private var protectedAnchor: LearnedRetirementHandle? = null
    private var parkedAt = -1L
    private var parkAfterSlice = false

    val hasCapture: Boolean get() = captured != null
    val selectionPending: Boolean get() = captured != null && !selected && !abandoned

    fun hasContinuation(session: PropagationSession): Boolean = captured != null ||
        (session.hasLearnedRetirementContinuation && parkedAt != session.ownershipWork)

    fun markSelected() {
        check(captured != null && !abandoned)
        selected = true
        published = false
    }

    fun rootPublished() {
        if (selected && !abandoned) published = true
    }

    fun abandon() {
        if (captured?.status == LearnedRetirementStatus.ScopeReleased) clearCapture()
        else if (captured != null) abandoned = true
        protectedAnchor = null
        parkedAt = -1L
        parkAfterSlice = false
    }

    fun retainedMemory(sample: SharingMemoryObservation, owner: Any) {
        if (captured == null && protectedAnchor == null) return
        sample.ownedReferences(owner, 1L, true)
        sample.owned(this, 12L, true)
        sample.ownedReferences(this, 2L, true)
    }

    fun capture(
        session: PropagationSession,
        index: Int,
        generation: Long,
        params: BacktrackParams,
        budget: InprocessingBudget,
    ): LearnedRetirementStatus {
        check(captured == null)
        if (budget.stopped() || budget.remaining < CAPTURE_OWNER_WORK) return LearnedRetirementStatus.Deferred
        val retained = minOf(params.learnedMemoryScratchBytes, params.learnedMemoryBytes ?: Long.MAX_VALUE)
        val before = session.work
        val result = session.queueLearnedRetirement(index, generation,
            budget.remaining - CAPTURE_OWNER_WORK, params.learnedMemoryScratchBytes,
            (retained - CALLER_BYTES).coerceAtLeast(0L), { budget.stopped() })
        if (result.status == LearnedRetirementStatus.Captured) {
            budget.charge(CAPTURE_OWNER_WORK.toInt())
            captured = checkNotNull(result.handle)
            selected = false
            published = false
            abandoned = false
            protectedAnchor = null
            parkedAt = -1L
            parkAfterSlice = false
        } else if (result.status == LearnedRetirementStatus.Deferred &&
            (result.minimumWork + CAPTURE_OWNER_WORK > budget.limit ||
                result.minimumScratchBytes > params.learnedMemoryScratchBytes ||
                result.minimumRetainedBytes > (retained - CALLER_BYTES).coerceAtLeast(0L))) {
            budget.note("RetirementUnfundableCapture")
        }
        budget.note("RetirementCaptureWork", session.work - before)
        budget.note("RetirementCapture${result.status.name}")
        if (result.status == LearnedRetirementStatus.AlreadyOwned &&
            (result.handle?.status == LearnedRetirementStatus.Captured ||
                result.handle?.status == LearnedRetirementStatus.Cancelled)) {
            return LearnedRetirementStatus.Deferred
        }
        return result.status
    }

    fun prepare(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget): Boolean {
        val handle = captured ?: return true
        if (handle.status == LearnedRetirementStatus.ScopeReleased) {
            clearCapture()
            return true
        }
        if (!abandoned && !selected) return true
        if (!abandoned && !published) return false
        if (budget.stopped()) return false
        val before = session.work
        val cancelling = abandoned
        val result = if (cancelling) {
            session.cancelLearnedRetirement(handle, budget.remaining, params.learnedMemoryScratchBytes) {
                budget.stopped()
            }
        } else {
            session.authorizeLearnedRetirement(handle, true, budget.remaining, params.learnedMemoryScratchBytes) {
                budget.stopped()
            }
        }
        val phase = if (cancelling) "Cancellation" else "Authorization"
        budget.note("Retirement${phase}Work", session.work - before)
        budget.note("Retirement$phase${result.status.name}")
        if (result.status == LearnedRetirementStatus.Deferred) return false
        when (result.status) {
            LearnedRetirementStatus.Pending, LearnedRetirementStatus.Retired, LearnedRetirementStatus.AlreadyGone,
            LearnedRetirementStatus.Cancelled, LearnedRetirementStatus.ScopeReleased -> clearCapture()
            else -> return false
        }
        return true
    }

    fun advance(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        if (!session.hasLearnedRetirementContinuation || parkedAt == session.ownershipWork || budget.stopped()) return
        val before = session.work
        val result = session.advanceLearnedRetirements(budget.remaining, params.learnedMemoryScratchBytes,
            { budget.stopped() }, publicationPending = false)
        budget.note("RetirementWork", session.work - before)
        budget.note("Retirement${result.status.name}")
        if (result.status == LearnedRetirementStatus.Deferred) {
            protectedAnchor = null
            parkedAt = -1L
            parkAfterSlice = false
            return
        }
        val handle = result.handle
        if (result.status == LearnedRetirementStatus.ProtectedFloor) {
            if (protectedAnchor === handle) parkAfterSlice = true
            else if (protectedAnchor == null) protectedAnchor = handle
            return
        }
        protectedAnchor = null
        parkedAt = -1L
        parkAfterSlice = false
        if (handle != null && !result.acknowledged && terminal(result.status) && !budget.stopped()) {
            val beforeAck = session.work
            val acknowledged = session.acknowledgeLearnedRetirement(handle, budget.remaining,
                params.learnedMemoryScratchBytes) { budget.stopped() }
            budget.note("RetirementAcknowledgementWork", session.work - beforeAck)
            budget.note("RetirementAcknowledgement${acknowledged.status.name}")
        }
    }

    fun finishSlice(session: PropagationSession) {
        if (parkAfterSlice) {
            parkedAt = session.ownershipWork
            parkAfterSlice = false
        }
    }

    private fun clearCapture() {
        captured = null
        selected = false
        published = false
        abandoned = false
    }

    private fun terminal(status: LearnedRetirementStatus): Boolean = when (status) {
        LearnedRetirementStatus.Retired, LearnedRetirementStatus.AlreadyGone,
        LearnedRetirementStatus.Cancelled, LearnedRetirementStatus.ScopeReleased -> true
        else -> false
    }

    private companion object {
        const val CAPTURE_OWNER_WORK = 2L
        const val CALLER_BYTES = 36L
    }
}
