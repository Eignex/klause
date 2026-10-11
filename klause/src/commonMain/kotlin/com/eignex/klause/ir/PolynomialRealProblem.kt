package com.eignex.klause.ir

/** A lossless pure polynomial-real source formula with declaration-ordered source identities. */
class PolynomialRealProblem internal constructor(
    internal val formula: PolynomialRealFormula,
    internal val totalRealCount: Int,
    internal val totalBoolCount: Int,
    /** Number of original source real declarations, excluding lowering auxiliaries. */
    val sourceRealCount: Int,
    /** Number of original source Boolean declarations. */
    val sourceBoolCount: Int,
    realVarNames: Map<String, Int>,
    boolVarNames: Map<String, Int>,
) {
    private val realNames = realVarNames.toMap()
    private val boolNames = boolVarNames.toMap()

    /** Independent declaration-name map with canonical unquoted SMT symbols. */
    val realVarNames: Map<String, Int> get() = realNames.toMap()

    /** Independent declaration-name map with canonical unquoted SMT symbols. */
    val boolVarNames: Map<String, Int> get() = boolNames.toMap()
}
