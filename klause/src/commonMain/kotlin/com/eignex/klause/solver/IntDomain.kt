package com.eignex.klause.solver

/** Closed interval bounds for an integer variable. */
data class IntDomain(val min: Int, val max: Int) {
    init { require(min <= max) { "Empty domain: $min..$max" } }
    val size: Int get() = max - min + 1
    operator fun contains(value: Int): Boolean = value in min..max
    fun clamp(value: Int): Int = if (value < min) min else if (value > max) max else value
}
