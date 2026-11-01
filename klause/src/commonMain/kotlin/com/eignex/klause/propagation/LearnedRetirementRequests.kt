package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation

internal enum class LearnedRetirementStatus {
    Captured, AlreadyOwned, Pending, Deferred, StaleSelection, Unsupported, ProtectedFloor,
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
    val minimumWork: Long = 0L,
    val minimumScratchBytes: Long = 0L,
    val minimumRetainedBytes: Long = 0L,
)

internal class LearnedRetirementRequests {
    private class Request(var index: Int, val key: Long, val handle: LearnedRetirementHandle) {
        var previous: Request? = null
        var next: Request? = null
    }
    private class IndexNode(val key: Long, val bit: Int, var left: IndexNode? = null,
        var right: IndexNode? = null, val request: Request? = null)

    private var scope = LearnedRetirementScope()
    private var first: Request? = null
    private var last: Request? = null
    private var cursor: Request? = null
    private var indexRoot: IndexNode? = null
    private var indexNodes = 0
    private var count = 0
    private var pending = 0
    private var terminalOwners = 0
    private var acknowledged = 0
    var generation = 0L
        private set
    val hasContinuation: Boolean get() = pending > 0 || terminalOwners > 0 || acknowledged > 0
    val hasRequests: Boolean get() = count > 0
    val remapWork: Long get() = count * 8L + 2L
    val ownedBytes: Long get() = 69L + count * 55L + indexNodes * 36L

    fun capture(
        index: Int,
        expectedGeneration: Long,
        budget: ReclamationPreparation,
        maxRetainedBytes: Long,
        validate: (Int) -> LearnedRetirementStatus?,
        keyOf: (Int) -> Long,
    ): LearnedRetirementResult {
        var requiredWork = 12L
        var requiredScratch = 64L
        return try {
            budget.visit(12L, 64L)
            if (!scope.active) return result(LearnedRetirementStatus.ScopeReleased, budget)
            if (expectedGeneration != generation) return result(LearnedRetirementStatus.StaleSelection, budget)
            val declined = validate(index)
            if (declined != null) return result(declined, budget)
            val key = keyOf(index)
            var node = indexRoot
            var parent: IndexNode? = null
            var right = false
            var differing = -1
            while (node != null) {
                requiredWork = budget.work + 4L
                budget.visit(4L)
                differing = Long.SIZE_BITS - 1 - (key xor node.key).countLeadingZeroBits()
                if (node.bit < 0 && differing < 0) {
                    return result(LearnedRetirementStatus.AlreadyOwned, budget, checkNotNull(node.request).handle)
                }
                if (differing > node.bit) break
                parent = node
                right = key ushr node.bit and 1L != 0L
                node = if (right) node.right else node.left
            }
            val nodes = if (node == null) 1 else 2
            val requiredRetained = ownedBytes + 55L + nodes * 36L
            if (requiredRetained > maxRetainedBytes) return result(LearnedRetirementStatus.Deferred, budget).copy(
                minimumRetainedBytes = requiredRetained)
            requiredWork = budget.work + 48L
            requiredScratch = 256L
            budget.visit(48L, 192L)
            budget.visit(0L)
            val handle = LearnedRetirementHandle(scope)
            val request = Request(index, key, handle)
            val leaf = IndexNode(key, -1, request = request)
            val replacement = if (node == null) leaf else if (key ushr differing and 1L != 0L) {
                IndexNode(key, differing, node, leaf)
            } else IndexNode(key, differing, leaf, node)
            if (parent == null) indexRoot = replacement else if (right) parent.right = replacement
            else parent.left = replacement
            indexNodes += nodes
            request.previous = last
            last?.next = request
            if (first == null) first = request
            last = request
            if (cursor == null) cursor = request
            count++
            result(LearnedRetirementStatus.Captured, budget, handle)
        } catch (_: ReclamationPreparation.Deferred) {
            result(LearnedRetirementStatus.Deferred, budget).copy(
                minimumWork = requiredWork, minimumScratchBytes = requiredScratch)
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
                terminalOwners++
            }
        }

    fun acknowledge(handle: LearnedRetirementHandle, budget: ReclamationPreparation): LearnedRetirementResult =
        change(handle, budget) {
            if (terminal(handle.storedStatus) && !handle.acknowledged) {
                handle.acknowledged = true
                terminalOwners--
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
        result(LearnedRetirementStatus.Deferred, budget, handle).copy(minimumWork = 16L, minimumScratchBytes = 64L)
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
                removeIndex(request, budget)
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
                terminalOwners++
            }
            result(retired.status, budget, handle)
        } catch (_: ReclamationPreparation.Deferred) {
            result(LearnedRetirementStatus.Deferred, budget, handle)
        }
    }

    private fun removeIndex(request: Request, budget: ReclamationPreparation) {
        var node = checkNotNull(indexRoot)
        var parent: IndexNode? = null
        var grandparent: IndexNode? = null
        var right = false
        var parentRight = false
        while (node.bit >= 0) {
            budget.visit(4L)
            grandparent = parent
            parentRight = right
            parent = node
            right = request.key ushr node.bit and 1L != 0L
            node = checkNotNull(if (right) node.right else node.left)
        }
        check(node.request === request)
        budget.visit(16L)
        budget.visit(0L)
        if (parent == null) {
            indexRoot = null
            indexNodes--
        } else {
            val sibling = if (right) parent.left else parent.right
            if (grandparent == null) indexRoot = sibling else if (parentRight) grandparent.right = sibling
            else grandparent.left = sibling
            indexNodes -= 2
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
        indexRoot = null
        indexNodes = 0
        count = 0
        pending = 0
        terminalOwners = 0
        acknowledged = 0
        generation++
    }

    fun sharingMemory(sample: SharingMemoryObservation) {
        sample.owned(this, 28L, true)
        sample.ownedReferences(this, 5L, true)
        sample.owned(scope, 1L, true)
        var request = first
        while (request != null) {
            sample.step()
            sample.owned(request, 12L, true)
            sample.ownedReferences(request, 3L, true)
            sample.owned(request.handle, 3L, true)
            sample.ownedReferences(request.handle, 2L, true)
            request = request.next
        }
        indexMemory(indexRoot, sample)
        sample.record(mapOf("selectedRetirementRequests" to count.toLong(),
            "selectedRetirementPending" to pending.toLong(),
            "selectedRetirementTerminalOwners" to terminalOwners.toLong(),
            "selectedRetirementOwnedBytes" to ownedBytes))
    }

    private fun indexMemory(node: IndexNode?, sample: SharingMemoryObservation) {
        if (node == null) return
        sample.step()
        sample.owned(node, 12L, true)
        sample.ownedReferences(node, 3L, true)
        indexMemory(node.left, sample)
        indexMemory(node.right, sample)
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
