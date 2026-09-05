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
class BakedProblem private constructor(
    numBoolVars: Int,
    numIntVars: Int,
    /**
     * The array the root fold writes into and every finite accessor reads: it also backs the source
     * declarations below, so a narrowing the fold proves lands in one place rather than leaving the two
     * readings of the same column disagreeing.
     *
     * Owned outright only when this projection ran its own fold. A caller handing over domains that
     * already carry one keeps the array's identity — that is what lets a per-firing presolve view share
     * the session's live domains — so nothing outside the fold may write through it.
     */
    internal val foldedIntDomains: Array<IntDomain>,
    /** Whether [foldedIntDomains] already carries this projection's root deductions, so no fold runs. */
    internal val alreadyFolded: Boolean,
    factors: Array<Factor>,
    seedDeductions: PropagationResult,
    impliedFactorMask: BooleanArray?,
    hasSymmetryBreaking: Boolean,
    numRealVars: Int,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    openIntLo: BooleanArray?,
    openIntHi: BooleanArray?,
    packedOpenIntLo: Bits?,
    packedOpenIntHi: Bits?,
    modelBounds: IntBounds?,
    internal val cancellation: Cancellation,
) : Problem(
    numBoolVars = numBoolVars,
    numIntVars = numIntVars,
    declaredIntDomains = SourceIntDomains.ofDomains(
        domains = foldedIntDomains,
        shared = true,
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
     * The array the fold writes into, for a consumer that only ever reads it.
     *
     * A model can declare millions of integer columns, so a per-firing presolve pass that indexes the
     * domains — or a search state seeded once per probe — must not pay [rootIntDomains]' copy. Writing
     * through this rewrites what every other consumer reads; a consumer that narrows the array takes
     * the copy.
     */
    internal val rootIntDomainsInPlace: Array<IntDomain> get() = foldedIntDomains

    internal constructor(
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
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        // The fold rewrites what every reader of this projection sees, so it needs an array of its own:
        // only a caller handing over domains that already carry the fold keeps its identity here.
        foldedIntDomains = if (alreadyFolded) intDomains else intDomains.copyOf(),
        alreadyFolded = alreadyFolded,
        factors = factors,
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
    )

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
