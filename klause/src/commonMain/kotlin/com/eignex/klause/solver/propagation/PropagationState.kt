package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableLongIntMap

// Three-tier learned-clause DB tiers (#201). Top-level so the engine's reduction policy in
// BacktrackSolver and the parallel tier array in PropagationState share one definition.
/** Not yet classified — the reduction policy assigns a tier by LBD on first encounter. */
internal const val TIER_UNSET: Int = -1

/** Permanent core: very low LBD, never deleted. */
internal const val TIER_CORE: Int = 0

/** Mid tier: kept across reductions, demoted to [TIER_LOCAL] when idle. */
internal const val TIER_MID: Int = 1

/** Local tier: aggressively deleted; promoted to [TIER_MID] on reuse. */
internal const val TIER_LOCAL: Int = 2

/**
 * Mutable working state passed to [Factor.propagate]. Tracks the currently-known pinned bool
 * values and the (tightened) int domains, plus a **decision level** per pinned variable for
 * conflict-driven backjumping.
 *
 *  - Decisions (external pins from the driver / session) bump the level monotonically.
 *  - Implied pins (from factor propagation) inherit the maximum level of the variables the
 *    factor reads — i.e. the deepest decision that contributed.
 *  - On contradiction, the set of decision levels touched by the failing factor is what the
 *    driver reports as [PropagationResult.Unsat.conflictLevels].
 *
 *  Factors don't see the level machinery directly — they keep calling `pinBool` /
 *  `tightenIntMin` / `tightenIntMax` / `setInt` as before. The driver sets `currentLevel`
 *  to the inherited level before each factor invocation; mutators read it.
 */
