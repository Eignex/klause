package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray

/**
 * Immutable solver-side problem. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [com.eignex.klause.solver.Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [com.eignex.klause.solver.Assignment].
 *
 * An integer variable may have an `IntDomain` for finite CP search, or be symbolic and owned by a
 * theory component. Factors mention either or both.
 * Occurrence lists are split per kind so `flip(boolVar)` and `setInt(intVar)` only walk the
 * factors mentioning that specific variable.
 *
 * Float variables, when the schema or front-end uses them, are bucketed to integer
 * variables in the factor system (so [factors] stays pure int+bool).
 */
open class Problem(
    /** Number of Boolean variables; ids occupy `[0, numBoolVars)`. */
    val numBoolVars: Int,
    /** Number of integer variables; ids occupy `[0, numIntVars)`. */
    val numIntVars: Int,
    /** Typed integer-column capabilities selected by the component plan. */
    val intColumns: IntColumns,
    /** The constraints over the variables. */
    val factors: Array<Factor>,
    /**
     * Per-factor flag marking constraints the model declared as *implied* — MiniZinc's
     * `redundant_constraint` / `symmetry_breaking_constraint` (surfaced via the klause MZN
     * library's annotation). Indexed parallel to [factors]; `null` (the common case) means no
     * factor is implied. Local search seeds these factors a lower initial violation weight so
     * the bulk of redundant / symmetry rows don't dominate the weighted-violation landscape
     * before the structural constraints are satisfied; every other consumer ignores it (the
     * constraints are still posted and propagate normally).
     */
    val impliedFactorMask: BooleanArray? = null,
    /**
     * True when the model declared at least one `symmetry_breaking_constraint`. Presolve uses
     * it to skip its own symmetry breaking ([com.eignex.klause.presolve.PresolvePass.BREAK_SYMMETRIES])
     * by default — stacking klause's automorphism break on top of the model's hand-written one
     * is redundant and the two can interact.
     */
    val hasSymmetryBreaking: Boolean = false,
    /**
     * Skip the defensive copy of finite domains: when `true`, the passed array is shared as-is rather than
     * copied. Internal to an already-folded propagation construction (the incremental
     * [com.eignex.klause.presolve.PresolveSession] and the SMT/MPS front-ends supply a re-propagated array
     * read — never mutated — within one firing and rebuilt on the next change, so sharing saves an
     * O([numIntVars]) copy per firing). A raw `Problem` leaves this off and copies, so nothing it is
     * constructed from can alias its domains.
     */
    val sharedDomains: Boolean = false,
    /**
     * Number of LP-only continuous (real) variables; ids occupy `[0, numRealVars)` in a namespace
     * separate from the integer and Boolean ones. A real variable is present in the LP relaxation as a
     * continuous column but absent from CP search — it has no [requireFiniteIntDomains] entry, no trail, and is never
     * branched. The simplex resolves it at nodes and leaves (the LP-only-columns hybrid engine).
     * Zero for the pure integer/Boolean core, which every existing consumer builds.
     */
    val numRealVars: Int = 0,
    /** Lower bound of each real variable (length [numRealVars]); `Double.NEGATIVE_INFINITY` for open. */
    val realLower: DoubleArray = EmptyDoubleArray,
    /** Upper bound of each real variable (length [numRealVars]); `Double.POSITIVE_INFINITY` for open. */
    val realUpper: DoubleArray = EmptyDoubleArray,
    /**
     * Integer variables whose declared domain is genuinely open on the low side, indexed by int var id;
     * `null` (the common case) means every domain is a real declared bound.
     *
     * A finite-search backend may close an otherwise open source side. The resulting finite endpoint is
     * an artefact, not a model constraint; this records its provenance so model-level consumers continue
     * to reason over the true open range rather than the materialized domain.
     */
    openIntLo: BooleanArray? = null,
    /** Integer variables genuinely open on the high side; see [openIntLo]. */
    openIntHi: BooleanArray? = null,
    /** Packed open lower sides retained across internal problem rebuilds. */
    packedOpenIntLo: Bits? = null,
    /** Packed open upper sides retained across internal problem rebuilds. */
    packedOpenIntHi: Bits? = null,
    /** Source-model bounds, when this finite problem was materialized from a [ProblemSpec]. */
    modelBounds: IntBounds? = null,
) {
    /** Finite CP domain capability of [v], or `null` when a theory owns the column. */
    fun intDomainOrNull(v: Int): IntDomain? = intColumns.domainOrNull(v)

    /** True when every integer column can be handed to the finite CP engine. */
    val hasFiniteIntDomains: Boolean get() = intColumns.allFiniteOrNull() != null

    /** Return all finite CP domains, rejecting a problem that contains symbolic theory columns. */
    fun requireFiniteIntDomains(): Array<IntDomain> = requireNotNull(intColumns.allFiniteOrNull()) {
        "finite CP state requested for a problem with symbolic integer columns"
    }

    /**
     * Model-level bounds of the integer columns. Unlike [requireFiniteIntDomains], either side may be absent when
     * the finite search domain was closed by an invented fallback bound. Consumers that reason over
     * the model rather than enumerate its values must read this state, or explicitly decline open
     * columns, instead of treating the fallback endpoint as a constraint.
     */
    val intBounds: IntBounds = modelBounds ?: run {
        require(hasFiniteIntDomains) { "symbolic integer columns require source model bounds" }
        requireFiniteIntDomains().let { domains ->
            IntBounds.fromFiniteBounds(
                lowerBounds = LongArray(numIntVars) { domains[it].min },
                upperBounds = LongArray(numIntVars) { domains[it].max },
                openLo = openIntLo,
                openHi = openIntHi,
                packedOpenLo = packedOpenIntLo,
                packedOpenHi = packedOpenIntHi,
            )
        }
    }

    init {
        require(intColumns.size == numIntVars) {
            "integer column count ${intColumns.size} != numIntVars $numIntVars"
        }
        require(openIntLo == null || openIntLo.size == numIntVars) {
            "openIntLo size ${openIntLo?.size} != numIntVars $numIntVars"
        }
        require(openIntHi == null || openIntHi.size == numIntVars) {
            "openIntHi size ${openIntHi?.size} != numIntVars $numIntVars"
        }
        require(impliedFactorMask == null || impliedFactorMask.size == factors.size) {
            "impliedFactorMask size ${impliedFactorMask?.size} != factors size ${factors.size}"
        }
        require(realLower.size == numRealVars && realUpper.size == numRealVars) {
            "real bound arrays (${realLower.size}/${realUpper.size}) != numRealVars $numRealVars"
        }
        factors.forEachIndexed { factorId, factor ->
            factor.boolVars.forEach { variable ->
                require(variable in 0 until numBoolVars) {
                    "factor $factorId references Boolean variable $variable outside [0, $numBoolVars)"
                }
            }
            factor.intVars.forEach { variable ->
                require(variable in 0 until numIntVars) {
                    "factor $factorId references integer variable $variable outside [0, $numIntVars)"
                }
            }
            factor.variables.reals.forEach { variable ->
                require(variable in 0 until numRealVars) {
                    "factor $factorId references real variable $variable outside [0, $numRealVars)"
                }
            }
        }
    }

    /**
     * Convenience overload taking factors as a [List]. Internally stored as an [Array] for
     * tighter hot-loop iteration; callers building a [MutableList] and then constructing the
     * problem can use this overload without converting first.
     */
    constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: Array<Factor>,
        impliedFactorMask: BooleanArray? = null,
        hasSymmetryBreaking: Boolean = false,
        sharedDomains: Boolean = false,
        numRealVars: Int = 0,
        realLower: DoubleArray = EmptyDoubleArray,
        realUpper: DoubleArray = EmptyDoubleArray,
        openIntLo: BooleanArray? = null,
        openIntHi: BooleanArray? = null,
        packedOpenIntLo: Bits? = null,
        packedOpenIntHi: Bits? = null,
        modelBounds: IntBounds? = null,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intColumns = FiniteIntColumns(intDomains, sharedDomains),
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        sharedDomains = sharedDomains,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        openIntLo = openIntLo,
        openIntHi = openIntHi,
        packedOpenIntLo = packedOpenIntLo,
        packedOpenIntHi = packedOpenIntHi,
        modelBounds = modelBounds,
    )

    constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: List<Factor>,
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
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intColumns = FiniteIntColumns(intDomains),
        factors = Array(factors.size) { factors[it] },
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
    )

    /**
     * A copy with the integer domains replaced — used when deferred bounding tightens the
     * open sides after parsing, before the problem flows into presolve. Every other structure (factors,
     * real bounds, implied/symmetry flags) is shared. The result is a raw `Problem` whose root bake is
     * still deferred.
     *
     * [newOpenLo] / [newOpenHi] record which sides of [newDomains] the bounding invented rather than
     * derived, so the LP relaxation can keep those columns open; `null` leaves the existing marks.
     */
    fun withIntDomains(
        newDomains: Array<IntDomain>,
        newOpenLo: BooleanArray? = null,
        newOpenHi: BooleanArray? = null,
    ): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = newDomains,
        factors = factors.asList(),
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        openIntLo = newOpenLo,
        openIntHi = newOpenHi,
        packedOpenIntLo = if (newOpenLo == null) intBounds.openLowerBits else null,
        packedOpenIntHi = if (newOpenHi == null) intBounds.openUpperBits else null,
        modelBounds = intBounds,
    )

    /** Total number of factors. */
    val numFactors: Int get() = factors.size
}
