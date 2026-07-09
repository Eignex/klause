package com.eignex.klause.localsearch

import com.eignex.klause.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

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

    /** DDFW-style per-invariant dynamic weights and their class-normalised baseline. */
    val weights: FactorWeightBook = FactorWeightBook(problem)

    /** Optimize-phase objective view: the injected objective, shaping lambda, and objective-hot-spot
     *  int-var bias. */
    val shaping: ObjectiveShaping = ObjectiveShaping()

    /** Tabu / activity bookkeeping: the accepted-move clock, last-touched stamps, and touch counts. */
    val tabu: TabuBook = TabuBook(problem)

    /** Implicit-solving setup: elected globals, disjoint seed set, owner map, implication graph. */
    val seeding: ImplicitSeeding = ImplicitSeeding(problem)

    /** Implicit-solving feasible init: seed every [ImplicitSeeding.implicitSeedFactors] global into a
     *  satisfying configuration (skipping vars frozen by [assumptions]). Caller is responsible for the
     *  subsequent [recompute]. */
    fun seedImplicitFeasible() = seeding.seedImplicitFeasible(this)

    /** Accepted-move step counter (the search clock); see [TabuBook.step]. */
    val step: Long get() = tabu.step

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

    // Degree scratch reused by evaluateCompound so an apply+revert probe allocates nothing on its
    // array-copy path (the dominant LS allocation source). State is per-worker, so no locking.
    private var degScratch: IntArray? = null

    // Per-part probe scratch reused across evaluateCompound calls, grown on demand to the widest
    // compound seen; only the first `parts.size` entries are live. Probes are strictly nested per
    // worker (never concurrent, no re-entrancy), so a single set is safe — as with [degScratch].
    private val inverseScratch = ArrayList<Move>()
    private var slotScratch: IntArray = EmptyIntArray
    private var savedTouchedScratch: LongArray = EmptyLongArray
    private var savedTouchCountScratch: IntArray = EmptyIntArray

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
        tabu.reset()
        for (i in boolConfChange.indices) boolConfChange[i] = true
        for (i in intConfChange.indices) intConfChange[i] = true
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
                if (assignment.intValue(n.out) != v) applyIntSet(n.out, v)
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
     * Reduces to `breakScore(move).toDouble()` when [ObjectiveShaping.shapingLambda] is zero,
     * [ObjectiveShaping.objective] is null, or the objective isn't a [LinearObjective], so non-shaping
     * callers see identical behavior.
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
        val obj = shaping.objective ?: return 0.0
        val lambda = shaping.shapingLambda
        if (lambda == 0.0) return 0.0
        val delta = when (obj) {
            is LinearObjective -> linearObjectiveDelta(move, obj)
            is IncrementalObjective -> obj.deltaIfApplied(assignment, move)
            else -> return 0.0
        }
        return lambda * delta
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
    fun synthesizeChannelingMove(intVar: Int, newValue: Long): Move {
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
     * Reads from [FactorWeightBook.factorWeights], lazily-allocating if untouched — check
     * [FactorWeightBook.allocated] first to avoid forcing the allocation on a probe.
     */
    fun weightedNetDelta(move: Move): Double {
        val w = weights.factorWeights
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
    @Suppress("NOTHING_TO_INLINE")
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
        tabu.step++
        tabu.lastTouched[slot] = tabu.step
        if (tabu.touchCount[slot] < Int.MAX_VALUE) tabu.touchCount[slot]++
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

    private fun applyIntSet(intVar: Int, newValue: Long) {
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
    internal inline fun forEachIntFactorDelta(v: Int, newValue: Long, action: (factorId: Int, delta: Int) -> Unit) {
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

    /**
     * Apply [move] forward, observe (newly-violated, net-cost-diff), revert via inverse primitives,
     * and restore step / lastTouched / conf-change so the state is exactly as it was before.
     */
    private fun evaluateCompound(move: Move.Compound): CompoundEval {
        val oldStep = tabu.step
        val oldCost = cost
        val oldBestCost = bestCostSeen
        // Degree snapshot for the exact weighted delta. Skipped when no strategy touched the weights
        // (all 1.0 ⇒ weighted == raw netDelta).
        val degBefore = if (weights.allocated) {
            (degScratch ?: IntArray(factorDegree.size)).also { degScratch = it }.also { factorDegree.copyInto(it) }
        } else {
            null
        }
        val n = move.parts.size
        // Inverse per part (BoolFlip self-inverts; IntSet needs current value). Reused list, refilled.
        val inverses = inverseScratch
        inverses.clear()
        for (p in move.parts) inverses += inverseOf(p)
        // Save lastTouched / touchCount for each affected slot; the apply+revert dance overwrites
        // them, and a probe must not register as real cross-epoch activity (ALNS keys on touchCount).
        // Reused scratch grown to the widest compound; only [0, n) is live.
        if (slotScratch.size < n) {
            slotScratch = IntArray(n)
            savedTouchedScratch = LongArray(n)
            savedTouchCountScratch = IntArray(n)
        }
        val touchedSlots = slotScratch
        val savedTouched = savedTouchedScratch
        val savedTouchCount = savedTouchCountScratch
        for (i in 0 until n) {
            val slot = slotOf(move.parts[i])
            touchedSlots[i] = slot
            savedTouched[i] = tabu.lastTouched[slot]
            savedTouchCount[i] = tabu.touchCount[slot]
        }

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
            val w = weights.factorWeights
            weightedNetDelta = 0.0
            for (i in degBefore.indices) {
                val d = factorDegree[i] - degBefore[i]
                if (d != 0) weightedNetDelta += w[i] * d
            }
        }

        for (i in inverses.indices.reversed()) apply(inverses[i])
        probeActive = false

        // Conf-change needs no restore — it was left untouched for the whole probe (see probeActive).
        tabu.step = oldStep
        for (i in 0 until n) tabu.lastTouched[touchedSlots[i]] = savedTouched[i]
        for (i in 0 until n) tabu.touchCount[touchedSlots[i]] = savedTouchCount[i]
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
