package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.snapshotValues

/** An exact QF_LRA assignment, independent of the finite CP [com.eignex.klause.solver.Sample]. */
class ExactLraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    bools: BooleanArray,
    /** Rational real values indexed by model real variable id. */
    reals: List<BigFraction>,
) {
    private val booleanValues: BooleanArray = bools.copyOf()

    /** Independent copy of the Boolean coordinates. */
    val bools: BooleanArray get() = booleanValues.copyOf()

    private val realValues: List<BigFraction> = snapshotValues(reals)

    /** Owned read-only rational coordinates. */
    val reals: List<BigFraction> get() = realValues

    /** Boolean coordinate without exporting its buffer. */
    fun boolValue(id: Int): Boolean = booleanValues[id]

    /** Capture replacement coordinates in an owned witness. */
    fun copy(
        bools: BooleanArray = booleanValues,
        reals: List<BigFraction> = realValues,
    ): ExactLraAssignment = ExactLraAssignment(bools, reals)

    /** Coordinate view for destructuring. */
    operator fun component1(): BooleanArray = bools

    /** Coordinate view for destructuring. */
    operator fun component2(): List<BigFraction> = reals

    override fun equals(other: Any?): Boolean = other is ExactLraAssignment &&
        booleanValues.contentEquals(other.booleanValues) &&
        realValues == other.realValues

    override fun hashCode(): Int = 31 * booleanValues.contentHashCode() + realValues.hashCode()

    override fun toString(): String = "ExactLraAssignment(bools=${booleanValues.contentToString()}, " +
        "reals=$realValues)"
}
