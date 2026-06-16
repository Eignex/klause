package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
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

    /** Factor ids currently violated (degree > 0). */
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)

    /** Per-factor graded violation degree (0 = satisfied), the source of truth for both
     *  [violated]-set membership (`degree > 0`) and [cost] (`Σ factorDegree`). Maintained
     *  incrementally from each factor's `deltaIf*`/`apply*` return value (Δdegree) and
     *  recomputed from [Factor.violationDegree] at [recompute]. Lets [cost] be a
     *  graded sum-of-degrees rather than a flat count of violated factors, giving CBLS a
     *  descent gradient on tight arithmetic/global constraints. */
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

    /** The problem's factors. Aliased here so the hot LS loops read `factors` directly;
     *  every [Factor] carries the local-search contract (with sound no-op defaults), so no
     *  cast or capability check is needed. */
    val factors: Array<Factor> = problem.factors

    /** Factor ids elected for implicit-solving structured neighbourhoods — structural globals
     *  whose [Factor.proposeStructuredMoves] preserves their own feasibility (see
     *  [Factor.providesImplicitNeighbourhood]). The engine draws these factors'
     *  feasibility-preserving moves even during infeasibility so they can clear violations in
     *  coupled constraints without ever breaking themselves, and seeds them feasible at search
     *  start. Built once on first access. */
    val electedImplicit: IntArray by lazy { electImplicitFactors() }

    private fun electImplicitFactors(): IntArray {
        val out = IntArrayList()
        for (id in 0 until problem.numFactors) {
            if (factors[id].providesImplicitNeighbourhood) out.add(id)
        }
        return IntArray(out.size) { out[it] }
    }

    /** Step counter incremented on every accepted move. Strategies use this together with
     *  [lastTouched] to enforce a tabu list. */
    var step: Long = 0L
        internal set

    /** Step at which each variable was last flipped or set. Index is the bool var id for
     *  Boolean vars (`[0, numBoolVars)`); int var ids are offset by `numBoolVars`.
     *  Reset to zero on [restart] — used only for tabu / CCA-window decisions within a
     *  single restart epoch. For cross-epoch activity tracking, see [touchCount]. */
    val lastTouched: LongArray = LongArray(problem.numBoolVars + problem.numIntVars)

    /** Cumulative count of moves applied to each variable. Same indexing as [lastTouched]
     *  (bool ids first, int ids offset by `numBoolVars`). Survives [restart] so it
     *  measures activity across the whole search run, not just the current restart epoch.
     *  Captured by [com.eignex.klause.solver.localsearch.WarmState] for ALNS's
     *  `activityBiased` destroy operator. */
    val touchCount: IntArray = IntArray(problem.numBoolVars + problem.numIntVars)

    /** Eagerly-maintained make/break vectors for `Move.BoolFlip`. Entry `boolBreakCount[v]`
     *  is the count of currently-satisfied factors that would become violated if `v` is
     *  flipped; `boolMakeCount[v]` is the symmetric count of currently-violated factors that
     *  would become satisfied. Both updated incrementally inside [applyBoolFlip] and
     *  [applyIntSet] for every var in the factor-neighbourhood of the move. Strategies that
     *  query break/make per pick (probSat, WalkSat, DDFW) read these in O(1).
     *
     *  Cost shifts from "first query post-flip pays O(occurrences × arity)" to "each flip
     *  pays O(Σ arity²) over its factor neighbourhood, queries are O(1)". The total work
     *  per (flip, query) round is comparable; the win is predictable latency — no cold-miss
     *  spike on the first query after a flip, and no boolean valid-flag bookkeeping. SAT-LS
     *  literature (YalSAT, NuWLS) maintains these vectors; the further O(1)-per-update
     *  refinement (numTrueLits per Clause + critical-literal tracking) is a follow-up. */
    internal val boolBreakCount: IntArray = IntArray(problem.numBoolVars)
    internal val boolMakeCount: IntArray = IntArray(problem.numBoolVars)

    /** Aggregated hard cost = `Σ factorDegree`, the graded total violation. `Long` because a
     *  single tight arithmetic factor can carry a residual near [Int.MAX_VALUE] and the sum
     *  across a large factor set would otherwise overflow. `cost == 0L` iff feasible. */
    var cost: Long = 0L
        internal set

    /** Lowest [cost] observed since this state was constructed. Updated at the end of
     *  every committed `apply(move)`; preserved across [restart] so the all-time minimum
     *  drives aspiration decisions even when individual restart epochs go uphill. Used
     *  by [com.eignex.klause.solver.localsearch.strategy.AspirationCriterion.OrImprovesBestEver]
     *  to admit tabu moves that would beat the historical low. */
    var bestCostSeen: Long = Long.MAX_VALUE
        internal set

    /** Objective injected by the engine during a `minimize` call. `null` outside
     *  `minimize` — strategies that consult [shapedBreakScore] gracefully fall back to
     *  the unshaped break score when this is null. */
    var objective: Objective? = null
        internal set

    /** Lambda coefficient extracted from `params.costShaping` for pre-feasibility shaping.
     *  Set by the engine when entering a `minimize` call; defaults to 0.0 (no shaping)
     *  outside a `minimize` call or under
     *  [com.eignex.klause.solver.localsearch.CostShaping.FeasibilityFirst]. */
    var shapingLambda: Double = 0.0
        internal set

    /** Soft cap for [com.eignex.klause.solver.factor.compressViolation]: residuals at or below it
     *  keep exact unit resolution, above it a log tail bounds how much one large-magnitude factor
     *  can dominate the cost sum. Set by the engine from [LocalSearchParams.violationSoftCap] once
     *  per solve, before the first [recompute]; every graded factor reads it so the whole cost
     *  model shares one cap. */
    var violationSoftCap: Int = DEFAULT_VIOLATION_SOFT_CAP
        internal set

    /** Per-factor weight, default 1.0. Not read by the engine itself — every factor
     *  contributes its [factorDegree] to [cost] regardless. Strategies that want to bias the search
     *  toward repairing persistently-violated factors (e.g. DDFW, SAPS) read and mutate
     *  this array between picks.
     *
     *  Lazily allocated on first access. The FocusedLs family (WalkSat / ProbSat / SA)
     *  never touches it and so pays no allocation cost; only CBLS (and any future weight-using
     *  strategy) triggers the `DoubleArray(numFactors)` allocation. [WarmState.captureFrom]
     *  probes [factorWeightsAllocated] before reading to avoid forcing the allocation just
     *  to capture all-1.0 defaults from sessions that ran a weight-blind strategy. */
    private var _factorWeights: DoubleArray? = null

    /** Seed [factorWeights] by per-class population so no constraint kind dominates the landscape by
     *  count. Set by the engine from [LocalSearchParams.normalizeWeightsByClass] once per solve,
     *  before the first weight access. */
    var normalizeWeightsByClass: Boolean = false
        internal set

    /** Per-factor dynamic weights for weighted-violation strategies. Factors the model declared
     *  implied (redundant / symmetry-breaking — [Problem.impliedFactorMask]) start at
     *  [IMPLIED_FACTOR_INITIAL_WEIGHT] rather than 1.0, so the bulk of those rows can't dominate
     *  the initial descent before the structural constraints are met. SAPS-style
     *  bumping still raises an implied factor's weight if it persistently blocks progress, so the
     *  lower seed biases the early landscape without making the constraint unenforceable.
     *
     *  When [normalizeWeightsByClass] is set, the remaining (non-implied) factors are additionally
     *  damped by class population — see [initialFactorWeights]. */
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
     *  [factorWeights] is first allocated and never mutated afterwards. SAPS-style smoothing pulls
     *  the live weights back toward this baseline rather than a flat constant, so the proactive
     *  per-class / implied seeding survives the reactive bumping instead of being washed out. */
    val baseFactorWeights: DoubleArray
        get() {
            _baseFactorWeights?.let { return it }
            factorWeights // forces allocation, which also assigns _baseFactorWeights
            return _baseFactorWeights ?: error("baseFactorWeights is assigned when factorWeights is allocated")
        }

    /** Build the initial per-factor weight vector. Non-implied factors start at 1.0, optionally
     *  class-normalised ([normalizeWeightsByClass]): an over-represented factor class — population
     *  above the mean over non-implied classes — is scaled so its aggregate weight is capped at that
     *  mean, never amplifying a smaller class above 1.0. Implied factors are pinned to
     *  [IMPLIED_FACTOR_INITIAL_WEIGHT] regardless, and are excluded from the class tally so a
     *  structural constraint isn't penalised for merely sharing a type with the implied bulk. */
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

    /** Configuration-Checking flag per Boolean variable. `true` means a neighboring
     *  variable has been touched since this var was last flipped (or since restart) — the
     *  var is "eligible to re-flip" by CCASat-style strategies. `false` means this var was
     *  the most recent flip in its neighborhood; flipping it again would be a no-progress
     *  cycle. */
    val boolConfChange: BooleanArray = BooleanArray(problem.numBoolVars) { true }

    /** Configuration-Checking flag per integer variable. See [boolConfChange]. */
    val intConfChange: BooleanArray = BooleanArray(problem.numIntVars) { true }

    // Degree scratch reused by [evaluateCompound] across calls so an apply+revert probe allocates
    // nothing on its array-copy path (the dominant LS allocation source). Lazily created because
    // compound moves are factor-specific; the state is per-worker, so no sharing/locking is needed.
    private var degScratch: IntArray? = null

    // Break-count probe scratch. While [breakProbeActive], [updateViolation] records each factor
    // whose degree changes during a probe's forward apply, snapshotting its pre-probe violated
    // status on first touch. The break count is then a scan of only the touched factors: a factor
    // can flip into violation only if its degree changed, so untouched factors contribute nothing
    // and the whole [violated] set need never be examined.
    private var breakProbeActive = false
    private val probeTouched: BooleanArray = BooleanArray(problem.numFactors)
    private val probeWasViolated: BooleanArray = BooleanArray(problem.numFactors)
    private val probeTouchedList: IntArrayList = IntArrayList()

    // Set for the whole apply+revert span of a compound probe. While active, [applyBoolFlip] and
    // [applyIntSet] skip configuration-change maintenance: a probe restores the assignment it
    // started from, so any conf-change marks it made would have to be reverted anyway. Suppressing
    // them lets the probe skip both the neighbor-marking scan and snapshotting the conf-change arrays.
    private var probeActive = false

    /** Reset to a fresh random assignment and reinitialise all factors. */
    fun restart() {
        assignment.randomize(rng, problem.intDomains)
        // Overwrite the assumed slots so the assignment starts feasible w.r.t. the caller's pins.
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
        // Initialize break/make vectors from factor deltas. After initialize() each factor's
        // payload is current; deltaIfBoolFlipped reads it.
        for (id in 0 until problem.numFactors) {
            val f = factors[id]
            for (w in f.boolVars) {
                val d = f.deltaIfBoolFlipped(this, id, w)
                if (d > 0) {
                    boolBreakCount[w]++
                } else if (d < 0) {
                    boolMakeCount[w]++
                }
            }
        }
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    /**
     * Per-move one-way invariant index (issue #153), set by the engine when enabled. After
     * every applied move, [apply] re-evaluates the affected definitional cone in topological
     * order through the same incremental primitives, so defined vars track their inputs and
     * payload/break-make state stays maintained. Null = no propagation (default behavior).
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
     * When [shapingLambda] is zero, [objective] is null (outside a `minimize` call), or
     * the objective isn't a [LinearObjective], this reduces exactly to
     * `breakScore(move).toDouble()` — so non-shaping callers see identical behavior.
     * Strategies that want pre-feasibility objective awareness call this in place of
     * [breakScore]; the engine populates the relevant fields on `minimize` and leaves
     * them at defaults otherwise.
     *
     * Two fast paths are recognised:
     *  - [LinearObjective]: O(1) coefficient lookup per Bool/IntSet move (existing path).
     *  - [IncrementalObjective]: caller-supplied `deltaIfApplied`. Lets piecewise-linear,
     *    abs-of-linear, max-of-linear, etc. objectives drive shaped descent at whatever
     *    cost they can manage.
     *
     * Anything else returns `0.0` — generic non-incremental objectives would require an
     * apply-revert with full re-evaluation per scored candidate, defeating the point of
     * a per-move score.
     */
    fun shapedBreakScore(move: Move): Double = breakScore(move).toDouble() + shapedObjectiveDelta(move)

    /**
     * Lambda-multiplied objective delta contribution for shaping any per-move score:
     * `shapingLambda * objectiveDelta(move)`. Returns `0.0` when shaping is off
     * (no objective, lambda = 0) or the objective doesn't support incremental deltas
     * (neither [LinearObjective] nor [IncrementalObjective]). Strategies that compose
     * objective-aware scores (DDFW's weighted break, ProbSat's exponent input) add this
     * on top of their base metric.
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
     * Raw per-move objective delta `evaluate(applyMove(current)) − evaluate(current)`,
     * computed against the current assignment WITHOUT committing the move — O(arity) for a
     * [LinearObjective], the objective's own cost for an [IncrementalObjective]. Returns
     * `null` for objectives with no incremental path, signalling the caller to fall back to
     * `apply` + full [Objective.evaluate].
     *
     * Distinct from [shapedObjectiveDelta], which multiplies by [shapingLambda] (zero on the
     * feasibility-gated descent path) for pre-feasibility shaping; this returns the unscaled
     * delta the optimize-side descent steps score candidates by, paired with [netDelta] for
     * the feasibility/cost side.
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
            // Linear deltas are additive over parts evaluated against the initial
            // assignment — same convention as the single-move delta.
            var sum = 0.0
            for (p in move.parts) sum += linearObjectiveDelta(p, obj)
            sum
        }
    }

    /**
     * Synthesize a value-driven move that sets [intVar] to [newValue] and coordinately
     * flips all indicator bools of sibling **reified single-var equality** factors on the
     * same int var. The classic channeling pattern: a course-period model encodes
     * `course[i] = p` via N parallel `int_eq_reif(course[i], p, b_ip)` factors, one per
     * possible period. A naive `IntSet(course[i], 7)` cascades into N indicator-violations
     * that the engine has to chase one bool-flip at a time; this helper rolls the
     * coordinated update into one atomic Compound.
     *
     * Returns the plain [Move.IntSet] when no sibling indicators need updating (no
     * channeling, or all indicators already consistent). Returns a [Move.Compound] when
     * at least one indicator flip is needed. Sibling factors of other shapes
     * (multi-var reified linear, LE/GE reified, etc.) are intentionally skipped — only
     * the single-var EQ channeling pattern has a deterministic "which indicator flips"
     * answer.
     */
    fun synthesizeChannelingMove(intVar: Int, newValue: Int): Move {
        val cur = assignment.intValue(intVar)
        if (cur == newValue) return Move.IntSet(intVar, newValue)
        val parts = ArrayList<Move>(4)
        // Pinned-target set: vars whose update we've already committed to so a sibling
        // factor doesn't try to override the choice. Without this two Linear EQs sharing
        // the same compensation target would both add IntSet for it and the second one
        // would clobber the first.
        val pinned = IntHashSet()
        pinned.add(intVar)
        parts += Move.IntSet(intVar, newValue)
        for (fid in problem.intOccurrences[intVar]) {
            val f = factors[fid]
            // Indicator channeling: single-var EQ reified-linear (the bool2int /
            // int_eq_reif pattern). Flip the aux bool iff the new value changes the truth
            // of `coeff·v == bound`.
            if (f is ReifiedLinear) {
                if (f.vars.size == 1 && f.op == LinearOp.EQ) {
                    val coeff = f.coeffs[0]
                    val auxVar = f.auxBoolVar
                    if (assumptions.isFrozenBool(auxVar)) continue
                    val shouldHold = coeff.toLong() * newValue == f.bound.toLong()
                    val auxCurrent = assignment.boolValue(auxVar)
                    if (auxCurrent != shouldHold) parts += Move.BoolFlip(auxVar)
                }
                continue
            }
            // Sum channeling: Linear EQ `Σ c[i]·x[i] = bound`. When v's value changes by
            // delta, the sum drifts by `c_v · delta` — pick another participant u in the
            // factor and shift u by the inverse amount to keep the equality balanced. The
            // classic case is `load[p] = Σ course_load[c] · x[p,c]` encoded as
            // `Σ c · x - load = 0`, so the var with coeff -1 (the "result") absorbs every
            // partial-sum change cleanly. We prefer compensation targets whose coefficient
            // divides the drift evenly so the new value lands on an integer.
            //
            // Only apply to *currently-satisfied* Linear EQs: a violated one is the very
            // constraint the caller is trying to repair via the IntSet — adding a
            // counter-shift would undo the repair. Side-effect preservation only.
            if (f is Linear &&
                f.op == LinearOp.EQ &&
                !violated.contains(fid)
            ) {
                propagateLinearEqShift(f, intVar, cur, newValue, parts, pinned)
            }
        }
        return if (parts.size == 1) parts[0] else Move.Compound(parts)
    }

    /** Helper for [synthesizeChannelingMove]: find a compensation target in a Linear EQ
     *  factor and append an IntSet that restores the sum invariant after [intVar] shifts
     *  from [oldV] to [newV]. Skips when no clean integer compensation exists, when the
     *  candidate target is frozen / pinned / would exit its domain. */
    private fun propagateLinearEqShift(
        f: Linear,
        intVar: Int,
        oldV: Int,
        newV: Int,
        parts: ArrayList<Move>,
        pinned: IntHashSet,
    ) {
        var coeffV = 0
        for (i in f.vars.indices) {
            if (f.vars[i] == intVar) {
                coeffV = f.coeffs[i]
                break
            }
        }
        if (coeffV == 0) return
        val drift = coeffV.toLong() * (newV - oldV)
        // Pick the lowest-|coeff| participant other than intVar to absorb the drift —
        // tied breaks toward coeff = ±1 since those guarantee integer landing.
        var bestIdx = -1
        var bestAbs = Int.MAX_VALUE
        for (i in f.vars.indices) {
            val u = f.vars[i]
            if (u == intVar || u in pinned) continue
            val cu = f.coeffs[i]
            if (cu == 0) continue
            val absC = if (cu < 0) -cu else cu
            if (absC < bestAbs && drift % cu == 0L) {
                bestAbs = absC
                bestIdx = i
            }
        }
        if (bestIdx < 0) return
        val u = f.vars[bestIdx]
        if (assumptions.isFrozenInt(u)) return
        val cu = f.coeffs[bestIdx]
        val uShift = -drift / cu // (uShift * cu) cancels the drift
        val curU = assignment.intValue(u)
        val newU = curU + uShift.toInt()
        if (newU == curU) return
        val dom = problem.intDomains[u]
        if (newU < dom.min || newU > dom.max) return
        parts += Move.IntSet(u, newU)
        pinned.add(u)
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
     * Companion to [netDelta] for CBLS strategies that score against the per-factor weight
     * vector instead of a flat violation count. Positive = more weighted violation after
     * the move; negative = less.
     *
     * Reads from [factorWeights], lazily-allocating if untouched — callers that want to
     * avoid forcing the allocation on every probe should check [factorWeightsAllocated]
     * first and fall back to [netDelta].
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

            // Compound: exact, via the same apply-evaluate-revert the raw netDelta uses,
            // diffing per-factor degrees against the weight vector. The old per-part
            // approximation (Σ weightedNetDelta(part) against the *initial* state) is
            // unusably biased for strongly-coupled chains — a directed dismantle chain whose
            // later parts repair the damage of earlier ones double-counts every intermediate
            // break, so truly improving escapes lost the score race to Δ0 primitives.
            is Move.Compound -> evaluateCompound(move).weightedNetDelta
        }
    }

    private fun applyBoolFlip(boolVar: Int) {
        val touchedFactors = problem.boolOccurrences[boolVar]
        // Phase 1: brute-force factors — subtract pre-flip break/make contributions.
        // Incremental factors handle the entire delta in updateBoolBreakMakeForFlip below.
        for (factorId in touchedFactors) {
            val f = factors[factorId]
            if (f.maintainsBreakMakeIncrementally) continue
            for (w in f.boolVars) {
                val d = f.deltaIfBoolFlipped(this, factorId, w)
                if (d > 0) {
                    boolBreakCount[w]--
                } else if (d < 0) {
                    boolMakeCount[w]--
                }
            }
        }
        // Phase 2: commit the flip and let each factor update its own payload.
        assignment.flipBool(boolVar)
        for (factorId in touchedFactors) {
            // applyBoolFlip maintains the factor's payload; we re-read violationDegree from
            // that payload for the exact cost delta rather than trusting the (sometimes
            // approximate) returned status delta.
            factors[factorId].applyBoolFlip(this, factorId, boolVar)
            updateViolation(factorId)
        }
        // Phase 3: brute-force factors — add post-flip contributions.
        // Incremental factors apply their O(1) / O(arity) update.
        for (factorId in touchedFactors) {
            val f = factors[factorId]
            if (f.maintainsBreakMakeIncrementally) {
                f.updateBoolBreakMakeForFlip(this, factorId, boolVar)
            } else {
                for (w in f.boolVars) {
                    val d = f.deltaIfBoolFlipped(this, factorId, w)
                    if (d > 0) {
                        boolBreakCount[w]++
                    } else if (d < 0) {
                        boolMakeCount[w]++
                    }
                }
            }
        }
        if (!probeActive) {
            markNeighborConfChange(touchedFactors)
            boolConfChange[boolVar] = false
        }
        step++
        lastTouched[boolVar] = step
        if (touchCount[boolVar] < Int.MAX_VALUE) touchCount[boolVar]++
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    private fun applyIntSet(intVar: Int, newValue: Int) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        val touchedFactors = problem.intOccurrences[intVar]
        // Phase 1: subtract bool break/make contributions for every bool var in every
        // touched factor — the int change shifts their per-var deltas. Incremental
        // factors skip this and apply a single diff in phase 3.
        for (factorId in touchedFactors) {
            val f = factors[factorId]
            if (f.maintainsIntBreakMakeIncrementallyForIntSet) continue
            for (w in f.boolVars) {
                val d = f.deltaIfBoolFlipped(this, factorId, w)
                if (d > 0) {
                    boolBreakCount[w]--
                } else if (d < 0) {
                    boolMakeCount[w]--
                }
            }
        }
        assignment.setInt(intVar, newValue)
        for (factorId in touchedFactors) {
            // See applyBoolFlip: re-read violationDegree from the maintained payload for the
            // exact graded cost delta instead of the returned status delta.
            factors[factorId].applyIntSet(this, factorId, intVar, old)
            updateViolation(factorId)
        }
        for (factorId in touchedFactors) {
            val f = factors[factorId]
            if (f.maintainsIntBreakMakeIncrementallyForIntSet) {
                f.updateIntBreakMakeForIntSet(this, factorId, intVar, old)
            } else {
                for (w in f.boolVars) {
                    val d = f.deltaIfBoolFlipped(this, factorId, w)
                    if (d > 0) {
                        boolBreakCount[w]++
                    } else if (d < 0) {
                        boolMakeCount[w]++
                    }
                }
            }
        }
        if (!probeActive) {
            markNeighborConfChange(touchedFactors)
            intConfChange[intVar] = false
        }
        step++
        val slot = problem.numBoolVars + intVar
        lastTouched[slot] = step
        if (touchCount[slot] < Int.MAX_VALUE) touchCount[slot]++
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    private fun markNeighborConfChange(factorIds: IntArray) {
        for (factorId in factorIds) {
            val f = factors[factorId]
            for (v in f.boolVars) boolConfChange[v] = true
            for (v in f.intVars) intConfChange[v] = true
        }
    }

    /** Walk every factor that touches bool var `v`, call its `deltaIfBoolFlipped`, and
     *  hand the (factorId, delta) pair to [action]. Inline so callers stay allocation-
     *  free. Shared by [breakScore], [netDelta], and DDFW's weighted-break score. */
    internal inline fun forEachBoolFactorDelta(v: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in problem.boolOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfBoolFlipped(this, factorId, v))
        }
    }

    /** Same as [forEachBoolFactorDelta] but for an `IntSet` move on int var `v` with
     *  target value [newValue]. */
    internal inline fun forEachIntFactorDelta(v: Int, newValue: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in problem.intOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfIntSet(this, factorId, v, newValue))
        }
    }

    /** Pick a uniformly-random violated factor, ask it for repair-move suggestions, and
     *  return the raw list. Returns `null` when no factor is violated or the violated
     *  factor proposed no moves. Every WalkSAT-family `Strategy.pickMove` starts the same
     *  way; this method is the shared opener. */
    fun proposeMovesFromRandomViolated(): List<Move>? {
        if (violated.isEmpty()) return null
        val factorId = violated.random(rng)
        moveSink.clear()
        factors[factorId].proposeRepairMoves(this, factorId, moveSink)
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
     * Apply [move] forward, observe (newly-violated, net-cost-diff), revert via inverse
     * primitives, and restore step / lastTouched / conf-change so the state is exactly
     * as it was before. Snapshot of `boolConfChange` and `intConfChange` is full arrays
     * — they're touched in the neighbourhood of every applied part and selective restore
     * would be more bookkeeping than it's worth for a Compound that's typically 2 parts.
     */
    private fun evaluateCompound(move: Move.Compound): CompoundEval {
        val oldStep = step
        val oldCost = cost
        val oldBestCost = bestCostSeen
        // Degree snapshot for the exact weighted delta. Skipped when no strategy has touched the
        // weights (weights all 1.0 ⇒ weighted == raw netDelta).
        val degBefore = if (factorWeightsAllocated) {
            (degScratch ?: IntArray(factorDegree.size)).also { degScratch = it }.also { factorDegree.copyInto(it) }
        } else {
            null
        }
        // Capture inverse per part (BoolFlip self-inverts; IntSet needs current value).
        val inverses = ArrayList<Move>(move.parts.size)
        for (p in move.parts) inverses += inverseOf(p)
        // Capture lastTouched for each affected slot — these will all get overwritten by
        // the apply+revert dance.
        val touchedSlots = IntArray(move.parts.size) { slotOf(move.parts[it]) }
        val savedTouched = LongArray(touchedSlots.size) { lastTouched[touchedSlots[it]] }
        // touchCount is cross-epoch activity (WarmState.activityTouches / ALNS). A probe must
        // not register as real activity: each apply+revert bumps it twice, so snapshot here and
        // restore below alongside lastTouched.
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

        // Restore: step, lastTouched, best-cost watermark. Conf-change needs no restore — it was
        // left untouched for the whole probe (see [probeActive]).
        step = oldStep
        for (i in touchedSlots.indices) lastTouched[touchedSlots[i]] = savedTouched[i]
        for (i in touchedSlots.indices) touchCount[touchedSlots[i]] = savedTouchCount[i]
        bestCostSeen = oldBestCost

        return CompoundEval(breakScore = breakCount, netDelta = netDelta, weightedNetDelta = weightedNetDelta)
    }

    private data class CompoundEval(val breakScore: Int, val netDelta: Long, val weightedNetDelta: Double)

    /** Re-read the factor's [Factor.violationDegree] from its just-updated payload
     *  and reconcile the maintained [factorDegree], [cost] (`Σ degree`), and [violated]-set
     *  membership (`degree > 0`). Called after the factor's `apply*` has refreshed its payload.
     *  Using the recomputed degree (rather than `apply*`'s returned delta) makes cost tracking
     *  exact for every factor, including globals whose returned status delta is approximate. */
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
}
