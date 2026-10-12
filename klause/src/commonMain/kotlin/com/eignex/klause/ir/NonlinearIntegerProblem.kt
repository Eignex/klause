package com.eignex.klause.ir

/** Exact polynomial source with integer coordinates distinguished from continuous coordinates. */
class NonlinearIntegerProblem internal constructor(
    internal val formula: PolynomialRealFormula,
    internal val numericCount: Int,
    internal val boolCount: Int,
    intVarNames: Map<String, Int>,
    realVarNames: Map<String, Int>,
    boolVarNames: Map<String, Int>,
) {
    private val integers = intVarNames.toMap()
    private val reals = realVarNames.toMap()
    private val booleans = boolVarNames.toMap()
    internal val integerCoordinates: List<Int> = integers.values.toList()
    internal val realCoordinates: List<Int> = reals.values.toList()

    init {
        require(integers.isNotEmpty()) { "nonlinear integer source requires an integer declaration" }
        require((integers.values + reals.values).toSet() == (0 until integers.size + reals.size).toSet())
        require(booleans.values.toSet() == (0 until boolCount).toSet())
        formula.requireVariableCounts(boolCount, numericCount)
    }

    /** Original integer names mapped to declaration-ordered integer output identities. */
    val intVarNames: Map<String, Int> get() = integers.keys.withIndex().associate { it.value to it.index }

    /** Original real names mapped to declaration-ordered real output identities. */
    val realVarNames: Map<String, Int> get() = reals.keys.withIndex().associate { it.value to it.index }

    /** Independent original Boolean declaration identities. */
    val boolVarNames: Map<String, Int> get() = booleans.toMap()
}
