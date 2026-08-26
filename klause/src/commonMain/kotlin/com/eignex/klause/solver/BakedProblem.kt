package com.eignex.klause.solver

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray
import kotlin.time.TimeSource

/**
 * A [Problem] whose root bake is guaranteed to have run: its [Problem.requireFiniteIntDomains] carry the
 * root-propagation fold, and it is the only problem type the solvers, the model counter,
 * sampling and the LP engine accept. Produced only by [Problem.bake] (or the presolve pipeline). A raw
 * [Problem] is the supertype, so handing an un-baked model to a solver is a compile error — the caller
 * must [Problem.bake] it first, which is where the parse-vs-solve boundary is enforced by the type system.
 */
class BakedProblem internal constructor(
    numBoolVars: Int,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
    factors: Array<Factor>,
    seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
    impliedFactorMask: BooleanArray? = null,
    hasSymmetryBreaking: Boolean = false,
    numRealVars: Int,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    openIntLo: BooleanArray? = null,
    openIntHi: BooleanArray? = null,
    packedOpenIntLo: Bits? = null,
    packedOpenIntHi: Bits? = null,
    modelBounds: IntBounds? = null,
    cancellation: Cancellation = Cancellation.Never,
    alreadyFolded: Boolean = false,
) : Problem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    intDomains = intDomains,
    factors = factors,
    seedDeductions = seedDeductions,
    cancellation = cancellation,
    impliedFactorMask = impliedFactorMask,
    hasSymmetryBreaking = hasSymmetryBreaking,
    sharedDomains = alreadyFolded,
    numRealVars = numRealVars,
    realLower = realLower,
    realUpper = realUpper,
    openIntLo = openIntLo,
    openIntHi = openIntHi,
    packedOpenIntLo = packedOpenIntLo,
    packedOpenIntHi = packedOpenIntHi,
    modelBounds = modelBounds,
) {
    init {
        if (!alreadyFolded) {
            val mark = TimeSource.Monotonic.markNow()
            foldIntoDomains(baked)
            bakeElapsed = mark.elapsedNow()
        }
    }

    internal constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: List<Factor>,
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        impliedFactorMask: BooleanArray? = null,
        hasSymmetryBreaking: Boolean = false,
        numRealVars: Int = 0,
        realLower: DoubleArray = EmptyDoubleArray,
        realUpper: DoubleArray = EmptyDoubleArray,
        openIntLo: BooleanArray? = null,
        openIntHi: BooleanArray? = null,
        packedOpenIntLo: Bits? = null,
        packedOpenIntHi: Bits? = null,
        modelBounds: IntBounds? = null,
        cancellation: Cancellation = Cancellation.Never,
        alreadyFolded: Boolean = false,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = intDomains,
        factors = Array(factors.size) { factors[it] },
        seedDeductions = seedDeductions,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        openIntLo = openIntLo,
        openIntHi = openIntHi,
        packedOpenIntLo = packedOpenIntLo,
        packedOpenIntHi = packedOpenIntHi,
        modelBounds = modelBounds,
        cancellation = cancellation,
        alreadyFolded = alreadyFolded,
    )
}
