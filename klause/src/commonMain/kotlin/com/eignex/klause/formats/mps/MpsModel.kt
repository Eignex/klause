package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense

/** A decision variable from an MPS source model. */
data class MpsVar(
    /** Source identifier. */
    val name: String,
    /** Whether the variable has integral values. */
    val integer: Boolean,
    /** Optional lower bound. */
    val lower: Double?,
    /** Optional upper bound. */
    val upper: Double?,
)

/** An MPS row indicator. */
data class MpsIndicator(
    /** Index of the controlling variable. */
    val column: Int,
    /** Whether the row is enabled when the variable equals one. */
    val whenOne: Boolean,
)

/** A two-sided sparse linear MPS row. */
data class MpsConstraint(
    /** Source row identifier. */
    val name: String,
    /** Variable index for each coefficient. */
    val indices: IntArray,
    /** Coefficients aligned with [indices]. */
    val coeffs: DoubleArray,
    /** Optional lower bound. */
    val lower: Double?,
    /** Optional upper bound. */
    val upper: Double?,
    /** Optional condition enabling this row. */
    val indicator: MpsIndicator? = null,
)

/** A sparse linear MPS objective. */
data class MpsObjective(
    /** Source row identifier. */
    val name: String,
    /** Variable index for each coefficient. */
    val indices: IntArray,
    /** Coefficients aligned with [indices]. */
    val coeffs: DoubleArray,
    /** Constant term. */
    val constant: Double,
)

/** A parsed MPS instance before lowering to a klause problem. */
data class MpsModel(
    /** Instance name. */
    val name: String,
    /** Objective direction. */
    val sense: ObjectiveSense,
    /** Parsed objective row. */
    val objective: MpsObjective,
    /** Decision variables in column order. */
    val variables: List<MpsVar>,
    /** Linear rows. */
    val constraints: List<MpsConstraint>,
)
