package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableLongIntMap

/** Sentinel for [PropagationState.propagateAtomsForVar]'s carved-value parameter. */
internal const val NO_CARVE: Int = Int.MIN_VALUE

/** Blocking-literal slot with no blocker (#200): the watcher always fires. Lit ids are
 *  non-negative ([Lit.make] = `var shl 1 | sign`), so −1 is a safe sentinel. */
internal const val NO_BLOCKER: Int = -1

/** Poll the cancellation token once every `CANCEL_POLL_MASK + 1` factor fires on the
 *  full-propagation path (power-of-two minus one so the gate is a single `and`). */
private const val CANCEL_POLL_MASK: Int = 1023

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
    internal val boolAssigned: Bits = Bits(problem.numBoolVars)
    internal val boolValueBits: Bits = Bits(problem.numBoolVars)

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
    internal val dirtyBools: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
    internal val dirtyInts: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))

    /**
     * Per-int-var bitmask of the [IntEvent] kinds that occurred since the variable was last
     * drained — recorded by `markIntDirty` alongside [dirtyInts] and consumed (then cleared) by
     * [enqueueForIntChange] to wake exactly the subscribed advisors. Empty (aliasing the shared
     * [com.eignex.klause.solver.EmptyIntArray]) when no factor subscribes to typed int events, so
     * the common case allocates nothing and skips the bookkeeping entirely.
     *
     * Sound to over-set (extra bit ⇒ harmless extra wake) but never under-set (a missing bit drops
     * a wake a subscriber relied on). Mark and drain are paired through [dirtyInts]; since the
     * driver only marks/undoes between propagation cycles — when [dirtyInts] is empty and thus every
     * mask already cleared — no stale bits survive a backtrack.
     */
    internal val dirtyIntKinds: IntArray =
        if (problem.usesIntEventWatchers) IntArray(problem.numIntVars) else EmptyIntArray

    // -------- Reusable propagation worklist (was allocated fresh per runToFixpoint) --------
    //
    // [propQueue] is the factor worklist; [propStamp] is a per-factor "currently queued"
    // membership set encoded as a generation stamp so resetting between propagation runs is
    // O(1) (just bump [propGen]) instead of zeroing a `BooleanArray(factorCount)` on every
    // pin. A factor is queued iff `propStamp[fid] == propGen`; dequeuing writes `propGen - 1`
    // (any value ≠ propGen) so a factor can still re-enqueue itself within the same run.
    internal val propQueue: IntArrayDeque =
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

    // Per-side split of [intLevel]: the decision level at which the *current* lower (resp. upper)
    // bound was established. [intLevel] is the max of the two. Lets a current-bound order literal
    // `[v ≥ d.min]` / `[v ≤ d.max]` reconstruct its exact (often shallower) level from a single
    // slot — the trail-resident replacement for the bound-history binary search inside
    // [atomLevelForConflict], so that function can become a plain stored-slot read. -1 = the bound
    // is still at its root value (a level-0 global fact). Logged/restored by the undo trail.
    internal val intMinLevel: IntArray = IntArray(problem.numIntVars) { -1 }
    internal val intMaxLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    // Per-int-var interior-hole carve history: the (value, level, reason) at which each
    // search-time interior carve happened — the surviving per-var history (the bound histories
    // are gone; order literals carry their own level/reason now). An eq atom ruled out by an
    // interior hole materialized after the carve reads its level/reason from here. Lazily
    // allocated, maintained while [undoLogging], truncated on backtrack via the undo log.
    internal val holeHistAnt: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    internal val holeHistVal: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)
    internal val holeHistLvl: Array<IntArrayList?> = arrayOfNulls(problem.numIntVars)

    /**
     * Decision-var encoded per level: index `lvl-1` holds either a bool var id (0..numBoolVars-1)
     * or a shifted int var id (numBoolVars + intVar). Grows as decisions are pushed. Primitive
     * int list (no boxing on push or indexed read).
     */
    internal val levelToDecisionVar: IntArrayList =
        IntArrayList()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    /** Reused dedup scratch for [composeIntVarAtomAntecedents] — packed `(var, side, bound)` keys,
     *  cleared per call so the constraint-wide reason path allocates no set and boxes no key. */
    private val composeAntecedentSeen: LongHashSet = LongHashSet()

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
        // Reused, cleared per call (not re-entrant within one propagate) so the dedup pays no
        // per-call set allocation nor the autoboxing a HashSet<Long> would on this hot reason path.
        val seen = composeAntecedentSeen
        seen.clear()
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

    /** Level any pin created during the current factor invocation inherits. Set by the driver. */
    internal var currentLevel: Int = 0

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
    internal val levelScratch: IntHashSet = IntHashSet()

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
    internal val refPayloadStore: ArrayList<Any?> = ArrayList<Any?>(problem.numFactors).apply {
        repeat(problem.numFactors) { add(null) }
    }

    /** Indices in [refPayloadStore] currently holding a [SnapshottablePayload]. Maintained on every
     *  write through [refPayload] so [mark] visits only these slots instead of scanning the whole
     *  factor list (incl. all learned clauses) per pin — the scan was an O(numFactors)-per-decision
     *  cost, and pure-clause problems have none. Snapshottable payloads belong to static factors
     *  (Table / Mdd), so their ids stay below `numFactors` and never move under learned-clause
     *  forgetting. */
    internal val snapshottableIndices: IntHashSet = IntHashSet()

    /** Per-factor mutable payload slots (reference-typed). Writes route through this view so
     *  [snapshottableIndices] stays in sync; reads and structural ops delegate to [refPayloadStore]. */
    val refPayload: MutableList<Any?> = object : MutableList<Any?> by refPayloadStore {
        override fun set(index: Int, element: Any?): Any? {
            if (element is SnapshottablePayload) snapshottableIndices.add(index) else snapshottableIndices.remove(index)
            return refPayloadStore.set(index, element)
        }
    }

    /** Learned clauses accumulated during search (LCG-style nogoods produced by
     *  [ConflictAnalyzer]). Their factor ids live in `[problem.numFactors, totalFactorCount)` —
     *  treat them like any other [Clause] via [factorAt];
     *  they participate in propagation through [boolWatchersByLit] just like static
     *  clauses. Survives `restore` (clauses are facts about the original problem, not
     *  trail state); pruned by [forgetLearnedClauses]. */
    internal val learnedClauseStore: ArrayList<Clause> = ArrayList()

    // Cached base factor table — `problem.factors` is immutable after construction, so hoist
    // the array reference and its size out of the per-call `problem.factors` / `.size` getters
    // that [factorAt] (a top BCP-loop method) pays on every watcher fire.
    internal val baseFactors: Array<Factor> = problem.factors
    internal val baseFactorCount: Int = problem.factors.size

    /** Clauses learned during conflict analysis. */
    val learnedClauses: List<Clause> get() = learnedClauseStore

    /** Count of binary (2-literal) clauses known — original problem clauses plus learned
     *  ones. Gates the #202 binary-resolution minimization, which is a no-op without binary
     *  clauses. Over-approximates after forgetting (never decremented), which only costs a
     *  harmless no-op pass — never correctness. */
    internal var binaryClauseCount: Int = run {
        var n = 0
        for (f in problem.factors) if (f is Clause && f.literals.size == 2) n++
        n
    }

    /** True iff any binary clause is known — the gate for binary-resolution minimization. */
    val hasBinaryClauses: Boolean get() = binaryClauseCount > 0

    /** LBD (Literal Block Distance) per learned clause, parallel to [learnedClauseStore].
     *  Glucose-style glue metric: lower = more re-usable. Forgetting policies key on
     *  this to decide which clauses to drop. */
    internal val learnedLbds: IntArrayList = IntArrayList()

    /** 1 for clauses that must survive every forgetting pass, parallel to [learnedClauseStore].
     *  Solution-blocking nogoods are the main client: dropping one re-opens an already
     *  reported leaf and the search can revisit it forever. */
    internal val learnedPermanent: IntArrayList = IntArrayList()

    /** Three-tier database tier per learned clause (#201), parallel to [learnedClauseStore],
     *  stored as [ClauseTier] ordinals. [ClauseTier.UNSET] until the reduction policy first
     *  classifies it by LBD; the policy then promotes/demotes clauses between tiers based on
     *  reuse, so the tier is persistent state rather than a pure function of LBD. */
    internal val learnedTier: IntArrayList = IntArrayList()

    /** 1 iff the learned clause has detected a conflict or forced a unit since the last
     *  reduction, parallel to [learnedClauseStore]. The three-tier reduction policy reads this
     *  to promote reused clauses and demote idle ones, then clears it for survivors. */
    internal val learnedUsedFlags: IntArrayList = IntArrayList()

    /** `problem.numFactors + learnedClauses.size`. Use this instead of `problem.numFactors`
     *  when iterating or sizing per-factor scratch in the engine. */
    val totalFactorCount: Int get() = problem.numFactors + learnedClauseStore.size

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
    internal val boolWatchPos: MutableLongIntMap = MutableLongIntMap()

    /**
     * Per-`(intVar, kind)` advisor index, the int-side analog of [boolWatchersByLit]: slot
     * `[IntEvent.pack(v, kind)]` lists the factor ids that subscribed to that event via
     * [com.eignex.klause.solver.Factor.initialIntEventWatches], so [enqueueForIntChange] can wake
     * only the factors that care about the kind of change that just happened. Sized
     * `numIntVars * IntEvent.COUNT` and populated once at construction; empty when no factor opts in
     * (the subscriptions are static — a propagator that loses interest in a variable simply ignores
     * the wake, which is sound).
     */
    internal val intEventWatchersBySlot: Array<IntArrayList> =
        if (problem.usesIntEventWatchers) {
            Array(problem.numIntVars * IntEvent.COUNT) { IntArrayList(initialCapacity = 1) }
        } else {
            emptyArray()
        }

    /**
     * Per-factor dirty-variable delta accumulator (#624): for each [com.eignex.klause.solver.Factor]
     * with [com.eignex.klause.solver.Factor.consumesIntEventDelta], the subscribed variables that
     * fired since the consumer last drained. [enqueueForIntChange] appends a variable when it wakes
     * the consumer via the advisor index ([eventDirtyMark] deduplicates), and
     * [drainIntEventDirtyVars] returns and clears the set on a fire.
     *
     * **Drift-tolerant superset.** It is never cleared on backtrack, so after a pop it may list a
     * variable whose change was undone — harmless, because the consumer diffs its own reversible
     * baseline and finds no change for a stale variable. It is therefore always a *superset* of
     * "changed since this consumer last fired": no real change is ever missed (every change to a
     * subscribed variable wakes the consumer and appends), which is the soundness-critical direction.
     * `null` per non-consuming factor; both arrays are empty when the problem has no consumer.
     */
    private val eventDirtyVars: Array<IntArrayList?> =
        if (problem.usesIntEventDeltaConsumers) arrayOfNulls(problem.numFactors) else emptyArray()
    private val eventDirtyMark: Array<IntHashSet?> =
        if (problem.usesIntEventDeltaConsumers) arrayOfNulls(problem.numFactors) else emptyArray()

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
     * bools based on int-domain state (e.g. [com.eignex.klause.solver.factor.arithmetic.ReifiedLinear])
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

    /** The relational form of each atom, parallel to [atomIntVar] / [atomThreshold]. */
    internal val atomKind: ArrayList<AtomKind> = ArrayList()

    /** Threshold value `k` for the atom. */
    internal val atomThreshold: IntArrayList = IntArrayList()

    // -------- Per-atom trail slots (LCG: order literals are trail-resident) --------
    //
    // Each materialized order literal carries the same trail metadata a bool var does:
    // the decision level it was established at and the literal-form antecedents of the
    // force. Truth is still read from the int-domain view (the two are kept in sync by
    // channeling), but level / antecedents are *stored* at the moment the bound crosses
    // the threshold rather than re-derived from a bound-change history. Parallel to
    // [atomIntVar]; one slot appended per [allocAtom]. Undone on backtrack alongside the
    // int-domain change that set them.
    //
    // [atomLvl] = -1 means "not established on the current path" (truth undetermined, or
    // a root/bake fact).

    /** Stored truth of this order literal — the canonical, BCP-cheap replacement for deriving it
     *  from [intDomains] on every clause touch (the #588 profile's dominant cost, `atomTruthOf`).
     *  0 = unassigned, 1 = true, 2 = false. Set by [wakeAtom] the instant a bound move crosses the
     *  threshold (which is exactly when the truth flips, since [propagateAtomsForVar] now visits
     *  every materialized atom of the var), cleared to 0 on backtrack by [resetAtomTrailFor]. A 0 slot
     *  on a determined atom (one materialized *after* its bound already crossed) falls back to the
     *  domain-derived [atomTruthOf] — sound, just not cached. */
    internal val atomState: IntArrayList = IntArrayList()

    /** Decision level at which this atom's current truth was established (-1 = none). */
    internal val atomLvl: IntArrayList = IntArrayList()

    /** Literal-form antecedents of this atom's current truth (null = leaf / root). */
    internal val atomAnt: ArrayList<IntArray?> = ArrayList()

    /** The reason of the bound move currently being channeled by [propagateAtomsForVar] — the
     *  literals whose conjunction forced it. [wakeAtom] stores it on each crossed atom's
     *  [atomAnt] slot (the trail-resident reason, recorded at the atom's establishment level —
     *  the canonical replacement for re-deriving it from the bound histories at conflict time). */
    internal var pendingMoveAnt: IntArray? = null

    /** Reverse lookup: packed key `(intVar << 33) | (kind << 32) | (threshold + INT_MAX)`
     *  → atomId. Allows O(1) re-allocation checks. */
    internal val atomByKey: MutableLongIntMap = MutableLongIntMap()

    /** Per-atom-lit watcher list — factor ids that fire when this atom-lit transitions
     *  to false. Mirrors [boolWatchersByLit] for atoms; keyed by atom-lit id rather than
     *  fixed-array indexed because atoms are allocated dynamically. */
    // Array-indexed by atom-lit (two slots per atom: positive at `atomId*2`, negative at
    // `atomId*2+1`), grown two slots per [allocAtom]. Replaces the former HashMap<lit, list>:
    // bool-var watchers are array-indexed ([boolWatchersByLit]), and order literals are now a
    // canonical representation, so they get the same O(1) array access in the BCP hot path
    // instead of a boxed-Int hash probe per wake. A null slot means "no watchers".
    internal val atomWatchersByLit: ArrayList<IntArrayList?> = ArrayList()

    /** For each int variable, the atoms whose truth depends on it — used to recompute
     *  atom truth and fire watchers after a successful tighten / exclude. Dense-indexed by the
     *  int-var id (always `0 until numIntVars`) so the post-tighten wake path pays no boxed-Int
     *  hash probe; a null slot means "no atoms materialised for this var yet". */
    internal val atomsByIntVar: Array<VarAtomIndex?> = arrayOfNulls(problem.numIntVars)

    /** Per-var sorted thresholds that some factor actually watches (either polarity of
     *  the atom's literal). Bound moves wake watchers by walking only this index — the
     *  full atom table grows with every reason ever materialised, but only watched atoms
     *  need eager transition wakeups; everything else is derived on demand. Dense-indexed by the
     *  int-var id like [atomsByIntVar]. */
    internal val watchedAtomsByVar: Array<VarAtomIndex?> = arrayOfNulls(problem.numIntVars)

    /** Factor ids woken by atom-lit transitions during the current propagation step.
     *  Drained alongside dirty-int / dirty-bool processing in [runToFixpoint]. */
    internal val dirtyAtomFactors: IntArrayDeque =
        IntArrayDeque(initialCapacity = 8)

    /** Allocate (or look up) the atom for `[intVar ≥ threshold]` and return its virtual
     *  variable id (past the bool var space). Pair with [Lit.make]
     *  to encode as a positive or negative literal. */
    fun atomVarGe(intVar: Int, threshold: Int): Int = allocAtom(intVar, kind = AtomKind.GE, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar ≤ threshold]`. */
    fun atomVarLe(intVar: Int, threshold: Int): Int = allocAtom(intVar, kind = AtomKind.LE, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar = value]`. The negative-polarity literal
     *  of this atom encodes `[intVar ≠ value]`; share the same atom id rather than allocating
     *  a dedicated `Ne` kind. */
    fun atomVarEq(intVar: Int, value: Int): Int = allocAtom(intVar, kind = AtomKind.EQ, threshold = value)

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
            AtomKind.GE -> if (pos) {
                tightenIntMinImpl(intVar, k, antecedents) // [v ≥ k] true → v.min ≥ k
            } else {
                tightenIntMaxImpl(intVar, k - 1, antecedents) // [v ≥ k] false → v ≤ k-1
            }

            AtomKind.LE -> if (pos) {
                tightenIntMaxImpl(intVar, k, antecedents) // [v ≤ k] true → v.max ≤ k
            } else {
                tightenIntMinImpl(intVar, k + 1, antecedents) // [v ≤ k] false → v ≥ k+1
            }

            AtomKind.EQ -> if (pos) {
                tightenIntMinImpl(intVar, k, antecedents) && // [v = k] true → v = k
                    tightenIntMaxImpl(intVar, k, antecedents)
            } else {
                excludeIntValueImpl(intVar, k, antecedents) // [v = k] false → v ≠ k
            }
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
    // Atom-table slots are *not* logged per-mutation: [undoTo] reconciles them after restoring
    // each int domain — [resetAtomTrailFor] / [resetAtomTrailForCarve] clear exactly the order
    // literals whose truth flips back to undetermined over the widened range, leaving every
    // still-determined atom its trail-resident level / reason (#708). The unified pin trail
    // [boolPinOrder] is truncated to the mark, so atoms allocated/established after it are dropped
    // wholesale.

    /** Per-variable unassign sink invoked by [undoTo]; see its doc. Null = no subscriber. */
    var unassignListener: ((Int) -> Unit)? = null
    internal val undoTag = IntArrayList()
    internal val undoVar = IntArrayList()
    internal val undoLevel = IntArrayList() // int: prior intLevel
    internal val undoMinLvl = IntArrayList() // int: prior intMinLevel
    internal val undoMaxLvl = IntArrayList() // int: prior intMaxLevel
    internal val undoMinReason = IntArrayList() // int: prior intMinReason
    internal val undoMaxReason = IntArrayList() // int: prior intMaxReason
    internal val undoDomain = ArrayList<IntDomain?>() // int: prior intDomains[v]
    internal val undoMinAnt = ArrayList<IntArray?>() // int: prior intMinAntecedents
    internal val undoMaxAnt = ArrayList<IntArray?>() // int: prior intMaxAntecedents
    internal val undoHoleHistLen = IntArrayList() // int: prior holeHist length for the var

    /** Separate trail for [Trailed] reversible cells (incremental factor state). Kept apart from the
     *  bool/int undo columns above so the hot domain-mutation path pays nothing; a [LevelMark]
     *  captures its size, [undoTo] replays it top-down. Each entry is a cell that changed (the cell
     *  holds its own typed prior-value stack), so restore is box-free. See [Trailed] / `Reversible.kt`. */
    internal val revTrail = ArrayList<Trailed>()

    /** Shared empty payload map for marks taken when no [SnapshottablePayload] is live —
     *  avoids a per-push allocation in the common (no Table/Mdd) case. `emptyMap()` returns
     *  a singleton, so this never allocates. Read-only: [undoTo] only iterates it. */
    internal val emptyPayloads: Map<Int, SnapshottablePayload> = emptyMap()

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

    /** Current undo-log size. A [LevelMark] captures this; iterating [undoVarAt] /
     *  [undoIsBoolAt] over `[base, undoTop)` enumerates exactly the variables mutated since
     *  position `base` — used by [PropagationSession] to compute the implied-fact diff of a
     *  push incrementally instead of scanning every variable. */
    val undoTop: Int get() = undoTag.size

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

    init {
        if (problem.usesIntEventWatchers) {
            for (fid in 0 until problem.numFactors) {
                val watches = problem.factors[fid].initialIntEventWatches ?: continue
                for (packed in watches) intEventWatchersBySlot[packed].add(fid)
            }
        }
    }

    init {
        if (problem.usesIntEventDeltaConsumers) {
            for (fid in 0 until problem.numFactors) {
                if (problem.factors[fid].consumesIntEventDelta) {
                    eventDirtyVars[fid] = IntArrayList()
                    eventDirtyMark[fid] = IntHashSet()
                }
            }
        }
    }

    init {
        seeded = seedAssumptions(assumptions)
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

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

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

    /** Append a reversible cell to [revTrail] so its latest mutation is undone on the next
     *  [undoTo] past this point. Called by [RevInt] / [RevRef] / [RevIntArray] on each logged write. */
    internal fun logReversible(cell: Trailed) {
        revTrail.add(cell)
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
        internal val revSize: Int,
    )

    /**
     * Run propagation until no factor can derive more. When [allFactors] is true, every
     * factor is enqueued initially (the usual one-shot path). When false, only factors
     * touching variables currently in the dirty queues are enqueued — for incremental use
     * by a session that just applied a pin and wants to extend the fixpoint.
     *
     * Returns `null` on success (state is at fixpoint); otherwise the conflict-levels set.
     *
     * [cancellation] is polled only on the full-propagation ([allFactors]) path — the one-time
     * bake / session-init fixpoint, the one place an uncancellable run can wedge on a slow
     * propagator over wide domains. The per-node search path always passes
     * `allFactors == false`, so the counter and the token call below are never reached there:
     * the BCP hot loop pays nothing beyond a dead, perfectly-predicted branch. When the token
     * fires the drain stops early and returns `null`; the partial fixpoint is sound (it only
     * ever tightens), and the deadline that fired it makes the caller abort promptly anyway.
     */
    internal fun runToFixpoint(
        allFactors: Boolean,
        initialFactor: Int = -1,
        cancellation: Cancellation = Cancellation.Never,
    ): IntArray? {
        // Clear conflict bookkeeping from any prior run — reusing the state across pushes
        // would otherwise mix old seeds into a new conflict's core.
        conflictSeedFactors.clear()
        val factorCount = totalFactorCount
        propBegin(factorCount)
        var fireCount = 0
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
                enqueueForIntChange(v)
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
            if (allFactors && (fireCount++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
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
                enqueueForIntChange(v)
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

    /**
     * Record that int var [v] just changed: add it to [dirtyInts] and, when typed int-event
     * watchers are active, OR the [kindMask] (one or more `IntEvent.*_BIT`s) into [dirtyIntKinds]`[v]`
     * so [enqueueForIntChange] wakes the right advisors. [IntEvent.FIXED_BIT] is added automatically
     * when the post-mutation domain is a singleton, so a mutator only passes the bound/value kind it
     * caused. No-op beyond the dirty enqueue when no factor subscribes (the mask array is empty).
     */
    internal fun markIntDirty(v: Int, kindMask: Int) {
        dirtyInts.addLast(v)
        if (dirtyIntKinds.isEmpty()) return
        val d = intDomains[v]
        val mask = if (d.min == d.max) kindMask or IntEvent.FIXED_BIT else kindMask
        dirtyIntKinds[v] = dirtyIntKinds[v] or mask
    }

    /**
     * Enqueue every factor that should fire on `v`'s domain change, mirroring [enqueueForBoolChange]
     * on the int side: the occurrence-list factors that don't subscribe to typed events on `v`
     * ([com.eignex.klause.solver.Problem.nonIntEventWatcherIntOccurrences]), plus — for each
     * [IntEvent] kind that actually occurred (read from [dirtyIntKinds]) — the advisors registered
     * in [intEventWatchersBySlot]. The kind mask is cleared after dispatch so it doesn't leak into a
     * later change to the same variable. When no factor subscribes this reduces to the plain
     * occurrence-list walk over [com.eignex.klause.solver.Problem.intOccurrences].
     */
    private fun enqueueForIntChange(v: Int) {
        for (fid in problem.nonIntEventWatcherIntOccurrences[v]) propEnq(fid)
        if (dirtyIntKinds.isEmpty()) return
        val mask = dirtyIntKinds[v]
        if (mask == 0) return
        dirtyIntKinds[v] = 0
        var kind = 0
        while (kind < IntEvent.COUNT) {
            if (mask and (1 shl kind) != 0) {
                val list = intEventWatchersBySlot[IntEvent.pack(v, kind)]
                for (i in 0 until list.size) {
                    val fid = list[i]
                    propEnq(fid)
                    accumulateDirtyVar(fid, v)
                }
            }
            kind++
        }
    }

    /** Record [v] in [fid]'s dirty-variable delta if [fid] consumes it ([eventDirtyMark] dedups).
     *  No-op for non-consumers and when no factor in the problem consumes a delta. */
    private fun accumulateDirtyVar(fid: Int, v: Int) {
        if (eventDirtyMark.isEmpty()) return
        val mark = eventDirtyMark[fid] ?: return
        val vars = eventDirtyVars[fid] ?: return
        if (mark.add(v)) vars.add(v)
    }

    /**
     * Drain and return the dirty-variable delta accumulated for [factorId] since it last drained —
     * the subscribed variables that fired, a superset of those actually changed since the consumer's
     * last fire (see [eventDirtyVars]). A consumer ([com.eignex.klause.solver.Factor.consumesIntEventDelta])
     * calls this on a fire and recovers the exact removed values by diffing its own reversible
     * baseline for these variables. Clears the accumulator (keeping it in lockstep with the
     * consumer's baseline update), so call it exactly when the baseline is advanced. Returns an empty
     * array for a non-consuming factor.
     */
    fun drainIntEventDirtyVars(factorId: Int): IntArray {
        if (eventDirtyVars.isEmpty()) return EmptyIntArray
        val list = eventDirtyVars[factorId] ?: return EmptyIntArray
        if (list.size == 0) return EmptyIntArray
        val out = list.toIntArray()
        list.clear()
        eventDirtyMark[factorId]?.clear()
        return out
    }
}
