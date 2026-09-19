package com.eignex.klause.lp.engine

import kotlin.math.abs

internal data class CostPerturbationOptions(
    val root: Boolean = false,
    val stall: Boolean = false,
    val zeroObjective: Boolean = false,
    val relativeMagnitude: Double = 1e-6,
    val stallIterations: Int = 16,
    val seed: Long = 0L,
) {
    init {
        require(relativeMagnitude.isFinite() && relativeMagnitude > 0.0 && relativeMagnitude <= 0.01)
        require(stallIterations > 0)
    }
}

internal enum class NumericalRecoveryStep {
    RESIDUAL_REBUILD,
    TIGHTER_PIVOT,
    UNSCALED,
    LOGICALS,
    RELAX_TOLERANCE,
    TIGHTEN_TOLERANCE,
    TEXTBOOK_RATIO,
}

internal data class NumericalRecoveryOptions(val enabled: Boolean = false)

internal data class NumericalRecoveryCount(val attempts: Int = 0, val successes: Int = 0, val skips: Int = 0) {
    val declines: Int get() = attempts - successes
}

internal class SimplexNumericalMetrics {
    var rootAttempts = 0
    var rootShifts = 0
    var stallAttempts = 0
    var stallShifts = 0
    var cleanupAttempts = 0
    var cleanupSuccesses = 0
    var primalBlandEntries = 0
    var capExits = 0
    val recovery = mutableMapOf<NumericalRecoveryStep, NumericalRecoveryCount>()
}

// A shift belongs to this original numerical cost vector, not to the column's subsequent status.
internal class CostPerturbation(original: DoubleArray) {
    private val original = original.copyOf()
    private val root = DoubleArray(original.size)
    private val stall = DoubleArray(original.size)
    private val working = original.copyOf()

    fun cost(column: Int): Double = working[column]

    fun apply(
        status: Array<VarStatus>,
        rootShift: Boolean,
        options: CostPerturbationOptions,
        fixed: (Int) -> Boolean,
    ): Int {
        require(status.size == original.size)
        val shifts = if (rootShift) root else stall
        var changed = 0
        for (j in original.indices) {
            if (fixed(j)) continue
            val direction = when (status[j]) {
                VarStatus.AT_LOWER -> 1.0
                VarStatus.AT_UPPER -> -1.0
                else -> continue
            }
            var hash = (j.toLong() + options.seed + if (rootShift) 1L else 104729L) * -7046029254386353131L
            hash = (hash xor (hash ushr 30)) * -4658895280553007687L
            hash = hash xor (hash ushr 27)
            val fraction = 0.5 + (hash ushr 32).toDouble() / 4294967296.0
            val shift = direction * options.relativeMagnitude * maxOf(1.0, abs(original[j])) * fraction
            val other = if (rootShift) stall[j] else root[j]
            val value = original[j] + other + shift
            if (!shift.isFinite() || !value.isFinite() || value == working[j]) continue
            shifts[j] = shift
            working[j] = value
            changed++
        }
        return changed
    }

    fun restore() {
        original.copyInto(working)
        root.fill(0.0)
        stall.fill(0.0)
    }
}
