package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntDomains
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A `Problem` whose root bake is guaranteed to have run, and the only problem type that owns finite
 * search domains: [rootIntDomain] carries the root-propagation fold for every integer column, which is what
 * the finite engines, the model counter, sampling and the LP engine branch and propagate over. Produced
 * only by `Problem.bake` (or the presolve pipeline). A raw `Problem` is the supertype, so handing an
 * un-baked model to a solver is a compile error — the caller must `Problem.bake` it first, which is where
 * the parse-vs-solve boundary is enforced by the type system.
 *
 * A finite domain here may be narrower than the column's `Problem.intBounds`, and its endpoints may have
 * been invented to close a side the source left open. Consumers reasoning about the model rather than
 * enumerating its values read the bounds; consumers that search read the domains.
 */
class BakedProblem internal constructor(
    numBoolVars: Int,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
    factors: Array<Factor>,
    private val seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
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
    internal val cancellation: Cancellation = Cancellation.Never,
    internal val alreadyFolded: Boolean = false,
) : Problem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    declaredIntDomains = SourceIntDomains.ofDomains(
        domains = intDomains,
        shared = alreadyFolded,
        openLo = openIntLo,
        openHi = openIntHi,
        packedOpenLo = packedOpenIntLo,
        packedOpenHi = packedOpenIntHi,
        modelBounds = modelBounds,
    ),
    factors = factors,
    impliedFactorMask = impliedFactorMask,
    hasSymmetryBreaking = hasSymmetryBreaking,
    numRealVars = numRealVars,
    realLower = realLower,
    realUpper = realUpper,
) {
    /** The array the root fold writes into and every finite accessor reads. */
    internal val foldedIntDomains: Array<IntDomain> = requireFiniteIntDomains()

    /** Deductions established while constructing this propagation projection. */
    internal val rootDeductions: PropagationResult = rootBake(this, seedDeductions, cancellation)

    /** Time spent building and folding this propagation projection. */
    val bakeElapsed: Duration

    init {
        if (!alreadyFolded) {
            val mark = TimeSource.Monotonic.markNow()
            foldRootDeductionsIntoDomains(rootDeductions)
            bakeElapsed = mark.elapsedNow()
        } else {
            bakeElapsed = Duration.ZERO
        }
    }

    /**
     * Root-propagated finite domain of integer column [v].
     *
     * The root domain, not the live one: `PropagationSession.intDomain` narrows as search descends and is
     * what a propagator, cut, or relaxation reads at a node. Reading a root domain where the live one is
     * meant loses every decision above the node.
     */
    fun rootIntDomain(v: Int): IntDomain = foldedIntDomains[v]

    /**
     * Every root-propagated finite domain, in column order.
     *
     * Copied: this projection owns the array the fold writes into, so a consumer that keeps one gets its
     * own. Read a single column through [rootIntDomain] rather than copying per access.
     */
    fun rootIntDomains(): Array<IntDomain> = foldedIntDomains.copyOf()

    /**
     * The array the fold writes into, for a consumer that reads it within one call and keeps nothing.
     *
     * A model can declare millions of integer columns, so a per-firing presolve pass that only indexes the
     * domains must not pay [rootIntDomains]' copy. Writing through this rewrites what every other consumer
     * reads; a consumer that narrows or retains the array takes the copy.
     */
    internal val rootIntDomainsInPlace: Array<IntDomain> get() = foldedIntDomains

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
