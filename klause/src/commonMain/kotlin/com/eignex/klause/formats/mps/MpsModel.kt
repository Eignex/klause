package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense

/** A decision variable from an MPS source model. */
data class MpsVar(val name: String, val integer: Boolean, val lower: Double?, val upper: Double?)

/** An MPS row indicator. */
data class MpsIndicator(val column: Int, val whenOne: Boolean)

/** A two-sided sparse linear MPS row. */
data class MpsConstraint(
    val name: String,
    val indices: IntArray,
    val coeffs: DoubleArray,
    val lower: Double?,
    val upper: Double?,
    val indicator: MpsIndicator? = null,
)

/** A sparse linear MPS objective. */
data class MpsObjective(val name: String, val indices: IntArray, val coeffs: DoubleArray, val constant: Double)

/** A parsed MPS instance before lowering to a klause problem. */
data class MpsModel(
    val name: String,
    val sense: ObjectiveSense,
    val objective: MpsObjective,
    val variables: List<MpsVar>,
    val constraints: List<MpsConstraint>,
)
