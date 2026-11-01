package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal enum class LearnedRetirementStatus {
    Captured, Pending, Deferred, StaleSelection, Unsupported, ProtectedFloor,
    Retired, AlreadyGone, Cancelled, ScopeReleased, Idle,
}

internal class LearnedRetirementScope { var active = true }

internal class LearnedRetirementHandle internal constructor(internal val scope: LearnedRetirementScope) {
    internal var storedStatus = LearnedRetirementStatus.Captured
    internal var acknowledged = false
    internal var payloadReleased = false
    internal var physicalReleased = false
    val status: LearnedRetirementStatus
        get() = if (scope.active) storedStatus else LearnedRetirementStatus.ScopeReleased
}

internal data class LearnedRetirementResult(
    val status: LearnedRetirementStatus,
    val handle: LearnedRetirementHandle? = null,
    val work: Long = 0L,
    val payloadReleased: Boolean = false,
    val physicalReleased: Boolean = false,
    val acknowledged: Boolean = false,
)

internal class LearnedRetirementRequests {
    private class Request(var index: Int, val handle: LearnedRetirementHandle) {
        var previous: Request? = null
        var next: Request? = null
    }
    private var scope = LearnedRetirementScope()
    private var first: Request? = null
    private var last: Request? = null
    private var cursor: Request? = null
    private var count = 0
    private var pending = 0
    private var acknowledged = 0
    var generation = 0L
        private set
    val hasContinuation: Boolean get() = pending > 0 || acknowledged > 0
    val hasRequests: Boolean get() = count > 0
    val remapWork: Long get() = count * 8L + 2L
    val ownedBytes: Long get() = 53L + count * 47L

    fun capture(
        index: Int,
        expectedGeneration: Long,
        maxWork: Long,
        maxScratchBytes: Long,
        maxRetainedBytes: Long,
        cancelled: () -> Boolean,
        charge: (Long) -> Unit,
        validate: (Int) -> LearnedRetirementStatus?,
    ): LearnedRetirementResult {
        val budget = ReclamationPreparation(maxWork, maxScratchBytes, cancelled, charge)
        return try {
            budget.visit(8L, 64L)
            if (!scope.active) return result(LearnedRetirementStatus.ScopeReleased, budget)
            if (expectedGeneration != generation) return result(LearnedRetirementStatus.StaleSelection, budget)
            val declined = validate(index)
            if (declined != null) return result(declined, budget)
            if (ownedBytes + 47L > maxRetainedBytes) return result(LearnedRetirementStatus.Deferred, budget)
            budget.visit(24L, 64L)
            budget.visit(0L)
            val handle = LearnedRetirementHandle(scope)
            val request = Request(index, handle)
            request.previous = last
            last?.next = request
            if (first == null) first = request
            last = request
            if (cursor == null) cursor = request
            count++
            result(LearnedRetirementStatus.Captured, budget, handle)
        } catch (_: ReclamationPreparation.Deferred) {
            result(LearnedRetirementStatus.Deferred, budget)
        }
    }

    fun authorize(handle: LearnedRetirementHandle, budget: ReclamationPreparation): LearnedRetirementResult =
        change(handle, budget) {
            if (handle.storedStatus == LearnedRetirementStatus.Captured) {
                handle.storedStatus = LearnedRetirementStatus.Pending
                pending++
            }
        }

    fun cancel(handle: LearnedRetirementHandle, budget: ReclamationPreparation): LearnedRetirementResult =
        change(handle, budget) {
            if (!terminal(handle.storedStatus)) {
                if (handle.storedStatus == LearnedRetirementStatus.Pending) pending--
                handle.storedStatus = LearnedRetirementStatus.Cancelled
            }
        }

    fun acknowledge(handle: LearnedRetirementHandle, budget: ReclamationPreparation): LearnedRetirementResult =
        change(handle, budget) {
            if (terminal(handle.storedStatus) && !handle.acknowledged) {
                handle.acknowledged = true
                acknowledged++
            }
        }

