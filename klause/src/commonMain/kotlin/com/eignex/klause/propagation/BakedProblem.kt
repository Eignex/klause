package com.eignex.klause.propagation

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedLongArray
import kotlin.time.TimeSource

/**
 * A [Problem] whose root bake is guaranteed to have run: its [Problem.requireFiniteIntDomains] carry the
 * root-propagation fold, and it is the only propagation-owned problem type the finite engines, the model counter,
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
            foldRootDeductionsIntoDomains(baked)
            bakeElapsed = mark.elapsedNow()
        }
    }

    /** This baked problem with [extra] appended without re-running the root bake. */
    internal fun withAppendedFactor(extra: Factor): BakedProblem = BakedProblem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = requireFiniteIntDomains(),
        factors = factors + extra,
        seedDeductions = baked,
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

    private fun foldIntoDomains(result: PropagationResult) {
        if (result !is PropagationResult.Implied) return
        result.forEachInt { v, value ->
            requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMinAtLeast(value).withMaxAtMost(value)
        }
        result.forEachIntMin { v, lo -> requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMinAtLeast(lo) }
        result.forEachIntMax { v, hi -> requireFiniteIntDomains()[v] = requireFiniteIntDomains()[v].withMaxAtMost(hi) }
        val holesByVar = MutableIntObjectMap<LongArrayList>()
        result.forEachIntHole { v, value -> holesByVar.getOrPut(v) { LongArrayList() }.add(value) }
        holesByVar.forEach { v, holes ->
            val sorted = holes.toSortedLongArray()
            requireFiniteIntDomains()[v] = requireNotNull(requireFiniteIntDomains()[v].excludeValues(sorted)) {
                "baked holes emptied domain $v despite an Implied bake"
            }
        }
        result.forEachIntSet { v, survivors -> requireFiniteIntDomains()[v] = intDomainFromSurvivors(survivors) }
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

/** Appends an already-folded factor through the propagation-owned bake lifecycle. */
internal fun Problem.withAppendedFactor(extra: Factor): BakedProblem = bake().withAppendedFactor(extra)
