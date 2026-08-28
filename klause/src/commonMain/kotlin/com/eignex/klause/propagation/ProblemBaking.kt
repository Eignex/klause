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

/** Build the propagation-owned finite-domain projection of this model. */
fun Problem.bake(cancellation: Cancellation = Cancellation.Never): BakedProblem {
    if (this is BakedProblem) return this
    require(hasFiniteIntDomains) { "only finite CP problems can be baked" }
    return BakedProblem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = Array(numIntVars) { requireFiniteIntDomains()[it] },
        factors = factors,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        packedOpenIntLo = intBounds.openLowerBits,
        packedOpenIntHi = intBounds.openUpperBits,
        modelBounds = intBounds,
    )
}

/** Cancellation that belongs to the propagation projection, or an unbounded token for raw model data. */
internal val Problem.propagationCancellation: Cancellation
    get() = (this as? BakedProblem)?.cancellation ?: Cancellation.Never

/** Whether this is a presolve pass view whose domains already carry its root deductions. */
internal val Problem.isFoldedPropagationView: Boolean
    get() = (this as? BakedProblem)?.alreadyFolded == true

/** Append a root-inert factor to an already baked propagation projection. */
internal fun Problem.withAppendedFactor(extra: Factor): BakedProblem = BakedProblem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = requireFiniteIntDomains(),
    factors = factors + extra,
    seedDeductions = baked,
    cancellation = propagationCancellation,
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