    private inline fun change(
        handle: LearnedRetirementHandle,
        budget: ReclamationPreparation,
        update: () -> Unit,
    ): LearnedRetirementResult = try {
        budget.visit(16L, 64L)
        budget.visit(0L)
        if (!handle.scope.active) result(LearnedRetirementStatus.ScopeReleased, budget, handle)
        else if (handle.scope !== scope) result(LearnedRetirementStatus.StaleSelection, budget, handle)
        else {
            update()
            result(handle.storedStatus, budget, handle)
        }
    } catch (_: ReclamationPreparation.Deferred) {
        result(LearnedRetirementStatus.Deferred, budget, handle)
    }

    fun advance(
        budget: ReclamationPreparation,
        retire: (Int, ReclamationPreparation) -> LearnedRetirementResult,
    ): LearnedRetirementResult {
        var handle: LearnedRetirementHandle? = null
        return try {
            budget.visit(32L, 96L)
            budget.visit(0L)
            val request = cursor ?: first ?: return result(LearnedRetirementStatus.Idle, budget)
            handle = request.handle
            cursor = request.next ?: first
            if (handle.acknowledged) {
                remove(request)
                return result(handle.storedStatus, budget, handle)
            }
            if (handle.storedStatus != LearnedRetirementStatus.Pending) {
                return result(if (handle.storedStatus == LearnedRetirementStatus.Captured) {
                    LearnedRetirementStatus.Deferred
                } else handle.storedStatus, budget, handle)
            }
            val retired = if (request.index < 0) {
                LearnedRetirementResult(LearnedRetirementStatus.AlreadyGone, payloadReleased = true,
                    physicalReleased = true)
            } else retire(request.index, budget)
            if (terminal(retired.status)) {
                handle.storedStatus = retired.status
                handle.payloadReleased = retired.payloadReleased
                handle.physicalReleased = retired.physicalReleased
                pending--
            }
            result(retired.status, budget, handle)
        } catch (_: ReclamationPreparation.Deferred) {
            result(LearnedRetirementStatus.Deferred, budget, handle)
        }
    }

    private fun remove(request: Request) {
        request.previous?.next = request.next
        request.next?.previous = request.previous
        if (first === request) first = request.next
        if (last === request) last = request.previous
        if (cursor === request) cursor = request.next ?: first
        request.next = null
        request.previous = null
        count--
        acknowledged--
    }

    fun remap(remap: IntArray) {
        var request = first
        while (request != null) {
            request.index = remap.getOrNull(request.index) ?: -1
            if (request.index < 0) {
                request.handle.payloadReleased = true
                request.handle.physicalReleased = true
            }
            request = request.next
        }
        generation++
    }

    fun resetScope() {
        release()
        scope = LearnedRetirementScope()
    }

    fun release() {
        scope.active = false
        first = null
        last = null
        cursor = null
        count = 0
        pending = 0
        acknowledged = 0
        generation++
    }

    fun sharingMemory(sample: SharingMemoryObservation) {
        sample.owned(this, 20L, true)
        sample.ownedReferences(this, 4L, true)
        sample.owned(scope, 1L, true)
        var request = first
        while (request != null) {
            sample.step()
            sample.owned(request, 4L, true)
            sample.ownedReferences(request, 3L, true)
            sample.owned(request.handle, 3L, true)
            sample.ownedReferences(request.handle, 2L, true)
            request = request.next
        }
        sample.record(mapOf("selectedRetirementRequests" to count.toLong(),
            "selectedRetirementPending" to pending.toLong(), "selectedRetirementOwnedBytes" to ownedBytes))
    }

    private fun result(status: LearnedRetirementStatus, budget: ReclamationPreparation,
        handle: LearnedRetirementHandle? = null): LearnedRetirementResult = LearnedRetirementResult(
        status, handle, budget.work, handle?.payloadReleased == true,
        handle?.physicalReleased == true, handle?.acknowledged == true,
    )

    private fun terminal(status: LearnedRetirementStatus): Boolean = when (status) {
        LearnedRetirementStatus.Retired, LearnedRetirementStatus.AlreadyGone,
        LearnedRetirementStatus.Cancelled, LearnedRetirementStatus.ScopeReleased -> true
        else -> false
    }
}
