package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.solver.snapshotValues

/** An owned exact polynomial-real witness, including irrational coordinates. */
class ExactNraAssignment(
    /** Boolean coordinates indexed by source variable ID. */
    bools: BooleanArray,
    /** Exact real coordinates indexed by source variable ID. */
    reals: List<AlgebraicReal>,
) {
    private val booleanValues = bools.copyOf()
    private val realValues = snapshotValues(reals)

    /** Number of Boolean coordinates. */
    val boolCount: Int get() = booleanValues.size

    /** Number of real coordinates. */
    val realCount: Int get() = realValues.size

    /** Independent copy of the Boolean coordinates. */
    val bools: BooleanArray get() = booleanValues.copyOf()

    /** Owned read-only exact coordinates, independent of arithmetic context lifetime. */
    val reals: List<AlgebraicReal> get() = realValues

    /** Boolean coordinate without exporting its buffer. */
    fun boolValue(id: Int): Boolean = booleanValues[id]

    /** Exact real coordinate without approximating or refining it. */
    fun realValue(id: Int): AlgebraicReal = realValues[id]
}
