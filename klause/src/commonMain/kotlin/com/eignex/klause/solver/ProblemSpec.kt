package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray

/**
 * Immutable model before an enumerating backend chooses finite integer search domains.
 *
 * [intBounds] are the ranges the source model states, so either integer side may be open. A CP, local
 * search, counting, or sampling backend must call [materialize] with finite [IntDomain]s; that deliberate
 * conversion is the only place an invented search bound may enter the model.
 */
class ProblemSpec(
    /** Number of Boolean variables. */
    val numBoolVars: Int,
    /** Source-model bounds of the integer variables. */
    val intBounds: IntBounds,
    /** Constraints over the variables. */
    val factors: Array<Factor>,
    /** Root deductions supplied by a frontend or compiler. */
    val seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
    /** Cancellation token for eventual finite materialization. */
    val cancellation: Cancellation = Cancellation.Never,
    /** Model-declared implied-factor mask, parallel to [factors]. */
    val impliedFactorMask: BooleanArray? = null,
    /** Whether the source model already includes symmetry breaking. */
    val hasSymmetryBreaking: Boolean = false,
    /** Number of continuous LP-only variables. */
    val numRealVars: Int = 0,
    /** Lower bounds of the continuous variables. */
    val realLower: DoubleArray = EmptyDoubleArray,
    /** Upper bounds of the continuous variables. */
    val realUpper: DoubleArray = EmptyDoubleArray,
) {
    /** Number of integer variables. */
    val numIntVars: Int get() = intBounds.size

    init {
        require(impliedFactorMask == null || impliedFactorMask.size == factors.size)
        require(realLower.size == numRealVars && realUpper.size == numRealVars)
    }

    /** Build a [Problem] using the component-selected integer-domain capabilities. */
    fun materialize(intDomains: Array<IntDomain>): Problem {
        require(intDomains.size == numIntVars)
        return Problem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intDomains = intDomains,
            factors = factors,
            seedDeductions = seedDeductions,
            cancellation = cancellation,
            impliedFactorMask = impliedFactorMask,
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = numRealVars,
            realLower = realLower,
            realUpper = realUpper,
            modelBounds = intBounds,
        )
    }

    /** Build a hybrid problem from typed column capabilities selected before search. */
    fun materialize(intColumns: IntColumns): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intColumns = intColumns,
        factors = factors,
        seedDeductions = seedDeductions,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        modelBounds = intBounds,
    )

    /** Materialize the model's own finite ranges without inventing a search window. */
    fun materializeFiniteBounds(): Problem = materialize(
        Array(numIntVars) { v ->
            require(intBounds.hasLower(v) && intBounds.hasUpper(v)) {
                "integer column $v is open and needs an explicit finite search domain"
            }
            IntDomain(intBounds.lower(v), intBounds.upper(v))
        },
    )
}