class PropagationState(
    /** The problem being propagated. */
    val problem: Problem,
    assumptions: Assumptions,
) {
    /** Two-bit-per-var three-valued pin store. [boolAssigned] says whether the variable has
     *  a definite value; [boolValueBits] holds the value when assigned (ignored otherwise).
     *  Backed by [Bits] — packed `LongArray`, 8× cache-denser than the old `Array<Boolean?>`
     *  and one less pointer indirection per read (no boxed `Boolean`). */
    private val boolAssigned: Bits = Bits(problem.numBoolVars)
    private val boolValueBits: Bits = Bits(problem.numBoolVars)

    /** Operator-indexable view preserving the call-site syntax `state.boolValues[v]` and
     *  `state.boolValues[v] = x`. Reads `null` when unassigned. Write `null` clears the
     *  assigned bit. The backing storage is the parallel-Bits pair above. */
    inner class BoolView {
        /** Number of Boolean variables. */
        val size: Int get() = problem.numBoolVars

        /** Valid Boolean variable id range. */
        val indices: IntRange get() = 0 until problem.numBoolVars

        /** Current value of bool `v`, or null if unassigned. */
        operator fun get(v: Int): Boolean? = if (boolAssigned.get(v)) boolValueBits.get(v) else null

        /** Assign bool `v` to [value], or null to unassign. */
        operator fun set(v: Int, value: Boolean?) {
            if (value == null) {
                boolAssigned.clear(v)
                return
            }
            if (value) boolValueBits.set(v) else boolValueBits.clear(v)
            boolAssigned.set(v)
        }
    }

    /** Read/write view over the current Boolean assignment. */
    val boolValues: BoolView = BoolView()

    /** Per-int current domain (copy of [Problem.intDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = Array(problem.numIntVars) { problem.intDomains[it] }

    /** Vars whose pin/domain changed since the driver last drained them. Primitive int
     *  ring buffers to avoid the autoboxing tax `ArrayDeque<Int>` pays on every push/poll. */
    private val dirtyBools: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
    private val dirtyInts: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))

    // -------- Reusable propagation worklist (was allocated fresh per runToFixpoint) --------
    //
    // [propQueue] is the factor worklist; [propStamp] is a per-factor "currently queued"
    // membership set encoded as a generation stamp so resetting between propagation runs is
    // O(1) (just bump [propGen]) instead of zeroing a `BooleanArray(factorCount)` on every
    // pin. A factor is queued iff `propStamp[fid] == propGen`; dequeuing writes `propGen - 1`
    // (any value ≠ propGen) so a factor can still re-enqueue itself within the same run.
    private val propQueue: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numFactors.coerceAtLeast(8))
    private var propStamp: IntArray = IntArray(problem.numFactors.coerceAtLeast(8))
    private var propGen: Int = 0

    /** Reset the worklist for a new propagation run over [factorCount] factors. Grows
     *  [propStamp] when learned clauses have pushed the factor count past its capacity, and
     *  wraps [propGen] safely on the (astronomically rare) Int overflow. */
    private fun propBegin(factorCount: Int) {
        if (propStamp.size < factorCount) {
            var n = propStamp.size
            while (n < factorCount) n *= 2
            propStamp = propStamp.copyOf(n)
        }
        if (propGen == Int.MAX_VALUE) {
            propStamp.fill(0)
            propGen = 0
        }
        propGen++
        propQueue.clear()
    }

    /** Enqueue [fid] if not already queued this run. */
    private fun propEnq(fid: Int) {
        if (propStamp[fid] != propGen) {
            propStamp[fid] = propGen
            propQueue.addLast(fid)
        }
    }

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    // Decision-level plumbing. ---------------------------------------------------------------

    /** Decision level when each bool was first pinned (-1 = unpinned). */
    val boolLevel: IntArray = IntArray(problem.numBoolVars) { -1 }

    /** Deepest decision level contributing to this int var's current domain (-1 = untouched). */
    val intLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    // Per-int-var bound-change history: the sequence of (value, level) at which `min` rose
    // (resp. `max` fell) past each search-time tighten. `min` is monotone-increasing along a
    // path, so [minHistVal] is ascending and [minHistLvl] non-decreasing (symmetric for max).
    // Lets [minLevelForGe] / [maxLevelForLe] answer "the level v's bound *first* reached k" —
    // the correct (often lower) level to attribute to a *relaxed* bound atom, instead of the
    // current [intLevel] which is too high. Allocated lazily per var; only maintained while
    // [undoLogging] (the search phase); truncated on backtrack via the undo log. Powers the
    // weakest-bound LCG relaxation (see collectLinearRelaxedConflictAntecedents).
    private val minHistVal: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val minHistLvl: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val maxHistVal: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val maxHistLvl: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)

    // Per-entry reasons alongside the bound histories: the near reason justifies the
    // requested bound, the far reason additionally carries the hole-snap chain, and the
    // requested value says which thresholds each covers. Together they let an atom's
    // antecedents be derived on demand for any threshold the move crossed — the lazy
    // replacement for storing a per-atom antecedent snapshot.
    private val minHistAntNear: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    private val minHistAntFar: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    private val minHistReq: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val maxHistAntNear: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    private val maxHistAntFar: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    private val maxHistReq: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val holeHistAnt: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)

    private fun pushMinHist(v: Int, value: Int, level: Int, antNear: IntArray?, antFar: IntArray?, requested: Int) {
        val vals = minHistVal[v] ?: IntArrayList(initialCapacity = 4).also { minHistVal[v] = it }
        val lvls = minHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { minHistLvl[v] = it }
        val near = minHistAntNear[v] ?: ArrayList<IntArray?>(4).also { minHistAntNear[v] = it }
        val far = minHistAntFar[v] ?: ArrayList<IntArray?>(4).also { minHistAntFar[v] = it }
        val req = minHistReq[v] ?: IntArrayList(initialCapacity = 4).also { minHistReq[v] = it }
        vals.add(value)
        lvls.add(level)
        near.add(antNear)
        far.add(antFar)
        req.add(requested)
    }

    private fun pushMaxHist(v: Int, value: Int, level: Int, antNear: IntArray?, antFar: IntArray?, requested: Int) {
        val vals = maxHistVal[v] ?: IntArrayList(initialCapacity = 4).also { maxHistVal[v] = it }
        val lvls = maxHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { maxHistLvl[v] = it }
        val near = maxHistAntNear[v] ?: ArrayList<IntArray?>(4).also { maxHistAntNear[v] = it }
        val far = maxHistAntFar[v] ?: ArrayList<IntArray?>(4).also { maxHistAntFar[v] = it }
        val req = maxHistReq[v] ?: IntArrayList(initialCapacity = 4).also { maxHistReq[v] = it }
        vals.add(value)
        lvls.add(level)
        near.add(antNear)
        far.add(antFar)
        req.add(requested)
    }

    /** Reason for `[v ≥ k]` being true: null (a root/bake fact) when no search-time move
     *  is on record, else the recorded reason of the move that first reached ≥ `k` — the
     *  near set when the move's requested bound already covers `k`, the far (hole-snap
     *  chained) set otherwise. */
    private fun minReasonFor(v: Int, k: Int): IntArray? {
        if (k <= problem.intDomains[v].min) return null
        val vals = minHistVal[v] ?: return null
        val i = vals.lowerBound(k)
        if (i >= vals.size) return null
        return if (k <= requireNotNull(minHistReq[v])[i]) {
            requireNotNull(minHistAntNear[v])[i]
        } else {
            requireNotNull(minHistAntFar[v])[i]
        }
    }

    /** Reason for `[v ≤ k]` being true; symmetric to [minReasonFor]. */
    private fun maxReasonFor(v: Int, k: Int): IntArray? {
        if (k >= problem.intDomains[v].max) return null
        val vals = maxHistVal[v] ?: return null
        val i = vals.lowerBoundDescending(k)
        if (i >= vals.size) return null
        return if (k >= requireNotNull(maxHistReq[v])[i]) {
            requireNotNull(maxHistAntNear[v])[i]
        } else {
            requireNotNull(maxHistAntFar[v])[i]
        }
    }

    /** Reason for the interior carve of `k` from `v`'s domain; null = bake-time fact. */
    private fun holeReasonFor(v: Int, k: Int): IntArray? {
        val vals = holeHistVal[v] ?: return null
        for (i in 0 until vals.size) if (vals[i] == k) return requireNotNull(holeHistAnt[v])[i]
        return null
    }

    // Per-int-var interior-hole history: the (value, level) at which each search-time carve
    // happened. The bound histories above cannot answer "when did k leave the domain" for a
    // value strictly inside the bounds, and the advisory [atomLevel] drifts across pops —
    // an eq atom falsified by an interior hole needs this record for an exact conflict
    // level. Same lifecycle as the bound histories: lazily allocated, maintained while
    // [undoLogging], truncated on backtrack via the undo log.
    private val holeHistVal: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    private val holeHistLvl: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)

    private fun pushHoleHist(v: Int, value: Int, level: Int, ant: IntArray?) {
        val vals = holeHistVal[v] ?: IntArrayList(initialCapacity = 4).also { holeHistVal[v] = it }
        val lvls = holeHistLvl[v] ?: IntArrayList(initialCapacity = 4).also { holeHistLvl[v] = it }
        val ants = holeHistAnt[v] ?: ArrayList<IntArray?>(4).also { holeHistAnt[v] = it }
        vals.add(value)
        lvls.add(level)
        ants.add(ant)
    }

    /** Level at which interior value `k` was carved out of `v`'s domain. `0` when no
     *  search-time carve is on record — the hole then predates the search (bake-time
     *  propagation), which is a root fact. */
    fun holeLevelFor(v: Int, k: Int): Int {
        val vals = holeHistVal[v] ?: return 0
        val lvls = holeHistLvl[v] ?: error("holeHistLvl[$v] missing while holeHistVal present")
        for (i in 0 until vals.size) if (vals[i] == k) return lvls[i]
        return 0
    }

    /** Level at which `v`'s min *first* reached ≥ `k`. `0` when `k` is within the root domain
     *  (a global fact). Conservative fallback to [intLevel] if history is absent. */
    fun minLevelForGe(v: Int, k: Int): Int {
        if (k <= problem.intDomains[v].min) return 0
        val vals = minHistVal[v] ?: return maxOf(intLevel[v], 0)
        val lvls = minHistLvl[v] ?: error("minHistLvl[$v] missing while minHistVal present")
        // [minHistVal] is ascending (min rises monotonically), so the first value ≥ k is the
        // lower bound of k — found in O(log n) instead of a linear scan (#97).
        val i = vals.lowerBound(k)
        return if (i < vals.size) lvls[i] else maxOf(intLevel[v], 0)
    }

    /** Level at which `v`'s max *first* reached ≤ `k`. Symmetric to [minLevelForGe]. */
    fun maxLevelForLe(v: Int, k: Int): Int {
        if (k >= problem.intDomains[v].max) return 0
        val vals = maxHistVal[v] ?: return maxOf(intLevel[v], 0)
        val lvls = maxHistLvl[v] ?: error("maxHistLvl[$v] missing while maxHistVal present")
        // [maxHistVal] is descending (max falls monotonically); the first value ≤ k is the
        // descending lower bound — O(log n) (#97).
        val i = vals.lowerBoundDescending(k)
        return if (i < vals.size) lvls[i] else maxOf(intLevel[v], 0)
    }

    /** Loosest (smallest) min-value established at a level strictly below [level]; the root
     *  min when `v` was never tightened before [level]. */
    fun minBelowLevel(v: Int, level: Int): Int {
        val rootMin = problem.intDomains[v].min
        val vals = minHistVal[v] ?: return rootMin
        val lvls = minHistLvl[v] ?: error("minHistLvl[$v] missing while minHistVal present")
        var best = rootMin
        for (i in 0 until vals.size) {
            if (lvls[i] < level) best = vals[i] else break // lvls non-decreasing → prefix
        }
        return best
    }

    /** Loosest (largest) max-value established at a level strictly below [level]; the root
     *  max when `v` was never tightened before [level]. */
    fun maxAboveLevel(v: Int, level: Int): Int {
        val rootMax = problem.intDomains[v].max
        val vals = maxHistVal[v] ?: return rootMax
        val lvls = maxHistLvl[v] ?: error("maxHistLvl[$v] missing while maxHistVal present")
        var best = rootMax
        for (i in 0 until vals.size) {
            if (lvls[i] < level) best = vals[i] else break
        }
        return best
    }

    /**
     * Decision-var encoded per level: index `lvl-1` holds either a bool var id (0..numBoolVars-1)
     * or a shifted int var id (numBoolVars + intVar). Grows as decisions are pushed. Primitive
     * int list (no boxing on push or indexed read).
     */
    private val levelToDecisionVar: IntArrayList =
        IntArrayList()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    /** Emit per-bound atom-lit antecedents for every var in [vars]. For each var,
     *  if its current `min` is tighter than the initial domain min, emit `¬[v ≥ d.min]`;
     *  similarly for the `max` side. The deduction's implicit clause is then
     *  `(⋀ premises) → result`, which as antecedents-on-result is the disjunction of the
     *  premises' negations.
     *
     *  Used by int-domain factors (AllDifferent, GCC, Element, Cumulative, Sort, ...)
     *  that emit a constraint-wide reason: every involved var's current bounds participate
     *  in the deduction. Per-bound atom-lits give 1UIP / minimization finer resolution than
     *  the coarser bool-lit union that the antecedents-of-antecedents would unfold to.
     *
     *  Returns `null` when no var's bounds have been tightened past the initial domain —
     *  the analyzer then treats the resulting int-fact as a level-0-style leaf. */
    fun composeIntVarAtomAntecedents(vars: IntArray): IntArray? {
        val seen = HashSet<Long>()
        val out = IntArrayList()
        for (v in vars) {
            val d = intDomains[v]
            val orig = problem.intDomains[v]
            if (d.min > orig.min) {
                val key = (v.toLong() shl 33) or (0L shl 32) or
                    (d.min.toLong() - Int.MIN_VALUE.toLong())
                if (seen.add(key)) {
                    out.add(
                        Lit.make(atomVarGe(v, d.min), false),
                    )
                }
            }
            if (d.max < orig.max) {
                val key = (v.toLong() shl 33) or (1L shl 32) or
                    (d.max.toLong() - Int.MIN_VALUE.toLong())
                if (seen.add(key)) {
                    out.add(
                        Lit.make(atomVarLe(v, d.max), false),
                    )
                }
            }
        }
        if (out.size == 0) return null
        return out.toIntArray()
    }

    /** True iff every decision on the trail so far is a bool decision (no int pin
     *  decisions). Lets conflict-reason fallbacks emit a sound "negate the current
     *  bool partial assignment" nogood without needing int-bound literals — the clause
     *  is sound exactly when no int decision is partly responsible for the conflict. */
    fun allDecisionsAreBool(): Boolean {
        val numBool = problem.numBoolVars
        for (lvl in 0 until levelToDecisionVar.size) {
            if (levelToDecisionVar[lvl] >= numBool) return false
        }
        return true
    }

    /** Level any pin created during the current factor invocation inherits. Set by the driver. */
    internal var currentLevel: Int = 0

    private companion object {
        /** Sentinel for propagateAtomsForVar's carved-value parameter. */
        const val NO_CARVE: Int = Int.MIN_VALUE

        /** Blocking-literal slot with no blocker (#200): the watcher always fires. Lit ids
         *  are non-negative ([Lit.make] = `var shl 1 | sign`), so −1 is a safe sentinel. */
        const val NO_BLOCKER: Int = -1
    }

    /** Populated on contradiction; the driver reads it to form [PropagationResult.Unsat]. */
    @Suppress("DoubleMutabilityForCollection") // lazily allocated on conflict
    internal var conflictLevels: IntArray? = null

    /** Per-var record of which factor most recently *forced* the value. `-1` means "set by a
     *  decision / assumption, not by any factor's propagation step". Read by
     *  [extractConflictFactors] to walk the propagation graph backwards from a conflict and
     *  collect every factor that contributed. */
    val boolReason: IntArray = IntArray(problem.numBoolVars) { -1 }

    /** Factor that most recently tightened this int var's lower bound. `-1` = decision /
     *  initial domain. Tracked separately from [intMaxReason] so two-sided narrowing
     *  conflicts (one factor tightens min, another tightens max into infeasibility) both
     *  surface in the core. */
    val intMinReason: IntArray = IntArray(problem.numIntVars) { -1 }

    /** Mirror of [intMinReason] for the upper bound. */
    val intMaxReason: IntArray = IntArray(problem.numIntVars) { -1 }

    /** Factor whose [Factor.propagate] is currently running. Read by the impl methods so
     *  state changes can be attributed back to a factor. `-1` between factor invocations
     *  (decisions, assumption seeding) — those pins/tightenings record `reason = -1`. */
    internal var currentFactor: Int = -1

    /** Cumulative count of factor-forced assignments (bool pins + int tightens applied while
     *  a factor's `propagate` is running, i.e. `currentFactor >= 0`). Decisions and seed
     *  pins don't count. Read via [PropagationSession.propagationCount] for the
     *  `propagations` stat; monotonic for the life of the state. */
    internal var propagations: Long = 0L

    /**
     * Seed set of factors directly implicated in a contradiction. Populated by [runToFixpoint]
     * (the factor that returned `false`) and by the impl methods (both sides of a two-source
     * narrowing). [extractConflictFactors] BFSes from this seed via the reason arrays to
     * produce the full propagation-graph core.
     */
    // Reused across conflicts (cleared, not reallocated) and primitive (no Int boxing) — this is
    // populated on the propagation conflict path, which is hot on conflict-heavy instances.
    internal val conflictSeedFactors: IntHashSet = IntHashSet()

    /** Reused dedup scratch for [collectLevelsForVars] — avoids a per-conflict HashSet alloc. */
    private val levelScratch: IntHashSet = IntHashSet()

    /** Var whose pinned value was contradicted by a decision-level pin attempt (i.e.
     *  `pinBoolAsDecision` tried to set the opposite value of an existing pin). `-1` when
     *  no such conflict is active. Lets the conflict analyzer learn from a
     *  decision-vs-prior-pin contradiction by seeding from the prior pin's antecedents
     *  plus the just-decided lit — without this, the analyzer falls back to chronological
     *  backtrack on any conflict that doesn't come from a factor's `propagate`. */
    internal var lastDecisionConflictVar: Int = -1

    /*
     * Per-factor mutable scratch space — mirrors [com.eignex.klause.solver.localsearch.LocalSearchState.refPayload]
     * on the LS side. Factors stash propagation-time bookkeeping here keyed by their own
     * factor id; the engine doesn't touch the contents. Today's only user is
     * [Clause]'s two-watched-literal scheme, but the slot
     * is general so future factors (Cardinality watched literals, etc.) can adopt the
     * same pattern.
     *
     * Drift across snapshot / restore is intentional. CDCL-style watches are advisory:
     * they point at "non-false-when-last-checked" literals, and propagate self-corrects
     * by re-validating on each fire. Carrying them across pops keeps work amortised
     * without the snapshot copying that level-aware state needs.
     */

    /** Backing list for [refPayload]; mutable so [addLearnedClause] can grow it
     *  alongside the learned-clause registry without copying the full array. */
    private val _refPayload: ArrayList<Any?> = ArrayList<Any?>(problem.numFactors).apply {
        repeat(problem.numFactors) { add(null) }
    }

    /** Indices in [_refPayload] currently holding a [SnapshottablePayload]. Maintained on every
     *  write through [refPayload] so [mark] visits only these slots instead of scanning the whole
     *  factor list (incl. all learned clauses) per pin — the scan was an O(numFactors)-per-decision
     *  cost, and pure-clause problems have none. Snapshottable payloads belong to static factors
     *  (Table / Mdd), so their ids stay below `numFactors` and never move under learned-clause
     *  forgetting. */
    private val snapshottableIndices: IntHashSet = IntHashSet()

    /** Per-factor mutable payload slots (reference-typed). Writes route through this view so
     *  [snapshottableIndices] stays in sync; reads and structural ops delegate to [_refPayload]. */
    val refPayload: MutableList<Any?> = object : MutableList<Any?> by _refPayload {
        override fun set(index: Int, element: Any?): Any? {
            if (element is SnapshottablePayload) snapshottableIndices.add(index) else snapshottableIndices.remove(index)
            return _refPayload.set(index, element)
        }
    }

    /** Learned clauses accumulated during search (LCG-style nogoods produced by
     *  [ConflictAnalyzer]). Their factor ids live in `[problem.numFactors, totalFactorCount)` —
     *  treat them like any other [Clause] via [factorAt];
     *  they participate in propagation through [boolWatchersByLit] just like static
     *  clauses. Survives `restore` (clauses are facts about the original problem, not
     *  trail state); pruned by [forgetLearnedClauses]. */
    private val _learnedClauses: ArrayList<Clause> = ArrayList()

    // Cached base factor table — `problem.factors` is immutable after construction, so hoist
    // the array reference and its size out of the per-call `problem.factors` / `.size` getters
    // that [factorAt] (a top BCP-loop method) pays on every watcher fire.
    private val baseFactors: Array<Factor> = problem.factors
    private val baseFactorCount: Int = problem.factors.size

    /** Clauses learned during conflict analysis. */
    val learnedClauses: List<Clause> get() = _learnedClauses

    /** Count of binary (2-literal) clauses known — original problem clauses plus learned
     *  ones. Gates the #202 binary-resolution minimization, which is a no-op without binary
     *  clauses. Over-approximates after forgetting (never decremented), which only costs a
     *  harmless no-op pass — never correctness. */
    private var binaryClauseCount: Int = run {
        var n = 0
        for (f in problem.factors) if (f is Clause && f.literals.size == 2) n++
        n
    }

    /** True iff any binary clause is known — the gate for binary-resolution minimization. */
    val hasBinaryClauses: Boolean get() = binaryClauseCount > 0

    /**
     * Invoke [action] with the *other* literal of every binary clause that contains [lit]
     * (#202). Binary clauses watch both their literals and never relocate a watch (there is
     * no third literal to move to), so [boolWatchersByLit] reliably lists every binary clause
     * on [lit]. No-op for atom-literal [lit] (the bool watcher index only covers bool vars).
     */
    internal fun forEachBinaryPartner(lit: Int, action: (other: Int) -> Unit) {
        if (Lit.variable(lit) >= problem.numBoolVars) return
        val list = boolWatchersByLit[lit]
        for (i in 0 until list.size) {
            val f = factorAt(list[i])
            if (f is Clause && f.literals.size == 2) {
                val a = f.literals[0]
                val b = f.literals[1]
                when (lit) {
                    a -> action(b)
                    b -> action(a)
                }
            }
        }
    }

    /** LBD (Literal Block Distance) per learned clause, parallel to [_learnedClauses].
     *  Glucose-style glue metric: lower = more re-usable. Forgetting policies key on
     *  this to decide which clauses to drop. */
    private val learnedLbds: IntArrayList = IntArrayList()

    /** 1 for clauses that must survive every forgetting pass, parallel to [_learnedClauses].
     *  Solution-blocking nogoods are the main client: dropping one re-opens an already
     *  reported leaf and the search can revisit it forever. */
    private val learnedPermanent: IntArrayList = IntArrayList()

    /** Three-tier database tier per learned clause (#201), parallel to [_learnedClauses]:
     *  [TIER_CORE] / [TIER_MID] / [TIER_LOCAL], or [TIER_UNSET] before the reduction policy
     *  first classifies it by LBD. The policy promotes/demotes clauses between tiers based on
     *  reuse, so the tier is persistent state rather than a pure function of LBD. */
    private val learnedTier: IntArrayList = IntArrayList()

    /** 1 iff the learned clause has detected a conflict or forced a unit since the last
     *  reduction, parallel to [_learnedClauses]. The three-tier reduction policy reads this
     *  to promote reused clauses and demote idle ones, then clears it for survivors. */
    private val learnedUsedFlags: IntArrayList = IntArrayList()

    /** `problem.numFactors + learnedClauses.size`. Use this instead of `problem.numFactors`
     *  when iterating or sizing per-factor scratch in the engine. */
    val totalFactorCount: Int get() = problem.numFactors + _learnedClauses.size

    /** Unified factor accessor; routes static factor ids to [Problem.factors] and learned
     *  factor ids (≥ `problem.numFactors`) to [learnedClauses]. */
    fun factorAt(fid: Int): Factor = if (fid < baseFactorCount) {
        baseFactors[fid]
    } else {
        _learnedClauses[fid - baseFactorCount]
    }

    /**
     * Register a learned clause and return its assigned factor id. Performs four things:
     *   - append to [_learnedClauses];
     *   - record the clause's [lbd] in [learnedLbds] (parallel array);
     *   - grow [_refPayload] by one slot so [Clause.propagate]'s
     *     `state.refPayload[factorId]` access stays in-bounds;
     *   - install the clause's initial watch literals in [boolWatchersByLit] so it
     *     participates in the wakeup index.
     *
     * Does NOT eagerly propagate — that's the session-level
     * [PropagationSession.addLearnedClause]'s job. Returns the new factor id.
     */
    fun addLearnedClause(clause: Clause, lbd: Int, permanent: Boolean = false): Int {
        val newFid = totalFactorCount
        _learnedClauses.add(clause)
        learnedLbds.add(lbd)
        learnedPermanent.add(if (permanent) 1 else 0)
        learnedTier.add(TIER_UNSET)
        learnedUsedFlags.add(0)
        if (clause.literals.size == 2) binaryClauseCount++ // keep the #202 gate current
        _refPayload.add(null)
        val watchers = clause.initialBoolWatchers
        val blockers = clause.initialBoolWatcherBlockers
        for (i in watchers.indices) installLitWatch(watchers[i], newFid, blockers?.getOrNull(i) ?: NO_BLOCKER)
        return newFid
    }

    /** Read-only view of LBDs for tests / introspection. Parallel to [learnedClauses]. */
    fun learnedClauseLbd(learnedIndex: Int): Int = learnedLbds[learnedIndex]

    /** True iff learned clause [learnedIndex] must survive every forgetting pass. */
    fun learnedClausePermanent(learnedIndex: Int): Boolean = learnedPermanent[learnedIndex] == 1

    /** Three-tier (#201) DB tier of learned clause [learnedIndex] ([TIER_UNSET] until the
     *  reduction policy classifies it). */
    fun learnedClauseTier(learnedIndex: Int): Int = learnedTier[learnedIndex]

    /** Set the three-tier DB tier of learned clause [learnedIndex] (promotion / demotion /
     *  initial classification by the reduction policy). */
    fun setLearnedClauseTier(learnedIndex: Int, tier: Int) {
        learnedTier[learnedIndex] = tier
    }

    /** True iff learned clause [learnedIndex] was used (conflict or unit) since the last
     *  reduction. */
    fun learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean = learnedUsedFlags[learnedIndex] == 1

    /** Clear the reuse flag for learned clause [learnedIndex] — called for survivors at the
     *  end of a reduction so the next window measures fresh activity. */
    fun clearLearnedClauseUsed(learnedIndex: Int) {
        learnedUsedFlags[learnedIndex] = 0
    }

    /** Mark learned clause [fid] (a factor id; ignored when it isn't a learned clause) as
     *  used since the last reduction — it just detected a conflict or forced a unit. Drives
     *  three-tier promotion (#201). */
    internal fun noteLearnedUse(fid: Int) {
        val idx = fid - problem.numFactors
        if (idx in 0 until learnedUsedFlags.size) learnedUsedFlags[idx] = 1
    }

    /**
     * Prune the learned-clause database. The [keep] predicate decides per (learnedIndex,
     * lbd) whether to retain that clause; dropped clauses' factor ids vanish and the
     * remaining clauses are renumbered contiguously starting at `problem.numFactors`.
     * Three things are rebuilt:
     *   - [_learnedClauses] / [learnedLbds] compacted to the kept entries in order;
     *   - the learned-clause tail of [_refPayload] compacted similarly;
     *   - every list in [boolWatchersByLit] walked once, with learned factor ids
     *     remapped through `oldFid → newFid` or removed when dropped.
     *
     * Watchers' positions inside each clause's `refPayload[fid]` are watch *indices*
     * (into `clause.literals`), not factor ids — they survive the compaction unchanged.
     * Cost is amortised across infrequent calls (typical: once per Luby restart).
     */
    fun forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
        val n = _learnedClauses.size
        if (n == 0) return
        val remap = IntArray(n) // remap[i] = new learned index, or -1 if dropped
        var newCount = 0
        for (i in 0 until n) {
            remap[i] = if (keep(i, learnedLbds[i])) newCount++ else -1
        }
        if (newCount == n) return // nothing dropped

        // Compact _learnedClauses + learnedLbds in place using a two-pointer walk —
        // every kept entry slides down to its new position; tail beyond newCount is
        // trimmed at the end.
        var w = 0
        for (i in 0 until n) {
            if (remap[i] >= 0) {
                _learnedClauses[w] = _learnedClauses[i]
                learnedLbds[w] = learnedLbds[i]
                learnedPermanent[w] = learnedPermanent[i]
                learnedTier[w] = learnedTier[i]
                learnedUsedFlags[w] = learnedUsedFlags[i]
                w++
            }
        }
        while (_learnedClauses.size > newCount) _learnedClauses.removeAt(_learnedClauses.size - 1)
        learnedLbds.truncateTo(newCount)
        learnedPermanent.truncateTo(newCount)
        learnedTier.truncateTo(newCount)
        learnedUsedFlags.truncateTo(newCount)

        // Compact the learned tail of _refPayload similarly. Static-factor entries stay
        // at indices [0, problem.numFactors) untouched.
        val refBase = problem.numFactors
        var rw = refBase
        for (i in 0 until n) {
            if (remap[i] >= 0) {
                _refPayload[rw] = _refPayload[refBase + i]
                rw++
            }
        }
        while (_refPayload.size > rw) _refPayload.removeAt(_refPayload.size - 1)

        // Remap each per-literal watcher list. Static fids pass through; learned fids
        // either rewrite to their new factor id or get dropped.
        for (lit in boolWatchersByLit.indices) {
            val list = boolWatchersByLit[lit]
            val blockers = boolBlockersByLit[lit] // compacted in lockstep so indices stay aligned
            var wi = 0
            for (r in 0 until list.size) {
                val fid = list[r]
                val blocker = blockers[r]
                if (fid < refBase) {
                    blockers[wi] = blocker
                    list[wi++] = fid
                } else {
                    val newLearnedIdx = remap[fid - refBase]
                    if (newLearnedIdx >= 0) {
                        blockers[wi] = blocker
                        list[wi++] = refBase + newLearnedIdx
                    }
                }
            }
            list.truncateTo(wi)
            blockers.truncateTo(wi)
        }

        // Atom-literal watcher lists carry learned fids too — a learned clause watching a
        // bound atom registers here, not in the bool-var lists. Skipping this remap left
        // stale fids pointing past the compacted clause array, crashing the next atom wake
        // on any model whose conflicts learn atom-literal clauses.
        for (list in atomWatchersByLit.values) {
            var wi = 0
            for (r in 0 until list.size) {
                val fid = list[r]
                if (fid < refBase) {
                    list[wi++] = fid
                } else {
                    val newLearnedIdx = remap[fid - refBase]
                    if (newLearnedIdx >= 0) list[wi++] = refBase + newLearnedIdx
                }
            }
            list.truncateTo(wi)
        }

        // The compaction renumbered learned fids and shifted every list position, so the
        // back-pointer index is stale — rebuild it wholesale from the final lists. Cheap
        // relative to the rest of forget, which is itself infrequent (≈ once per restart).
        boolWatchPos.clear()
        for (lit in boolWatchersByLit.indices) {
            val list = boolWatchersByLit[lit]
            for (i in 0 until list.size) boolWatchPos.put(packWatch(list[i], lit), i)
        }

        // A conflict return leaves the propagation queues holding in-flight fids, and the
        // engine forgets at the following restart — so the queues can still carry learned
        // fids from before the compaction. Remap them like the watcher lists: a stale fid
        // surviving here indexes past the compacted clause array on the next drain.
        remapQueue(propQueue, remap, refBase)
        remapQueue(dirtyAtomFactors, remap, refBase)

        // The per-variable reason fields record which factor forced each currently-implied
        // value, learned-clause ids included. Level-0 facts — e.g. a permanent blocking nogood
        // or a learned unit that propagated at the root — survive the restart's pop-to-root, so
        // their reason fids outlive this renumber. Left unremapped, the next conflict's
        // [extractConflictFactors] would dereference a stale learned fid through [factorAt] and
        // index past the compacted clause array (the php8 crash). Remap them like the watchers:
        // a kept clause's reason rewrites to its new id, a dropped clause's reason clears to -1.
        remapReasons(boolReason, remap, refBase)
        remapReasons(intMinReason, remap, refBase)
        remapReasons(intMaxReason, remap, refBase)
    }

    /** Rewrite learned-clause factor ids stored in a per-variable reason array through [remap]
     *  (static fids `< refBase` pass through; dropped clauses' reasons clear to -1). */
    private fun remapReasons(reasons: IntArray, remap: IntArray, refBase: Int) {
        for (i in reasons.indices) {
            val fid = reasons[i]
            if (fid >= refBase) {
                val idx = fid - refBase
                reasons[i] = if (idx < remap.size && remap[idx] >= 0) refBase + remap[idx] else -1
            }
        }
    }

    /** Rewrite every learned fid in `queue` through [remap] (static fids pass through;
     *  dropped clauses' fids are removed). Preserves order. */
    private fun remapQueue(queue: IntArrayDeque, remap: IntArray, refBase: Int) {
        if (queue.isEmpty()) return
        val drained = IntArrayList(queue.size)
        while (queue.isNotEmpty()) drained.add(queue.removeFirst())
        for (i in 0 until drained.size) {
            val fid = drained[i]
            if (fid < refBase) {
                queue.addLast(fid)
            } else {
                val idx = fid - refBase
                if (idx < remap.size) {
                    val newLearnedIdx = remap[idx]
                    if (newLearnedIdx >= 0) queue.addLast(refBase + newLearnedIdx)
                }
            }
        }
    }

    /**
     * Per-literal wakeup index for factors opting into [Factor.initialBoolWatchers].
     * Slot `boolWatchersByLit[lit]` lists factor ids that should fire when literal `lit`
     * transitions to false. Sized `2 * problem.numBoolVars`; lit ids are the standard
     * [Lit.make] encoding. Populated at construction from each
     * factor's initial watch set; factors with dynamic watches (Clause) keep it in sync
     * via [moveBoolWatcher] as their watches drift during propagation.
     *
     * Like [refPayload], the index drifts across snapshot / restore on purpose. After a
     * pop the watches reflect their state at the deepest level reached — that's still
     * sound, since the invariant is "watch is on a non-false literal", and pop reverts
     * pins which only *adds* non-false literals.
     */
    internal val boolWatchersByLit: Array<IntArrayList> =
        Array(2 * problem.numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Blocking literals paired index-for-index with [boolWatchersByLit] (#200). Entry `i`
     * holds a literal that, if currently true, proves the watcher at the same index is
     * already satisfied — so [enqueueForBoolChange] can skip waking that factor entirely,
     * removing a large fraction of clause touches in the hot BCP loop on dense instances.
     * [NO_BLOCKER] means "no blocker, always fire", which is the default for every factor
     * that doesn't supply [com.eignex.klause.solver.Factor.initialBoolWatcherBlockers]
     * (e.g. cardinality), so behaviour for those is unchanged.
     *
     * Held in lockstep with [boolWatchersByLit] through every mutation ([installLitWatch],
     * [moveBoolWatcher]'s swap-pop, and the [forgetLearnedClauses] compaction). Like the
     * watcher lists it drifts across snapshot / restore; a stale blocker is always sound
     * because it is still a real literal of the factor — if true the factor really is
     * satisfied; if not we simply fire as before.
     */
    internal val boolBlockersByLit: Array<IntArrayList> =
        Array(2 * problem.numBoolVars) { IntArrayList(initialCapacity = 2) }

    /**
     * Back-pointer index for O(1) [moveBoolWatcher] removal (#42): maps `pack(fid, lit)` to
     * `fid`'s position inside `boolWatchersByLit[lit]`, so removing a moved watch is a swap-pop
     * at a known index instead of a linear scan of a possibly-long popular-literal list.
     *
     * Kept in sync at every list mutation: [installLitWatch] records the appended position,
     * [moveBoolWatcher] swap-pops and fixes the swapped element's recorded position, and
     * [forgetLearnedClauses] rebuilds it wholesale after compacting/remapping the lists. Like
     * the watcher lists themselves it drifts across snapshot/restore (pop never edits the
     * lists, so both stay mutually consistent at the deepest level reached).
     *
     * Correctness guard: [moveBoolWatcher] verifies `list[pos] == fid` before swap-popping and
     * falls back to the linear scan on any mismatch — a desynced index can never silently
     * remove the wrong watcher (the soundness hazard called out in #42).
     */
    private val boolWatchPos: MutableLongIntMap = MutableLongIntMap()

    private fun packWatch(fid: Int, lit: Int): Long = (fid.toLong() shl 32) or (lit.toLong() and 0xFFFFFFFFL)

    /**
     * Per-bool-var antecedent literals — the literal-form reason why this variable's
     * current pin was implied. For a Clause that unit-propagated `v`, the antecedents are
     * all the *other* literals in that clause, every one of which was already false at
     * pin time (that's why the clause was unit). `null` = no antecedents recorded, which
     * means either:
     *   - the var was pinned as a decision / assumption (no factor reason), or
     *   - the var was pinned by a factor that doesn't yet record antecedents
     *     (everything except [Clause] today).
     *
     * Maintained alongside [boolReason] (factor id) for first-UIP conflict analysis
     * (lazy clause generation). When the conflict analyzer hits a `null` entry it treats
     * the variable as a search-tree leaf, same as a decision.
     */
    val boolAntecedents: Array<IntArray?> = arrayOfNulls(problem.numBoolVars)

    /**
     * Bool literals that forced the most-recent tightening of `intDomains[v].min`. Null
     * when no factor recorded antecedents — analyzer treats as a leaf, same as a
     * decision. Mirrors [boolAntecedents] on the int side; used by factors that pin
     * bools based on int-domain state (e.g. [com.eignex.klause.solver.factor.ReifiedLinear])
     * so the aux pin's antecedents transitively reference the bool decisions that
     * narrowed the int domain.
     *
     * Note: only the *current* tightening is tracked. If a factor at level 1 sets
     * `v.min = 3` and another at level 2 sets `v.min = 5`, this array holds the level-2
     * tightening's antecedents — analyzer reasoning about why `v.min = 3` later is lost.
     * Full LCG with bound atoms `[v ≥ k]` solves this; for now the single-slot
     * approximation suffices for the common bool-decisions-cause-int-facts pattern.
     */
    val intMinAntecedents: Array<IntArray?> = arrayOfNulls(problem.numIntVars)

    /** Mirror of [intMinAntecedents] for the upper-bound side. */
    val intMaxAntecedents: Array<IntArray?> = arrayOfNulls(problem.numIntVars)

    // -------- Bound-atom registry (LCG with virtual int-bound literals) --------
    //
    // An "atom" represents a fact like `[x ≥ k]` or `[x ≤ k]`. Each atom gets a virtual
    // variable id past the bool var space (`numBoolVars + atomId`), so atom *literals*
    // — encoded with [Lit.make] using that virtual id — slot
    // into the same array structure the analyzer already understands. Allocation is
    // lazy: an atom only enters the registry when a factor first references it as an
    // antecedent or conflict-reason literal.
    //
    // Atom truth, level, and antecedents are *snapshotted at allocation time* from the
    // current `intDomains` / `intLevel` / `intMin/MaxAntecedents`. This avoids the cost
    // of maintaining a full history of bound changes and matches how factors already
    // use the current state to derive their reasons. The atom is then immutable for the
    // life of the snapshot; restore drops atoms allocated after the snapshot point.

    /** Atom id → (intVar, kind = 0 for GE / 1 for LE, threshold). Packed into a single
     *  long for the reverse lookup; stored separately here for fast iteration. */
    internal val atomIntVar: IntArrayList = IntArrayList()

    /** 0 = `[x ≥ k]`, 1 = `[x ≤ k]`. */
    internal val atomKind: IntArrayList = IntArrayList()

    /** Threshold value `k` for the atom. */
    internal val atomThreshold: IntArrayList = IntArrayList()

    /** Reverse lookup: packed key `(intVar << 33) | (kind << 32) | (threshold + INT_MAX)`
     *  → atomId. Allows O(1) re-allocation checks. */
    private val atomByKey: MutableLongIntMap = MutableLongIntMap()

    /** One-slot-per-`(intVar, kind)` memo in front of [atomByKey]. Reason building (the
     *  dominant CP-engine cost) cites each var's *current* bound — `atomVarGe(v, curMin)` etc.
     *  — so the same `(v, kind, threshold)` is looked up over and over until that var next
     *  tightens. Caching the last `threshold → id` per slot turns the hot [allocAtom] hash
     *  probe into an int compare. Always correct: an atom id for a given key never changes.
     *  `atomMemoId[slot] < 0` marks an empty slot. Slot = `intVar * 3 + kind`. */
    private val atomMemoThr: IntArray = IntArray(problem.numIntVars * 3)
    private val atomMemoId: IntArray = IntArray(problem.numIntVars * 3) { -1 }

    /** Per-atom-lit watcher list — factor ids that fire when this atom-lit transitions
     *  to false. Mirrors [boolWatchersByLit] for atoms; keyed by atom-lit id rather than
     *  fixed-array indexed because atoms are allocated dynamically. */
    internal val atomWatchersByLit: HashMap<Int, IntArrayList> = HashMap()

    /** For each int variable, the atoms whose truth depends on it — used to recompute
     *  atom truth and fire watchers after a successful tighten / exclude. */
    private val atomsByIntVar: HashMap<Int, VarAtomIndex> = HashMap()

    /** Per-var sorted thresholds that some factor actually watches (either polarity of
     *  the atom's literal). Bound moves wake watchers by walking only this index — the
     *  full atom table grows with every reason ever materialised, but only watched atoms
     *  need eager transition wakeups; everything else is derived on demand. */
    private val watchedAtomsByVar: HashMap<Int, VarAtomIndex> = HashMap()

    /** Per-var atom index, segregated by kind and sorted ascending by threshold. A bound
     *  move flips a contiguous threshold range per kind, so wakeups visit exactly the
     *  flipped atoms via binary search. */
    internal class VarAtomIndex {
        val geKeys = IntArrayList()
        val geIds = IntArrayList()
        val leKeys = IntArrayList()
        val leIds = IntArrayList()
        val eqKeys = IntArrayList()
        val eqIds = IntArrayList()

        fun insert(kind: Int, k: Int, id: Int) {
            val keys = keysOf(kind)
            val ids = idsOf(kind)
            val at = keys.lowerBound(k)
            keys.insertAt(at, k)
            ids.insertAt(at, id)
        }

        fun keysOf(kind: Int): IntArrayList = when (kind) {
            0 -> geKeys
            1 -> leKeys
            else -> eqKeys
        }

        fun idsOf(kind: Int): IntArrayList = when (kind) {
            0 -> geIds
            1 -> leIds
            else -> eqIds
        }
    }

    /** Register [atomId] in the watched index (idempotent per threshold/kind). */
    private fun markAtomWatched(atomId: Int) {
        val v = atomIntVar[atomId]
        val kind = atomKind[atomId]
        val k = atomThreshold[atomId]
        val idx = watchedAtomsByVar.getOrPut(v) { VarAtomIndex() }
        val keys = idx.keysOf(kind)
        val at = keys.lowerBound(k)
        if (at < keys.size && keys[at] == k) return // already tracked
        idx.insert(kind, k, atomId)
    }

    /** Factor ids woken by atom-lit transitions during the current propagation step.
     *  Drained alongside dirty-int / dirty-bool processing in [runToFixpoint]. */
    private val dirtyAtomFactors: IntArrayDeque =
        IntArrayDeque(initialCapacity = 8)

    private fun atomKey(intVar: Int, kind: Int, threshold: Int): Long {
        // Threshold can be negative; bias by Int.MIN_VALUE to keep it non-negative within
        // the lower 32 bits. Kind (0..2) takes bits 32..33; intVar takes bits 34..63.
        val biased = threshold.toLong() - Int.MIN_VALUE.toLong()
        return (intVar.toLong() shl 34) or (kind.toLong() shl 32) or biased
    }

    /** Allocate (or look up) the atom for `[intVar ≥ threshold]` and return its virtual
     *  variable id (past the bool var space). Pair with [Lit.make]
     *  to encode as a positive or negative literal. */
    fun atomVarGe(intVar: Int, threshold: Int): Int = allocAtom(intVar, kind = 0, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar ≤ threshold]`. */
    fun atomVarLe(intVar: Int, threshold: Int): Int = allocAtom(intVar, kind = 1, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar = value]`. The negative-polarity literal
     *  of this atom encodes `[intVar ≠ value]`; share the same atom id rather than allocating
     *  a dedicated `Ne` kind. */
    fun atomVarEq(intVar: Int, value: Int): Int = allocAtom(intVar, kind = 2, threshold = value)

    /**
     * Allocate a *relaxed* bound atom (`kind` 0 = `[v ≥ threshold]`, 1 = `[v ≤ threshold]`)
     * for use as a conflict-reason leaf: when the atom is **freshly** allocated and currently
     * true, pin its level to [level] (the level the bound first reached [threshold], from the
     * history) and clear its antecedent so the analyzer keeps it as a leaf rather than the
     * over-attributed current [intLevel]. An already-existing atom is returned untouched (its
     * level is ≥ the true level — sound, just not improved). Returns the virtual var id.
     *
     * Caller guarantees the atom is currently true (the relaxed bound is implied by the
     * current domain) and [level] is exactly the level it became true — both required for the
     * learned clause's backjump level to be sound.
     */
    @Suppress("UNUSED_PARAMETER")
    fun atomBoundLeafIfNew(intVar: Int, kind: Int, threshold: Int, level: Int): Int =
        allocAtom(intVar, kind = kind, threshold = threshold)

    /** Encode a *positive* atom-lit (the atom holds) directly as a [Lit]-style id. */
    fun atomLitGe(intVar: Int, threshold: Int): Int = Lit.make(atomVarGe(intVar, threshold), true)

    /** Literal for the bound atom `intVar ≤ threshold`. */
    fun atomLitLe(intVar: Int, threshold: Int): Int = Lit.make(atomVarLe(intVar, threshold), true)

    /** Literal for the value atom `intVar = value`. */
    fun atomLitEq(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), true)

    /** Literal for the value atom `intVar ≠ value`. */
    fun atomLitNe(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), false)

    /** True iff `v` is an atom-id (past the bool var space). Used by the conflict
     *  analyzer to dispatch between bool-trail and atom-table lookups. */
    fun isAtomVar(v: Int): Boolean = v >= problem.numBoolVars

    /** Translate a virtual atom-var id back to its 0-based atom index. */
    fun atomIdOf(v: Int): Int = v - problem.numBoolVars

    /** Current truth of an atom — derived fresh from `intDomains`, not the
     *  snapshot-at-allocation `atomValue`. Returns `null` when undetermined (the bound
     *  isn't either side-decided yet). Used by [litTrue] / [litFalse] / [pinLit]. */
    fun atomCurrentTruth(atomId: Int): Boolean? =
        atomTruthOf(atomIntVar[atomId], atomKind[atomId], atomThreshold[atomId])

    /**
     * Decision level at which atom [atomId]'s **current** truth was established on the
     * **current** search path — recomputed from the bound-change and hole-carve histories,
     * which are truncated on every undo and re-pushed on every move, so they always
     * reflect the current path. An undetermined atom reports the live decision count
     * (conservative: it can only constrain at the current level).
     */
    internal fun atomLevelForConflict(atomId: Int): Int {
        val v = atomIntVar[atomId]
        val k = atomThreshold[atomId]
        val truth = atomCurrentTruth(atomId) ?: return levelToDecisionVar.size
        return when (atomKind[atomId]) {
            0 -> if (truth) minLevelForGe(v, k) else maxLevelForLe(v, k - 1)

            // v ≥ k
            1 -> if (truth) maxLevelForLe(v, k) else minLevelForGe(v, k + 1)

            // v ≤ k
            2 -> if (truth) { // v = k : true when the later of the two bounds reached k
                maxOf(minLevelForGe(v, k), maxLevelForLe(v, k))
            } else { // v ≠ k : established at the level k left the domain
                val d = intDomains[v]
                when {
                    k < d.min -> minLevelForGe(v, k + 1)
                    k > d.max -> maxLevelForLe(v, k - 1)
                    else -> holeLevelFor(v, k) // interior hole — carve history
                }
            }

            else -> levelToDecisionVar.size
        }
    }

    /** True iff bool [v] is currently assigned (by decision or propagation). Primitive — lets
     *  hot factor loops test assignment without the `Boolean?` box that `boolValues[v]` allocates.
     *  Pair with [boolValueAt] (only meaningful when this returns true). */
    fun boolAssignedAt(v: Int): Boolean = boolAssigned.get(v)

    /** Stored value of bool [v]; meaningful only when [boolAssignedAt] is true (undefined
     *  otherwise). Primitive companion to [boolAssignedAt] for box-free hot-loop reads. */
    fun boolValueAt(v: Int): Boolean = boolValueBits.get(v)

    /** Unified truth lookup over bool literals and atom-lit literals. Returns `null`
     *  when undetermined. Pair with [Lit.evaluate] / explicit polarity branching to
     *  reason about literal truth. */
    fun litTruth(lit: Int): Boolean? {
        val v = Lit.variable(lit)
        val raw: Boolean? = if (v < problem.numBoolVars) {
            boolValues[v]
        } else {
            atomCurrentTruth(atomIdOf(v))
        }
        if (raw == null) return null
        return Lit.evaluate(lit, raw)
    }

    /** True iff [lit] is currently `true` (returns false when undetermined).
     *
     *  Plain bool literals (the dominant BCP case) read the packed bits directly instead of
     *  routing through [litTruth]'s boxed `Boolean?` — a literal is true iff its variable is
     *  assigned and the stored value matches the literal's polarity. Atom literals fall back to
     *  the general path. */
    fun litTrue(lit: Int): Boolean {
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            return boolAssigned.get(v) && (boolValueBits.get(v) == Lit.isPositive(lit))
        }
        return litTruth(lit) == true
    }

    /** True iff [lit] is currently `false` (returns false when undetermined). Bool-literal fast
     *  path mirrors [litTrue]; assigned with the value opposing the literal's polarity. */
    fun litFalse(lit: Int): Boolean {
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            return boolAssigned.get(v) && (boolValueBits.get(v) != Lit.isPositive(lit))
        }
        return litTruth(lit) == false
    }

    /** Assign [lit] to true, recording [antecedents]. Dispatches between bool pins
     *  ([pinBool]) and atom assignment (re-derived as the corresponding int-bound
     *  tighten on the underlying var). Returns `false` on conflict. */
    fun pinLit(lit: Int, antecedents: IntArray? = null): Boolean {
        val v = Lit.variable(lit)
        val pos = Lit.isPositive(lit)
        if (v < problem.numBoolVars) return pinBoolImpl(v, pos, antecedents)
        val atomId = atomIdOf(v)
        val intVar = atomIntVar[atomId]
        val k = atomThreshold[atomId]
        return when (atomKind[atomId]) {
            0 -> if (pos) {
                tightenIntMinImpl(intVar, k, antecedents) // [v ≥ k] true → v.min ≥ k
            } else {
                tightenIntMaxImpl(intVar, k - 1, antecedents) // [v ≥ k] false → v ≤ k-1
            }

            1 -> if (pos) {
                tightenIntMaxImpl(intVar, k, antecedents) // [v ≤ k] true → v.max ≤ k
            } else {
                tightenIntMinImpl(intVar, k + 1, antecedents) // [v ≤ k] false → v ≥ k+1
            }

            2 -> if (pos) {
                tightenIntMinImpl(intVar, k, antecedents) && // [v = k] true → v = k
                    tightenIntMaxImpl(intVar, k, antecedents)
            } else {
                excludeIntValueImpl(intVar, k, antecedents) // [v = k] false → v ≠ k
            }

            else -> error("unknown atom kind")
        }
    }

    private fun allocAtom(intVar: Int, kind: Int, threshold: Int): Int {
        val slot = intVar * 3 + kind
        if (atomMemoId[slot] >= 0 && atomMemoThr[slot] == threshold) {
            return problem.numBoolVars + atomMemoId[slot]
        }
        val key = atomKey(intVar, kind, threshold)
        val existing = atomByKey.getOrDefault(key, -1)
        if (existing >= 0) {
            atomMemoThr[slot] = threshold
            atomMemoId[slot] = existing
            return problem.numBoolVars + existing
        }
        val id = atomIntVar.size
        atomIntVar.add(intVar)
        atomKind.add(kind)
        atomThreshold.add(threshold)
        atomByKey.put(key, id)
        atomsByIntVar.getOrPut(intVar) { VarAtomIndex() }.insert(kind, threshold, id)
        atomMemoThr[slot] = threshold
        atomMemoId[slot] = id
        return problem.numBoolVars + id
    }

    /**
     * Antecedents of atom [atomId], derived on demand: a true bound atom resolves to the
     * recorded reason of the move that first established it (the bound histories); a
     * false bound atom and a bound-excluded eq atom cite the opposing bound atom, which
     * the analyzer unfolds through its own history; a true eq atom cites both endpoint
     * atoms; an interior-hole eq atom resolves to the carve's recorded reason. `null`
     * marks a root/bake fact or an undetermined atom — the analyzer keeps such literals
     * instead of resolving through them.
     */
    internal fun atomAntecedentsDerived(atomId: Int): IntArray? {
        val v = atomIntVar[atomId]
        val k = atomThreshold[atomId]
        val truth = atomCurrentTruth(atomId) ?: return null
        return when (atomKind[atomId]) {
            0 -> if (truth) minReasonFor(v, k) else intArrayOf(Lit.make(atomVarLe(v, k - 1), false))

            1 -> if (truth) maxReasonFor(v, k) else intArrayOf(Lit.make(atomVarGe(v, k + 1), false))

            2 -> if (truth) {
                composeIntVarAtomAntecedents(intArrayOf(v))
            } else {
                val d = intDomains[v]
                when {
                    k < d.min -> intArrayOf(Lit.make(atomVarGe(v, k + 1), false))
                    k > d.max -> intArrayOf(Lit.make(atomVarLe(v, k - 1), false))
                    else -> holeReasonFor(v, k)
                }
            }

            else -> null
        }
    }

    private fun atomTruthOf(v: Int, kind: Int, k: Int): Boolean? {
        val d = intDomains[v]
        return when (kind) {
            0 -> when {
                d.min >= k -> true
                d.max < k -> false
                else -> null
            }

            1 -> when {
                d.max <= k -> true
                d.min > k -> false
                else -> null
            }

            2 -> when {
                d.min == d.max && d.min == k -> true

                // singleton {k} → eq true
                k !in d -> false

                // k absent → eq false
                else -> null
            }

            else -> error("unknown atom kind")
        }
    }

    /**
     * After a successful [tightenIntMinImpl] / [tightenIntMaxImpl] / [excludeIntValueImpl]
     * on int var `v`, recompute the truth of every atom that depends on `v`. Atoms whose
     * truth flipped get their level / antecedents updated, and watchers on the now-false
     * atom-lit are scheduled to fire.
     *
     * A move carries two reason sets: [antNear] justifies the requested bound, [antFar]
     * additionally carries the hole-crossing chain when the landed bound snapped further
     * (they alias without a snap). Each flipped atom takes the weakest set that still
     * implies its truth, split by threshold against the requested [reqMin] / [reqMax].
     * An eq atom flipping TRUE needs BOTH endpoint premises while the move supplied one,
     * so it cites the two bound atoms instead.
     */
    private fun propagateAtomsForVar(
        v: Int,
        @Suppress("UNUSED_PARAMETER") antNear: IntArray?,
        @Suppress("UNUSED_PARAMETER") antFar: IntArray? = antNear,
        @Suppress("UNUSED_PARAMETER") reqMin: Int = intDomains[v].min,
        @Suppress("UNUSED_PARAMETER") reqMax: Int = intDomains[v].max,
        oldMin: Int,
        oldMax: Int,
        carved: Int = NO_CARVE,
    ) {
        val idx = watchedAtomsByVar[v] ?: return
        val d = intDomains[v]
        val newMin = d.min
        val newMax = d.max
        if (newMin > oldMin) {
            visitAtomRange(idx.geKeys, idx.geIds, oldMin + 1, newMin) { id -> wakeAtom(id, true) }
            visitAtomRange(idx.leKeys, idx.leIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
            visitAtomRange(idx.eqKeys, idx.eqIds, oldMin, newMin - 1) { id -> wakeAtom(id, false) }
        }
        if (newMax < oldMax) {
            visitAtomRange(idx.leKeys, idx.leIds, newMax, oldMax - 1) { id -> wakeAtom(id, true) }
            visitAtomRange(idx.geKeys, idx.geIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
            visitAtomRange(idx.eqKeys, idx.eqIds, newMax + 1, oldMax) { id -> wakeAtom(id, false) }
        }
        if (carved != NO_CARVE && carved in (newMin + 1) until newMax) {
            visitAtomRange(idx.eqKeys, idx.eqIds, carved, carved) { id -> wakeAtom(id, false) }
        }
        if (newMin == newMax && (newMin > oldMin || newMax < oldMax)) {
            visitAtomRange(idx.eqKeys, idx.eqIds, newMin, newMin) { id -> wakeAtom(id, true) }
        }
    }

    private inline fun visitAtomRange(
        keys: IntArrayList,
        ids: IntArrayList,
        from: Int,
        to: Int,
        action: (atomId: Int) -> Unit,
    ) {
        if (to < from || keys.size == 0) return
        var i = keys.lowerBound(from)
        while (i < keys.size && keys[i] <= to) {
            action(ids[i])
            i++
        }
    }

    /** Wake the watchers of [atomId]'s now-false literal after its truth flipped to
     *  [newT]. Truth itself is never stored — it is derived from the domains on read. */
    private fun wakeAtom(atomId: Int, newT: Boolean) {
        val falseLit = Lit.make(problem.numBoolVars + atomId, !newT)
        val w = atomWatchersByLit[falseLit] ?: return
        for (j in 0 until w.size) dirtyAtomFactors.addLast(w[j])
    }

    /** Install [fid] as a watcher of [lit]. Dispatches between [boolWatchersByLit]
     *  (bool var space) and [atomWatchersByLit] (atom var space). */
    internal fun installLitWatch(lit: Int, fid: Int, blocker: Int = NO_BLOCKER) {
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            val list = boolWatchersByLit[lit]
            boolWatchPos.put(packWatch(fid, lit), list.size) // position of the about-to-append entry
            list.add(fid)
            boolBlockersByLit[lit].add(blocker) // index-aligned with the watcher just appended
        } else {
            val list = atomWatchersByLit.getOrPut(lit) { IntArrayList(initialCapacity = 2) }
            list.add(fid)
            markAtomWatched(atomIdOf(v))
        }
    }

    /**
     * Chronological journal of bool pins, decisions and implications interleaved. Used by
     * [com.eignex.klause.solver.propagation.ConflictAnalyzer] to walk the implication
     * graph in reverse pin order — the standard 1UIP loop needs to resolve against the
     * *most recently pinned* variable in the current conflict, which requires this
     * append-only trail.
     */
    internal val boolPinOrder: IntArrayList =
        IntArrayList(initialCapacity = problem.numBoolVars.coerceAtLeast(8))

    // -------- Undo trail (replaces per-level full-array snapshots) --------
    //
    // Each mutation that a pop must rewind appends one record here recording the cell's
    // *prior* value. A pop replays records from the top down to a [LevelMark]'s [undoSize]
    // — O(changes-since-mark) instead of the old snapshot/restore's O(numVars) per level.
    //
    // Records are stored column-wise across parallel lists (no per-record object alloc).
    // Two record kinds:
    //   tag 0 — bool pin: a bool was assigned at this level (prior state is always
    //           unassigned, since [pinBoolImpl] only proceeds when the var was free), so
    //           only [undoVar] is needed; the int/obj columns are padding.
    //   tag 1 — int change: a tighten / exclude replaced the var's domain. The full prior
    //           int-var state is recorded so replay restores it exactly even when the same
    //           var is narrowed several times within a level.
    //
    // Atom-table mutations are *not* logged: [undoTo] re-derives surviving atoms' truth
    // from the restored int domains (matching the old `restore`), and atoms allocated
    // after a mark are truncated wholesale. atomLevel / atomAntecedents drift across pops,
    // exactly as they did under the snapshot scheme (advisory, like watches).

    /** Per-variable unassign sink invoked by [undoTo]; see its doc. Null = no subscriber. */
    var unassignListener: ((Int) -> Unit)? = null
    private val undoTag = IntArrayList()
    private val undoVar = IntArrayList()
    private val undoLevel = IntArrayList() // int: prior intLevel
    private val undoMinReason = IntArrayList() // int: prior intMinReason
    private val undoMaxReason = IntArrayList() // int: prior intMaxReason
    private val undoDomain = ArrayList<IntDomain?>() // int: prior intDomains[v]
    private val undoMinAnt = ArrayList<IntArray?>() // int: prior intMinAntecedents
    private val undoMaxAnt = ArrayList<IntArray?>() // int: prior intMaxAntecedents
    private val undoMinHistLen = IntArrayList() // int: prior minHist length for the var (history truncation)
    private val undoMaxHistLen = IntArrayList() // int: prior maxHist length for the var
    private val undoHoleHistLen = IntArrayList() // int: prior holeHist length for the var

    /** Shared empty payload map for marks taken when no [SnapshottablePayload] is live —
     *  avoids a per-push allocation in the common (no Table/Mdd) case. `emptyMap()` returns
     *  a singleton, so this never allocates. Read-only: [undoTo] only iterates it. */
    private val emptyPayloads: Map<Int, SnapshottablePayload> = emptyMap()

    /** Reusable conflict analyzer for this state — one instance whose scratch buffers
     *  persist across conflicts instead of reallocating per analysis. Single-threaded
     *  session, so [LazyThreadSafetyMode.NONE] is safe and skips the sync overhead.
     *  Created on the first conflict. */
    internal val conflictAnalyzer: ConflictAnalyzer by lazy(LazyThreadSafetyMode.NONE) { ConflictAnalyzer(this) }

    /** When false, mutators skip undo-log recording. Default off so the one-shot
     *  propagation path ([Problem.propagate]) and bake-time fixpoint — neither of which
     *  backtracks — pay nothing. [PropagationSession] flips it true after bake, before its
     *  first push. */
    var undoLogging: Boolean = false

    private fun logBoolPin(v: Int) {
        undoTag.add(0)
        undoVar.add(v)
        undoLevel.add(0)
        undoMinReason.add(0)
        undoMaxReason.add(0)
        undoDomain.add(null)
        undoMinAnt.add(null)
        undoMaxAnt.add(null)
        undoMinHistLen.add(0)
        undoMaxHistLen.add(0)
        undoHoleHistLen.add(0)
    }

    /** Capture int var `v`'s full prior state. Must be called *before* the mutation. */
    private fun logIntChange(v: Int) {
        undoTag.add(1)
        undoVar.add(v)
        undoLevel.add(intLevel[v])
        undoMinReason.add(intMinReason[v])
        undoMaxReason.add(intMaxReason[v])
        undoDomain.add(intDomains[v])
        undoMinAnt.add(intMinAntecedents[v])
        undoMaxAnt.add(intMaxAntecedents[v])
        undoMinHistLen.add(minHistVal[v]?.size ?: 0)
        undoMaxHistLen.add(maxHistVal[v]?.size ?: 0)
        undoHoleHistLen.add(holeHistVal[v]?.size ?: 0)
    }

    /** Journal an interior carve as just the carved value: replay re-inserts it instead
     *  of restoring a retained domain snapshot, whose O(holes) cost per carve made deep
     *  searches on wide hole-list domains quadratic in retained heap (tag 2). Columns:
     *  [undoMinReason] = carved value, [undoLevel] = prior intLevel,
     *  [undoMaxReason] = prior holeHist length. */
    private fun logIntCarve(v: Int, value: Int) {
        undoTag.add(2)
        undoVar.add(v)
        undoLevel.add(intLevel[v])
        undoMinReason.add(value)
        undoMaxReason.add(holeHistVal[v]?.size ?: 0)
        undoDomain.add(null)
        undoMinAnt.add(null)
        undoMaxAnt.add(null)
        undoMinHistLen.add(0)
        undoMaxHistLen.add(0)
        undoHoleHistLen.add(0)
    }

    private fun truncateUndo(n: Int) {
        undoTag.truncateTo(n)
        undoVar.truncateTo(n)
        undoLevel.truncateTo(n)
        undoMinReason.truncateTo(n)
        undoMaxReason.truncateTo(n)
        while (undoDomain.size > n) undoDomain.removeAt(undoDomain.size - 1)
        while (undoMinAnt.size > n) undoMinAnt.removeAt(undoMinAnt.size - 1)
        while (undoMaxAnt.size > n) undoMaxAnt.removeAt(undoMaxAnt.size - 1)
        undoMinHistLen.truncateTo(n)
        undoMaxHistLen.truncateTo(n)
        undoHoleHistLen.truncateTo(n)
    }

    /** Current undo-log size. A [LevelMark] captures this; iterating [undoVarAt] /
     *  [undoIsBoolAt] over `[base, undoTop)` enumerates exactly the variables mutated since
     *  position `base` — used by [PropagationSession] to compute the implied-fact diff of a
     *  push incrementally instead of scanning every variable. */
    val undoTop: Int get() = undoTag.size

    /** Variable id recorded by undo record `i`. */
    fun undoVarAt(i: Int): Int = undoVar[i]

    /** True iff undo record `i` is a bool pin (vs. an int-domain change). */
    fun undoIsBoolAt(i: Int): Boolean = undoTag[i] == 0

    init {
        for (fid in 0 until problem.numFactors) {
            val factor = problem.factors[fid]
            val watchers = factor.initialBoolWatchers ?: continue
            val blockers = factor.initialBoolWatcherBlockers
            for (i in watchers.indices) {
                installLitWatch(watchers[i], fid, blockers?.getOrNull(i) ?: NO_BLOCKER)
            }
        }
    }

    /**
     * Move factor `[factorId]`'s registration from [oldLit] to [newLit] in
     * [boolWatchersByLit]. Called by watcher-using factors when they relocate a watch
     * during propagation. The removal scans [oldLit]'s slot (typically a handful of
     * entries) and swap-and-pops; the insert is O(1).
     */
    fun moveBoolWatcher(factorId: Int, oldLit: Int, newLit: Int, blocker: Int = NO_BLOCKER) {
        if (oldLit == newLit) return
        val oldV = Lit.variable(oldLit)
        if (oldV < problem.numBoolVars) {
            removeBoolWatch(factorId, oldLit)
        } else {
            atomWatchersByLit[oldLit]?.removeValue(factorId)
        }
        // Install on new, carrying the blocking literal supplied by the watcher-using factor
        // (#200). Defaults to NO_BLOCKER for factors that don't track blockers.
        installLitWatch(newLit, factorId, blocker)
    }

    /** O(1) removal of `[factorId]` from `boolWatchersByLit[lit]` via the [boolWatchPos]
     *  back-pointer: swap the tail entry into the freed slot and fix its recorded position.
     *  Verifies the recorded position actually holds `[factorId]`; on any miss/mismatch falls
     *  back to the linear swap-remove and self-heals the index, so a stale back-pointer can
     *  never remove the wrong watcher. */
    private fun removeBoolWatch(factorId: Int, lit: Int) {
        val list = boolWatchersByLit[lit]
        val blockers = boolBlockersByLit[lit]
        val key = packWatch(factorId, lit)
        // Remove-and-read in a single table walk; the key is gone from the index either way.
        val recorded = boolWatchPos.removeAndGet(key, -1)
        if (recorded < 0 || recorded >= list.size || list[recorded] != factorId) {
            // Index miss/desync — fall back to a linear scan, swap-pop both lists in lockstep
            // at the found index, and resync this lit's positions.
            var pos = -1
            for (i in 0 until list.size) {
                if (list[i] == factorId) {
                    pos = i
                    break
                }
            }
            if (pos >= 0) swapPopWatch(list, blockers, pos)
            resyncBoolWatchPos(lit)
            return
        }
        val last = list.size - 1
        if (recorded != last) {
            val movedFid = list[last]
            boolWatchPos.put(packWatch(movedFid, lit), recorded)
        }
        swapPopWatch(list, blockers, recorded)
    }

    /** Swap-pop index [pos] from a watcher list and its parallel blocker list in lockstep,
     *  keeping the two index-aligned. The caller fixes [boolWatchPos] for the moved entry. */
    private fun swapPopWatch(list: IntArrayList, blockers: IntArrayList, pos: Int) {
        val last = list.size - 1
        if (pos != last) {
            list[pos] = list[last]
            blockers[pos] = blockers[last]
        }
        list.truncateTo(last)
        blockers.truncateTo(last)
    }

    /** Recompute every recorded position for [lit]'s watcher list (used by the self-heal
     *  fallback in [removeBoolWatch]). */
    private fun resyncBoolWatchPos(lit: Int) {
        val list = boolWatchersByLit[lit]
        for (i in 0 until list.size) boolWatchPos.put(packWatch(list[i], lit), i)
    }

    init {
        seeded = seedAssumptions(assumptions)
    }

    /** Push every pin in `a` as a fresh decision; return `false` (so [seeded] becomes
     *  `false`) on the first contradiction. Direct primitive-array iteration so the early
     *  exit is a clean `return`. */
    private fun seedAssumptions(a: Assumptions): Boolean {
        val bk = a.boolKeys
        val bv = a.boolValues
        for (i in bk.indices) {
            if (!pinBoolAsDecision(bk[i], bv[i])) return false
        }
        val ik = a.intKeys
        val iv = a.intValues
        for (i in ik.indices) {
            if (!setIntAsDecision(ik[i], iv[i])) return false
        }
        // Non-singleton bound tightenings ride at the same decision level as int pins —
        // they're seed-time inputs, not propagated conclusions. Each takes its own level
        // so the conflict analyzer can attribute backjumps to the specific tightening.
        val minK = a.intMinKeys
        val minV = a.intMinValues
        for (i in minK.indices) {
            levelToDecisionVar.add(problem.numBoolVars + minK[i])
            currentLevel = levelToDecisionVar.size
            currentFactor = -1
            if (!tightenIntMinImpl(minK[i], minV[i], null)) return false
        }
        val maxK = a.intMaxKeys
        val maxV = a.intMaxValues
        for (i in maxK.indices) {
            levelToDecisionVar.add(problem.numBoolVars + maxK[i])
            currentLevel = levelToDecisionVar.size
            currentFactor = -1
            if (!tightenIntMaxImpl(maxK[i], maxV[i], null)) return false
        }
        val holeIds = a.intHoleVarIds
        val holeVals = a.intHoleValues
        for (i in holeIds.indices) {
            levelToDecisionVar.add(problem.numBoolVars + holeIds[i])
            currentLevel = levelToDecisionVar.size
            currentFactor = -1
            if (!excludeIntValueImpl(holeIds[i], holeVals[i], null)) return false
        }
        return true
    }

    /**
     * Push a bool var as a new decision: bumps the level and pins it. Used by the driver to
     * seed input assumptions and by [PropagationSession] to push branches.
     */
    fun pinBoolAsDecision(v: Int, value: Boolean): Boolean {
        levelToDecisionVar.add(v)
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        return pinBoolImpl(v, value, antecedents = null)
    }

    /** Push an int var as a new decision. */
    fun setIntAsDecision(v: Int, value: Int): Boolean {
        levelToDecisionVar.add(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        return setIntImpl(v, value, null)
    }

    /**
     * Push an int upper-bound tightening (`v ≤ hi`) as a new decision. Unlike
     * [setIntAsDecision], this records a *single* bound atom at the new decision level, so
     * conflicts seeded by it have a single 1UIP literal there (an equality pin contributes
     * two same-level bound atoms that 1UIP cannot collapse). The caller must ensure `hi`
     * strictly narrows the domain (`hi in d.min until d.max`) so the level is non-empty.
     */
    fun setIntMaxAsDecision(v: Int, hi: Int): Boolean {
        levelToDecisionVar.add(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        return tightenIntMaxImpl(v, hi, null)
    }

    /** Push an int lower-bound tightening (`v ≥ lo`) as a new decision. See [setIntMaxAsDecision]. */
    fun setIntMinAsDecision(v: Int, lo: Int): Boolean {
        levelToDecisionVar.add(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        currentFactor = -1
        return tightenIntMinImpl(v, lo, null)
    }

    /** Force bool `v` to [value]; returns false on conflict. */
    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolImpl(v, value, antecedents = null)

    /** Variant that records [antecedents] — the literals whose truth values implied this
     *  pin. Lets the conflict analyzer reconstruct the propagation chain backwards. Pass
     *  `null` (the default no-arg form) when the factor doesn't track antecedents — that's
     *  fine, the analyzer just treats this pin as a leaf in the implication graph. */
    fun pinBool(v: Int, value: Boolean, antecedents: IntArray?): Boolean = pinBoolImpl(v, value, antecedents)

    /** Raise int `v`'s lower bound to [lo]; returns false on conflict. */
    fun tightenIntMin(v: Int, lo: Int): Boolean = tightenIntMinImpl(v, lo, null)

    /** Variant that records [antecedents] — bool literals (false in the current state)
     *  whose collective truth forced this lower-bound tightening. Used by the conflict
     *  analyzer to walk the implication graph backwards through int-domain factors. */
    fun tightenIntMin(v: Int, lo: Int, antecedents: IntArray?): Boolean = tightenIntMinImpl(v, lo, antecedents)

    /** Lower int `v`'s upper bound to [hi]; returns false on conflict. */
    fun tightenIntMax(v: Int, hi: Int): Boolean = tightenIntMaxImpl(v, hi, null)

    /** As [tightenIntMax] with explicit conflict [antecedents]. */
    fun tightenIntMax(v: Int, hi: Int, antecedents: IntArray?): Boolean = tightenIntMaxImpl(v, hi, antecedents)

    /** Pin int `v` to [value]; returns false on conflict. */
    fun setInt(v: Int, value: Int): Boolean = setIntImpl(v, value, null)

    /** As [setInt] with explicit conflict [antecedents]. */
    fun setInt(v: Int, value: Int, antecedents: IntArray?): Boolean = setIntImpl(v, value, antecedents)

    /** Punch a hole in `v`'s domain at [value]. Returns `true` on success (including the
     *  no-op case when [value] is already absent), `false` on conflict (would empty the
     *  domain). When [value] is at the current endpoint, this is equivalent to a
     *  bound-tighten by one; when it's interior, it transitions the domain to sparse
     *  representation. */
    fun excludeIntValue(v: Int, value: Int): Boolean = excludeIntValueImpl(v, value, null)

    /** Forbid [value] for int `v`; returns false on conflict. */
    fun excludeIntValue(v: Int, value: Int, antecedents: IntArray?): Boolean =
        excludeIntValueImpl(v, value, antecedents)

    private fun pinBoolImpl(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
        // Read the packed bits directly rather than through the boxing `boolValues[v]`
        // accessor — this runs once per pin (≈ once per propagation) and the `Boolean?`
        // box dominated the BCP CPU profile.
        if (boolAssigned.get(v)) {
            if (boolValueBits.get(v) == value) return true
            // Conflict — record levels of both contributors, and seed the factor core with
            // the prior pin's reason (whichever factor forced `cur`, if any) plus the
            // currently-running factor (if any). Also record [v] so the analyzer can
            // synthesise a clause-form seed from the prior pin's antecedents when this
            // is a decision-level vs prior-pin contradiction (currentFactor == -1).
            recordConflictLevels(boolLevel[v], currentLevel)
            seedConflictFactor(boolReason[v])
            seedConflictFactor(currentFactor)
            if (currentFactor < 0) lastDecisionConflictVar = v
            return false
        }
        if (undoLogging) logBoolPin(v)
        if (currentFactor >= 0) propagations++
        if (value) boolValueBits.set(v) else boolValueBits.clear(v)
        boolAssigned.set(v)
        boolLevel[v] = currentLevel
        boolReason[v] = currentFactor
        noteLearnedUse(currentFactor) // a learned clause that forces a unit counts as reused (#201)
        boolAntecedents[v] = antecedents
        boolPinOrder.add(v)
        dirtyBools.addLast(v)
        return true
    }

    /** Append the prior bound atom's clause-form literal to [base] when the prior bound was
     *  itself search-derived ([cite]); a root-level bound is a global fact and needs none. */
    private fun appendPriorBound(priorLit: Int, cite: Boolean, base: IntArray?): IntArray? {
        if (!cite) return base
        if (base != null && base.contains(priorLit)) return base
        val out = IntArray((base?.size ?: 0) + 1)
        base?.copyInto(out)
        out[out.size - 1] = priorLit
        return out
    }

    /**
     * Antecedents for a bound move that snapped past interior holes. The supplied [base] reason
     * justifies the *requested* bound only; when the hole-aware domain update lands the endpoint
     * further (because the values in between are excluded), the deduction additionally rests on
     * those exclusions. Each value in [crossed] that exists in the root domain was carved out
     * during search, so its positive eq-atom literal joins the reason — omitting it makes the
     * recorded implication stronger than what was actually derived, and clauses learned through
     * it can prune feasible assignments. Values absent from the root domain are global facts and
     * need no citation.
     */
    private fun antecedentsAcrossHoles(v: Int, crossed: IntRange, base: IntArray?): IntArray? {
        var out: IntArrayList? = null
        val orig = problem.intDomains[v]
        for (value in crossed) {
            if (value in orig) {
                val o = out ?: IntArrayList().also { fresh ->
                    out = fresh
                    base?.forEach { fresh.add(it) }
                }
                o.add(Lit.make(atomVarEq(v, value), true))
            }
        }
        return out?.toIntArray() ?: base
    }

    private fun tightenIntMinImpl(v: Int, lo: Int, antecedents: IntArray?): Boolean {
        val d = intDomains[v]
        if (lo <= d.min) return true
        if (lo > d.max) {
            // Two-sided narrowing emptied the domain: the existing upper bound came from
            // `intMaxReason[v]`, and `currentFactor` is the one trying to push the lower
            // past it. Both go into the core seed.
            recordConflictLevels(intLevel[v], currentLevel)
            seedConflictFactor(intMaxReason[v])
            seedConflictFactor(currentFactor)
            return false
        }
        if (undoLogging) logIntChange(v)
        if (currentFactor >= 0) propagations++
        // Preserve interior holes via the sparse-aware constructor path. For contiguous
        // domains this is functionally identical to `IntDomain(lo, d.max)`.
        val newDomain = d.withMinAtLeast(lo)
        // A landing value inside a hole snaps the min further. The snapped bound rests on
        // the requested bound plus the crossed holes; without the requested-bound atom in
        // the chain a decision's contribution vanishes from conflict analysis.
        val ant = if (newDomain.min > lo) {
            appendPriorBound(
                Lit.make(atomVarGe(v, lo), false),
                lo > problem.intDomains[v].min,
                antecedentsAcrossHoles(v, lo until newDomain.min, antecedents),
            )
        } else {
            antecedents
        }
        intDomains[v] = newDomain
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = ant
        // Record the post-snap bound, not the requested one: when the landing value sits in
        // a hole the actual min jumps further, and minLevelForGe must attribute every value
        // in the jumped-over range to this level.
        pushMinHist(v, newDomain.min, currentLevel, antecedents, ant, lo)
        dirtyInts.addLast(v)
        propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMin = lo, oldMin = d.min, oldMax = d.max)
        return true
    }

    private fun tightenIntMaxImpl(v: Int, hi: Int, antecedents: IntArray?): Boolean {
        val d = intDomains[v]
        if (hi >= d.max) return true
        if (hi < d.min) {
            recordConflictLevels(intLevel[v], currentLevel)
            seedConflictFactor(intMinReason[v])
            seedConflictFactor(currentFactor)
            return false
        }
        if (undoLogging) logIntChange(v)
        if (currentFactor >= 0) propagations++
        val newDomain = d.withMaxAtMost(hi)
        // Snap chaining mirrors [tightenIntMinImpl]: requested-bound atom + crossed holes.
        val ant = if (newDomain.max < hi) {
            appendPriorBound(
                Lit.make(atomVarLe(v, hi), false),
                hi < problem.intDomains[v].max,
                antecedentsAcrossHoles(v, (newDomain.max + 1)..hi, antecedents),
            )
        } else {
            antecedents
        }
        intDomains[v] = newDomain
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = ant
        // Post-snap bound for the same reason as the min side.
        pushMaxHist(v, newDomain.max, currentLevel, antecedents, ant, hi)
        dirtyInts.addLast(v)
        propagateAtomsForVar(v, antNear = antecedents, antFar = ant, reqMax = hi, oldMin = d.min, oldMax = d.max)
        return true
    }

    /**
     * Punch a hole at [value] in `intDomains[v]`. Three cases:
     *  - `value` not in the current domain → no-op, returns `true`.
     *  - `value` is at the current min or max → equivalent to a one-step bound tighten.
     *  - `value` is interior → the domain transitions to sparse representation. Other
     *    propagators still see the same `min`/`max` until further tightening; the hole
     *    affects `contains(value)` lookups and `forEach` iteration.
     *
     * Returns `false` only when removing [value] would empty the domain (singleton
     * domain whose sole value is [value]). On conflict, seeds the factor core with the
     * level / reason fields already tracked for the min and max sides.
     */
    private fun excludeIntValueImpl(v: Int, value: Int, antecedents: IntArray?): Boolean {
        val d = intDomains[v]
        if (value !in d) return true
        if (d.min == d.max && d.min == value) {
            recordConflictLevels(intLevel[v], currentLevel)
            seedConflictFactor(intMinReason[v])
            seedConflictFactor(intMaxReason[v])
            seedConflictFactor(currentFactor)
            return false
        }
        val interior = value > d.min && value < d.max
        if (undoLogging) {
            if (interior) logIntCarve(v, value) else logIntChange(v)
        }
        if (currentFactor >= 0) propagations++
        val newDomain = d.excludeValue(value)
        // An edge exclusion advances the endpoint: the new bound rests on the *prior* bound, the
        // exclusion itself, and any further holes crossed on the way. The supplied reason only
        // justifies the exclusion, so the prior bound atom and the crossed values' exclusions
        // must join the recorded reason (see [antecedentsAcrossHoles]) — without them the
        // implication is stronger than what was derived and learned clauses can prune feasible
        // assignments.
        // The exclusion's immediate consequence is the one-step bound move (prior bound +
        // the exclusion); the landed bound additionally rests on any holes crossed by the
        // snap. Kept as separate near/far reason sets so each flipped atom can take the
        // weakest sufficient one.
        val antNear = when {
            newDomain.min != d.min -> appendPriorBound(
                Lit.make(atomVarGe(v, d.min), false),
                d.min > problem.intDomains[v].min,
                antecedents,
            )

            newDomain.max != d.max -> appendPriorBound(
                Lit.make(atomVarLe(v, d.max), false),
                d.max < problem.intDomains[v].max,
                antecedents,
            )

            else -> antecedents
        }
        val ant = when {
            newDomain.min != d.min -> antecedentsAcrossHoles(v, (value + 1) until newDomain.min, antNear)
            newDomain.max != d.max -> antecedentsAcrossHoles(v, (newDomain.max + 1) until value, antNear)
            else -> antecedents
        }
        intDomains[v] = newDomain
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        // History upkeep mirrors the tighten paths: an edge carve joins the bound history
        // (this path bypasses tightenInt*Impl), an interior carve the hole history —
        // without the record the level lookups mis-attribute the change.
        when {
            newDomain.min != d.min -> pushMinHist(v, newDomain.min, currentLevel, antNear, ant, value + 1)
            newDomain.max != d.max -> pushMaxHist(v, newDomain.max, currentLevel, antNear, ant, value - 1)
            else -> pushHoleHist(v, value, currentLevel, antecedents)
        }
        // Reason attribution: which side (min/max) "moved" depends on where the hole
        // landed. Pure interior holes don't shift either endpoint; in that case the
        // current factor still becomes the relevant reason for any future propagator
        // walking back through this variable.
        if (newDomain.min != d.min) {
            intMinReason[v] = currentFactor
            intMinAntecedents[v] = ant
        }
        if (newDomain.max != d.max) {
            intMaxReason[v] = currentFactor
            intMaxAntecedents[v] = ant
        }
        dirtyInts.addLast(v)
        when {
            newDomain.min != d.min ->
                propagateAtomsForVar(
                    v,
                    antNear = antNear,
                    antFar = ant,
                    reqMin = value + 1,
                    oldMin = d.min,
                    oldMax = d.max,
                )

            newDomain.max != d.max ->
                propagateAtomsForVar(
                    v,
                    antNear = antNear,
                    antFar = ant,
                    reqMax = value - 1,
                    oldMin = d.min,
                    oldMax = d.max,
                )

            else ->
                propagateAtomsForVar(v, antNear = antecedents, oldMin = d.min, oldMax = d.max, carved = value)
        }
        return true
    }

    private fun seedConflictFactor(fid: Int) {
        if (fid < 0) return
        noteLearnedUse(fid) // a learned clause that detects a conflict counts as reused (#201)
        conflictSeedFactors.add(fid)
    }

    private fun setIntImpl(v: Int, value: Int, antecedents: IntArray?): Boolean =
        tightenIntMinImpl(v, value, antecedents) && tightenIntMaxImpl(v, value, antecedents)

    private fun recordConflictLevels(a: Int, b: Int) {
        conflictLevels = when {
            a > 0 && b > 0 && a != b -> intArrayOf(a, b)
            a > 0 -> intArrayOf(a)
            b > 0 -> intArrayOf(b)
            else -> EmptyIntArray
        }
    }

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

    /** Max decision level of any variable in `boolVars` / `intVars`. Used by the driver to
     *  set `currentLevel` before each factor invocation.
     *
     *  No variable's level can exceed the number of decisions pushed so far (`cap`); once
     *  the running max reaches that ceiling, the remaining vars can't raise it, so we stop
     *  early. This is an exact short-circuit (same result, fewer reads) — it mainly trims
     *  the scan for large-arity global constraints that fire often during search. */
    fun maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
        // No live level can exceed the number of decisions pushed so far; a stored level
        // above that is a stale advisory left by a pop and must be clamped, or it poisons
        // currentLevel and every pin stamped from it.
        val cap = levelToDecisionVar.size
        var max = 0
        for (v in boolVars) {
            // boolVars may include atom-var ids when a Clause has atom-lits; dispatch.
            val l = if (v < problem.numBoolVars) {
                boolLevel[v]
            } else {
                atomLevelForConflict(v - problem.numBoolVars)
            }
            if (l > max) {
                max = l
                if (max >= cap) return cap
            }
        }
        for (v in intVars) {
            val l = intLevel[v]
            if (l > max) {
                max = l
                if (max >= cap) return cap
            }
        }
        return max
    }

    /** Variant that also folds in atom-lit levels for a Clause's literals — used for
     *  learned clauses that reference atom-vars, where the relevant decision level isn't
     *  captured by `boolVars` / `intVars` alone. */
    fun maxLevelForClause(literals: IntArray): Int {
        // Clamped to the live decision count for the same reason as [maxLevelForVars].
        val cap = levelToDecisionVar.size
        var max = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val l = if (v < problem.numBoolVars) boolLevel[v] else atomLevelForConflict(v - problem.numBoolVars)
            if (l > max) {
                max = l
                if (max >= cap) return cap
            }
        }
        return max
    }

    /** Collect every decision level touched by `boolVars` / `intVars` — the factor's view of
     *  who's responsible. Used when a factor returns `false` without explicitly setting
     *  [conflictLevels].
     *
     *  Atom-lit dispatch: `boolVars` may legitimately contain virtual atom-var ids when the
     *  failing factor is a learned Clause whose literals reference atom-lits (encoded as
     *  `Lit.make(v, ...)` with `v >= problem.numBoolVars`). Those map into `atomLevel`,
     *  not [boolLevel] — mirrors the [maxLevelForVars] dispatch a few lines above. */
    fun collectLevelsForVars(boolVars: IntArray, intVars: IntArray): IntArray {
        // Dedup levels in a reused primitive set (no per-conflict HashSet / Int boxing), then
        // materialize a plain IntArray — this is on the per-conflict path.
        levelScratch.clear()
        val numBool = problem.numBoolVars
        for (v in boolVars) {
            val l = if (v < numBool) boolLevel[v] else atomLevelForConflict(v - numBool)
            if (l > 0) levelScratch.add(l)
        }
        for (v in intVars) {
            val l = intLevel[v]
            if (l > 0) levelScratch.add(l)
        }
        return levelScratch.toIntArray()
    }

    /** Decode [levels] (a subset of pushed decision levels) into the bool decision vars at
     *  those levels. */
    internal fun extractConflictBools(levels: IntArray): IntArray {
        if (levels.isEmpty()) return EmptyIntArray
        val out = IntHashSet(levels.size)
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded < problem.numBoolVars) out.add(encoded)
        }
        return out.toIntArray()
    }

    /** Decode [levels] into the int decision vars at those levels. */
    internal fun extractConflictInts(levels: IntArray): IntArray {
        if (levels.isEmpty()) return EmptyIntArray
        val out = IntHashSet(levels.size)
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded >= problem.numBoolVars) out.add(encoded - problem.numBoolVars)
        }
        return out.toIntArray()
    }

    /**
     * BFS the propagation graph backwards from [conflictSeedFactors] (factors directly
     * implicated in a contradiction) through the per-var reason arrays, collecting every
     * factor whose firing transitively contributed. Each visited factor F is expanded by
     * walking its `boolVars` / `intVars`: for each variable, the factor (if any) that
     * forced the current value / domain bound is added to the frontier. Returns the full
     * factor-level core, or the empty set when no seed was recorded (e.g. seed-assumption
     * contradictions that never reached a factor).
     *
     * Two-sided narrowing is handled because [intMinReason] and [intMaxReason] are tracked
     * separately and both endpoints are walked for every int var.
     */
    internal fun extractConflictFactors(): IntArray {
        if (conflictSeedFactors.isEmpty()) return EmptyIntArray
        // Primitive BFS over the propagation graph: [out] dedups reached factor ids, [frontier]
        // is a grow-only worklist walked by a head index (no boxing, no per-step dequeue alloc).
        val out = IntHashSet(conflictSeedFactors.size * 2)
        val frontier = IntArrayList(conflictSeedFactors.size)
        conflictSeedFactors.forEach { fid ->
            out.add(fid)
            frontier.add(fid)
        }
        var head = 0
        while (head < frontier.size) {
            // factorAt routes learned-clause ids (≥ problem.numFactors) to the session's clause
            // registry — conflicts can name a learned clause as their failing factor.
            val f = factorAt(frontier.get(head++))
            for (v in f.boolVars) {
                // Skip atom-encoded literal ids (≥ numBoolVars) — their causation is captured
                // through intMinReason / intMaxReason on the underlying int var, expanded below.
                if (v >= problem.numBoolVars) continue
                val r = boolReason[v]
                if (r >= 0 && out.add(r)) frontier.add(r)
            }
            for (v in f.intVars) {
                val rMin = intMinReason[v]
                if (rMin >= 0 && out.add(rMin)) frontier.add(rMin)
                val rMax = intMaxReason[v]
                if (rMax >= 0 && out.add(rMax)) frontier.add(rMax)
            }
        }
        return out.toIntArray()
    }

    // Mark / undo for [PropagationSession]. A pop rewinds to a prior fixpoint by replaying
    // the undo log (above) rather than re-propagating. The caller only ever marks / undoes
    // between propagation cycles (i.e. when dirty queues are empty).

    /** Opt-in marker for [refPayload] entries that need to participate in mark / undo.
     *  By default refPayload drifts across push/pop (Clause-watcher style); a factor that
     *  maintains level-sensitive incremental state (e.g. STR2 Table's sparse set of valid
     *  tuples) implements this to get correct backtrack behavior. */
    interface SnapshottablePayload {
        /** Deep-copy this payload for trail snapshotting. */
        fun snapshotCopy(): SnapshottablePayload
    }

    /**
     * Lightweight per-level marker. Records only the *positions* a pop must rewind to: the
     * undo-log size and the three append-only stacks' sizes, plus snapshot copies of any
     * [SnapshottablePayload]s (the rare factors — Mdd / Table — whose incremental state
     * isn't recomputed from scratch on each `propagate`). Replaces the old `Snapshot`,
     * which copied ~12 numVars-sized arrays per level; [undoTo] instead replays the undo
     * log in O(changes-since-mark).
     */
    class LevelMark internal constructor(
        internal val undoSize: Int,
        internal val ltdvSize: Int,
        internal val pinOrderSize: Int,
        internal val snapshottablePayloads: Map<Int, SnapshottablePayload>,
    )

    /** Capture a [LevelMark] at the current state. Cheap: three ints plus a snapshotCopy of
     *  each [SnapshottablePayload]. The map is allocated only when at least one payload is
     *  present (Table / Mdd factors); the common no-payload case shares [emptyPayloads] and
     *  never allocates per push. */
    fun mark(): LevelMark {
        @Suppress("DoubleMutabilityForCollection") // lazily allocated when a snapshot is taken
        var payloads: HashMap<Int, SnapshottablePayload>? = null
        // Only the tracked snapshottable slots need copying — no per-pin scan of every factor.
        snapshottableIndices.forEach { i ->
            val p = _refPayload[i]
            if (p is SnapshottablePayload) {
                val m = payloads ?: HashMap<Int, SnapshottablePayload>().also { payloads = it }
                m[i] = p.snapshotCopy()
            }
        }
        return LevelMark(
            undoSize = undoTag.size,
            ltdvSize = levelToDecisionVar.size,
            pinOrderSize = boolPinOrder.size,
            snapshottablePayloads = payloads ?: emptyPayloads,
        )
    }

    /**
     * Rewind the state to [mark] by replaying the undo log from the top down to
     * [LevelMark.undoSize], then truncating the append-only stacks (decision vars, pin
     * order) and restoring snapshottable payloads. Replays in reverse so a var
     * narrowed several times since the mark lands on its mark-time value. Transient
     * bookkeeping (dirty queues, conflict seeds, current level/factor) is cleared — the
     * caller only ever marks / undoes between propagation cycles, when those are idle.
     */
    fun undoTo(mark: LevelMark) {
        // Optional sink for variables made (potentially) free again by this revert. Used by
        // VSIDS-style pickers that remove a variable from their order heap when it's assigned
        // and need it re-inserted on backtrack (combined-index encoding: bool id `v`, int id
        // `numBoolVars + v`). Captured once so the no-listener case stays a single null check.
        val unassigned = unassignListener
        val numBool = problem.numBoolVars
        var i = undoTag.size - 1
        while (i >= mark.undoSize) {
            when (undoTag[i]) {
                0 -> { // bool pin — prior state is always unassigned
                    val v = undoVar[i]
                    boolValues[v] = null
                    boolLevel[v] = -1
                    boolReason[v] = -1
                    boolAntecedents[v] = null
                    unassigned?.invoke(v)
                }

                1 -> { // int change — restore the full recorded prior int-var state
                    val v = undoVar[i]
                    unassigned?.invoke(numBool + v)
                    intDomains[v] = requireNotNull(undoDomain[i])
                    intLevel[v] = undoLevel[i]
                    intMinReason[v] = undoMinReason[i]
                    intMaxReason[v] = undoMaxReason[i]
                    intMinAntecedents[v] = undoMinAnt[i]
                    intMaxAntecedents[v] = undoMaxAnt[i]
                    // Truncate the bound-change history back to its pre-mutation length so the
                    // (value, level) record stays exactly aligned with the restored domain.
                    minHistVal[v]?.truncateTo(undoMinHistLen[i])
                    minHistLvl[v]?.truncateTo(undoMinHistLen[i])
                    maxHistVal[v]?.truncateTo(undoMaxHistLen[i])
                    maxHistLvl[v]?.truncateTo(undoMaxHistLen[i])
                    holeHistVal[v]?.truncateTo(undoHoleHistLen[i])
                    holeHistLvl[v]?.truncateTo(undoHoleHistLen[i])
                    minHistAntNear[v]?.let { a -> while (a.size > undoMinHistLen[i]) a.removeAt(a.size - 1) }
                    minHistAntFar[v]?.let { a -> while (a.size > undoMinHistLen[i]) a.removeAt(a.size - 1) }
                    minHistReq[v]?.truncateTo(undoMinHistLen[i])
                    maxHistAntNear[v]?.let { a -> while (a.size > undoMaxHistLen[i]) a.removeAt(a.size - 1) }
                    maxHistAntFar[v]?.let { a -> while (a.size > undoMaxHistLen[i]) a.removeAt(a.size - 1) }
                    maxHistReq[v]?.truncateTo(undoMaxHistLen[i])
                    holeHistAnt[v]?.let { a -> while (a.size > undoHoleHistLen[i]) a.removeAt(a.size - 1) }
                }

                2 -> { // interior carve — re-insert the carved value
                    val v = undoVar[i]
                    unassigned?.invoke(numBool + v)
                    intDomains[v] = intDomains[v].includeInteriorValue(undoMinReason[i])
                    intLevel[v] = undoLevel[i]
                    holeHistVal[v]?.truncateTo(undoMaxReason[i])
                    holeHistLvl[v]?.truncateTo(undoMaxReason[i])
                    holeHistAnt[v]?.let { a -> while (a.size > undoMaxReason[i]) a.removeAt(a.size - 1) }
                }

                else -> error("unknown undo tag")
            }
            i--
        }
        truncateUndo(mark.undoSize)
        boolPinOrder.truncateTo(mark.pinOrderSize)
        levelToDecisionVar.truncateTo(mark.ltdvSize)
        // Restore snapshottable per-factor payloads. Defensive snapshotCopy so a later
        // undo to the same mark returns to the same logical state.
        for ((fid, payload) in mark.snapshottablePayloads) {
            _refPayload[fid] = payload.snapshotCopy()
        }
        // Atoms carry no stored state to reconcile: truth, level and antecedents are all
        // derived on demand from the domains and histories restored above.
        dirtyAtomFactors.clear()
        dirtyBools.clear()
        dirtyInts.clear()
        conflictLevels = null
        conflictSeedFactors.clear()
        lastDecisionConflictVar = -1
        currentLevel = 0
        currentFactor = -1
    }

    /**
     * Run propagation until no factor can derive more. When [allFactors] is true, every
     * factor is enqueued initially (the usual one-shot path). When false, only factors
     * touching variables currently in the dirty queues are enqueued — for incremental use
     * by a session that just applied a pin and wants to extend the fixpoint.
     *
     * Returns `null` on success (state is at fixpoint); otherwise the conflict-levels set.
     */
    internal fun runToFixpoint(allFactors: Boolean, initialFactor: Int = -1): IntArray? {
        // Clear conflict bookkeeping from any prior run — reusing the state across pushes
        // would otherwise mix old seeds into a new conflict's core.
        conflictSeedFactors.clear()
        val factorCount = totalFactorCount
        propBegin(factorCount)
        if (allFactors) {
            for (fid in 0 until factorCount) propEnq(fid)
        } else {
            while (true) {
                val v = pollDirtyBool()
                if (v < 0) break
                enqueueForBoolChange(v)
            }
            while (true) {
                val v = pollDirtyInt()
                if (v < 0) break
                for (fid in problem.intOccurrences[v]) propEnq(fid)
            }
            // Atom-lit watchers woken by int tightens before runToFixpoint was called.
            while (dirtyAtomFactors.isNotEmpty()) {
                val fid = dirtyAtomFactors.removeFirst()
                if (fid in 0 until factorCount) propEnq(fid)
            }
            // Optional seed — used by [PropagationSession.addLearnedClause] to force the
            // newly-stored learned clause to fire on the next propagation cycle (it would
            // otherwise sit dormant since the watcher index only wakes on false-going
            // literals, and a freshly-added clause's watches haven't been triggered yet).
            if (initialFactor in 0 until factorCount) propEnq(initialFactor)
        }
        while (propQueue.isNotEmpty()) {
            val fid = propQueue.removeFirst()
            propStamp[fid] = propGen - 1 // mark dequeued (≠ propGen) so it can re-enqueue
            val f = factorAt(fid)
            // Level for the firing factor. A Clause's effective level is the max decision
            // level over its literals; its [boolVars] is the deduplicated variable set of
            // those literals and its [intVars] is empty, so maxLevelForVars is redundant
            // with maxLevelForClause (a second O(arity) pass over the same variables on
            // every fire — the BCP hot path). Better still: a *pure-bool* clause only ever
            // fires when a watched bool literal just went false at the current decision
            // level (bools are only pinned by decisions or clause propagation, all stamped
            // at the current level), so its effective level is exactly the current decision
            // level — no scan at all. Atom-lit clauses can fire on an atom that flipped at a
            // sub-decision level, so they keep the literal scan.
            currentLevel = if (f is Clause) {
                if (f.allLiteralsBool(problem.numBoolVars)) {
                    levelToDecisionVar.size
                } else {
                    maxLevelForClause(f.literals)
                }
            } else {
                maxLevelForVars(f.boolVars, f.intVars)
            }
            currentFactor = fid
            conflictLevels = null
            if (!f.propagate(this, fid)) {
                // The failing factor is always in the core, regardless of whether it
                // recorded a conflict via the impl methods (some factors return false
                // without calling pin/tighten — they just detected infeasibility from
                // the current state).
                seedConflictFactor(fid)
                return conflictLevels ?: collectLevelsForVars(f.boolVars, f.intVars)
            }
            while (true) {
                val v = pollDirtyBool()
                if (v < 0) break
                enqueueForBoolChange(v)
            }
            while (true) {
                val v = pollDirtyInt()
                if (v < 0) break
                for (other in problem.intOccurrences[v]) propEnq(other)
            }
            // Wake factors registered as atom-lit watchers whose atom truth just flipped.
            while (dirtyAtomFactors.isNotEmpty()) {
                propEnq(dirtyAtomFactors.removeFirst())
            }
        }
        return null
    }

    /**
     * Add every factor that should fire on `v`'s newly-pinned value to `queue`, using the
     * split wakeup paths: occurrence-list for factors that don't watch literals, plus the
     * per-literal watcher index for those that do (currently Clauses). For watcher-using
     * factors only the literal that just transitioned to *false* triggers a fire — true
     * literals satisfy the clause, no propagation needed.
     */
    private fun enqueueForBoolChange(v: Int) {
        for (fid in problem.nonBoolWatcherBoolOccurrences[v]) propEnq(fid)
        // The literal that just became false is the one whose polarity opposes the pin.
        // The var is assigned here (added to dirtyBools only after a successful pin), so read
        // the packed value bit directly instead of the boxing `boolValues[v]` accessor.
        val falseLit = Lit.make(v, !boolValueBits.get(v))
        val watchers = boolWatchersByLit[falseLit]
        val blockers = boolBlockersByLit[falseLit]
        for (i in 0 until watchers.size) {
            // Blocking-literal short-cut (#200): if the cached blocker for this watch is
            // already true, the factor is satisfied and waking it would be a no-op — skip
            // the enqueue and the clause dereference entirely. NO_BLOCKER falls through and
            // always fires, so factors without blockers behave exactly as before.
            val blocker = blockers[i]
            if (blocker != NO_BLOCKER && litTrue(blocker)) continue
            propEnq(watchers[i])
        }
    }
}
