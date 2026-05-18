package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem

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
 *  `tightenIntMin` / `tightenIntMax` / `setInt` as before. The driver sets [currentLevel]
 *  to the inherited level before each factor invocation; mutators read it.
 */
class PropagationState(
    val problem: Problem,
    assumptions: Assumptions,
) {
    /** Per-bool current pin; `null` means unassigned. */
    val boolValues: Array<Boolean?> = arrayOfNulls(problem.numBoolVars)

    /** Per-int current domain (copy of [Problem.intDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = Array(problem.numIntVars) { problem.intDomains[it] }

    /** Vars whose pin/domain changed since the driver last drained them. Primitive int
     *  ring buffers to avoid the autoboxing tax `ArrayDeque<Int>` pays on every push/poll. */
    private val dirtyBools: com.eignex.klause.util.IntArrayDeque =
        com.eignex.klause.util.IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
    private val dirtyInts: com.eignex.klause.util.IntArrayDeque =
        com.eignex.klause.util.IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    // Decision-level plumbing. ---------------------------------------------------------------

    /** Decision level when each bool was first pinned (-1 = unpinned). */
    val boolLevel: IntArray = IntArray(problem.numBoolVars) { -1 }
    /** Deepest decision level contributing to this int var's current domain (-1 = untouched). */
    val intLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    /**
     * Decision-var encoded per level: index `lvl-1` holds either a bool var id (0..numBoolVars-1)
     * or a shifted int var id (numBoolVars + intVar). Grows as decisions are pushed. Primitive
     * int list (no boxing on push or indexed read).
     */
    private val levelToDecisionVar: com.eignex.klause.util.IntArrayList =
        com.eignex.klause.util.IntArrayList()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    /** Level any pin created during the current factor invocation inherits. Set by the driver. */
    internal var currentLevel: Int = 0

    /** Populated on contradiction; the driver reads it to form [PropagationResult.Unsat]. */
    internal var conflictLevels: MutableSet<Int>? = null

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

    /**
     * Seed set of factors directly implicated in a contradiction. Populated by [runToFixpoint]
     * (the factor that returned `false`) and by the impl methods (both sides of a two-source
     * narrowing). [extractConflictFactors] BFSes from this seed via the reason arrays to
     * produce the full propagation-graph core.
     */
    internal var conflictSeedFactors: MutableSet<Int>? = null

    /**
     * Per-factor mutable scratch space — mirrors [com.eignex.klause.solver.localsearch.LocalSearchState.refPayload]
     * on the LS side. Factors stash propagation-time bookkeeping here keyed by their own
     * factor id; the engine doesn't touch the contents. Today's only user is
     * [com.eignex.klause.solver.factor.Clause]'s two-watched-literal scheme, but the slot
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
    val refPayload: MutableList<Any?> get() = _refPayload

    /** Learned clauses accumulated during search (LCG-style nogoods produced by
     *  [ConflictAnalyzer]). Their factor ids live in `[problem.numFactors, totalFactorCount)` —
     *  treat them like any other [com.eignex.klause.solver.factor.Clause] via [factorAt];
     *  they participate in propagation through [boolWatchersByLit] just like static
     *  clauses. Survives [restore] (clauses are facts about the original problem, not
     *  trail state). */
    private val _learnedClauses: ArrayList<com.eignex.klause.solver.factor.Clause> = ArrayList()
    val learnedClauses: List<com.eignex.klause.solver.factor.Clause> get() = _learnedClauses

    /** `problem.numFactors + learnedClauses.size`. Use this instead of `problem.numFactors`
     *  when iterating or sizing per-factor scratch in the engine. */
    val totalFactorCount: Int get() = problem.numFactors + _learnedClauses.size

    /** Unified factor accessor; routes static factor ids to [Problem.factors] and learned
     *  factor ids (≥ `problem.numFactors`) to [learnedClauses]. */
    fun factorAt(fid: Int): com.eignex.klause.solver.Factor =
        if (fid < problem.numFactors) problem.factors[fid]
        else _learnedClauses[fid - problem.numFactors]

    /**
     * Register a learned clause and return its assigned factor id. Performs three things:
     *   - append to [_learnedClauses];
     *   - grow [_refPayload] by one slot so [Clause.propagate]'s
     *     `state.refPayload[factorId]` access stays in-bounds;
     *   - install the clause's initial watch literals in [boolWatchersByLit] so it
     *     participates in the wakeup index.
     *
     * Does NOT eagerly propagate — that's the session-level
     * [PropagationSession.addLearnedClause]'s job. Returns the new factor id.
     */
    fun addLearnedClause(clause: com.eignex.klause.solver.factor.Clause): Int {
        val newFid = totalFactorCount
        _learnedClauses.add(clause)
        _refPayload.add(null)
        val watchers = clause.initialBoolWatchers
        if (watchers != null) {
            for (lit in watchers) boolWatchersByLit[lit].add(newFid)
        }
        return newFid
    }

    /**
     * Per-literal wakeup index for factors opting into [com.eignex.klause.solver.Factor.initialBoolWatchers].
     * Slot `boolWatchersByLit[lit]` lists factor ids that should fire when literal `lit`
     * transitions to false. Sized `2 * problem.numBoolVars`; lit ids are the standard
     * [com.eignex.klause.solver.Lit.make] encoding. Populated at construction from each
     * factor's initial watch set; factors with dynamic watches (Clause) keep it in sync
     * via [moveBoolWatcher] as their watches drift during propagation.
     *
     * Like [refPayload], the index drifts across snapshot / restore on purpose. After a
     * pop the watches reflect their state at the deepest level reached — that's still
     * sound, since the invariant is "watch is on a non-false literal", and pop reverts
     * pins which only *adds* non-false literals.
     */
    val boolWatchersByLit: Array<com.eignex.klause.util.IntArrayList> =
        Array(2 * problem.numBoolVars) { com.eignex.klause.util.IntArrayList(initialCapacity = 2) }

    /**
     * Per-bool-var antecedent literals — the literal-form reason why this variable's
     * current pin was implied. For a Clause that unit-propagated `v`, the antecedents are
     * all the *other* literals in that clause, every one of which was already false at
     * pin time (that's why the clause was unit). `null` = no antecedents recorded, which
     * means either:
     *   - the var was pinned as a decision / assumption (no factor reason), or
     *   - the var was pinned by a factor that doesn't yet record antecedents
     *     (everything except [com.eignex.klause.solver.factor.Clause] today).
     *
     * Maintained alongside [boolReason] (factor id) for first-UIP conflict analysis
     * (lazy clause generation). When the conflict analyzer hits a `null` entry it treats
     * the variable as a search-tree leaf, same as a decision.
     */
    val boolAntecedents: Array<IntArray?> = arrayOfNulls(problem.numBoolVars)

    /**
     * Chronological journal of bool pins, decisions and implications interleaved. Used by
     * [com.eignex.klause.solver.propagation.ConflictAnalyzer] to walk the implication
     * graph in reverse pin order — the standard 1UIP loop needs to resolve against the
     * *most recently pinned* variable in the current conflict, which requires this
     * append-only trail.
     */
    val boolPinOrder: com.eignex.klause.util.IntArrayList =
        com.eignex.klause.util.IntArrayList(initialCapacity = problem.numBoolVars.coerceAtLeast(8))

    init {
        for (fid in 0 until problem.numFactors) {
            val watchers = problem.factors[fid].initialBoolWatchers ?: continue
            for (lit in watchers) boolWatchersByLit[lit].add(fid)
        }
    }

    /**
     * Move factor [factorId]'s registration from [oldLit] to [newLit] in
     * [boolWatchersByLit]. Called by watcher-using factors when they relocate a watch
     * during propagation. The removal scans [oldLit]'s slot (typically a handful of
     * entries) and swap-and-pops; the insert is O(1).
     */
    fun moveBoolWatcher(factorId: Int, oldLit: Int, newLit: Int) {
        if (oldLit == newLit) return
        val from = boolWatchersByLit[oldLit]
        for (i in 0 until from.size) {
            if (from[i] == factorId) { from.removeAt(i); break }
        }
        boolWatchersByLit[newLit].add(factorId)
    }

    init {
        seeded = seedAssumptions(assumptions)
    }

    /** Push every pin in [a] as a fresh decision; return `false` (so [seeded] becomes
     *  `false`) on the first contradiction. Direct primitive-array iteration so the early
     *  exit is a clean `return`. */
    private fun seedAssumptions(a: Assumptions): Boolean {
        val bk = a.boolKeys; val bv = a.boolValues
        for (i in bk.indices) {
            if (!pinBoolAsDecision(bk[i], bv[i])) return false
        }
        val ik = a.intKeys; val iv = a.intValues
        for (i in ik.indices) {
            if (!setIntAsDecision(ik[i], iv[i])) return false
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
        return setIntImpl(v, value)
    }

    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolImpl(v, value, antecedents = null)
    /** Variant that records [antecedents] — the literals whose truth values implied this
     *  pin. Lets the conflict analyzer reconstruct the propagation chain backwards. Pass
     *  `null` (the default no-arg form) when the factor doesn't track antecedents — that's
     *  fine, the analyzer just treats this pin as a leaf in the implication graph. */
    fun pinBool(v: Int, value: Boolean, antecedents: IntArray?): Boolean =
        pinBoolImpl(v, value, antecedents)
    fun tightenIntMin(v: Int, lo: Int): Boolean = tightenIntMinImpl(v, lo)
    fun tightenIntMax(v: Int, hi: Int): Boolean = tightenIntMaxImpl(v, hi)
    fun setInt(v: Int, value: Int): Boolean = setIntImpl(v, value)

    private fun pinBoolImpl(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
        val cur = boolValues[v]
        if (cur != null) {
            if (cur == value) return true
            // Conflict — record levels of both contributors, and seed the factor core with
            // the prior pin's reason (whichever factor forced `cur`, if any) plus the
            // currently-running factor (if any).
            recordConflictLevels(boolLevel[v], currentLevel)
            seedConflictFactor(boolReason[v])
            seedConflictFactor(currentFactor)
            return false
        }
        boolValues[v] = value
        boolLevel[v] = currentLevel
        boolReason[v] = currentFactor
        boolAntecedents[v] = antecedents
        boolPinOrder.add(v)
        dirtyBools.addLast(v)
        return true
    }

    private fun tightenIntMinImpl(v: Int, lo: Int): Boolean {
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
        intDomains[v] = IntDomain(lo, d.max)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMinReason[v] = currentFactor
        dirtyInts.addLast(v)
        return true
    }

    private fun tightenIntMaxImpl(v: Int, hi: Int): Boolean {
        val d = intDomains[v]
        if (hi >= d.max) return true
        if (hi < d.min) {
            recordConflictLevels(intLevel[v], currentLevel)
            seedConflictFactor(intMinReason[v])
            seedConflictFactor(currentFactor)
            return false
        }
        intDomains[v] = IntDomain(d.min, hi)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMaxReason[v] = currentFactor
        dirtyInts.addLast(v)
        return true
    }

    private fun seedConflictFactor(fid: Int) {
        if (fid < 0) return
        val s = conflictSeedFactors ?: HashSet<Int>().also { conflictSeedFactors = it }
        s.add(fid)
    }

    private fun setIntImpl(v: Int, value: Int): Boolean =
        tightenIntMinImpl(v, value) && tightenIntMaxImpl(v, value)

    private fun recordConflictLevels(a: Int, b: Int) {
        val s = HashSet<Int>()
        if (a > 0) s.add(a)
        if (b > 0) s.add(b)
        conflictLevels = s
    }

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

    /** Max decision level of any variable in [boolVars] / [intVars]. Used by the driver to
     *  set [currentLevel] before each factor invocation. */
    fun maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
        var max = 0
        for (v in boolVars) { val l = boolLevel[v]; if (l > max) max = l }
        for (v in intVars) { val l = intLevel[v]; if (l > max) max = l }
        return max
    }

    /** Collect every decision level touched by [boolVars] / [intVars] — the factor's view of
     *  who's responsible. Used when a factor returns `false` without explicitly setting
     *  [conflictLevels]. */
    fun collectLevelsForVars(boolVars: IntArray, intVars: IntArray): Set<Int> {
        val out = HashSet<Int>()
        for (v in boolVars) { val l = boolLevel[v]; if (l > 0) out.add(l) }
        for (v in intVars) { val l = intLevel[v]; if (l > 0) out.add(l) }
        return out
    }

    /** Decode [levels] (a subset of pushed decision levels) into the bool decision vars at
     *  those levels. */
    internal fun extractConflictBools(levels: Set<Int>): Set<Int> {
        if (levels.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded < problem.numBoolVars) out.add(encoded)
        }
        return out
    }

    /** Decode [levels] into the int decision vars at those levels. */
    internal fun extractConflictInts(levels: Set<Int>): Set<Int> {
        if (levels.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (lvl in levels) {
            if (lvl <= 0 || lvl > levelToDecisionVar.size) continue
            val encoded = levelToDecisionVar[lvl - 1]
            if (encoded >= problem.numBoolVars) out.add(encoded - problem.numBoolVars)
        }
        return out
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
    internal fun extractConflictFactors(): Set<Int> {
        val seed = conflictSeedFactors ?: return emptySet()
        if (seed.isEmpty()) return emptySet()
        val out = HashSet<Int>(seed)
        val frontier = ArrayDeque<Int>().apply { addAll(seed) }
        while (frontier.isNotEmpty()) {
            val fid = frontier.removeFirst()
            // factorAt routes learned-clause ids (≥ problem.numFactors) to the
            // session's clause registry — required now that conflicts can name a
            // learned clause as their failing factor.
            val f = factorAt(fid)
            for (v in f.boolVars) {
                val r = boolReason[v]
                if (r >= 0 && out.add(r)) frontier.addLast(r)
            }
            for (v in f.intVars) {
                val rMin = intMinReason[v]
                if (rMin >= 0 && out.add(rMin)) frontier.addLast(rMin)
                val rMax = intMaxReason[v]
                if (rMax >= 0 && out.add(rMax)) frontier.addLast(rMax)
            }
        }
        return out
    }

    // Snapshot / restore for [PropagationSession]. Captures every mutable field so a pop
    // can rewind the state to a prior fixpoint without re-propagating. Dirty queues are not
    // snapshotted — the caller is expected to snapshot only between propagation cycles
    // (i.e. when dirty queues are empty).
    class Snapshot internal constructor(
        internal val boolValues: Array<Boolean?>,
        internal val intDomains: Array<IntDomain>,
        internal val boolLevel: IntArray,
        internal val intLevel: IntArray,
        internal val decisionVars: IntArray,
        internal val boolReason: IntArray,
        internal val intMinReason: IntArray,
        internal val intMaxReason: IntArray,
        internal val boolAntecedents: Array<IntArray?>,
        internal val boolPinOrderSize: Int,
    )

    fun snapshot(): Snapshot = Snapshot(
        boolValues = boolValues.copyOf(),
        intDomains = intDomains.copyOf(),
        boolLevel = boolLevel.copyOf(),
        intLevel = intLevel.copyOf(),
        decisionVars = levelToDecisionVar.toIntArray(),
        boolReason = boolReason.copyOf(),
        intMinReason = intMinReason.copyOf(),
        intMaxReason = intMaxReason.copyOf(),
        boolAntecedents = boolAntecedents.copyOf(),
        boolPinOrderSize = boolPinOrder.size,
    )

    fun restore(s: Snapshot) {
        for (i in s.boolValues.indices) boolValues[i] = s.boolValues[i]
        for (i in s.intDomains.indices) intDomains[i] = s.intDomains[i]
        for (i in s.boolLevel.indices) boolLevel[i] = s.boolLevel[i]
        for (i in s.intLevel.indices) intLevel[i] = s.intLevel[i]
        for (i in s.boolReason.indices) boolReason[i] = s.boolReason[i]
        for (i in s.intMinReason.indices) intMinReason[i] = s.intMinReason[i]
        for (i in s.intMaxReason.indices) intMaxReason[i] = s.intMaxReason[i]
        for (i in s.boolAntecedents.indices) boolAntecedents[i] = s.boolAntecedents[i]
        boolPinOrder.truncateTo(s.boolPinOrderSize)
        levelToDecisionVar.clear()
        for (v in s.decisionVars) levelToDecisionVar.add(v)
        // Aborted pushes may have left dirty queue entries behind; drop them.
        dirtyBools.clear()
        dirtyInts.clear()
        conflictLevels = null
        conflictSeedFactors = null
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
    internal fun runToFixpoint(allFactors: Boolean, initialFactor: Int = -1): Set<Int>? {
        // Clear conflict bookkeeping from any prior run — reusing the state across pushes
        // would otherwise mix old seeds into a new conflict's core.
        conflictSeedFactors = null
        val factorCount = totalFactorCount
        val pending = BooleanArray(factorCount)
        val queue = com.eignex.klause.util.IntArrayDeque(initialCapacity = factorCount.coerceAtLeast(8))
        if (allFactors) {
            for (fid in 0 until factorCount) { pending[fid] = true; queue.addLast(fid) }
        } else {
            while (true) {
                val v = pollDirtyBool(); if (v < 0) break
                enqueueForBoolChange(v, pending, queue)
            }
            while (true) {
                val v = pollDirtyInt(); if (v < 0) break
                for (fid in problem.intOccurrences[v]) {
                    if (!pending[fid]) { pending[fid] = true; queue.addLast(fid) }
                }
            }
            // Optional seed — used by [PropagationSession.addLearnedClause] to force the
            // newly-stored learned clause to fire on the next propagation cycle (it would
            // otherwise sit dormant since the watcher index only wakes on false-going
            // literals, and a freshly-added clause's watches haven't been triggered yet).
            if (initialFactor in 0 until factorCount && !pending[initialFactor]) {
                pending[initialFactor] = true; queue.addLast(initialFactor)
            }
        }
        while (queue.isNotEmpty()) {
            val fid = queue.removeFirst()
            pending[fid] = false
            val f = factorAt(fid)
            currentLevel = maxLevelForVars(f.boolVars, f.intVars)
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
                val v = pollDirtyBool(); if (v < 0) break
                enqueueForBoolChange(v, pending, queue)
            }
            while (true) {
                val v = pollDirtyInt(); if (v < 0) break
                for (other in problem.intOccurrences[v]) {
                    if (!pending[other]) { pending[other] = true; queue.addLast(other) }
                }
            }
        }
        return null
    }

    /**
     * Add every factor that should fire on [v]'s newly-pinned value to [queue], using the
     * split wakeup paths: occurrence-list for factors that don't watch literals, plus the
     * per-literal watcher index for those that do (currently Clauses). For watcher-using
     * factors only the literal that just transitioned to *false* triggers a fire — true
     * literals satisfy the clause, no propagation needed.
     */
    private fun enqueueForBoolChange(
        v: Int,
        pending: BooleanArray,
        queue: com.eignex.klause.util.IntArrayDeque,
    ) {
        for (fid in problem.nonBoolWatcherBoolOccurrences[v]) {
            if (!pending[fid]) { pending[fid] = true; queue.addLast(fid) }
        }
        // The literal that just became false is the one whose polarity opposes the pin.
        // boolValues[v] is non-null here (the var was added to dirtyBools only after a
        // successful pin); read it directly.
        val falseLit = Lit.make(v, !boolValues[v]!!)
        val watchers = boolWatchersByLit[falseLit]
        for (i in 0 until watchers.size) {
            val fid = watchers[i]
            if (!pending[fid]) { pending[fid] = true; queue.addLast(fid) }
        }
    }
}
