package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException

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

@Suppress("ThrowsCount") // Each preparation stage must finish before the replacement root is published.
internal fun BakedProblem.conditionedRoot(assumptions: Assumptions, token: Cancellation): BakedProblem {
    if (token()) throw CancellationException("root preparation cancelled")
    val state = PropagationState(
        PropagationProblem(this),
        (rootDeductions as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None,
    )
    val seeded = state.seeded && state.seedAssumptions(assumptions)
    val conflict = if (seeded) state.runToFixpoint(allFactors = true, cancellation = token) else null
    if (state.runCancelled || token()) throw CancellationException("root preparation cancelled")
    val deductions = if (rootDeductions is PropagationResult.Unsat || !seeded || conflict != null) {
        PropagationResult.Unsat()
    } else {
        val keys = (0 until numBoolVars).filter { state.boolValues[it] != null }.toIntArray()
        PropagationResult.Implied(
            boolKeys = keys,
            boolValues = BooleanArray(keys.size) { state.boolValues[keys[it]]!! },
            intKeys = intArrayOf(),
            intValues = longArrayOf(),
        )
    }
    val conditioned = BakedProblem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = state.intDomains.copyOf(),
        factors = factors,
        seedDeductions = deductions,
        cancellation = token,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        packedOpenIntLo = intBounds.openLowerBits,
        packedOpenIntHi = intBounds.openUpperBits,
        modelBounds = intBounds,
    )
    if (token()) throw CancellationException("root preparation cancelled")
    return conditioned
}
