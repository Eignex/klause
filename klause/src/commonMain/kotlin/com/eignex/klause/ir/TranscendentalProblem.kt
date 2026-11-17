package com.eignex.klause.ir

/** Exact real function source with shared Boolean, integer and continuous declaration identities. */
class TranscendentalProblem internal constructor(
    internal val formula: TranscendentalFormula,
    intVarNames: Map<String, Int>,
    realVarNames: Map<String, Int>,
    boolVarNames: Map<String, Int>,
    internal val objective: TranscendentalExpression? = null,
    /** Original objective direction, when an objective was captured. */
    val objectiveSense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Effective source declaration semantics captured before lowering. */
    override val semantics: EffectiveSourceSemantics = defaultIrSemantics,
    /** Native factor relations sharing these declaration identities, when supplied. */
    val factorModel: Problem? = null,
    internal val auxiliaryCount: Int = 0,
) : SourceSemanticsOwner {
    private val integers = intVarNames.toMap()
    private val reals = realVarNames.toMap()
    private val booleans = boolVarNames.toMap()
    internal val integerCoordinates: List<Int> = integers.values.toList()
    internal val realCoordinates: List<Int> = reals.values.toList()
    internal val numericCount: Int = integers.size + reals.size + auxiliaryCount
    internal val boolCount: Int = booleans.size

    init {
        require(auxiliaryCount >= 0)
        require((integers.values + reals.values).toSet() == (0 until integers.size + reals.size).toSet())
        require(booleans.values.toSet() == (0 until boolCount).toSet())
    }

    /** Original integer names mapped to declaration-ordered integer output identities. */
    val intVarNames: Map<String, Int> get() = integers.keys.withIndex().associate { it.value to it.index }

    /** Original real names mapped to declaration-ordered real output identities. */
    val realVarNames: Map<String, Int> get() = reals.keys.withIndex().associate { it.value to it.index }

    /** Independent original Boolean declaration identities. */
    val boolVarNames: Map<String, Int> get() = booleans.toMap()

    /** Whether the source includes an objective whose exact optimization is unsupported by this route. */
    val hasObjective: Boolean get() = objective != null

    /**
     * Attach native relations using the same declaration-ordered Boolean, integer and real IDs.
     * Preparation reports unsupported domains or factor roles through ordinary strategy admission.
     */
    fun withFactors(problem: Problem): TranscendentalProblem {
        require(factorModel == null && auxiliaryCount == 0) { "function source already has private composition" }
        require(problem.numBoolVars == boolCount && problem.numIntVars == integers.size &&
            problem.numRealVars == reals.size) { "factor and function declaration counts differ" }
        return TranscendentalProblem(formula, integers, reals, booleans, objective, objectiveSense,
            semantics, problem)
    }

    internal fun withFormula(formula: TranscendentalFormula, auxiliaries: Int): TranscendentalProblem =
        TranscendentalProblem(formula, integers, reals, booleans, objective, objectiveSense, semantics,
            auxiliaryCount = auxiliaries)
}
