package com.eignex.klause.localsearch

import com.eignex.klause.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random
import kotlin.reflect.KClass

/** Initial weight for factors the model declared implied (redundant / symmetry-breaking). An
 *  order of magnitude below the 1.0 structural default: a model padded with hundreds of redundant
 *  rows then aggregates to roughly the weight of a handful of structural ones, so the early descent
 *  follows the real feasible region instead of chasing the implied bulk. */
internal const val IMPLIED_FACTOR_INITIAL_WEIGHT: Double = 0.1

/**
 * Mutable state of an ongoing solve. Owns the [Assignment], the violated-factor set, the
 * per-factor scratch arrays ([intPayload], [refPayload]), and the aggregated hard cost.
 */
class LocalSearchState(
    /** The problem being searched. */
    val problem: Problem,
    /** Search RNG. */
    val rng: Random,
    /** Variables pinned for this search. */
    var assumptions: Assumptions = Assumptions.None,
) {
    /** The current variable assignment. */
    val assignment: Assignment = Assignment(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
    )

    /** Invariant ids currently violated (degree > 0). */
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)

    /** Per-invariant graded violation degree (0 = satisfied), the source of truth for both
     *  [violated]-set membership (`degree > 0`) and [cost] (`Σ factorDegree`). Maintained
     *  incrementally from each invariant's `deltaIf*`/`apply*` and recomputed from
     *  [Invariant.violationDegree] at [recompute]. The graded sum gives CBLS a descent gradient on
     *  tight arithmetic/global constraints rather than a flat count of violated invariants. */
    val factorDegree: IntArray = IntArray(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)

    /** Per-factor `Long` scratch, the wide counterpart to [intPayload]. The weighted-sum
     *  family ([Linear], [ReifiedLinear], `PseudoBoolean`, `ReifiedPseudoBoolean`) keeps its
     *  running `Σ coeff·value` here so large coefficients / wide domains can't wrap a 32-bit
     *  accumulator and silently corrupt `isViolated` / `violationDegree`. */
    val longPayload: LongArray = LongArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)

    /** Buffer that strategies push candidate moves into. */
    val moveSink: MoveSink = MoveSink(assumptions)

    /** The problem's invariants, aliased so the hot LS loops read `factors` directly. */
    val factors: Array<out Invariant> = problem.invariants

    /** Factor ids elected for implicit-solving structured neighbourhoods — structural globals whose
     *  [Invariant.proposeStructuredMoves] preserves their own feasibility (see
     *  [Invariant.providesImplicitNeighbourhood]). The engine draws their feasibility-preserving moves
     *  even during infeasibility, and seeds them feasible at search start. Built once on first
     *  access. */
    val electedImplicit: IntArray by lazy { electImplicitFactors() }

    /** Scope-disjoint subset of [electedImplicit] for feasible-init seeding: greedily chosen
     *  largest-scope-first so no two seed factors share an int variable. Disjointness guarantees one
     *  factor's [Invariant.seedFeasible] never overwrites another's seeded vars, so the post-seed
     *  assignment satisfies every seeded global simultaneously. */
    val implicitSeedFactors: IntArray by lazy { electImplicitSeedSet() }

    /** Implicit-solving owner map over int vars: `ownerInt[v]` is the factor id that owns int var
     *  `v`, or `-1` if `v` is searched freely. A variable is owned once [seedImplicitFeasible] seeds
     *  its [implicitSeedFactors] global feasibly; from then on only that global's structure-preserving
     *  [Invariant.proposeStructuredMoves] may change it, so the generic neighbourhood can never break
     *  the implicitly-solved constraint. `null` until the first seeding pass, and only ever populated
     *  when implicit feasible-init is enabled — so a search that does not seed implicitly is
     *  unaffected. The [MoveSink] enforces the filter via [MoveSink.setOwners]. */
    var ownerInt: IntArray? = null
        private set

    /** Binary-implication graph of [problem], literal-indexed at `2·numBoolVars`: `graph[Lit.make(v,
     *  value)]` lists every literal that pinning `v = value` forces (sound, from probing-style
     *  propagation). Built once on first access — the implication-aware move sources
     *  ([com.eignex.klause.localsearch.movesource.FlipAndPropagate]) bundle a flip's forced
     *  literals into one atomic move. The candidate cap mirrors probing's free-Boolean bound, so the
     *  build cost is paid once per solve rather than per move. */
    val implicationGraph: Array<IntArray> by lazy {
        Presolve.implicationGraph(problem, problem.numBoolVars, Cancellation.Never)
    }

    private fun electImplicitFactors(): IntArray {
        val out = IntArrayList()
        for (id in 0 until problem.numFactors) {
            if (factors[id].providesImplicitNeighbourhood) out.add(id)
        }
        return IntArray(out.size) { out[it] }
    }

    private fun electImplicitSeedSet(): IntArray {
        // Largest scope first to seed the most variables; ties broken by factor id for determinism
        // (election must be reproducible, so the RNG never enters it).
        val candidates = electedImplicit.sortedWith(
            compareByDescending<Int> { problem.factors[it].intVars.size }.thenBy { it },
        )
        val owned = BooleanArray(problem.numIntVars)
        val seeds = IntArrayList()
        for (id in candidates) {
            val scope = problem.factors[id].intVars
            var disjoint = true
            for (v in scope) {
                if (owned[v]) {
                    disjoint = false
                    break
                }
            }
            if (!disjoint) continue
            for (v in scope) owned[v] = true
            seeds.add(id)
        }
        return IntArray(seeds.size) { seeds[it] }
    }

    /** Implicit-solving feasible init: seed every [implicitSeedFactors] global into a satisfying
     *  configuration (skipping vars frozen by [assumptions]). Caller is responsible for the
     *  subsequent [recompute]. */
    fun seedImplicitFeasible() {
        val seeds = implicitSeedFactors
        if (seeds.isEmpty()) return
        val owners = ownerInt ?: IntArray(problem.numIntVars) { -1 }
        owners.fill(-1)
        for (i in seeds.indices) {
            val fid = seeds[i]
            // Own a global's variables only when it actually seeded feasible: a failed seed (e.g. an
            // all-different with no perfect matching) leaves its vars infeasible, so they must stay in
            // the generic neighbourhood to be repaired rather than be frozen out as "implicitly solved".
            if (factors[fid].seedFeasible(this, fid)) {
                for (v in problem.factors[fid].intVars) owners[v] = fid
            }
        }
        ownerInt = owners
        moveSink.setOwners(owners)
    }

    /** Step counter incremented on every accepted move. Strategies use this together with
     *  [lastTouched] to enforce a tabu list. */
    var step: Long = 0L
        internal set

    /** Step at which each variable was last flipped or set. Bool var ids in `[0, numBoolVars)`; int
     *  var ids offset by `numBoolVars`. Reset to zero on [restart] — used only for tabu / CCA-window
     *  decisions within a single restart epoch. For cross-epoch activity, see [touchCount]. */
    val lastTouched: LongArray = LongArray(problem.numBoolVars + problem.numIntVars)

    /** Cumulative count of moves applied to each variable, same indexing as [lastTouched]. Survives
     *  [restart] so it measures activity across the whole search run. Captured by
     *  [com.eignex.klause.localsearch.WarmState] for ALNS's `activityBiased` destroy
     *  operator. */
    val touchCount: IntArray = IntArray(problem.numBoolVars + problem.numIntVars)

    /** Eagerly-maintained make/break vectors for `Move.BoolFlip`. `boolBreakCount[v]` counts
     *  currently-satisfied invariants that would become violated if `v` is flipped; `boolMakeCount[v]`
     *  is the symmetric count of currently-violated invariants that would become satisfied. Both
     *  updated incrementally in [applyBoolFlip] and [applyIntSet] over the move's invariant
     *  neighbourhood. Strategies querying break/make per pick (probSat, WalkSat, DDFW) read these in
     *  O(1), trading an O(Σ arity²) per-flip update cost for predictable O(1) query latency. */
    internal val boolBreakCount: IntArray = IntArray(problem.numBoolVars)
    internal val boolMakeCount: IntArray = IntArray(problem.numBoolVars)

    /** Aggregated hard cost = `Σ factorDegree`, the graded total violation. `Long` because a
     *  single tight arithmetic factor can carry a residual near [Int.MAX_VALUE] and the sum
     *  across a large factor set would otherwise overflow. `cost == 0L` iff feasible. */
    var cost: Long = 0L
        internal set

    /** Lowest [cost] observed since this state was constructed. Updated at the end of every
     *  committed `apply(move)` and preserved across [restart], so the all-time minimum drives
     *  aspiration decisions even when restart epochs go uphill. Used by
     *  [com.eignex.klause.localsearch.AspirationCriterion.OrImprovesBestEver]. */
    var bestCostSeen: Long = Long.MAX_VALUE
        internal set

    /** Objective injected by the engine during a `minimize` call; `null` otherwise — strategies
     *  consulting [shapedBreakScore] fall back to the unshaped break score. */
    var objective: Objective? = null
        internal set

    private var objIntVarsCache: IntArray? = null
    private var objIntVarsFor: Objective? = null

    /**
     * Int decision variables the current [objective] depends on — nonzero-coefficient vars of a
     * [LinearObjective], leaf vars of a [FunctionalObjective], empty for any other shape or a
     * satisfiability problem. Recomputed only when [objective] changes. This is the *objective*
     * hot-spot set: the feasible-phase analogue of the violated-factor bias the infeasibility-phase
     * sources already use, so an objective-descent structural move can concentrate on variables that
     * actually move the objective rather than swapping objective-irrelevant pairs.
     */
    val objectiveIntVars: IntArray
        get() {
            val obj = objective ?: return EmptyIntArray
            val cached = objIntVarsCache
            if (cached != null && objIntVarsFor === obj) return cached
            val computed = computeObjectiveIntVars(obj)
            objIntVarsCache = computed
            objIntVarsFor = obj
            return computed
        }

    private fun computeObjectiveIntVars(obj: Objective): IntArray = when (obj) {
        is LinearObjective -> {
            val out = IntArrayList()
            for (v in obj.intCoefficients.indices) if (obj.intCoefficients[v] != 0L) out.add(v)
            IntArray(out.size) { out[it] }
        }

        is FunctionalObjective -> obj.leafVars.copyOf()

        else -> EmptyIntArray
    }

    /** Sample an int decision variable biased toward the objective gradient ([objectiveIntVars]),
     *  consuming one RNG int, or `-1` when the objective exposes no per-var int direction. The shared
     *  hot-spot variable-selection primitive for feasible-phase structured sources. */
    fun objectiveHotSpotIntVar(rng: Random): Int {
        val vs = objectiveIntVars
        return if (vs.isEmpty()) -1 else vs[rng.nextInt(vs.size)]
    }

    /** Lambda coefficient from `params.costShaping` for pre-feasibility shaping. Set by the engine
     *  on entering `minimize`; 0.0 (no shaping) otherwise or under
     *  [com.eignex.klause.localsearch.CostShaping.FeasibilityFirst]. */
    var shapingLambda: Double = 0.0
        internal set

    /** Soft cap for `compressViolation`: residuals at or below it
     *  keep exact unit resolution, above it a log tail bounds how much one large-magnitude factor
     *  dominates the cost sum. Set by the engine from [LocalSearchParams.violationSoftCap] once per
     *  solve, before the first [recompute]; every graded factor shares this one cap. */
    var violationSoftCap: Int = DEFAULT_VIOLATION_SOFT_CAP
        internal set

    /** Per-invariant weight, default 1.0. Not read by the engine itself; strategies that bias toward
     *  repairing persistently-violated invariants (DDFW, SAPS) read and mutate this between picks.
     *
     *  Lazily allocated on first access. Weight-blind strategies (WalkSat / ProbSat / SA) never
     *  touch it and pay no allocation; only CBLS triggers the `DoubleArray(numFactors)`.
     *  [WarmState.captureFrom] probes [factorWeightsAllocated] first to avoid forcing the allocation
     *  to capture all-1.0 defaults. */
    private var _factorWeights: DoubleArray? = null

    /** Seed [factorWeights] by per-class population so no constraint kind dominates by count. Set by
     *  the engine from [LocalSearchParams.normalizeWeightsByClass] before the first weight access. */
    var normalizeWeightsByClass: Boolean = false
        internal set

    /** Per-invariant dynamic weights for weighted-violation strategies. Invariants the model declared
     *  implied ([Problem.impliedFactorMask]) start at [IMPLIED_FACTOR_INITIAL_WEIGHT] rather than
     *  1.0, so the implied bulk can't dominate the initial descent before structural constraints are
     *  met; SAPS-style bumping still raises an implied invariant's weight if it persistently blocks
     *  progress.
     *
     *  When [normalizeWeightsByClass] is set, non-implied invariants are additionally damped by class
     *  population — see [initialFactorWeights]. */
    val factorWeights: DoubleArray
        get() {
            var w = _factorWeights
            if (w == null) {
                w = initialFactorWeights()
                _factorWeights = w
                _baseFactorWeights = w.copyOf()
            }
            return w
        }

    private var _baseFactorWeights: DoubleArray? = null

    /** The initial seeded per-factor weights ([initialFactorWeights]), snapshotted once when
     *  [factorWeights] is first allocated and never mutated. SAPS-style smoothing pulls the live
     *  weights back toward this baseline rather than a flat constant, so the per-class / implied
     *  seeding survives the reactive bumping. */
    val baseFactorWeights: DoubleArray
        get() {
            _baseFactorWeights?.let { return it }
            factorWeights // forces allocation, which also assigns _baseFactorWeights
            return _baseFactorWeights ?: error("baseFactorWeights is assigned when factorWeights is allocated")
        }

    /** Build the initial per-factor weight vector. Non-implied factors start at 1.0, optionally
     *  class-normalised ([normalizeWeightsByClass]): an over-represented factor class (population
     *  above the mean over non-implied classes) is scaled so its aggregate weight is capped at that
     *  mean, never amplifying a smaller class above 1.0. Implied factors are pinned to
     *  [IMPLIED_FACTOR_INITIAL_WEIGHT] and excluded from the class tally. */
    private fun initialFactorWeights(): DoubleArray {
        val n = problem.numFactors
        val implied = problem.impliedFactorMask
        val w = DoubleArray(n) { 1.0 }
        if (normalizeWeightsByClass) {
            val counts = HashMap<KClass<*>, Int>()
            for (i in 0 until n) {
                if (implied != null && implied[i]) continue
                val k = problem.factors[i]::class
                counts[k] = (counts[k] ?: 0) + 1
            }
            if (counts.isNotEmpty()) {
                val meanClassSize = counts.values.sum().toDouble() / counts.size
                for (i in 0 until n) {
                    if (implied != null && implied[i]) continue
                    val c = counts.getValue(problem.factors[i]::class)
                    if (c > meanClassSize) w[i] = meanClassSize / c
                }
            }
        }
        if (implied != null) for (i in 0 until n) if (implied[i]) w[i] = IMPLIED_FACTOR_INITIAL_WEIGHT
        return w
    }

    /** True iff [factorWeights] has been touched (allocated) on this state. Reading is
     *  free; allows callers to probe without forcing the lazy allocation. */
    internal val factorWeightsAllocated: Boolean get() = _factorWeights != null

    /** Configuration-Checking flag per Boolean variable. `true` means a neighboring variable has
     *  been touched since this var was last flipped (or since restart), so CCASat-style strategies
     *  treat it as eligible to re-flip; `false` means re-flipping it would be a no-progress cycle. */
    val boolConfChange: BooleanArray = BooleanArray(problem.numBoolVars) { true }

    /** Configuration-Checking flag per integer variable. See [boolConfChange]. */
    val intConfChange: BooleanArray = BooleanArray(problem.numIntVars) { true }

    // Degree scratch reused by evaluateCompound so an apply+revert probe allocates nothing on its
    // array-copy path (the dominant LS allocation source). State is per-worker, so no locking.
    private var degScratch: IntArray? = null

    // Break-count probe scratch. While breakProbeActive, updateViolation records each factor whose
    // degree changes during a probe's forward apply, snapshotting its pre-probe violated status on
    // first touch. The break count is then a scan of only the touched factors: a factor can flip
    // into violation only if its degree changed.
    private var breakProbeActive = false
    private val probeTouched: BooleanArray = BooleanArray(problem.numFactors)
    private val probeWasViolated: BooleanArray = BooleanArray(problem.numFactors)
    private val probeTouchedList: IntArrayList = IntArrayList()

    // Set for the whole apply+revert span of a compound probe. While active, applyBoolFlip /
    // applyIntSet skip configuration-change maintenance: a probe restores its start assignment, so
    // any conf-change marks would have to be reverted anyway.
    private var probeActive = false

    /** Reset to a fresh random assignment and reinitialise all factors. */
    fun restart() {
        assignment.randomize(rng, problem.intDomains)
        // Overwrite the assumed slots so the assignment starts consistent with the caller's pins.
        assumptions.forEachBool { id, value ->
            if (assignment.boolValue(id) != value) assignment.flipBool(id)
        }
        assumptions.forEachInt { id, value -> assignment.setInt(id, value) }
        resetStepCounters()
        recompute()
    }

    /** Clear tabu / CCA bookkeeping without touching the assignment. Used by
     *  optimization-side warm-up passes (e.g. greedy-repair) that mutate the assignment
     *  via [apply] but should leave the LS engine a fresh tabu epoch afterwards. */
    fun resetStepCounters() {
        for (i in lastTouched.indices) lastTouched[i] = 0L
        for (i in boolConfChange.indices) boolConfChange[i] = true
        for (i in intConfChange.indices) intConfChange[i] = true
        step = 0L
    }

    /** Recompute cost and per-factor degrees from scratch. */
    fun recompute() {
        for (i in 0 until problem.numFactors) violated.remove(i)
        cost = 0L
        for (v in boolBreakCount.indices) {
            boolBreakCount[v] = 0
            boolMakeCount[v] = 0
        }
        factors.forEachIndexed { id, factor ->
            factor.initialize(this, id)
            val deg = factor.violationDegree(this, id)
            factorDegree[id] = deg
            if (deg > 0) {
                violated.add(id)
                cost += deg
            }
        }
        // Initialize break/make vectors from factor deltas (payloads are current after initialize()).
        for (id in 0 until problem.numFactors) adjustBoolBreakMake(id, +1)
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    /**
     * Per-move one-way invariant index, set by the engine when enabled. After every applied move,
     * [apply] re-evaluates the affected definitional cone in topological order through the same
     * incremental primitives, so defined vars track their inputs and payload/break-make state stays
     * maintained. Null = no propagation.
     */
    var invariants: InvariantNetwork? = null
        set(value) {
            field = value
            moveSink.setInvariants(value)
        }

    /** Apply [move], updating cost and payloads incrementally; when [invariants] is set, the
     *  affected definitional cone is propagated afterwards through the same primitives. */
    fun apply(move: Move) {
        applyCore(move)
        val net = invariants ?: return
        propagateInvariants(net, move)
    }

    private fun applyCore(move: Move): Unit = when (move) {
        is Move.BoolFlip -> applyBoolFlip(move.varId)

        is Move.IntSet -> applyIntSet(move.varId, move.newValue)

        is Move.Compound -> {
            for (p in move.parts) applyCore(p)
        }
    }

    /** Re-evaluate the definitional cone the [move]'s touched vars feed, in topological order,
     *  writing changes through the incremental primitives (no full recompute). */
    private fun propagateInvariants(net: InvariantNetwork, move: Move) {
        val ints = IntArrayList(2)
        val bools = IntArrayList(2)
        fun collect(m: Move) {
            when (m) {
                is Move.BoolFlip -> bools.add(m.varId)
                is Move.IntSet -> ints.add(m.varId)
                is Move.Compound -> for (p in m.parts) collect(p)
            }
        }
        collect(move)
        val affected = net.affectedNodes(ints.toIntArray(), bools.toIntArray())
        for (idx in affected) {
            val n = net.node(idx)
            val v = n.eval(assignment, problem.intDomains)
            if (v == DefinitionalSweep.SweepNode.NO_WRITE) continue
            if (n.outIsBool) {
                if (assignment.boolValue(n.out) != (v != 0L)) applyBoolFlip(n.out)
            } else {
                if (assignment.intValue(n.out) != v.toInt()) applyIntSet(n.out, v.toInt())
            }
        }
    }

    /**
     * Number of currently-satisfied factors that would become violated if [move] were
     * applied. Used by strategies (WalkSAT-style noise/greedy, probSAT-style weighting) to
     * score repair candidates. Computed on demand by walking the var's occurrence list and
     * asking each factor for its `deltaIf*`.
     */
    fun breakScore(move: Move): Int = when (move) {
        is Move.BoolFlip -> boolBreakCount[move.varId]

        is Move.IntSet -> {
            var count = 0
            forEachIntFactorDelta(move.varId, move.newValue) { _, d -> if (d > 0) count++ }
            count
        }

        is Move.Compound -> evaluateCompound(move).breakScore
    }

    /** Count of currently-violated factors that would become satisfied if [move] were
     *  applied. Symmetric to [breakScore]. O(1) for `BoolFlip` via [boolMakeCount];
     *  O(arity) for `IntSet`. Used by probSat/SATLike-style strategies that want both. */
    fun makeScore(move: Move): Int = when (move) {
        is Move.BoolFlip -> boolMakeCount[move.varId]

        is Move.IntSet -> {
            var count = 0
            forEachIntFactorDelta(move.varId, move.newValue) { _, d -> if (d < 0) count++ }
            count
        }

        is Move.Compound -> 0 // Compound make rarely useful; skip the apply-revert dance.
    }

    /**
     * Break score fused with the per-move objective delta:
     *   `breakScore(move).toDouble() + shapingLambda * objectiveDelta(move)`
     * Reduces to `breakScore(move).toDouble()` when [shapingLambda] is zero, [objective] is null,
     * or the objective isn't a [LinearObjective], so non-shaping callers see identical behavior.
     *
     * Two fast paths are recognised: [LinearObjective] (O(1) coefficient lookup per move) and
     * [IncrementalObjective] (caller-supplied `deltaIfApplied`). Anything else returns `0.0`, since
     * a generic objective would need an apply-revert with full re-evaluation per candidate.
     */
    fun shapedBreakScore(move: Move): Double = breakScore(move).toDouble() + shapedObjectiveDelta(move)

    /**
     * Lambda-multiplied objective delta for shaping any per-move score: `shapingLambda *
     * objectiveDelta(move)`. Returns `0.0` when shaping is off (no objective, lambda = 0) or the
     * objective supports no incremental delta. Strategies composing objective-aware scores (DDFW's
     * weighted break, ProbSat's exponent input) add this to their base metric.
     */
    fun shapedObjectiveDelta(move: Move): Double {
        val obj = objective ?: return 0.0
        if (shapingLambda == 0.0) return 0.0
        val delta = when (obj) {
            is LinearObjective -> linearObjectiveDelta(move, obj)
            is IncrementalObjective -> obj.deltaIfApplied(assignment, move)
            else -> return 0.0
        }
        return shapingLambda * delta
    }

    /**
     * Raw per-move objective delta `evaluate(applyMove(current)) − evaluate(current)`, computed
     * against the current assignment WITHOUT committing the move. Returns `null` for objectives with
     * no incremental path, signalling the caller to fall back to `apply` + full [Objective.evaluate].
     *
     * Unlike [shapedObjectiveDelta], this is unscaled — the delta the optimize-side descent scores
     * candidates by, paired with [netDelta] for the feasibility/cost side.
     */
    fun objectiveDelta(obj: Objective, move: Move): Double? = when (obj) {
        is LinearObjective -> linearObjectiveDelta(move, obj)
        is IncrementalObjective -> obj.deltaIfApplied(assignment, move)
        else -> null
    }

    private fun linearObjectiveDelta(move: Move, obj: LinearObjective): Double = when (move) {
        is Move.BoolFlip -> {
            val v = move.varId
            if (v < obj.boolWeights.size) {
                val w = obj.boolWeights[v]
                (if (assignment.boolValue(v)) -w else w).toDouble()
            } else {
                0.0
            }
        }

        is Move.IntSet -> {
            val v = move.varId
            if (v < obj.intCoefficients.size) {
                (obj.intCoefficients[v] * (move.newValue - assignment.intValue(v))).toDouble()
            } else {
                0.0
            }
        }

        is Move.Compound -> {
            // Linear deltas are additive over parts evaluated against the initial assignment.
            var sum = 0.0
            for (p in move.parts) sum += linearObjectiveDelta(p, obj)
            sum
        }
    }

    /**
     * Synthesize a value-driven move that sets [intVar] to [newValue] and coordinately flips all
     * indicator bools of sibling **reified single-var equality** factors on the same int var. The
     * channeling pattern: a course-period model encodes `course(i) = p` via N parallel
     * `int_eq_reif(course(i), p, b_ip)` factors; a naive `IntSet` cascades into N indicator
     * violations the engine chases one flip at a time, so this rolls the update into one Compound.
     *
     * Returns a plain [Move.IntSet] when no sibling indicators need updating, else a [Move.Compound].
     * Sibling factors of other shapes (multi-var reified linear, LE/GE reified, etc.) are skipped —
     * only single-var EQ channeling has a deterministic "which indicator flips" answer.
     */
    fun synthesizeChannelingMove(intVar: Int, newValue: Int): Move {
        val cur = assignment.intValue(intVar)
        if (cur == newValue) return Move.IntSet(intVar, newValue)
        // Each sibling factor mentioning intVar contributes its own consistency-preserving update
        // (indicator flip / sum counter-shift) via Invariant.contributeChanneling; the sink folds them
        // into one Compound and pins claimed vars so two siblings can't clobber the same target.
        val sink = ChannelingSink(intVar, newValue)
        for (fid in problem.lsIntOccurrences[intVar]) {
            factors[fid].contributeChanneling(this, fid, intVar, cur, newValue, sink)
        }
        return sink.toMove()
    }

    /** Net cost change if [move] were applied, without mutating state. */
    fun netDelta(move: Move): Long = when (move) {
        is Move.BoolFlip -> {
            var sum = 0L
            forEachBoolFactorDelta(move.varId) { _, d -> sum += d }
            sum
        }

        is Move.IntSet -> {
            var sum = 0L
            forEachIntFactorDelta(move.varId, move.newValue) { _, d -> sum += d }
            sum
        }

        is Move.Compound -> evaluateCompound(move).netDelta
    }

    /**
     * Weighted net change in violated-factor count for [move]: `Σ factorWeights[f] · Δviolated[f]`.
     * Companion to [netDelta] for CBLS strategies that score against the per-factor weight vector.
     * Reads from [factorWeights], lazily-allocating if untouched — check [factorWeightsAllocated]
     * first to avoid forcing the allocation on a probe.
     */
    fun weightedNetDelta(move: Move): Double {
        val w = factorWeights
        return when (move) {
            is Move.BoolFlip -> {
                var sum = 0.0
                forEachBoolFactorDelta(move.varId) { fid, d ->
                    if (d != 0) sum += w[fid] * d
                }
                sum
            }

            is Move.IntSet -> {
                var sum = 0.0
                forEachIntFactorDelta(move.varId, move.newValue) { fid, d ->
                    if (d != 0) sum += w[fid] * d
                }
                sum
            }

            // Compound: exact, via the same apply-evaluate-revert raw netDelta uses, diffing
            // per-factor degrees against the weight vector. A per-part approximation against the
            // initial state would double-count intermediate breaks on strongly-coupled chains.
            is Move.Compound -> evaluateCompound(move).weightedNetDelta
        }
    }

    /** Shift [boolBreakCount]/[boolMakeCount] for every bool var of [factorId] by [sign]: a var whose
     *  flip would break the factor (`deltaIfBoolFlipped > 0`) moves the break count, one whose flip
     *  would make it (`< 0`) moves the make count. `sign = -1` retracts the factor's pre-move
     *  contribution, `+1` re-adds it post-move. Inline so the hot apply path stays allocation-free. */
    private inline fun adjustBoolBreakMake(factorId: Int, sign: Int) {
        val f = factors[factorId]
        for (w in problem.factors[factorId].boolVars) {
            val d = f.deltaIfBoolFlipped(this, factorId, w)
            if (d > 0) {
                boolBreakCount[w] += sign
            } else if (d < 0) {
                boolMakeCount[w] += sign
            }
        }
    }

    /**
     * Shared apply skeleton for the primitive moves: retract brute-force break/make over
     * [touchedFactors], [commit] the assignment change, refresh each factor's payload and violation,
     * then re-add brute-force / apply incremental break/make. Closes with the conf-change and
     * tabu/activity bookkeeping for [slot]. Inline so the per-move-type lambdas fold away and the two
     * callers keep their original allocation-free, single-pass shape.
     */
    private inline fun applyMove(
        touchedFactors: IntArray,
        slot: Int,
        maintainsIncrementally: (Invariant) -> Boolean,
        commit: () -> Unit,
        applyToFactor: (factorId: Int) -> Unit,
        updateIncremental: (factorId: Int) -> Unit,
        markMovedVar: () -> Unit,
    ) {
        // Phase 1: brute-force factors subtract pre-move break/make contributions; incremental factors
        // fold the whole delta into their own update in phase 3.
        for (factorId in touchedFactors) {
            if (!maintainsIncrementally(factors[factorId])) adjustBoolBreakMake(factorId, -1)
        }
        // Phase 2: commit the change and let each factor update its own payload. Re-read
        // violationDegree from the payload for the exact cost delta rather than the returned status
        // delta, which is sometimes approximate.
        commit()
        for (factorId in touchedFactors) {
            applyToFactor(factorId)
            updateViolation(factorId)
        }
        // Phase 3: incremental factors apply their O(1) / O(arity) update; brute-force factors add
        // post-move contributions.
        for (factorId in touchedFactors) {
            if (maintainsIncrementally(factors[factorId])) {
                updateIncremental(factorId)
            } else {
                adjustBoolBreakMake(factorId, +1)
            }
        }
        if (!probeActive) {
            markNeighborConfChange(touchedFactors)
            markMovedVar()
        }
        step++
        lastTouched[slot] = step
        if (touchCount[slot] < Int.MAX_VALUE) touchCount[slot]++
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    private fun applyBoolFlip(boolVar: Int) = applyMove(
        touchedFactors = problem.lsBoolOccurrences[boolVar],
        slot = boolVar,
        maintainsIncrementally = { it.maintainsBreakMakeIncrementally },
        commit = { assignment.flipBool(boolVar) },
        applyToFactor = { factors[it].applyBoolFlip(this, it, boolVar) },
        updateIncremental = { factors[it].updateBoolBreakMakeForFlip(this, it, boolVar) },
        markMovedVar = { boolConfChange[boolVar] = false },
    )

    private fun applyIntSet(intVar: Int, newValue: Int) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        applyMove(
            touchedFactors = problem.lsIntOccurrences[intVar],
            slot = problem.numBoolVars + intVar,
            maintainsIncrementally = { it.maintainsIntBreakMakeIncrementallyForIntSet },
            commit = { assignment.setInt(intVar, newValue) },
            applyToFactor = { factors[it].applyIntSet(this, it, intVar, old) },
            updateIncremental = { factors[it].updateIntBreakMakeForIntSet(this, it, intVar, old) },
            markMovedVar = { intConfChange[intVar] = false },
        )
    }

    private fun markNeighborConfChange(factorIds: IntArray) {
        for (factorId in factorIds) {
            for (v in problem.factors[factorId].boolVars) boolConfChange[v] = true
            for (v in problem.factors[factorId].intVars) intConfChange[v] = true
        }
    }

    /** Walk every factor touching bool var `v`, call its `deltaIfBoolFlipped`, and hand the
     *  (factorId, delta) pair to [action]. Inline so callers stay allocation-free. */
    internal inline fun forEachBoolFactorDelta(v: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in problem.lsBoolOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfBoolFlipped(this, factorId, v))
        }
    }

    /** Same as [forEachBoolFactorDelta] but for an `IntSet` move on int var `v` with
     *  target value [newValue]. */
    internal inline fun forEachIntFactorDelta(v: Int, newValue: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in problem.lsIntOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfIntSet(this, factorId, v, newValue))
        }
    }

    /** Pick a uniformly-random violated factor, ask it for repair-move suggestions, and return the
     *  raw list. `null` when no factor is violated or the chosen factor proposed no moves. The shared
     *  opener of every WalkSAT-family `Strategy.pickMove`. */
    fun proposeMovesFromRandomViolated(): List<Move>? {
        if (violated.isEmpty()) return null
        moveSink.clear()
        ViolatedRepairs.SINGLE.generate(this, moveSink)
        val raw = moveSink.list
        return if (raw.isEmpty()) null else raw
    }

    /** Greedy reservoir-sampled pick: the move with the smallest [shapedBreakScore]
     *  (ties broken uniformly at random). Used by WalkSat / ProbSat after candidate
     *  filtering. Returns `null` on an empty input. */
    fun greedyPickByShapedBreak(moves: List<Move>): Move? {
        if (moves.isEmpty()) return null
        var bestBreak = Double.POSITIVE_INFINITY
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = shapedBreakScore(m)
            if (brk < bestBreak) {
                bestBreak = brk
                bestCount = 1
                pick = m
            } else if (brk == bestBreak) {
                bestCount++
                if (rng.nextInt(bestCount) == 0) pick = m
            }
        }
        return pick
    }

    /** True iff [move]'s var was touched within the last [tenure] accepted moves. For
     *  a [Move.Compound], conservative: true if *any* part is tabu. */
    fun isTaboo(move: Move, tenure: Int): Boolean {
        if (tenure <= 0) return false
        return when (move) {
            is Move.BoolFlip -> isTabooSlot(move.varId, tenure)
            is Move.IntSet -> isTabooSlot(problem.numBoolVars + move.varId, tenure)
            is Move.Compound -> move.parts.any { isTaboo(it, tenure) }
        }
    }

    private fun isTabooSlot(slot: Int, tenure: Int): Boolean {
        val touched = lastTouched[slot]
        if (touched == 0L) return false
        return step - touched < tenure
    }

    /**
     * Apply [move] forward, observe (newly-violated, net-cost-diff), revert via inverse primitives,
     * and restore step / lastTouched / conf-change so the state is exactly as it was before.
     */
    private fun evaluateCompound(move: Move.Compound): CompoundEval {
        val oldStep = step
        val oldCost = cost
        val oldBestCost = bestCostSeen
        // Degree snapshot for the exact weighted delta. Skipped when no strategy touched the weights
        // (all 1.0 ⇒ weighted == raw netDelta).
        val degBefore = if (factorWeightsAllocated) {
            (degScratch ?: IntArray(factorDegree.size)).also { degScratch = it }.also { factorDegree.copyInto(it) }
        } else {
            null
        }
        // Inverse per part (BoolFlip self-inverts; IntSet needs current value).
        val inverses = ArrayList<Move>(move.parts.size)
        for (p in move.parts) inverses += inverseOf(p)
        // Save lastTouched / touchCount for each affected slot; the apply+revert dance overwrites
        // them, and a probe must not register as real cross-epoch activity (ALNS keys on touchCount).
        val touchedSlots = IntArray(move.parts.size) { slotOf(move.parts[it]) }
        val savedTouched = LongArray(touchedSlots.size) { lastTouched[touchedSlots[it]] }
        val savedTouchCount = IntArray(touchedSlots.size) { touchCount[touchedSlots[it]] }

        probeTouchedList.clear()
        probeActive = true
        breakProbeActive = true
        for (p in move.parts) apply(p)
        breakProbeActive = false

        var breakCount = 0
        for (i in 0 until probeTouchedList.size) {
            val fid = probeTouchedList[i]
            if (factorDegree[fid] > 0 && !probeWasViolated[fid]) breakCount++
            probeTouched[fid] = false
        }
        val netDelta: Long = cost - oldCost
        var weightedNetDelta = netDelta.toDouble()
        if (degBefore != null) {
            val w = factorWeights
            weightedNetDelta = 0.0
            for (i in degBefore.indices) {
                val d = factorDegree[i] - degBefore[i]
                if (d != 0) weightedNetDelta += w[i] * d
            }
        }

        for (i in inverses.indices.reversed()) apply(inverses[i])
        probeActive = false

        // Conf-change needs no restore — it was left untouched for the whole probe (see probeActive).
        step = oldStep
        for (i in touchedSlots.indices) lastTouched[touchedSlots[i]] = savedTouched[i]
        for (i in touchedSlots.indices) touchCount[touchedSlots[i]] = savedTouchCount[i]
        bestCostSeen = oldBestCost

        return CompoundEval(breakScore = breakCount, netDelta = netDelta, weightedNetDelta = weightedNetDelta)
    }

    private data class CompoundEval(val breakScore: Int, val netDelta: Long, val weightedNetDelta: Double)

    /** Re-read the factor's [Invariant.violationDegree] from its just-updated payload and reconcile the
     *  maintained [factorDegree], [cost] (`Σ degree`), and [violated]-set membership. Using the
     *  recomputed degree rather than `apply*`'s returned delta makes cost tracking exact even for
     *  globals whose returned status delta is approximate. */
    private fun updateViolation(factorId: Int) {
        val newDegree = factors[factorId].violationDegree(this, factorId)
        val delta = newDegree - factorDegree[factorId]
        if (delta == 0) return
        if (breakProbeActive && !probeTouched[factorId]) {
            probeTouched[factorId] = true
            probeWasViolated[factorId] = factorDegree[factorId] > 0
            probeTouchedList.add(factorId)
        }
        factorDegree[factorId] = newDegree
        cost += delta
        if (newDegree > 0) violated.add(factorId) else violated.remove(factorId)
    }

    /**
     * Reconcile a single factor after an *external* change to its violation semantics that left the
     * assignment — and thus the factor's payload — untouched: the objective-bound ratchet tightening its
     * shared bound between moves. Mirrors [applyMove]'s per-factor break/make retract → [updateViolation]
     * → re-add, so [cost], [factorDegree], [violated], and the break/make vectors stay exact without a
     * full [recompute] over every factor. The factor must maintain break/make brute-force (no flip
     * occurred, so there is no incremental update to drive) — true for the objective-bound factor.
     */
    internal fun reevaluateFactor(factorId: Int) {
        require(!factors[factorId].maintainsBreakMakeIncrementally) {
            "reevaluateFactor expects a brute-force break/make factor (no flip to drive an incremental update)"
        }
        adjustBoolBreakMake(factorId, -1)
        updateViolation(factorId)
        adjustBoolBreakMake(factorId, +1)
    }
}
