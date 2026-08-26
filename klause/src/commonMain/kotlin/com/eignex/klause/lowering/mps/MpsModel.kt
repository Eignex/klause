package com.eignex.klause.lowering.mps

import com.eignex.klause.ir.ObjectiveSense

/** A decision variable from an MPS source model. */
data class MpsVar(
    /** Column name. */
    val name: String,
    /** Whether the column has integral values. */
    val integer: Boolean,
    /** Lower bound, or `null` when open. */
    val lower: Double?,
    /** Upper bound, or `null` when open. */
    val upper: Double?,
)

/** An MPS row indicator. */
data class MpsIndicator(
    /** Index of the indicator column in [MpsModel.variables]. */
    val column: Int,
    /** Whether the row is enforced when the column equals one. */
    val whenOne: Boolean,
)

/** A two-sided sparse linear MPS row. */
data class MpsConstraint(
    /** Row name. */
    val name: String,
    /** Variable indices, parallel to [coeffs]. */
    val indices: IntArray,
    /** Coefficients, parallel to [indices]. */
    val coeffs: DoubleArray,
    /** Lower bound, or `null` when open. */
    val lower: Double?,
    /** Upper bound, or `null` when open. */
    val upper: Double?,
    /** Optional indicator gating this row. */
    val indicator: MpsIndicator? = null,
)

/** A sparse linear MPS objective. */
data class MpsObjective(
    /** Objective row name. */
    val name: String,
    /** Variable indices, parallel to [coeffs]. */
    val indices: IntArray,
    /** Coefficients, parallel to [indices]. */
    val coeffs: DoubleArray,
    /** Constant term. */
    val constant: Double,
)

/** A parsed MPS instance before lowering to a klause problem. */
data class MpsModel(
    /** Problem name. */
    val name: String,
    /** Objective optimisation sense. */
    val sense: ObjectiveSense,
    /** Objective linear form. */
    val objective: MpsObjective,
    /** Declared variables. */
    val variables: List<MpsVar>,
    /** Constraint rows. */
    val constraints: List<MpsConstraint>,
)
