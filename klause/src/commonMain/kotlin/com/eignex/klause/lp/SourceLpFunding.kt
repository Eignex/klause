package com.eignex.klause.lp

internal const val SOURCE_LP_WORK_QUANTUM = 20_000_000L

internal class SourceLpFunding(
    private val workLimit: Long,
    private val allocationLimit: Long,
) {
    init {
        require(workLimit >= 0 && allocationLimit >= 0)
    }

    var spent: Long = 0
        private set
    var allocated: Long = 0
        private set
    private var allocationDenied = false
    private var granted = 0L
    val remainingWork: Long get() = (workLimit - spent).coerceAtLeast(0L)
    val remainingAllocation: Long get() = (allocationLimit - allocated).coerceAtLeast(0L)
    val availableWork: Long get() = minOf(remainingWork, (granted - spent).coerceAtLeast(0L))
    val exhausted: Boolean get() = spent >= workLimit || allocated >= allocationLimit || allocationDenied
    val paused: Boolean get() = exhausted || spent >= granted

    fun grant(work: Long) {
        require(work >= 0)
        granted = spent + minOf(work, (workLimit - spent).coerceAtLeast(0))
    }

    fun charge(work: Long, allocation: Long = 0) {
        require(work >= 0 && allocation >= 0)
        spent += minOf(work, Long.MAX_VALUE - spent)
        allocated += minOf(allocation, Long.MAX_VALUE - allocated)
    }

    fun reserveAllocation(bytes: Long): Boolean {
        require(bytes >= 0L)
        if (bytes > remainingAllocation) {
            declineAllocation()
            return false
        }
        charge(0L, bytes)
        return true
    }

    fun declineAllocation() {
        allocationDenied = true
    }
}
