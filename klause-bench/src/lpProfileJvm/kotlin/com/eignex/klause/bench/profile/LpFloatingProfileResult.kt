package com.eignex.klause.bench.profile

import kotlin.math.abs
import kotlin.math.max

internal data class LpFloatingProfileResult(val outcome: String, val objective: Double?) {
    fun matches(other: LpFloatingProfileResult): Boolean {
        if (outcome != other.outcome) return false
        val value = objective ?: return other.objective == null
        val expected = other.objective ?: return false
        return value.isFinite() && expected.isFinite() &&
            abs(value - expected) <= OBJECTIVE_RELATIVE_TOLERANCE * max(1.0, max(abs(value), abs(expected)))
    }

    companion object {
        const val OPTIMAL = "OPTIMAL_FLOAT_ITERATE"
        const val OBJECTIVE_RELATIVE_TOLERANCE = 1e-8
    }
}
