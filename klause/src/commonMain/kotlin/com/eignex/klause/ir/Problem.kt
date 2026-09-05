package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray

/**
 * Immutable logical model and the canonical source of solver projections. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [com.eignex.klause.solver.Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [com.eignex.klause.solver.Assignment].
 *
 * An integer variable declares the values it may take — a range, possibly with an explicit value set
 * inside it ([declaredIntDomains]). Which engine owns the column is not part of the model:
 * [com.eignex.klause.solver.pipeline.ComponentPlan] selects that once per solve, and the finite,
 * root-propagated domains a finite engine branches on live on [com.eignex.klause.propagation.BakedProblem].
 * Factors mention Boolean, integer, and real variables in any mix.
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
    /** Values the model declares for each integer column. */
    val declaredIntDomains: SourceIntDomains,
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
     * Number of LP-only continuous (real) variables; ids occupy `[0, numRealVars)` in a namespace
     * separate from the integer and Boolean ones. A real variable is present in the LP relaxation as a
     * continuous column but absent from CP search — it has no declared integer domain, no trail, and is
     * never branched. The simplex resolves it at nodes and leaves (the LP-only-columns hybrid engine).
     * Zero for the pure integer/Boolean core, which every existing consumer builds.
     */
    val numRealVars: Int = 0,
    /** Lower bound of each real variable (length [numRealVars]); `Double.NEGATIVE_INFINITY` for open. */
    val realLower: DoubleArray = EmptyDoubleArray,
    /** Upper bound of each real variable (length [numRealVars]); `Double.POSITIVE_INFINITY` for open. */
    val realUpper: DoubleArray = EmptyDoubleArray,
) {
    /**
     * Model-level bounds of the integer columns. Either side may be absent when a finite search domain
     * was closed by an invented fallback bound. Consumers that reason over the model rather than
     * enumerate its values must read this state, or explicitly decline open columns, instead of treating
     * the fallback endpoint as a constraint.
     */
    val intBounds: IntBounds get() = declaredIntDomains.bounds

    /**
     * Value set declared for [v], or `null` when the model admits its whole [intBounds] range.
     *
     * A column with an open side declares nothing, whatever finite box a lane materialized for it. The
     * finite, root-propagated domains a search branches on are not read here at all — they belong to
     * [com.eignex.klause.propagation.BakedProblem], which states them in its own type.
     */
    fun intDomainOrNull(v: Int): IntDomain? = declaredIntDomains.declaredOrNull(v)

    /**
     * Finite domain of [v] as this model states it: the declared value set, or the closed [intBounds]
     * range materialized where none was declared. On a [com.eignex.klause.propagation.BakedProblem] the
     * two readings coincide — its declarations are the array its root fold writes into.
     *
     * The model-side reading, taken where a projection may not exist yet: a span estimate, the
     * materialization a bake is built from, a column a rebuild is about to box. A finite engine does not
     * reach for it — it takes a `BakedProblem` and branches on `rootIntDomain`.
     */
    internal fun finiteIntDomain(v: Int): IntDomain = declaredIntDomains.finiteDomain(v)

    /**
     * Every [finiteIntDomain], in column order, copied so a caller may narrow the result in place — the
     * materialization a bake or an explicitly boxed candidate model is built from.
     */
    internal fun finiteIntDomains(): Array<IntDomain> = declaredIntDomains.finiteDomains()

    init {
        require(declaredIntDomains.size == numIntVars) {
            "integer column count ${declaredIntDomains.size} != numIntVars $numIntVars"
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
     * Build a canonical source model from its declared integer bounds.
     *
     * The columns admit their whole range: a model that also excludes interior values states them, and
     * reaches this class through [declaredIntDomains] or the value-set constructors instead.
     */
    constructor(
        numBoolVars: Int,
        intBounds: IntBounds,
        factors: Array<Factor>,
        impliedFactorMask: BooleanArray? = null,
        hasSymmetryBreaking: Boolean = false,
        numRealVars: Int = 0,
        realLower: DoubleArray = EmptyDoubleArray,
        realUpper: DoubleArray = EmptyDoubleArray,
    ) : this(
        numBoolVars = numBoolVars,
        numIntVars = intBounds.size,
        declaredIntDomains = SourceIntDomains.ofBounds(intBounds),
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )

    /**
     * Build a source model whose integer columns declare explicit value sets.
     *
     * [openIntLo] / [openIntHi] (or their packed forms) mark the sides of [intDomains] whose endpoint was
     * invented to close a genuinely open declaration, so model-level consumers keep reasoning over the
     * true open range. [modelBounds] supplies those bounds directly when a rebuild retains them.
     */
    constructor(
        numBoolVars: Int,
        numIntVars: Int,
        intDomains: Array<IntDomain>,
        factors: Array<Factor>,
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
        declaredIntDomains = SourceIntDomains.ofDomains(
            domains = intDomains,
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
    )

    /**
     * Convenience overload taking factors as a [List]. Internally stored as an [Array] for
     * tighter hot-loop iteration; callers building a [MutableList] and then constructing the
     * problem can use this overload without converting first.
     */
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
        intDomains = intDomains,
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
     * A copy declaring [newDomains] — used when deferred bounding narrows a wide column after parsing,
     * before the problem flows into presolve. Every other structure (factors, real bounds,
     * implied/symmetry flags) is shared. The result is a raw `Problem` whose root bake is still deferred.
     *
     * [intBounds] is carried through rather than re-read off [newDomains]: an endpoint bounding invented
     * to narrow a column is an artefact of the finite lane, so the model-level range stays what the
     * source declared and the LP relaxation keeps reasoning over it.
     */
    fun withIntDomains(newDomains: Array<IntDomain>): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = newDomains,
        factors = factors.asList(),
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        modelBounds = intBounds,
    )

    /**
     * This model over [newFactors], carrying its declared columns through unchanged.
     *
     * The one spelling for a source rewrite that keeps the columns and changes the rows. Rebuilding from
     * [intBounds] instead widens a column that declares a value set into its hull, which is a different
     * model; a rewrite states which rows it keeps, not which values.
     *
     * [newImpliedFactorMask] is indexed by [newFactors], so a rewrite that reorders or drops rows states
     * the mask it kept; `null` marks no row implied.
     */
    fun withFactors(newFactors: Array<Factor>, newImpliedFactorMask: BooleanArray? = null): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        declaredIntDomains = declaredIntDomains,
        factors = newFactors,
        impliedFactorMask = newImpliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )

    /** Total number of factors. */
    val numFactors: Int get() = factors.size
}
