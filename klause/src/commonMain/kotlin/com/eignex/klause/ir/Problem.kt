package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyDoubleArray

/**
 * Read-only logical model and the canonical source of solver projections. Variables come in three id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [com.eignex.klause.solver.Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Long] values in [com.eignex.klause.solver.Assignment].
 *  - Real vars: ids `[0, numRealVars)`, a separate namespace with no finite domain.
 *
 * An integer variable declares the values it may take — a range, possibly with an explicit value set
 * inside it ([declaredIntDomains]). Which engine owns the column is not part of the model:
 * [com.eignex.klause.solver.pipeline.ComponentPlan] selects that once per solve, and the finite,
 * root-propagated domains a finite engine branches on live on [com.eignex.klause.propagation.BakedProblem].
 * Factors mention Boolean, integer, and real variables in any mix.
 *
 * Structural arrays and factor payloads are borrowed read-only. Callers must keep them unchanged
 * for this model's lifetime, including lazy or resumable searches and plans derived from it.
 * Rewrites construct a fresh model and invalidate source-scoped plans and evidence. Assignment
 * publications and objective coefficients instead own their captured buffers.
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
     * library's annotation) or XCSP3's `redundantConstraints` / `symmetryBreaking` classes.
     * Indexed parallel to [factors]; `null` (the common case) means no factor is implied. Local
     * search seeds these factors a lower initial violation weight so the bulk of redundant /
     * symmetry rows don't dominate the weighted-violation landscape before the structural
     * constraints are satisfied; every other consumer ignores it (the constraints are still posted
     * and propagate normally).
     */
    val impliedFactorMask: BooleanArray? = null,
    /**
     * True when the model declared at least one `symmetry_breaking_constraint` or XCSP3
     * `symmetryBreaking` constraint. Presolve uses it to skip its own symmetry breaking
     * ([com.eignex.klause.presolve.PresolvePass.BREAK_SYMMETRIES]) by default — stacking klause's
     * automorphism break on top of the model's hand-written one is redundant and the two can
     * interact.
     */
    val hasSymmetryBreaking: Boolean = false,
    /**
     * Number of continuous real variables; ids occupy `[0, numRealVars)` in a namespace separate from
     * the integer and Boolean ones. Continuous state belongs to the selected arithmetic theory or
     * linear completion mechanism. These columns have no finite CP domain or propagation trail.
     */
    val numRealVars: Int = 0,
    /** Lower bound of each real variable (length [numRealVars]); `Double.NEGATIVE_INFINITY` for open. */
    val realLower: DoubleArray = EmptyDoubleArray,
    /** Upper bound of each real variable (length [numRealVars]); `Double.POSITIVE_INFINITY` for open. */
    val realUpper: DoubleArray = EmptyDoubleArray,
    /** Captured resource policy; independent of the logical model semantics. */
    val settings: ProblemSettings = ProblemSettings(),
    /** Declared defaults and effective caller policy captured before source lowering. */
    override val semantics: EffectiveSourceSemantics = defaultIrSemantics,
) : SourceSemanticsOwner {
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
    internal fun finiteIntDomain(v: Int): IntDomain = declaredIntDomains.finiteDomain(v).withStorage(settings.storage)

    /**
     * Every [finiteIntDomain], in column order, copied so a caller may narrow the result in place — the
     * materialization a bake or an explicitly boxed candidate model is built from.
     */
    internal fun finiteIntDomains(): Array<IntDomain> = Array(numIntVars) { finiteIntDomain(it) }

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
        settings: ProblemSettings = ProblemSettings(),
        semantics: EffectiveSourceSemantics = defaultIrSemantics,
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
        settings = settings,
        semantics = semantics,
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
        settings: ProblemSettings = ProblemSettings(),
        semantics: EffectiveSourceSemantics = defaultIrSemantics,
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
        settings = settings,
        semantics = semantics,
    )

    /** Overload taking factors as a [List]; they are stored as an [Array]. */
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
        settings: ProblemSettings = ProblemSettings(),
        semantics: EffectiveSourceSemantics = defaultIrSemantics,
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
        settings = settings,
        semantics = semantics,
        openIntLo = openIntLo,
        openIntHi = openIntHi,
        packedOpenIntLo = packedOpenIntLo,
        packedOpenIntHi = packedOpenIntHi,
        modelBounds = modelBounds,
    )

    /** Copy the logical model with explicit resource [settings], leaving preparation deferred. */
    fun withSettings(settings: ProblemSettings): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        declaredIntDomains = declaredIntDomains,
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        settings = settings,
        semantics = semantics,
    )

    internal fun withSourceSemantics(semantics: EffectiveSourceSemantics): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        declaredIntDomains = declaredIntDomains,
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        settings = settings,
        semantics = semantics,
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
        settings = settings,
        semantics = semantics,
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
        settings = settings,
        semantics = semantics,
    )

    /**
     * This model over [newDomains], carrying every row and every other declaration through unchanged.
     *
     * Takes the rebounded declarations rather than a range, so the caller has already intersected its
     * proved range with whatever value sets the columns declare — see [SourceIntDomains.rebounded]. That
     * keeps the widening [withFactors] warns about out of this path: a range cannot arrive here and
     * quietly replace a value set with its hull.
     */
    internal fun withDeclaredIntDomains(newDomains: SourceIntDomains): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        declaredIntDomains = newDomains,
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
        settings = settings,
        semantics = semantics,
    )

    /** Total number of factors. */
    val numFactors: Int get() = factors.size
}
