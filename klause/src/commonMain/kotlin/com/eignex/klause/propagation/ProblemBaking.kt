package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation

/**
 * Result of the propagation engine's root bake. A [BakedProblem] retains the deductions that
 * produced its folded domains; an unprojected [Problem] is propagated on demand.
 */
val Problem.baked: PropagationResult
    get() = if (this is BakedProblem) {
        rootDeductions
    } else {
        rootBake(
            this,
            PropagationResult.Implied.EMPTY,
            Cancellation.Never,
        )
    }

/**
 * Build the finite, root-propagated projection of this model's declared integer domains.
 *
 * A column that declares a value set keeps it, holes included; one that declares its whole range has that
 * range materialized. Every declared side must be closed — no fallback endpoint is invented here, because
 * inventing one narrows the model.
 */
fun Problem.bake(cancellation: Cancellation = Cancellation.Never): BakedProblem {
    if (this is BakedProblem) return this
    return BakedProblem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = finiteIntDomains(),
        factors = factors,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        modelBounds = intBounds,
    )
}

/** Append a root-inert factor to an already baked propagation projection. */
internal fun BakedProblem.withAppendedFactor(extra: Factor): BakedProblem = BakedProblem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = rootIntDomainsInPlace,
    factors = factors + extra,
    seedDeductions = rootDeductions,
    cancellation = cancellation,
    impliedFactorMask = impliedFactorMask?.let { it + false },
    hasSymmetryBreaking = hasSymmetryBreaking,
    numRealVars = numRealVars,
    realLower = realLower,
    realUpper = realUpper,
    packedOpenIntLo = intBounds.openLowerBits,
    packedOpenIntHi = intBounds.openUpperBits,
    modelBounds = intBounds,
    alreadyFolded = true,
)

/** Run deductive propagation against [assumptions] through the propagation engine. */
fun Problem.propagate(
    assumptions: Assumptions = Assumptions.None,
    cancellation: Cancellation = Cancellation.Never,
    skipExpensiveBake: Boolean = false,
): PropagationResult = runRootPropagation(this, assumptions, cancellation, skipExpensiveBake)
