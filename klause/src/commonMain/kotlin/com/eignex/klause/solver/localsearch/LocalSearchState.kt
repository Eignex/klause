package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntSwapSet
import kotlin.random.Random

/**
 * Mutable state of an ongoing solve. Owns the [Assignment], the violated-factor set, the
 * per-factor scratch arrays ([intPayload], [refPayload]), and the aggregated hard cost.
 */
class LocalSearchState(
    val problem: Problem,
    val rng: Random,
    var assumptions: Assumptions = Assumptions.None,
) {
    val assignment: Assignment = Assignment(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
    )
    val violated: IntSwapSet = IntSwapSet(problem.numFactors)
    val intPayload: IntArray = IntArray(problem.numFactors)
    val refPayload: Array<Any?> = arrayOfNulls(problem.numFactors)
    val moveSink: MoveSink = MoveSink(assumptions)

    /**
     * LS-cast view over [Problem.factors]. Every factor used by [LocalSearchSolver] must
     * implement [LocalSearchFactor]; this array is the pre-checked cast so factor-method
     * call sites avoid `as LocalSearchFactor` noise. Throws [ClassCastException] at
     * construction if any factor is propagation-only.
     */
    val factors: Array<LocalSearchFactor> = Array(problem.numFactors) {
        problem.factors[it] as LocalSearchFactor
    }

    /** Step counter incremented on every accepted move. Strategies use this together with
     *  [lastTouched] to enforce a tabu list. */
    var step: Long = 0L
        private set

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

    /** Lazy cache for [breakScore] of `Move.BoolFlip`. Entry `v` is fresh iff
     *  `boolBreakValid[v]`; otherwise the cached value is stale and must be recomputed. The
     *  cache is invalidated for every variable in the factor-neighbourhood of an applied
     *  move (so a flip of `u` invalidates `u` itself plus every other var sharing a factor
     *  with `u`). `IntSet` break scores are not cached — the target value widens the key. */
    private val boolBreakCache: IntArray = IntArray(problem.numBoolVars)
    private val boolBreakValid: BooleanArray = BooleanArray(problem.numBoolVars)

    var cost: Int = 0
        internal set

    /** Lowest [cost] observed since this state was constructed. Updated at the end of
     *  every committed `apply(move)`; preserved across [restart] so the all-time minimum
     *  drives aspiration decisions even when individual restart epochs go uphill. Used
     *  by [com.eignex.klause.solver.localsearch.strategy.AspirationCriterion.OrImprovesBestEver]
     *  to admit tabu moves that would beat the historical low. */
    var bestCostSeen: Int = Int.MAX_VALUE
        internal set

    /** Objective injected by the engine during a `minimize` call. `null` outside
     *  `minimize` — strategies that consult [shapedBreakScore] gracefully fall back to
     *  the unshaped break score when this is null. */
    var objective: Objective? = null
        internal set

    /** Lambda coefficient extracted from `params.costShaping` for pre-feasibility shaping.
     *  Set by the engine in [com.eignex.klause.solver.localsearch.LocalSearchSolver.minimizeImpl];
     *  defaults to 0.0 (no shaping) outside a `minimize` call or under
     *  [com.eignex.klause.solver.localsearch.CostShaping.FeasibilityFirst]. */
    var shapingLambda: Double = 0.0
        internal set

    /** Per-factor weight, default 1.0. Not read by the engine itself — every violation
     *  contributes +1/-1 to [cost] regardless. Strategies that want to bias the search
     *  toward repairing persistently-violated factors (e.g. DDFW, SAPS) read and mutate
     *  this array between picks.
     *
     *  Lazily allocated on first access. WalkSat / ProbSat / SimulatedAnnealing / CcaWalkSat
     *  never touch it and so pay no allocation cost; only DDFW (and any future weight-using
     *  strategy) triggers the `DoubleArray(numFactors)` allocation. [WarmState.captureFrom]
     *  probes [factorWeightsAllocated] before reading to avoid forcing the allocation just
     *  to capture all-1.0 defaults from sessions that ran a weight-blind strategy. */
    private var _factorWeights: DoubleArray? = null
    val factorWeights: DoubleArray
        get() {
            var w = _factorWeights
            if (w == null) {
                w = DoubleArray(problem.numFactors) { 1.0 }
                _factorWeights = w
            }
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

    fun restart() {
        assignment.randomize(rng, problem.intDomains)
        // Overwrite the assumed slots so the assignment starts feasible w.r.t. the caller's pins.
        assumptions.forEachBool { id, value ->
            if (assignment.boolValue(id) != value) assignment.flipBool(id)
        }
        assumptions.forEachInt { id, value -> assignment.setInt(id, value) }
        for (i in lastTouched.indices) lastTouched[i] = 0L
        for (i in boolConfChange.indices) boolConfChange[i] = true
        for (i in intConfChange.indices) intConfChange[i] = true
        step = 0L
        recompute()
    }

    fun recompute() {
        for (i in 0 until problem.numFactors) violated.remove(i)
        cost = 0
        for (v in boolBreakValid.indices) boolBreakValid[v] = false
        factors.forEachIndexed { id, factor ->
            factor.initialize(this, id)
            if (factor.isViolated(this, id)) {
                violated.add(id)
                cost++
            }
        }
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    fun apply(move: Move): Unit = when (move) {
        is Move.BoolFlip -> applyBoolFlip(move.varId)
        is Move.IntSet -> applyIntSet(move.varId, move.newValue)
        is Move.Compound -> { for (p in move.parts) apply(p) }
    }

    /**
     * Number of currently-satisfied factors that would become violated if [move] were
     * applied. Used by strategies (WalkSAT-style noise/greedy, probSAT-style weighting) to
     * score repair candidates. Computed on demand by walking the var's occurrence list and
     * asking each factor for its `deltaIf*`.
     */
    fun breakScore(move: Move): Int = when (move) {
        is Move.BoolFlip -> {
            val v = move.varId
            if (boolBreakValid[v]) boolBreakCache[v] else {
                var count = 0
                forEachBoolFactorDelta(v) { _, d -> if (d > 0) count++ }
                boolBreakCache[v] = count
                boolBreakValid[v] = true
                count
            }
        }
        is Move.IntSet -> {
            var count = 0
            forEachIntFactorDelta(move.varId, move.newValue) { _, d -> if (d > 0) count++ }
            count
        }
        is Move.Compound -> evaluateCompound(move).breakScore
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
     * Only [LinearObjective] is shaped today (O(1) coefficient lookup per move).
     * Non-linear objectives would require an apply-revert with full re-evaluation; the
     * benefit doesn't justify the state churn so they fall through to the unshaped path.
     */
    fun shapedBreakScore(move: Move): Double =
        breakScore(move).toDouble() + shapedObjectiveDelta(move)

    /**
     * Lambda-multiplied objective delta contribution for shaping any per-move score:
     * `shapingLambda * objectiveDelta(move)`. Returns `0.0` when shaping is off
     * (no objective, lambda = 0, or non-Linear objective). Strategies that compose
     * objective-aware scores (DDFW's weighted break, ProbSat's exponent input) add
     * this on top of their base metric.
     */
    fun shapedObjectiveDelta(move: Move): Double {
        val obj = objective ?: return 0.0
        if (shapingLambda == 0.0) return 0.0
        if (obj !is LinearObjective) return 0.0
        return shapingLambda * linearObjectiveDelta(move, obj)
    }

    private fun linearObjectiveDelta(move: Move, obj: LinearObjective): Double = when (move) {
        is Move.BoolFlip -> {
            val v = move.varId
            if (v < obj.boolWeights.size) {
                val w = obj.boolWeights[v]
                if (assignment.boolValue(v)) -w else w
            } else 0.0
        }
        is Move.IntSet -> {
            val v = move.varId
            if (v < obj.intCoefficients.size) {
                obj.intCoefficients[v] * (move.newValue - assignment.intValue(v))
            } else 0.0
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
     * Net change in the violated-factor count that would result from applying [move]. Used
     * by [TabuFilter]'s [AspirationCriterion.OrImproving] to decide whether a tabu move
     * is improving enough to override the tabu. Walks the affected var's occurrence list
     * once; same O(arity) cost as [breakScore].
     */
    fun netDelta(move: Move): Int = when (move) {
        is Move.BoolFlip -> {
            var sum = 0
            forEachBoolFactorDelta(move.varId) { _, d -> sum += d }
            sum
        }
        is Move.IntSet -> {
            var sum = 0
            forEachIntFactorDelta(move.varId, move.newValue) { _, d -> sum += d }
            sum
        }
        is Move.Compound -> evaluateCompound(move).netDelta
    }

    private fun applyBoolFlip(boolVar: Int) {
        assignment.flipBool(boolVar)
        val touchedFactors = problem.boolOccurrences[boolVar]
        for (factorId in touchedFactors) {
            val factor = factors[factorId]
            updateViolation(factorId, factor.applyBoolFlip(this, factorId, boolVar))
        }
        invalidateBoolBreakNeighbourhood(touchedFactors)
        markNeighborConfChange(touchedFactors)
        boolConfChange[boolVar] = false
        step++
        lastTouched[boolVar] = step
        if (touchCount[boolVar] < Int.MAX_VALUE) touchCount[boolVar]++
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    private fun applyIntSet(intVar: Int, newValue: Int) {
        val old = assignment.intValue(intVar)
        if (old == newValue) return
        assignment.setInt(intVar, newValue)
        val touchedFactors = problem.intOccurrences[intVar]
        for (factorId in touchedFactors) {
            val factor = factors[factorId]
            updateViolation(factorId, factor.applyIntSet(this, factorId, intVar, old))
        }
        invalidateBoolBreakNeighbourhood(touchedFactors)
        markNeighborConfChange(touchedFactors)
        intConfChange[intVar] = false
        step++
        val slot = problem.numBoolVars + intVar
        lastTouched[slot] = step
        if (touchCount[slot] < Int.MAX_VALUE) touchCount[slot]++
        if (cost < bestCostSeen) bestCostSeen = cost
    }

    private fun invalidateBoolBreakNeighbourhood(factorIds: IntArray) {
        for (factorId in factorIds) {
            val f = factors[factorId]
            for (v in f.boolVars) boolBreakValid[v] = false
        }
    }

    private fun markNeighborConfChange(factorIds: IntArray) {
        for (factorId in factorIds) {
            val f = factors[factorId]
            for (v in f.boolVars) boolConfChange[v] = true
            for (v in f.intVars) intConfChange[v] = true
        }
    }

    /** Walk every factor that touches bool var [v], call its `deltaIfBoolFlipped`, and
     *  hand the (factorId, delta) pair to [action]. Inline so callers stay allocation-
     *  free. Shared by [breakScore], [netDelta], and DDFW's weighted-break score. */
    inline fun forEachBoolFactorDelta(v: Int, action: (factorId: Int, delta: Int) -> Unit) {
        for (factorId in problem.boolOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfBoolFlipped(this, factorId, v))
        }
    }

    /** Same as [forEachBoolFactorDelta] but for an `IntSet` move on int var [v] with
     *  target value [newValue]. */
    inline fun forEachIntFactorDelta(
        v: Int, newValue: Int, action: (factorId: Int, delta: Int) -> Unit,
    ) {
        for (factorId in problem.intOccurrences[v]) {
            action(factorId, factors[factorId].deltaIfIntSet(this, factorId, v, newValue))
        }
    }

    /** Pick a uniformly-random violated factor, ask it for repair-move suggestions, and
     *  return the raw list. Returns `null` when no factor is violated or the violated
     *  factor proposed no moves. Every WalkSAT-family [Strategy.pickMove] starts the same
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
     *  (ties broken uniformly at random). Used by WalkSat / CcaWalkSat after candidate
     *  filtering. Returns `null` on an empty input. */
    fun greedyPickByShapedBreak(moves: List<Move>): Move? {
        if (moves.isEmpty()) return null
        var bestBreak = Double.POSITIVE_INFINITY
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = shapedBreakScore(m)
            if (brk < bestBreak) {
                bestBreak = brk; bestCount = 1; pick = m
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
        val oldViolatedIds: Set<Int> = violated.toIntArray().toHashSet()
        val oldBoolConf = boolConfChange.copyOf()
        val oldIntConf = intConfChange.copyOf()
        // Capture inverse per part (BoolFlip self-inverts; IntSet needs current value).
        val inverses = ArrayList<Move>(move.parts.size)
        for (p in move.parts) inverses += inverseOf(p)
        // Capture lastTouched for each affected slot — these will all get overwritten by
        // the apply+revert dance.
        val touchedSlots = IntArray(move.parts.size) { slotOf(move.parts[it]) }
        val savedTouched = LongArray(touchedSlots.size) { lastTouched[touchedSlots[it]] }

        for (p in move.parts) apply(p)

        var breakCount = 0
        val newViolated = violated.toIntArray()
        for (fid in newViolated) if (fid !in oldViolatedIds) breakCount++
        val netDelta = cost - oldCost

        for (i in inverses.indices.reversed()) apply(inverses[i])

        // Restore: step, lastTouched, conf-change arrays, best-cost watermark.
        step = oldStep
        for (i in touchedSlots.indices) lastTouched[touchedSlots[i]] = savedTouched[i]
        for (i in oldBoolConf.indices) boolConfChange[i] = oldBoolConf[i]
        for (i in oldIntConf.indices) intConfChange[i] = oldIntConf[i]
        bestCostSeen = oldBestCost

        return CompoundEval(breakScore = breakCount, netDelta = netDelta)
    }

    private fun inverseOf(part: Move): Move = when (part) {
        is Move.BoolFlip -> part
        is Move.IntSet -> Move.IntSet(part.varId, assignment.intValue(part.varId))
        is Move.Compound -> error("Compound parts are primitive by construction")
    }

    private fun slotOf(part: Move): Int = when (part) {
        is Move.BoolFlip -> part.varId
        is Move.IntSet -> problem.numBoolVars + part.varId
        is Move.Compound -> error("Compound parts are primitive by construction")
    }

    private data class CompoundEval(val breakScore: Int, val netDelta: Int)

    private fun updateViolation(factorId: Int, deltaViolated: Int) {
        when (deltaViolated) {
            +1 -> {
                violated.add(factorId)
                cost++
            }
            -1 -> {
                violated.remove(factorId)
                cost--
            }
        }
    }
}
