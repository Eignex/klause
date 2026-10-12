package com.eignex.klause.ir

/** Exact real function source with shared Boolean, integer and continuous declaration identities. */
class TranscendentalProblem internal constructor(
    internal val formula: TranscendentalFormula,
    intVarNames: Map<String, Int>,
    realVarNames: Map<String, Int>,
    boolVarNames: Map<String, Int>,
) {
    private val integers = intVarNames.toMap()
    private val reals = realVarNames.toMap()
    private val booleans = boolVarNames.toMap()
    internal val integerCoordinates: List<Int> = integers.values.toList()
    internal val realCoordinates: List<Int> = reals.values.toList()
    internal val numericCount: Int = integers.size + reals.size
    internal val boolCount: Int = booleans.size

    init {
        require((integers.values + reals.values).toSet() == (0 until numericCount).toSet())
        require(booleans.values.toSet() == (0 until boolCount).toSet())
    }

    /** Original integer names mapped to declaration-ordered integer output identities. */
    val intVarNames: Map<String, Int> get() = integers.keys.withIndex().associate { it.value to it.index }

    /** Original real names mapped to declaration-ordered real output identities. */
    val realVarNames: Map<String, Int> get() = reals.keys.withIndex().associate { it.value to it.index }

    /** Independent original Boolean declaration identities. */
    val boolVarNames: Map<String, Int> get() = booleans.toMap()
}
