package com.eignex.klause.propagation

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray
import kotlin.time.TimeSource

/**
 * A [Problem] whose finite domains include the root-propagation fold.
 *
 * Construction forces propagation and folds its deductions into the model domains. This is a
 * propagation projection, not source-model data; finite search engines receive it after presolve.
 */
class BakedProblem internal constructor(
    numBoolVars: Int,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
    factors: Array<Factor>,
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

    /** Convenience overload taking factors as a [List] (stored as an [Array]). */
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
