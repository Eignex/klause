package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.rootBake
import com.eignex.klause.propagation.runRootPropagation
import com.eignex.klause.ir.values
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray
import kotlin.time.Duration

/**
 * Immutable solver-side problem. Variables come in two id spaces:
 *  - Boolean vars: ids `[0, numBoolVars)`, packed bits in [Assignment].
 *  - Integer vars: ids `[0, numIntVars)`, raw [Int] values in [Assignment].
 *
 * An integer variable may have an [IntDomain] for finite CP search, or be symbolic and owned by a
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
     * Extra root deductions computed outside the kernel — the failed-literal / SAC probing tiers
     * live in [com.eignex.klause.presolve.RootBaker], which runs them against an already-base-baked
     * [Problem] and feeds the result back here. Merged into the base `propagate(Assumptions.None)`
     * bake before it folds into [requireFiniteIntDomains], so the extra pins / bound tightenings / holes become
     * part of [baked] and the problem's own domains. Defaults to empty = base bake only; the kernel
     * never initiates probing itself (that would create a `solver → presolve → solver` cycle).
     */
    seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
    /**
     * Cooperative-cancellation token for the construction-time bake ([baked]). Polled on the
     * full-propagation fixpoint and between SAC passes so a `-t` deadline can abort an
     * otherwise-uncancellable bake on a slow propagator over wide domains. The partial bake
     * that results is sound (it only ever tightens). Defaults to [Cancellation.Never], so
     * every consumer that doesn't pass a deadline bakes to completion.
     */
    val cancellation: Cancellation = Cancellation.Never,
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
     * copied. Internal to [BakedProblem]'s already-folded construction (the incremental
     * [com.eignex.klause.presolve.PresolveSession] and the SMT/MPS front-ends supply a re-propagated array
     * read — never mutated — within one firing and rebuilt on the next change, so sharing saves an
     * O([numIntVars]) copy per firing). A raw [Problem] leaves this off and copies, so nothing it is
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
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
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
        seedDeductions = seedDeductions,
        cancellation = cancellation,
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
        seedDeductions: PropagationResult = PropagationResult.Implied.EMPTY,
        cancellation: Cancellation = Cancellation.Never,
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
        seedDeductions = seedDeductions,
        cancellation = cancellation,
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
     * real bounds, implied/symmetry flags) is shared. The result is a raw [Problem] whose root bake is
     * still deferred; must not be called on a [BakedProblem], whose fold this copy would not reproduce.
     *
     * [newOpenLo] / [newOpenHi] record which sides of [newDomains] the bounding invented rather than
     * derived, so the LP relaxation can keep those columns open; `null` leaves the existing marks.
     */
    fun withIntDomains(
        newDomains: Array<IntDomain>,
        newOpenLo: BooleanArray? = null,
        newOpenHi: BooleanArray? = null,
    ): Problem {
        require(this !is BakedProblem) { "withIntDomains is for raw problems only" }
        return Problem(
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
    }

    /** Total number of factors. */
    val numFactors: Int get() = factors.size

    /**
     * Result of running [propagate] once with empty assumptions at construction time, merged with
     * any [seedDeductions] the presolve-lane probing supplied. Caches literals/values forced by the
     * constraints alone — every solver call gets a smaller residual problem with no per-call
     * propagation cost, and trivially-Unsat problems surface here instead of after a full search
     * budget. May be [PropagationResult.Unsat] for trivially-infeasible problems; callers that want
     * fail-fast behavior can check this.
     *
     * The deductions recorded here are also folded back into [requireFiniteIntDomains], so the diff is
     * expressed relative to the constructor-input domains rather than the tightened ones.
     * Re-seeding it on an already-tightened domain is a no-op, so existing consumers that
     * replay [baked] as assumptions are unaffected.
     */
    val baked: PropagationResult by lazy(LazyThreadSafetyMode.NONE) {
        rootBake(this, seedDeductions, cancellation)
    }

    /** Wall time the root bake took on a [BakedProblem]: forcing [baked] (root propagation to fixpoint)
     *  and folding it into [requireFiniteIntDomains]. Zero on a raw [Problem] (which never bakes) and on a
     *  [sharedDomains] baked problem (whose domains arrive already folded). Lets a front-end separate parse
     *  cost from bake cost when reporting load time. Set once by [BakedProblem]'s construction. */
    var bakeElapsed: Duration = Duration.ZERO
        protected set

    /**
     * Force the root bake and return the solve-ready [BakedProblem] — the only problem type the solvers,
     * the model counter, sampling and the LP engine accept. Idempotent: returns `this` when already a
     * [BakedProblem]. Otherwise constructs a [BakedProblem] over the same factors, folding the root-bake
     * deductions into its domains. [cancellation] budgets the fold: on a pathologically wide domain the
     * cheap bound-propagation fixpoint can grind, so the presolve pipeline threads its own (budget-capped)
     * cancellation here. A fired budget yields a sound *partial* bake (the fixpoint only ever tightens);
     * the deferred expensive propagators and the search re-derive the rest at the root.
     */

    fun bake(cancellation: Cancellation = this.cancellation): BakedProblem {
        if (this is BakedProblem) return this
        require(hasFiniteIntDomains) { "only finite CP problems can be baked" }
        // A raw front-end/builder problem carries no seedDeductions (those come from a presolve rebuild,
        // which constructs its BakedProblem directly), so the bake is the plain base propagation.
        return BakedProblem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intDomains = Array(numIntVars) { requireFiniteIntDomains()[it] },
            factors = factors,
            cancellation = cancellation,
            impliedFactorMask = impliedFactorMask,
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = numRealVars,
            realLower = realLower,
            realUpper = realUpper,
            packedOpenIntLo = intBounds.openLowerBits,
            packedOpenIntHi = intBounds.openUpperBits,
            modelBounds = intBounds,
        )
    }

    /**
     * This problem with [extra] appended, reusing the bake rather than paying a fresh one.
     *
     * Every field the problem carries comes forward, and a caller names none of them. A rebuild that
     * listed the fields it thought of instead dropped the ones it did not — the real columns, so factors
     * referenced continuous columns the rebuild had declared away, and the open-bound provenance, so an
     * invented endpoint read back as a declared one. Both were silent at the call site, which is the
     * argument for a derivation over a constructor call.
     *
     * [extra] is appended, so existing factor ids keep their meaning and an implied-factor mask grows by
     * one non-implied slot. The appended factor must derive nothing the bake would have: it is recorded
     * as already folded, so a factor that propagates at the root would have its deduction missed.
     */
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

    /**
     * Run sound-but-incomplete deductive propagation against [assumptions]. Each factor's
     * [com.eignex.klause.propagation.Propagator.propagate] is invoked to fixed point; pins and domain
     * tightenings cascade through the occurrence lists. Returns deductions beyond [assumptions]
     * (disjoint from the input), or [PropagationResult.Unsat] if a contradiction is derived.
     *
     * This is the same routine the solver uses internally at init and at every sample / solve
     * call that carries non-empty assumptions.
     */
    fun propagate(
        assumptions: Assumptions = Assumptions.None,
        cancellation: Cancellation = Cancellation.Never,
        skipExpensiveBake: Boolean = false,
    ): PropagationResult = runRootPropagation(this, assumptions, cancellation, skipExpensiveBake)
}
