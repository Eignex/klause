package com.eignex.klause.simplex.basis

// The caller funds ordinary source/workspace construction separately from this additional factor-copy work.
internal interface BasisFactorCopy {
    val dimension: Int
    val entries: Long
    val additionalWork: Long

    fun create(
        columns: IntArray,
        unitRows: IntArray,
        policy: LuPivotPolicy,
        work: Long,
    ): BasisSolver?
}
