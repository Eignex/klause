package com.eignex.klause.propagation

import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.magnitudeBitLength

internal data class ArithmeticLearningLimits(
    val work: Long = 262_144,
    val allocationBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(work >= 0 && allocationBytes >= 0)
    }
}

internal class ArithmeticLearningDeclined(val reason: String) : RuntimeException()

internal class ArithmeticLearningBudget(
    private val state: PropagationState,
    private val limits: ArithmeticLearningLimits = state.arithmeticLearningLimits,
) {
    private val initialWork = state.work
    var allocatedBytes = 0L
        private set

    fun spend(work: Long = 1, bytes: Long = 0) {
        require(work >= 0 && bytes >= 0)
        if (work > 64) state.finiteWork.checkNow() else state.finiteWork.check()
        if (work > state.workLimit - state.work) throw FinitePropagationInterrupted()
        val reason = when {
            work > limits.work - (state.work - initialWork) -> "work"
            bytes > limits.allocationBytes - allocatedBytes -> "allocation"
            else -> null
        }
        if (reason != null) throw ArithmeticLearningDeclined(reason)
        state.work += work
        allocatedBytes += bytes
    }

    fun replayIfFunded(work: Long, bytes: Long): Boolean {
        if (work > state.workLimit - state.work ||
            work > limits.work - (state.work - initialWork) ||
            bytes > limits.allocationBytes - allocatedBytes
        ) return false
        // A partial payment must replay the original scan to preserve its cancellation and decline boundary.
        state.finiteWork.checkNow()
        state.work += work
        allocatedBytes += bytes
        return true
    }

    fun scalar(left: BigInt, right: BigInt = left) {
        val leftWords = integerWords(left)
        val rightWords = integerWords(right)
        spend(leftWords * rightWords, 64 + (leftWords + rightWords) * 16)
    }
}

internal fun PropagationState.checkIntegerScalarWork(left: BigInt, right: BigInt = left) {
    val units = integerWords(left) * integerWords(right)
    if (units > workLimit - work) throw FinitePropagationInterrupted()
    if (units > 64) finiteWork.checkNow() else finiteWork.check()
    work += units
}

internal fun PropagationState.checkIntegerWordWork(budget: ArithmeticLearningBudget?) {
    if (budget == null) {
        checkFiniteWork()
        checkFiniteWork()
    } else budget.spend(work = 2, bytes = 64)
}

private fun integerWords(value: BigInt): Long =
    ((value.magnitudeBitLength().toLong() + 63) / 64).coerceAtLeast(1)
