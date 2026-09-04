package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
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

/** Cancellation that belongs to the propagation projection, or an unbounded token for raw model data. */
internal val Problem.propagationCancellation: Cancellation
    get() = (this as? BakedProblem)?.cancellation ?: Cancellation.Never

/** Whether this is a presolve pass view whose domains already carry its root deductions. */
internal val Problem.isFoldedPropagationView: Boolean
    get() = (this as? BakedProblem)?.alreadyFolded == true

/**
 * The finite domains an engine seeds its own root state from.
 *
 * A projection hands back the array its fold owns, so seeding a state costs no copy on the paths that
 * build one per probe. Raw model data has no fold to read, and materializes the declared value sets
 * instead — which is what the projection itself was built from.
 */
internal val Problem.finiteRootDomains: Array<IntDomain>
    get() = (this as? BakedProblem)?.foldedIntDomains ?: finiteIntDomains()

/** Append a root-inert factor to an already baked propagation projection. */
internal fun Problem.withAppendedFactor(extra: Factor): BakedProblem = BakedProblem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = finiteRootDomains,
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
