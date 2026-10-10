package com.eignex.klause.localsearch

import com.eignex.klause.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.randomValue
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

/** Initial weight for factors the model declared implied (redundant / symmetry-breaking). An
 *  order of magnitude below the 1.0 structural default: a model padded with hundreds of redundant
 *  rows then aggregates to roughly the weight of a handful of structural ones, so the early descent
 *  follows the real feasible region instead of chasing the implied bulk. */
internal const val IMPLIED_FACTOR_INITIAL_WEIGHT: Double = 0.1

// Real-set moves between scheduled re-summations of the rows over continuous columns.
private const val REAL_REFRESH_INTERVAL: Int = 4096

// Bits of the widest anchored draw: values within about a million of the anchor.
private const val ANCHOR_BITS: Int = 20

/**
 * Mutable state of an ongoing solve. Owns the [Assignment], the violated-factor set, the
 * per-factor scratch arrays ([intPayload], [refPayload]), and the aggregated hard cost.
 */
class LocalSearchState(
    /** The model being searched. */
    val model: LocalSearchModel,
    /** Search RNG. */
    val rng: Random,
    /** Variables pinned for this search. */
    var assumptions: Assumptions = Assumptions.None,
    /** Local-search projection for this state. */
    val projection: LocalSearchProblem = LocalSearchProblem(model.problem, model.domains),
) {
    /** A search over the finite model [problem]. */
    constructor(
        problem: BakedProblem,
        rng: Random,
        assumptions: Assumptions = Assumptions.None,
    ) : this(LocalSearchModel.of(problem), rng, assumptions)

    /** The problem being searched. */
    val problem: Problem get() = model.problem

    /** The stable domains this search moves over — read by invariants for a variable's bounds. The
     *  model's own, aliased rather than copied: local search reads these and never narrows them. */
    val rootDomains: Array<IntDomain> = model.domains

    /** The current variable assignment. */
    val assignment: Assignment = Assignment(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        numRealVars = problem.numRealVars,
    )

    /** Invariant ids currently violated (degree > 0). */
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)

    /** Per-invariant graded violation degree (0 = satisfied), the source of truth for both
     *  [violated]-set membership (`degree > 0`) and [cost] (`Σ factorDegree`). Maintained
     *  from exact post-move degrees after updating invariant payloads, and recomputed from
     *  [Invariant.violationDegree] at [recompute]. The graded sum gives CBLS a descent gradient on
     *  tight arithmetic/global constraints rather than a flat count of violated invariants. */
    val factorDegree: IntArray = IntArray(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)

    /** Per-factor `Long` scratch, the wide counterpart to [intPayload]. The weighted-sum
     *  family ([Linear], [ReifiedLinear], `PseudoBoolean`, `ReifiedPseudoBoolean`) keeps its
     *  running `Σ coeff·value` here so large coefficients / wide domains can't wrap a 32-bit
     *  accumulator and silently corrupt `isViolated` / `violationDegree`. Clauses pack their two
     *  watched literal indices into this slot. */
    val longPayload: LongArray = LongArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)

    /** Per-factor `Double` scratch for rows over continuous columns, which keep their running sum in
     *  floating point; empty for a model with no continuous column. */
    val doublePayload: DoubleArray = if (problem.numRealVars == 0) EmptyDoubleArray else DoubleArray(problem.numFactors)

    // Factors that read a continuous column; their floating-point sums drift and are refreshed from scratch.
    private val realFactors: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        if (problem.numRealVars == 0) EmptyIntArray else realFactorIds()
    }

    // Real-set moves applied since the real rows were last re-summed from scratch.
    private var realMovesSinceRefresh = 0

    /** Buffer that strategies push candidate moves into. */
    val moveSink: MoveSink = MoveSink(assumptions)

    internal val repairChainDegrees: RepairChainDegrees by lazy(LazyThreadSafetyMode.NONE) { RepairChainDegrees() }
    internal val repairChainFirsts: MoveSink by lazy(LazyThreadSafetyMode.NONE) { MoveSink() }
    internal val repairChainProposals: MoveSink by lazy(LazyThreadSafetyMode.NONE) { MoveSink() }

    // Clause degrees are binary, so their maintained break/make counts sum the exact raw delta.
    internal val repairChainClauseOnly: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        problem.factors.all { it is Clause }
    }

    /** The problem's invariants, aliased so the hot LS loops read `factors` directly. */
    val factors: Array<out Invariant> = projection.invariants

    /** DDFW-style per-invariant dynamic weights and their class-normalised baseline. */
    val weights: FactorWeightBook = FactorWeightBook(problem)

    /** Optimize-phase objective view: the injected objective, shaping lambda, and objective-hot-spot
     *  int-var bias. */
    val shaping: ObjectiveShaping = ObjectiveShaping()

    /** Tabu / activity bookkeeping: the accepted-move clock, last-touched stamps, and touch counts. */
    val tabu: TabuBook = TabuBook(problem)

    /** Implicit-solving setup: elected globals, disjoint seed set, owner map, implication graph. */
    val seeding: ImplicitSeeding = ImplicitSeeding(problem, projection)

    /** Implicit-solving feasible init: seed every [ImplicitSeeding.implicitSeedFactors] global into a
     *  satisfying configuration (skipping vars frozen by [assumptions]). Caller is responsible for the
     *  subsequent [recompute]. */
    fun seedImplicitFeasible() = seeding.seedImplicitFeasible(this)

    /** Accepted-move step counter (the search clock); see [TabuBook.step]. */
    val step: Long get() = tabu.step

    // Walks that never query Boolean break/make scores avoid their initialization and maintenance cost.
    private val cachedBoolBreakCount: IntArray = IntArray(problem.numBoolVars)
    private val cachedBoolMakeCount: IntArray = IntArray(problem.numBoolVars)
    private var boolScoresInitialized = false
    internal val boolBreakCount: IntArray
        get() {
            initializeBoolScores()
            return cachedBoolBreakCount
        }
    internal val boolMakeCount: IntArray
        get() {
            initializeBoolScores()
            return cachedBoolMakeCount
        }

    private fun initializeBoolScores() {
        if (boolScoresInitialized) return
        boolScoresInitialized = true
        addAllBreakMake()
    }

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

    /** Soft cap for `compressViolation`: residuals at or below it
     *  keep exact unit resolution, above it a log tail bounds how much one large-magnitude factor
     *  dominates the cost sum. Set by the engine from [LocalSearchParams.violationSoftCap] once per
     *  solve, before the first [recompute]; every graded factor shares this one cap. */
    var violationSoftCap: Int = DEFAULT_VIOLATION_SOFT_CAP
        internal set

    /** Configuration-Checking flag per Boolean variable. `true` means a neighboring variable has
     *  been touched since this var was last flipped (or since restart), so CCASat-style strategies
     *  treat it as eligible to re-flip; `false` means re-flipping it would be a no-progress cycle. */
    val boolConfChange: BooleanArray = BooleanArray(problem.numBoolVars) { true }

    /** Configuration-Checking flag per integer variable. See [boolConfChange]. */
    val intConfChange: BooleanArray = BooleanArray(problem.numIntVars) { true }

    // Which factors make their variables each other's neighbours for configuration checking: the model's own. The
    // objective-bound overlay spans every objective variable, so marking through it would make every variable a
    // neighbour of every other, which is no configuration checking at all, and cost a pass over the objective on
    // every flip.
    private val confNeighbours = BooleanArray(problem.factors.size) { problem.factors[it] !is ObjectiveBoundFactor }

    // A weighted probe saves only degrees that change, avoiding a model-wide copy and scan per candidate.
    private var degScratch: IntArray? = null

    // Coordinates include definition outputs so a NO_WRITE inverse cannot strand a probe's output.
    private val probeSlots = IntArrayList()
    private val probeSlotSet = IntHashSet()
    private var savedValuesScratch: LongArray = EmptyLongArray

    // Break-count probe scratch. While breakProbeActive, updateViolation records each factor whose
    // degree changes during a probe's forward apply, snapshotting its pre-probe violated status on
    // first touch. The break count is then a scan of only the touched factors: a factor can flip
    // into violation only if its degree changed.
    private var breakProbeActive = false
    private val probeTouched: BooleanArray = BooleanArray(problem.numFactors)
    private val probeWasViolated: BooleanArray = BooleanArray(problem.numFactors)
    private val probeTouchedList: IntArrayList = IntArrayList()

    // Set for the whole apply+revert span of a move probe. While active, applyBoolFlip /
    // applyIntSet skip configuration-change maintenance: a probe restores its start assignment, so
    // any conf-change marks would have to be reverted anyway.
    private var probeActive = false
    private var activityTracking = true

    // Repair keeps payloads and scores current, but its transient activity epoch is discarded.
    internal fun repairInitialization(repair: () -> Unit) {
        val wasTracking = activityTracking
        activityTracking = false
        try {
            repair()
        } finally {
            activityTracking = wasTracking
            resetStepCounters()
        }
    }

    /** Reset to a fresh random assignment and reinitialise all factors. */
    fun restart() {
        randomizeAssignment()
        recompute()
    }

    internal fun randomizeAssignment() {
        assignment.randomize(rng, rootDomains)
        if (model.anchoredSampling) {
            for (v in rootDomains.indices) assignment.setInt(v, anchoredValue(rootDomains[v], rootDomains[v].clamp(0L)))
        }
        // Overwrite the assumed slots so the assignment starts consistent with the caller's pins.
        assumptions.forEachBool { id, value ->
            if (assignment.boolValue(id) != value) assignment.flipBool(id)
        }
        assumptions.forEachInt { id, value -> assignment.setInt(id, value) }
        for (r in 0 until problem.numRealVars) assignment.setReal(r, startingReal(r))
        resetStepCounters()
    }

    /** Set the continuous columns to [sample]'s values when it carries one per column, else to their starting
     *  values. */
    internal fun seedReals(sample: Sample) {
        val carried = sample.numRealVars == problem.numRealVars
        for (r in 0 until problem.numRealVars) {
            assignment.setReal(r, if (carried) sample.approximateRealValue(r) else startingReal(r))
        }
    }

    // The value of real column [r] nearest zero within its bounds.
    private fun startingReal(r: Int): Double = 0.0.coerceIn(problem.realLower[r], problem.realUpper[r])

    private fun realFactorIds(): IntArray {
        val ids = IntArrayList()
        for (fid in 0 until problem.numFactors) {
            if (factors[fid] !== NoInvariant && problem.factors[fid].variables.reals.isNotEmpty()) ids.add(fid)
        }
        return ids.toIntArray()
    }

    /**
     * Re-sum every row over a continuous column from scratch and reconcile its degree. Their sums are kept
     * incrementally in floating point, so each move adds rounding; a scheduled refresh bounds that drift, and a
     * refresh before a candidate is reported keeps a row the drift had pushed inside its tolerance from reading
     * as satisfied.
     */
    fun refreshRealRows() {
        realMovesSinceRefresh = 0
        for (fid in realFactors) {
            adjustBoolBreakMake(fid, -1)
            factors[fid].initialize(this, fid)
            updateViolation(fid)
            adjustBoolBreakMake(fid, +1)
        }
    }

    /** A random value of int var [v]'s domain: uniform, or near its current value under
     *  [LocalSearchModel.anchoredSampling]. */
    fun randomIntValue(v: Int): Long {
        val d = rootDomains[v]
        return if (model.anchoredSampling) anchoredValue(d, assignment.intValue(v)) else d.randomValue(rng)
    }

    // A value at a log-uniform distance below 2^ANCHOR_BITS from [near], so small steps and long jumps are both
    // drawn while the search starts and moves near the values it already holds.
    private fun anchoredValue(d: IntDomain, near: Long): Long {
        val magnitude = rng.nextLong(1L shl rng.nextInt(ANCHOR_BITS + 1))
        val target = if (rng.nextBoolean()) near + magnitude else near - magnitude
        return d.clamp(target)
    }

    /** Clear tabu / CCA bookkeeping without touching the assignment. Used by
     *  optimization-side warm-up passes (e.g. greedy-repair) that mutate the assignment
     *  via [apply] but should leave the LS engine a fresh tabu epoch afterwards. */
    fun resetStepCounters() {
        tabu.reset()
        for (i in boolConfChange.indices) boolConfChange[i] = true
        for (i in intConfChange.indices) intConfChange[i] = true
    }

    /** Recompute cost and per-factor degrees from scratch. */
    fun recompute() {
        clearViolationState()
        initializeFactors()
        finishRecompute()
    }

    internal fun finishRecompute() {
        // Initialize break/make vectors from factor deltas (payloads are current after initialize()).
        if (boolScoresInitialized) addAllBreakMake()
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    // The passes of [recompute] are separate methods: each loops over every factor or variable, and one body
    // holding them all is compiled again on-stack for each loop it enters.
    internal fun clearViolationState() {
        for (i in 0 until problem.numFactors) violated.remove(i)
        cost = 0L
        for (v in cachedBoolBreakCount.indices) {
            cachedBoolBreakCount[v] = 0
            cachedBoolMakeCount[v] = 0
        }
    }

    internal fun initializeFactors(from: Int = 0, end: Int = problem.numFactors) {
        for (id in from until end) {
            val factor = factors[id]
            factor.initialize(this, id)
            val deg = factor.violationDegree(this, id)
            factorDegree[id] = deg
            if (deg > 0) {
                violated.add(id)
                cost += deg
            }
        }
    }

    private fun addAllBreakMake() {
        for (id in 0 until problem.numFactors) adjustBoolBreakMake(id, +1)
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

        is Move.RealSet -> applyRealSet(move.varId, move.newValue)

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
                is Move.RealSet -> {}
                is Move.Compound -> for (p in m.parts) collect(p)
            }
        }
        collect(move)
        val affected = net.affectedNodes(ints.toIntArray(), bools.toIntArray())
        for (idx in affected) {
            val n = net.node(idx)
            val v = n.eval(assignment, rootDomains)
            if (v == DefinitionalSweep.SweepNode.NO_WRITE) continue
            if (n.outIsBool) {
                if (assignment.boolValue(n.out) != (v != 0L)) applyBoolFlip(n.out)
            } else {
                if (assignment.intValue(n.out) != v) applyIntSet(n.out, v)
            }
        }
    }

    /**
     * Count of factors whose violation degree increases under a primitive [move]. Compound moves
     * count factors becoming violated. Definition inputs use a reversible propagated probe;
     * other primitives read occurrence deltas or maintained Boolean scores.
     */
    fun breakScore(move: Move): Int {
        if (feedsDefinitions(move)) return evaluateMove(move).breakScore
        return when (move) {
            is Move.BoolFlip -> boolBreakCount[move.varId]

            is Move.IntSet -> {
                var count = 0
                forEachIntFactorDelta(move.varId, move.newValue) { _, d -> if (d > 0) count++ }
                count
            }

            is Move.RealSet -> {
                var count = 0
                forEachRealFactorDelta(move.varId, move.newValue) { _, d -> if (d > 0) count++ }
                count
            }

            is Move.Compound -> evaluateMove(move).breakScore
        }
    }

    /** Count of factors whose violation degree decreases under a primitive [move]. Definition
     *  inputs use a reversible propagated probe; other primitives use occurrence deltas or
     *  maintained Boolean scores. Compound moves return zero. */
    fun makeScore(move: Move): Int {
        if (move !is Move.Compound && feedsDefinitions(move)) return evaluateMove(move).makeScore
        return when (move) {
            is Move.BoolFlip -> boolMakeCount[move.varId]

            is Move.IntSet -> {
                var count = 0
                forEachIntFactorDelta(move.varId, move.newValue) { _, d -> if (d < 0) count++ }
                count
            }

            is Move.RealSet -> {
                var count = 0
                forEachRealFactorDelta(move.varId, move.newValue) { _, d -> if (d < 0) count++ }
                count
            }

            is Move.Compound -> 0 // Compound make rarely useful; skip the apply-revert dance.
        }
    }

    /**
     * Break score fused with the per-move objective delta:
     *   `breakScore(move).toDouble() + shapingLambda * objectiveDelta(move)`
     * Reduces to `breakScore(move).toDouble()` when [ObjectiveShaping.shapingLambda] is zero,
     * [ObjectiveShaping.objective] is null, or the objective supports no incremental delta, so non-shaping
     * callers see identical behavior.
     *
     * Moves feeding definitions use a reversible probe. Other moves use coefficient lookup for
     * [LinearObjective] or the caller-supplied [IncrementalObjective.deltaIfApplied]; repeated
     * compound coordinates use a probe for their final linear delta. Other objective types
     * contribute `0.0`.
     */
    fun shapedBreakScore(move: Move): Double = breakScore(move).toDouble() + shapedObjectiveDelta(move)

    /**
     * Lambda-multiplied objective delta for shaping any per-move score: `shapingLambda *
     * objectiveDelta(move)`. Returns `0.0` when shaping is off (no objective, lambda = 0) or the
     * objective supports no incremental delta. Strategies composing objective-aware scores (DDFW's
     * weighted break, ProbSat's exponent input) add this to their base metric.
     */
    fun shapedObjectiveDelta(move: Move): Double {
        val obj = shaping.objective ?: return 0.0
        val lambda = shaping.shapingLambda
        if (lambda == 0.0 || (obj !is LinearObjective && obj !is IncrementalObjective)) return 0.0
        return lambda * checkNotNull(objectiveDelta(obj, move))
    }

    /**
     * Raw per-move objective delta `evaluate(applyMove(current)) − evaluate(current)`, computed
     * against the current assignment without committing the move. Definition inputs use a
     * reversible probe; other moves return `null` for objectives with no incremental path,
     * signalling the caller to fall back to `apply` + full [Objective.evaluate].
     *
     * Unlike [shapedObjectiveDelta], this is unscaled — the delta the optimize-side descent scores
     * candidates by, paired with [netDelta] for the feasibility/cost side.
     */
    fun objectiveDelta(obj: Objective, move: Move): Double? {
        if (feedsDefinitions(move)) return evaluateMove(move, obj).objectiveDelta
        return when (obj) {
            is LinearObjective -> linearObjectiveDelta(move, obj)
            is IncrementalObjective -> obj.deltaIfApplied(assignment, move)
            else -> null
        }
    }

    private fun linearObjectiveDelta(move: Move, obj: LinearObjective): Double {
        return when (move) {
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

            is Move.RealSet -> {
                val v = move.varId
                if (v < obj.realCoefficients.size) {
                    obj.realCoefficients[v] * (
                        move.newValue - assignment.realValue(
                            v,
                        )
                        )
                } else {
                    0.0
                }
            }

            is Move.Compound -> {
                // Distinct-coordinate deltas are additive; repeated writes need their final combined value.
                probeSlotSet.clear()
                var sum = 0.0
                for (p in move.parts) {
                    if (!probeSlotSet.add(slotOf(p))) return evaluateMove(move, obj).objectiveDelta
                    sum += linearObjectiveDelta(p, obj)
                }
                sum
            }
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
    fun synthesizeChannelingMove(intVar: Int, newValue: Long): Move {
        val cur = assignment.intValue(intVar)
        if (cur == newValue) return Move.IntSet(intVar, newValue)
        // Each sibling factor mentioning intVar contributes its own consistency-preserving update
        // (indicator flip / sum counter-shift) via Invariant.contributeChanneling; the sink folds them
        // into one Compound and pins claimed vars so two siblings can't clobber the same target.
        val sink = ChannelingSink(intVar, newValue)
        for (fid in projection.intOccurrences[intVar]) {
            factors[fid].contributeChanneling(this, fid, intVar, cur, newValue, sink)
        }
        return sink.toMove()
    }

    /** Net cost change if [move] were applied, without mutating state. */
    fun netDelta(move: Move): Long {
        if (feedsDefinitions(move)) return evaluateMove(move).netDelta
        return when (move) {
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

            is Move.RealSet -> {
                var sum = 0L
                forEachRealFactorDelta(move.varId, move.newValue) { _, d -> sum += d }
                sum
            }

            is Move.Compound -> evaluateMove(move).netDelta
        }
    }

    /**
     * Weighted net change in violated-factor count for [move]: `Σ factorWeights[f] · Δviolated[f]`.
     * Companion to [netDelta] for CBLS strategies that score against the per-factor weight vector.
     * Reads from [FactorWeightBook.factorWeights], lazily-allocating if untouched — check
     * [FactorWeightBook.allocated] first to avoid forcing the allocation on a probe.
     */
    fun weightedNetDelta(move: Move): Double {
        val w = weights.factorWeights
        if (feedsDefinitions(move)) return evaluateMove(move).weightedNetDelta
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

            is Move.RealSet -> {
                var sum = 0.0
                forEachRealFactorDelta(move.varId, move.newValue) { fid, d ->
                    if (d != 0) sum += w[fid] * d
                }
                sum
            }

            // Compound: exact, via the same apply-evaluate-revert raw netDelta uses, diffing
            // per-factor degrees against the weight vector. A per-part approximation against the
            // initial state would double-count intermediate breaks on strongly-coupled chains.
            is Move.Compound -> evaluateMove(move).weightedNetDelta
        }
    }

    /** Shift [boolBreakCount]/[boolMakeCount] for every bool var of [factorId] by [sign]: a var whose
     *  flip would break the factor (`deltaIfBoolFlipped > 0`) moves the break count, one whose flip
     *  would make it (`< 0`) moves the make count. `sign = -1` retracts the factor's pre-move
     *  contribution, `+1` re-adds it post-move. Inline so the hot apply path stays allocation-free. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun adjustBoolBreakMake(factorId: Int, sign: Int) {
        if (!boolScoresInitialized) return
        val f = factors[factorId]
        for (w in problem.factors[factorId].boolVars) {
            val d = f.deltaIfBoolFlipped(this, factorId, w)
            if (d > 0) {
                cachedBoolBreakCount[w] += sign
            } else if (d < 0) {
                cachedBoolMakeCount[w] += sign
            }
        }
    }

    /**
     * Shared apply skeleton for the primitive moves: [retract] brute-force break/make over
     * [touchedFactors], [commit] the assignment change, [refresh] each factor's payload and violation,
     * then [settle] the break/make vectors. Closes with the conf-change and tabu/activity bookkeeping
     * for [slot]. Each phase is a per-move-type method holding one loop, so C2 compiles them apart
     * instead of recompiling the whole move on-stack for each loop it enters.
     */
    @Suppress("LongParameterList")
    private inline fun applyMove(
        touchedFactors: IntArray,
        slot: Int,
        retract: () -> Unit,
        commit: () -> Unit,
        refresh: () -> Unit,
        settle: () -> Unit,
        markMovedVar: () -> Unit,
    ) {
        retract()
        commit()
        refresh()
        settle()
        if (activityTracking) {
            if (!probeActive) {
                markNeighborConfChange(touchedFactors)
                markMovedVar()
            }
            tabu.step++
            tabu.lastTouched[slot] = tabu.step
            if (tabu.touchCount[slot] < Int.MAX_VALUE) tabu.touchCount[slot]++
        }
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    // Brute-force factors subtract their pre-move break/make contributions; incremental factors fold the whole
    // delta into their own update once the move is committed.
    private inline fun retractBruteForce(touchedFactors: IntArray, maintainsIncrementally: (Invariant) -> Boolean) {
        if (!boolScoresInitialized) return
        for (factorId in touchedFactors) {
            if (!maintainsIncrementally(factors[factorId])) adjustBoolBreakMake(factorId, -1)
        }
    }

    // Each factor updates its own payload and supplies an exact degree; apply* status deltas are not cost inputs.
    private inline fun refreshFactors(touchedFactors: IntArray, applyToFactor: (factorId: Int) -> Int) {
        for (factorId in touchedFactors) {
            updateViolation(factorId, applyToFactor(factorId))
        }
    }

    // Incremental factors apply their O(1) / O(arity) update; brute-force factors add post-move contributions.
    private inline fun settleBreakMake(
        touchedFactors: IntArray,
        maintainsIncrementally: (Invariant) -> Boolean,
        updateIncremental: (factorId: Int) -> Unit,
    ) {
        if (!boolScoresInitialized) return
        for (factorId in touchedFactors) {
            if (maintainsIncrementally(factors[factorId])) {
                updateIncremental(factorId)
            } else {
                adjustBoolBreakMake(factorId, +1)
            }
        }
    }

    private fun applyBoolFlip(boolVar: Int) {
        val touched = projection.boolOccurrences[boolVar]
        applyMove(
            touchedFactors = touched,
            slot = boolVar,
            retract = { retractForBoolFlip(touched) },
            commit = { assignment.flipBool(boolVar) },
            refresh = { refreshForBoolFlip(touched, boolVar) },
            settle = { settleForBoolFlip(touched, boolVar) },
            markMovedVar = { boolConfChange[boolVar] = false },
        )
    }

    private fun retractForBoolFlip(touched: IntArray) =
        retractBruteForce(touched) { it.maintainsBreakMakeIncrementally }

    private fun refreshForBoolFlip(touched: IntArray, boolVar: Int) = refreshFactors(touched) {
        val factor = factors[it]
        if (factor is DegreeUpdatingInvariant) {
            factor.applyBoolFlipDegree(this, it, boolVar)
        } else {
            factor.applyBoolFlip(this, it, boolVar)
            factor.violationDegree(this, it)
        }
    }

    private fun settleForBoolFlip(touched: IntArray, boolVar: Int) = settleBreakMake(
        touched,
        { it.maintainsBreakMakeIncrementally },
        { factors[it].updateBoolBreakMakeForFlip(this, it, boolVar) },
    )

    private fun applyIntSet(intVar: Int, newValue: Long) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        val touched = projection.intOccurrences[intVar]
        applyMove(
            touchedFactors = touched,
            slot = problem.numBoolVars + intVar,
            retract = { retractForIntSet(touched) },
            commit = { assignment.setInt(intVar, newValue) },
            refresh = { refreshForIntSet(touched, intVar, old) },
            settle = { settleForIntSet(touched, intVar, old) },
            markMovedVar = { intConfChange[intVar] = false },
        )
    }

    private fun retractForIntSet(touched: IntArray) =
        retractBruteForce(touched) { it.maintainsIntBreakMakeIncrementallyForIntSet }

    private fun refreshForIntSet(touched: IntArray, intVar: Int, old: Long) = refreshFactors(touched) {
        val factor = factors[it]
        if (factor is DegreeUpdatingInvariant) {
            factor.applyIntSetDegree(this, it, intVar, old)
        } else {
            factor.applyIntSet(this, it, intVar, old)
            factor.violationDegree(this, it)
        }
    }

    private fun settleForIntSet(touched: IntArray, intVar: Int, old: Long) = settleBreakMake(
        touched,
        { it.maintainsIntBreakMakeIncrementallyForIntSet },
        { factors[it].updateIntBreakMakeForIntSet(this, it, intVar, old) },
    )

    private fun applyRealSet(realVar: Int, newValue: Double) {
        val old = assignment.realValue(realVar)
        if (old.toRawBits() == newValue.toRawBits()) return
        val touched = projection.realOccurrences[realVar]
        applyMove(
            touchedFactors = touched,
            slot = problem.numBoolVars + problem.numIntVars + realVar,
            retract = { retractBruteForce(touched) { false } },
            commit = { assignment.setReal(realVar, newValue) },
            refresh = {
                refreshFactors(touched) {
                    factors[it].applyRealSet(this, it, realVar, old)
                    factors[it].violationDegree(this, it)
                }
            },
            settle = { settleBreakMake(touched, { false }, {}) },
            markMovedVar = {},
        )
        if (++realMovesSinceRefresh >= REAL_REFRESH_INTERVAL && !probeActive) refreshRealRows()
    }

    private fun markNeighborConfChange(factorIds: IntArray) {
        for (factorId in factorIds) {
            if (!confNeighbours[factorId]) continue
            for (v in problem.factors[factorId].boolVars) boolConfChange[v] = true
            for (v in problem.factors[factorId].intVars) intConfChange[v] = true
        }
    }

    /** Walk every factor touching bool var `v`, call its `deltaIfBoolFlipped`, and hand the
     *  (factorId, delta) pair to [action]. Inline so callers stay allocation-free. */
    internal inline fun forEachBoolFactorDelta(v: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in projection.boolOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfBoolFlipped(this, factorId, v))
        }
    }

    /** Same as [forEachBoolFactorDelta] but for an `IntSet` move on int var `v` with
     *  target value [newValue]. */
    internal inline fun forEachIntFactorDelta(v: Int, newValue: Long, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in projection.intOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfIntSet(this, factorId, v, newValue))
        }
    }

    /** Same as [forEachBoolFactorDelta] but for a `RealSet` move on real var `v` with target [newValue]. */
    internal inline fun forEachRealFactorDelta(v: Int, newValue: Double, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in projection.realOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfRealSet(this, factorId, v, newValue))
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

    private fun feedsDefinitions(move: Move): Boolean {
        val net = invariants ?: return false
        return when (move) {
            is Move.BoolFlip -> net.readsBool(move.varId)
            is Move.IntSet -> net.readsInt(move.varId)
            is Move.RealSet -> false
            is Move.Compound -> move.parts.any { feedsDefinitions(it) }
        }
    }

    private fun saveProbeCoordinates(move: Move) {
        probeSlots.clear()
        probeSlotSet.clear()
        fun saveSlot(slot: Int) {
            if (probeSlotSet.add(slot)) probeSlots.add(slot)
        }
        val ints = IntArrayList(2)
        val bools = IntArrayList(2)
        fun collect(part: Move) {
            when (part) {
                is Move.BoolFlip -> bools.add(part.varId)
                is Move.IntSet -> ints.add(part.varId)
                is Move.RealSet -> {}
                is Move.Compound -> {
                    for (p in part.parts) collect(p)
                    return
                }
            }
            saveSlot(slotOf(part))
        }
        collect(move)
        val net = invariants
        if (net != null) {
            for (idx in net.affectedNodes(ints.toIntArray(), bools.toIntArray())) {
                val node = net.node(idx)
                saveSlot(if (node.outIsBool) node.out else problem.numBoolVars + node.out)
            }
        }
        if (savedValuesScratch.size < probeSlots.size) savedValuesScratch = LongArray(probeSlots.size)
        for (i in 0 until probeSlots.size) savedValuesScratch[i] = probeValue(probeSlots[i])
    }

    private fun probeValue(slot: Int): Long = when {
        slot < problem.numBoolVars -> if (assignment.boolValue(slot)) 1L else 0L
        slot < problem.numBoolVars + problem.numIntVars -> assignment.intValue(slot - problem.numBoolVars)
        else -> assignment.realValue(slot - problem.numBoolVars - problem.numIntVars).toRawBits()
    }

    private fun restoreProbeCoordinates() {
        for (i in probeSlots.size - 1 downTo 0) {
            val slot = probeSlots[i]
            val value = savedValuesScratch[i]
            if (probeValue(slot) == value) continue
            when {
                slot < problem.numBoolVars -> applyBoolFlip(slot)
                slot < problem.numBoolVars + problem.numIntVars -> applyIntSet(slot - problem.numBoolVars, value)
                else -> applyRealSet(slot - problem.numBoolVars - problem.numIntVars, Double.fromBits(value))
            }
        }
    }

    // Use the committed move's single propagation pass and restore saved outputs without replaying definitions.
    private fun evaluateMove(move: Move, objective: Objective? = null): MoveEval {
        val oldCost = cost
        val oldBestCost = bestCostSeen
        val oldTracking = activityTracking
        val oldObjective = if (objective != null && objective !is LinearObjective) {
            objective.evaluate(assignment)
        } else {
            0.0
        }
        val degBefore = if (weights.allocated || move !is Move.Compound) {
            (degScratch ?: IntArray(factorDegree.size)).also { degScratch = it }
        } else {
            null
        }
        saveProbeCoordinates(move)
        probeTouchedList.clear()
        probeActive = true
        activityTracking = false
        breakProbeActive = true
        try {
            apply(move)
            breakProbeActive = false
            var breakCount = 0
            var makeCount = 0
            var newlyViolated = 0
            for (i in 0 until probeTouchedList.size) {
                val fid = probeTouchedList[i]
                val degree = factorDegree[fid]
                if (degree > 0 && !probeWasViolated[fid]) newlyViolated++
                if (degBefore != null) {
                    val before = degBefore[fid]
                    if (degree > before) breakCount++
                    if (degree < before) makeCount++
                }
            }
            val delta = cost - oldCost
            val weighted = if (weights.allocated) weightedDegreeDelta(checkNotNull(degBefore)) else delta.toDouble()
            val objectiveDelta = when (objective) {
                is LinearObjective -> probeLinearObjectiveDelta(objective)
                null -> 0.0
                else -> objective.evaluate(assignment) - oldObjective
            }
            val breaks = if (move is Move.Compound) newlyViolated else breakCount
            return MoveEval(breaks, makeCount, delta, weighted, objectiveDelta)
        } finally {
            breakProbeActive = false
            restoreProbeCoordinates()
            for (i in 0 until probeTouchedList.size) probeTouched[probeTouchedList[i]] = false
            probeActive = false
            activityTracking = oldTracking
            bestCostSeen = oldBestCost
        }
    }

    private fun probeLinearObjectiveDelta(objective: LinearObjective): Double {
        var delta = 0.0
        for (i in 0 until probeSlots.size) {
            val slot = probeSlots[i]
            val old = savedValuesScratch[i]
            if (slot < problem.numBoolVars) {
                val coefficient = objective.boolWeights.getOrElse(slot) { 0L }
                delta += (coefficient * (probeValue(slot) - old)).toDouble()
            } else if (slot < problem.numBoolVars + problem.numIntVars) {
                val coefficient = objective.intCoefficients.getOrElse(slot - problem.numBoolVars) { 0L }
                delta += (coefficient * (probeValue(slot) - old)).toDouble()
            } else {
                val real = slot - problem.numBoolVars - problem.numIntVars
                val coefficient = objective.realCoefficients.getOrElse(real) { 0.0 }
                delta += coefficient * (assignment.realValue(real) - Double.fromBits(old))
            }
        }
        return delta
    }

    private fun weightedDegreeDelta(degBefore: IntArray): Double {
        val w = weights.factorWeights
        var delta = 0.0
        // Keep factor-id order so floating-point accumulation matches a full degree scan.
        probeTouchedList.backingData.sort(0, probeTouchedList.size)
        for (p in 0 until probeTouchedList.size) {
            val i = probeTouchedList[p]
            val d = factorDegree[i] - degBefore[i]
            if (d != 0) delta += w[i] * d
        }
        return delta
    }

    private data class MoveEval(
        val breakScore: Int,
        val makeScore: Int,
        val netDelta: Long,
        val weightedNetDelta: Double,
        val objectiveDelta: Double,
    )

    // Reconcile exact degrees independently of apply status deltas; fused updates avoid a second degree read.
    private fun updateViolation(factorId: Int, newDegree: Int = factors[factorId].violationDegree(this, factorId)) {
        val oldDegree = factorDegree[factorId]
        val delta = newDegree - oldDegree
        if (delta == 0) return
        if (breakProbeActive && !probeTouched[factorId]) {
            probeTouched[factorId] = true
            probeWasViolated[factorId] = oldDegree > 0
            degScratch?.set(factorId, oldDegree)
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

    /**
     * True iff every int var holds a value of its root domain. Domain membership is not a cost term, so
     * a value in a hole reads as feasible at `cost == 0`; this is the check that keeps such an
     * assignment from being reported. O(numIntVars), so it runs where an incumbent is published, never
     * per move.
     */
    internal fun intValuesInDomain(): Boolean {
        for (v in rootDomains.indices) {
            if (assignment.intValue(v) !in rootDomains[v]) return false
        }
        return true
    }
}
