package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntObjectMap

/** Sentinel for [PropagationState.propagateAtomsForVar]'s carved-value parameter. */
internal const val NO_CARVE: Long = Long.MIN_VALUE

/** Blocking-literal slot with no blocker: the watcher always fires. Lit ids are
 *  non-negative ([Lit.make] = `var shl 1 | sign`), so −1 is a safe sentinel. */
internal const val NO_BLOCKER: Int = -1

/** Poll the cancellation token once every `CANCEL_POLL_MASK + 1` factor fires when a real token
 *  is supplied (power-of-two minus one so the gate is a single `and`). */
private const val CANCEL_POLL_MASK: Int = 1023

/** Fires a single [PropagationState.runToFixpoint] must exceed before its cancellation poll engages.
 *  Below it the deadline is left to the engine's between-decision poll — only a runaway fixpoint (an
 *  O(span) bound crawl) does this many fires in one call, so normal propagation and resumable slicing
 *  stay untouched. A multiple of `CANCEL_POLL_MASK + 1` so the aligned poll lands exactly on it.
 *
 *  NOTE: a floor counts *fires*, but a deadline is spent in *work*. A fixpoint whose fires each allocate
 *  order atoms can burn tens of seconds in far fewer fires than this trips, leaving `-t` ignored; see
 *  [com.eignex.klause.backtrack.BacktrackParams.propagationCancelFloor] for the per-run override. */
internal const val PROPAGATION_CANCEL_FLOOR: Int = 1 shl 20

/**
 * Mutable working state passed to [Propagator.propagate]. Tracks the currently-known pinned bool
 * values and the (tightened) int domains, plus a **decision level** per pinned variable for
 * conflict-driven backjumping.
 *
 *  - Decisions (external pins from the driver / session) bump the level monotonically.
 *  - Implied pins (from factor propagation) inherit the maximum level of the variables the
 *    factor reads — i.e. the deepest decision that contributed.
 *  - On contradiction, the set of decision levels touched by the failing factor is what the
 *    driver reports as [PropagationResult.Unsat.conflictLevels].
 *
 *  Factors don't see the level machinery directly — they only call `pinBool` /
 *  `tightenIntMin` / `tightenIntMax` / `setInt`. The driver sets `currentLevel`
 *  to the inherited level before each factor invocation; mutators read it.
 */
class PropagationState(
    /** The problem being propagated. */
    val problem: Problem,
    assumptions: Assumptions,
    /**
     * Incremental-presolve mode. When `true` the state supports mid-life factors — the
     * presolve session appends factors and tombstones others between rounds via [addMidlifeFactor] /
     * [tombstoneFactor] instead of rebuilding a fresh [Problem] each pass. The int-event
     * machinery is forced on so a mid-life factor that subscribes to typed events wakes correctly even
     * when the initial problem had no such factor. Defaults to `false`: a search state never adds
     * presolve factors, so every allocation and branch below drops out.
     */
    internal val incremental: Boolean = false,
    /**
     * Opt into the native-SAT BCP lane. When `true` and the problem is
     * [com.eignex.klause.solver.Problem.isNativeSatEligible], propagation runs through [NativeSatState]
     * — an arena-packed two-watched-literal loop — instead of the general factor-queue fixpoint, and
     * the general per-literal / occurrence watch indices are left unregistered. Defaults to `false`.
     */
    internal val nativeSat: Boolean = false,
    /**
     * Opt into pseudo-Boolean cutting-planes conflict learning. When `true` and the
     * problem is pure-Boolean, [ConflictAnalyzer] runs a [PbConflictResolvent] (with a clause-resolvent
     * fallback) so conflicts on pseudo-Boolean constraints learn PB nogoods, not just clauses. Defaults
     * to `false`; ignored on problems with integer variables (order-literal atoms have no PB reason).
     */
    internal val pbLearning: Boolean = false,
) {
    /** Two-bit-per-var three-valued pin store. [boolAssigned] says whether the variable has
     *  a definite value; [boolValueBits] holds the value when assigned (ignored otherwise).
     *  Backed by [Bits] — packed `LongArray`, 8× cache-denser than an `Array<Boolean?>`
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

    /** The stable root domains this state was seeded from — the search root, read by propagators for a
     *  variable's original bounds. Decoupled from [problem] so the bake can seed from raw declared domains
     *  and search from baked ones without the machinery caring which. */
    val rootDomains: Array<IntDomain> = problem.requireFiniteIntDomains()

    /** Per-int current domain (copy of [rootDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = Array(problem.numIntVars) { rootDomains[it] }

    /** Vars whose pin/domain changed since the driver last drained them. Primitive int
     *  ring buffers to avoid the autoboxing tax `ArrayDeque<Int>` pays on every push/poll. */
    internal val dirtyBools: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
    internal val dirtyInts: IntArrayDeque =
        IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))

    /** Typed int-event wakeup machinery (advisor index, pending-kind masks, delta accumulators) —
     *  see [IntEventMachinery]. Empty when no factor opts in; forced live in [incremental] mode. */
    internal val intEvents: IntEventMachinery = IntEventMachinery(problem, incremental)

    // [propQueue] is the reusable factor worklist; [propStamp] is a per-factor "currently queued"
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
    internal fun propEnq(fid: Int) {
        if (propStamp[fid] != propGen) {
            propStamp[fid] = propGen
            propQueue.addLast(fid)
        }
    }

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    // Decision-level plumbing.

    /** Decision level when each bool was first pinned (-1 = unpinned). */
    val boolLevel: IntArray = IntArray(problem.numBoolVars) { -1 }

    /** Deepest decision level contributing to this int var's current domain (-1 = untouched). */
    val intLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    // Per-side split of [intLevel]: the decision level at which the *current* lower (resp. upper)
    // bound was established. [intLevel] is the max of the two. Lets a current-bound order literal
    // `[v ≥ d.min]` / `[v ≤ d.max]` reconstruct its exact (often shallower) level from a single
    // slot, which is why [atomLevelForConflict] is a plain stored-slot read rather than a
    // bound-history search. -1 = the bound
    // is still at its root value (a level-0 global fact). Logged/restored by the undo trail.
    internal val intMinLevel: IntArray = IntArray(problem.numIntVars) { -1 }
    internal val intMaxLevel: IntArray = IntArray(problem.numIntVars) { -1 }

    // Per-int-var interior-hole carve history: the (value, level, reason) at which each
    // search-time interior carve happened. Bound moves need no such history — order literals carry
    // their own level/reason on their trail slots. An eq atom ruled out by an
    // interior hole materialized after the carve reads its level/reason from here. Lazily
    // allocated, maintained while [undoLogging], truncated on backtrack via the undo log.
    internal val holeHistAnt: Array<ArrayList<IntArray?>?> = arrayOfNulls(problem.numIntVars)
    internal val holeHistVal: Array<LongArrayList?> = arrayOfNulls(problem.numIntVars)
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
            val orig = rootDomains[v]
            if (d.min > orig.min) {
                // Dedup on the atom's virtual-var id: it is a unique identity per (v, GE, d.min),
                // so the threshold value need not be packed into the key (it may exceed 32 bits).
                val atom = atomVarGe(v, d.min)
                if (seen.add(atom.toLong())) out.add(Lit.make(atom, false))
            }
            if (d.max < orig.max) {
                val atom = atomVarLe(v, d.max)
                if (seen.add(atom.toLong())) out.add(Lit.make(atom, false))
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

    /** Factor whose [Propagator.propagate] is currently running. Read by the impl methods so
     *  state changes can be attributed back to a factor. `-1` between factor invocations
     *  (decisions, assumption seeding) — those pins/tightenings record `reason = -1`. */
    internal var currentFactor: Int = -1

    /** Cumulative count of factor-forced assignments (bool pins + int tightens applied while
     *  a factor's `propagate` is running, i.e. `currentFactor >= 0`). Decisions and seed
     *  pins don't count. Read via [PropagationSession.propagationCount] for the
     *  `propagations` stat; monotonic for the life of the state. */
    internal var propagations: Long = 0L

    /** Set once [runToFixpoint]'s cancellation poll fires — the deadline passed mid-fixpoint, so the
     *  state is at a partial (only-tightened, never over-pruned) fixpoint. Sticky: the deadline is
     *  monotone, so once tripped the whole solve is aborting. The engine reads it through
     *  [PropagationSession.fixpointCancelled] and returns `BudgetCapped` rather than treating the
     *  under-propagated state as a solved leaf (which would be an unsound SAT). */
    internal var runCancelled: Boolean = false

    /** Per-call fire floor before [runToFixpoint]'s cancellation poll engages; see
     *  [PROPAGATION_CANCEL_FLOOR]. Overridable so a test can force the runaway-fixpoint path
     *  deterministically instead of having to drive a million-fire propagation. */
    internal var cancelFloor: Int = PROPAGATION_CANCEL_FLOOR

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

    /** Reused scratch for [com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents] — a single
     *  wide pseudo-Boolean factor pins many literals per sweep, each rebuilding the same assigned-literal
     *  antecedent set, so a per-call [IntHashSet] alloc and its regrowth dominated wide-WBO root bake. */
    internal val pbAntecedentSeen: IntHashSet = IntHashSet()
    internal val pbAntecedentBuf: IntArrayList = IntArrayList()

    /** Var whose pinned value was contradicted by a decision-level pin attempt (i.e.
     *  `pinBoolAsDecision` tried to set the opposite value of an existing pin). `-1` when
     *  no such conflict is active. Lets the conflict analyzer learn from a
     *  decision-vs-prior-pin contradiction by seeding from the prior pin's antecedents
     *  plus the just-decided lit — without this, the analyzer falls back to chronological
     *  backtrack on any conflict that doesn't come from a factor's `propagate`. */
    internal var lastDecisionConflictVar: Int = -1

    /*
     * Per-factor mutable scratch space — mirrors [com.eignex.klause.localsearch.LocalSearchState.refPayload]
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

    /** Learned-clause database — the [ConflictAnalyzer] nogoods plus their policy columns.
     *  See [LearnedClauseDb]; registration / lookup / pruning live in `ClauseDb.kt`. */
    internal val learned: LearnedClauseDb = LearnedClauseDb(
        binaryClauseCount = run {
            var n = 0
            for (f in problem.factors) if (f is Clause && f.literals.size == 2) n++
            n
        },
    )

    // Cached base factor table — `problem.factors` is immutable after construction, so hoist
    // the array reference and its size out of the per-call `problem.factors` / `.size` getters
    // that [factorAt] (a top BCP-loop method) pays on every watcher fire.
    internal val baseFactors: Array<out Propagator> = problem.propagators
    internal val baseFactorCount: Int = problem.factors.size

    // Occurrence-list wakeup arrays cached off [problem] once at construction: they are lazily
    // built on [Problem] (deferred entirely for a presolve pass-view), so read them here — where a
    // state is always over a fully-baked problem — to force them once instead of paying a delegated
    // lazy access on every wakeup in the BCP hot loop.
    private val nonBoolWatcherOcc: Array<IntArray> = problem.nonBoolWatcherBoolOccurrences
    private val nonIntEventWatcherOcc: Array<IntArray> = problem.nonIntEventWatcherIntOccurrences

    /** Constraints learned during conflict analysis (clause-only today; see [LearnedClauseDb]). */
    internal val learnedClauses: List<LearnedPropagator> get() = learned.store

    /** True iff any binary clause is known — the gate for binary-resolution minimization. */
    val hasBinaryClauses: Boolean get() = learned.binaryClauseCount > 0

    /** Mid-life presolve factor overlay for [incremental] mode — see [MidlifeFactors]. */
    internal val midlife: MidlifeFactors = MidlifeFactors()

    /** `problem.numFactors + learnedClauses.size`. Use this instead of `problem.numFactors`
     *  when iterating or sizing per-factor scratch in the engine. */
    val totalFactorCount: Int get() = problem.numFactors + learned.size + midlife.store.size

    /** Per-literal bool watcher index (watch lists, blockers, back-pointers) —
     *  see [BoolWatcherIndex]. Mutated by `Watches.kt` and the [forgetLearnedClauses] compaction. */
    internal val watches: BoolWatcherIndex = BoolWatcherIndex(problem.numBoolVars)

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
     * bools based on int-domain state (e.g. [com.eignex.klause.factor.arithmetic.ReifiedLinear])
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

    /** Bound-atom registry (LCG with virtual int-bound literals) — see [AtomStore]. Atom truth,
     *  level, and antecedents are snapshotted when the bound crosses (never re-derived from a
     *  bound-change history); restore drops atoms established after the snapshot point.
     *  Allocation is lazy via [allocAtom]; the channeling and wake logic lives in `Atoms.kt`. */
    internal val atoms: AtomStore = AtomStore(problem.numIntVars)

    /** LCG channeling clauses (`[x≥k+1]→[x≥k]`, `[x=k]↔[x≥k]∧[x≤k]`, …) accumulated as atoms
     *  materialize, awaiting registration. [allocAtom] appends here; [flushPendingChanneling]
     *  drains it into the learned-clause store as permanent clauses at a safe propagation boundary
     *  (never mid-conflict-analysis, where `allocAtom` also fires). */
    internal val pendingChanneling: ArrayList<IntArray> = ArrayList()

    /** Allocate (or look up) the atom for `[intVar ≥ threshold]` and return its virtual
     *  variable id (past the bool var space). Pair with [Lit.make]
     *  to encode as a positive or negative literal. */
    fun atomVarGe(intVar: Int, threshold: Long): Int = allocAtom(intVar, kind = AtomKind.GE, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar ≤ threshold]`. */
    fun atomVarLe(intVar: Int, threshold: Long): Int = allocAtom(intVar, kind = AtomKind.LE, threshold = threshold)

    /** Allocate (or look up) the atom for `[intVar = value]`. The negative-polarity literal
     *  of this atom encodes `[intVar ≠ value]`; share the same atom id rather than allocating
     *  a dedicated `Ne` kind. */
    fun atomVarEq(intVar: Int, value: Long): Int = allocAtom(intVar, kind = AtomKind.EQ, threshold = value)

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

    /** Assign [lit] to true, recording [antecedents] (the forcing clause's other literals).
     *  Dispatches between bool pins ([pinBool]) and first-class atom assignment ([pinAtomLit]).
     *  Returns `false` on conflict. */
    fun pinLit(lit: Int, antecedents: IntArray? = null): Boolean {
        val v = Lit.variable(lit)
        val pos = Lit.isPositive(lit)
        if (v < problem.numBoolVars) return pinBoolImpl(v, pos, antecedents)
        return pinAtomLit(atomIdOf(v), pos, antecedents)
    }

    /**
     * Assign atom [atomId] to [newT] as a first-class LCG literal: stamp its truth, level and
     * forcing-clause [antecedents] on the atom trail directly, then reconcile the int domain to
     * match. A clause (channeling / learned / propagator-explanation) that forces an atom thus
     * records *itself* as the atom's reason — no reconstruction — which is what makes conflict
     * analysis resolve through real clauses. For a bound the domain already implies (a looser atom
     * cascaded by a monotonicity clause) the reconcile tighten is a no-op and the stamped truth
     * stands; for a genuine narrowing (a learned clause pushing a new bound) it narrows the domain
     * and channels onward. Returns `false` on conflict.
     */
    internal fun pinAtomLit(atomId: Int, newT: Boolean, antecedents: IntArray?): Boolean {
        val target = if (newT) 1 else 2
        val cur = atoms.truth[atomId]
        if (cur == target) return true // already established on this path
        val intVar = atoms.intVar[atomId]
        val k = atoms.threshold[atomId]
        // A clause propagates a literal only when it is *genuinely unassigned*. The stored `0` is a
        // cache miss, not proof of that — an atom the live domain already entails (at some lower
        // level) also reads `0` when it was materialized after its bound crossed. Re-stamping such an
        // atom at the current level, with this clause as its reason, would mis-date its truth (really
        // decided earlier) and mis-attribute it, corrupting the levels 1UIP backjumps on. So stamp
        // directly only when the domain does not yet decide the literal — a real narrowing this clause
        // is forcing now. Otherwise fall through: an entailed atom keeps its domain-derived level and
        // channeling reason (a no-op reconcile), and a domain-refuted one conflicts in the reconcile.
        if (cur == 0 && atomTruthOf(intVar, atoms.kind[atomId], k) == null) {
            // Stamp the forcing clause as this atom's reason directly — the literal-primary step that
            // lets conflict analysis and minimization resolve through a real, valid clause instead of
            // a reconstructed bound reason. Recorded on the reversible atom trail first so backtrack
            // restores it exactly, and before the reconcile so the narrowing's own [wakeAtom] finds
            // the truth already set and keeps this clause reason rather than a channeling one.
            recordAtomTruthChange(atomId)
            boolPinOrder.add(problem.numBoolVars + atomId)
            atoms.truth[atomId] = target
            atoms.lvl[atomId] = currentLevel
            atoms.ant[atomId] = antecedents
            // No watcher wake here: the reconcile below narrows the domain across this atom's
            // threshold, and that narrowing's own [wakeAtom] fires the false-literal watchers (it
            // finds the truth already stamped and takes its early-return wake path). Waking here as
            // well would double-drive the channeling clauses and let them re-pin adjacent same-var
            // atoms ahead of the order the narrowing establishes them in — fabricating a same-var
            // coupling that 1UIP resolves into an unsound nogood.
        }
        return when (atoms.kind[atomId]) {
            AtomKind.GE -> if (newT) {
                tightenIntMinImpl(intVar, k, antecedents) // [v ≥ k] true → v.min ≥ k
            } else {
                tightenIntMaxImpl(intVar, k - 1, antecedents) // [v ≥ k] false → v ≤ k-1
            }

            AtomKind.LE -> if (newT) {
                tightenIntMaxImpl(intVar, k, antecedents) // [v ≤ k] true → v.max ≤ k
            } else {
                tightenIntMinImpl(intVar, k + 1, antecedents) // [v ≤ k] false → v ≥ k+1
            }

            AtomKind.EQ -> if (newT) {
                tightenIntMinImpl(intVar, k, antecedents) && // [v = k] true → v = k
                    tightenIntMaxImpl(intVar, k, antecedents)
            } else {
                excludeIntValueImpl(intVar, k, antecedents) // [v = k] false → v ≠ k
            }
        }
    }

    /**
     * Chronological journal of bool pins, decisions and implications interleaved. Used by
     * [com.eignex.klause.propagation.ConflictAnalyzer] to walk the implication
     * graph in reverse pin order — the standard 1UIP loop needs to resolve against the
     * *most recently pinned* variable in the current conflict, which requires this
     * append-only trail.
     */
    internal val boolPinOrder: IntArrayList =
        IntArrayList(initialCapacity = problem.numBoolVars.coerceAtLeast(8))

    /** Per-variable unassign sink invoked by [undoTo]; see its doc. Null = no subscriber. */
    var unassignListener: ((Int) -> Unit)? = null

    /** Undo trail for bool/int cells (replaces per-level full-array snapshots) — see [UndoLog].
     *  Appended to by the `log*` functions in `Undo.kt`. Atom-table slots ride their own reversible
     *  trail ([AtomStore.undoAtomId] and parallels); [undoTo] replays both, with the pin trail
     *  [boolPinOrder] truncated to the mark so atoms established after it are dropped wholesale. */
    internal val undo: UndoLog = UndoLog()

    /** Shared empty payload map for marks taken when no [SnapshottablePayload] is live —
     *  avoids a per-push allocation in the common (no Table/Mdd) case. `emptyMap()` returns
     *  a singleton, so this never allocates. Read-only: [undoTo] only iterates it. */
    internal val emptyPayloads: MutableIntObjectMap<SnapshottablePayload> = MutableIntObjectMap(1)

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

    // Incremental objective lower bound: the always-on trivial bound, kept O(1) per node.
    // The trivial lower bound on a `Σ boolWeights·x` objective under the current partial assignment is
    //   objective.constant + Σ_b contribution(b) + <integer part>, where
    //   contribution(b) = pinned ? (value ? w : 0) : min(w, 0).
    // Pinning a bool var raises the bound by a nonnegative penalty, so the bool part is maintained on
    // the reversible trail instead of rescanned every node — that scan dominated CPU on large
    // pseudo-Boolean optimization (a node visits O(numBoolVars) here otherwise). Installed lazily at the
    // root; the integer part (usually empty for pseudo-Boolean) stays the caller's per-node scan.
    private var objBoolWeights: LongArray? = null
    private var objBoolBaseConst: Long = 0L
    private var objBoolPenalty: RevLong? = null

    /** Whether the incremental objective bool lower bound is live (see [objectiveBoolLowerBound]). */
    internal val objectiveBoolBoundInstalled: Boolean get() = objBoolWeights != null

    /**
     * Install the incremental objective bool lower bound from [weights] (indexed by bool var id). A no-op
     * unless at the root (`currentLevel == 0`) and not already installed: the baseline sums the current
     * level-0 contributions, which are never undone, so subsequent pins/undos maintain the bound soundly
     * on the reversible trail. Returns true once (or while) the incremental bound is live; false when it
     * declined (not at root, or the baseline overflowed) so the caller rescans instead.
     */
    internal fun installObjectiveBoolBound(weights: LongArray): Boolean {
        if (objBoolWeights != null) return true
        if (currentLevel != 0) return false
        var base = 0L
        val nb = minOf(problem.numBoolVars, weights.size)
        for (b in 0 until nb) {
            val w = weights[b]
            if (w == 0L) continue
            val contribution = when {
                boolValueBits.get(b) && boolAssigned.get(b) -> w
                boolAssigned.get(b) -> 0L
                w < 0L -> w
                else -> 0L
            }
            val next = base + contribution
            if (((base xor next) and (contribution xor next)) < 0L) return false // overflow ⇒ decline
            base = next
        }
        objBoolBaseConst = base
        objBoolPenalty = RevLong(this, 0L)
        objBoolWeights = weights
        return true
    }

    /** Fold the pin of bool [v] to [value] into the incremental objective bool lower bound. The penalty
     *  is `contribution(pinned) − contribution(unpinned) = value ? max(w,0) : max(−w,0)`, always ≥ 0; the
     *  reversible cell restores it on backtrack. */
    internal fun bumpObjectiveBoolBound(v: Int, value: Boolean) {
        val weights = objBoolWeights ?: return
        if (v >= weights.size) return
        val w = weights[v]
        if (w == 0L) return
        val penalty = if (value) (if (w > 0L) w else 0L) else (if (w < 0L) -w else 0L)
        if (penalty != 0L) objBoolPenalty?.let { it.set(it.value + penalty) }
    }

    /** The incremental bool part of the trivial objective lower bound — `Σ_b contribution(b)` over the
     *  current assignment, excluding the objective constant and any integer terms. Valid only when
     *  [objectiveBoolBoundInstalled]; O(1). */
    internal fun objectiveBoolLowerBound(): Long = objBoolBaseConst + (objBoolPenalty?.value ?: 0L)

    /** Current undo-log size. A [LevelMark] captures this; iterating [undoVarAt] /
     *  [undoIsBoolAt] over `[base, undoTop)` enumerates exactly the variables mutated since
     *  position `base` — used by [PropagationSession] to compute the implied-fact diff of a
     *  push incrementally instead of scanning every variable. */
    val undoTop: Int get() = undo.size

    /** Native-SAT BCP engine when this state opted in and the problem qualifies; null on the
     *  general LCG path. Its construction reads [boolPinOrder] and the clause arena (both live above),
     *  so it is declared after them. */
    internal val nativeEngine: NativeSatState? =
        if (nativeSat && problem.isNativeSatEligible) NativeSatState(this) else null

    /** Seed reason (all-false clause literals) stashed by [NativeSatState] on a conflict, so
     *  [PropagationSession] can drive 1UIP through [ConflictAnalyzer.analyzeConflictClause] without a
     *  factor lookup. Cleared by [undoTo]. */
    internal var nativeConflictReason: IntArray? = null

    init {
        // The native lane replaces the per-literal / occurrence watch indices with its own arena
        // watches, so it skips base-factor registration entirely.
        if (nativeEngine == null) {
            for (fid in 0 until problem.numFactors) registerFactor(fid, problem.propagators[fid])
        }
    }

    /** Install factor [fid]'s static wakeup subscriptions into the per-literal / per-int-event advisor
     *  indices and its delta-consumer accumulators. Factored out of the constructor loops so an
     *  incremental presolve session can register a factor added mid-run without rebuilding the whole
     *  state; the constructor just calls it once per initial factor. Order-preserving: appending in
     *  factor-id order reproduces the original three-pass construction exactly. */
    private fun registerFactor(fid: Int, propagator: Propagator) {
        val watchers = propagator.initialBoolWatchers
        if (watchers != null) {
            val blockers = propagator.initialBoolWatcherBlockers
            for (i in watchers.indices) installLitWatch(watchers[i], fid, blockers?.getOrNull(i) ?: NO_BLOCKER)
        }
        // Base factors' int-event subscriptions are prebuilt into the CSR at [IntEventMachinery]
        // construction; only mid-life factors (fid past the base count) subscribe here, into the overflow.
        if (intEvents.on && fid >= baseFactorCount) {
            propagator.initialIntEventWatches?.let { for (packed in it) intEvents.subscribeMidlife(packed, fid) }
        }
        if (intEvents.deltaOn && propagator.consumesIntEventDelta) {
            intEvents.dirtyVars[fid] = IntArrayList()
            intEvents.dirtyMark[fid] = IntHashSet()
        }
    }

    /** True iff factor id [fid] has not been tombstoned. */
    internal fun factorAliveAt(fid: Int): Boolean = fid !in midlife.tombstoned

    /**
     * Append presolve [factor] to the live state as a mid-life factor and return its stable id.
     * Installs its watcher subscriptions ([registerFactor]) and its occurrence-list wakeup overlay
     * ([registerMidlifeOccurrences]), and grows [refPayload] by one slot so the factor's
     * `state.refPayload[fid]` access stays in bounds. The factor does NOT fire here; the session drives
     * a [runToFixpoint] afterwards (seeding it via `initialFactor` or `allFactors`).
     *
     * Only valid in [incremental] mode. A factor that consumes the int-event delta (a symmetry or
     * structural pass can add one) is supported: the per-factor accumulators grow by one slot here so
     * [registerFactor] can install it, and the delta machinery is always live in incremental mode.
     */
    internal fun addMidlifeFactor(factor: Factor): Int {
        require(incremental) { "mid-life factors require an incremental PropagationState" }
        val prop = factor.asPropagator()
        val fid = totalFactorCount
        midlife.store.add(prop)
        midlife.factors.add(factor)
        refPayloadStore.add(null)
        // Grow the delta accumulators in lockstep so a mid-life consumer's `intEvents.dirtyVars[fid]` slot
        // exists before [registerFactor] populates it (delta machinery is always live in incremental mode).
        if (intEvents.deltaOn) {
            intEvents.dirtyVars.add(null)
            intEvents.dirtyMark.add(null)
        }
        if (prop is ClausePropagator && prop.literals.size == 2) learned.binaryClauseCount++
        registerFactor(fid, prop)
        registerMidlifeOccurrences(fid, factor, prop)
        return fid
    }

    /**
     * Tombstone factor [fid] (base or mid-life): mark it dropped ([factorAt] then returns [NoPropagator],
     * so it never fires again) and clear its [refPayload] slot. The id is retired, never reused — the
     * slot must stay cleared so a later add can't inherit a stale payload (the wrong-optimum guard). The
     * factor's entries linger in the watcher indices and occurrence lists; they wake a [NoPropagator],
     * a no-op.
     */
    internal fun tombstoneFactor(fid: Int) {
        require(incremental) { "tombstoning requires an incremental PropagationState" }
        if (!midlife.tombstoned.add(fid)) return
        refPayloadStore[fid] = null
    }

    /**
     * Register mid-life factor [fid]'s occurrence-list wakeup, mirroring [Problem.nonBoolWatcherBoolOccurrences]
     * / [Problem.nonIntEventWatcherIntOccurrences]: a factor using per-literal bool watchers is excluded
     * from every bool var's overlay (it wakes through [BoolWatcherIndex.byLit]); on the int side a var is
     * excluded only when the factor subscribes to a typed event on *that* var. The overlays are allocated
     * lazily on first need.
     */
    private fun registerMidlifeOccurrences(fid: Int, factor: Factor, prop: Propagator) {
        if (prop === NoPropagator) return
        if (prop.initialBoolWatchers == null) {
            val occ = midlife.boolOccurrences ?: Array(
                problem.numBoolVars,
            ) { IntArrayList() }.also { midlife.boolOccurrences = it }
            for (v in factor.boolVars) occ[v].add(fid)
        }
        val intVars = factor.intVars
        if (intVars.isNotEmpty()) {
            val watched: IntHashSet? = prop.initialIntEventWatches?.let { ws ->
                IntHashSet(ws.size).apply { for (w in ws) add(IntEvent.intVarOf(w)) }
            }
            val occ = midlife.intOccurrences ?: Array(
                problem.numIntVars,
            ) { IntArrayList() }.also { midlife.intOccurrences = it }
            for (v in intVars) if (watched?.contains(v) != true) occ[v].add(fid)
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
    fun tightenIntMin(v: Int, lo: Long): Boolean = tightenIntMinImpl(v, lo, null)

    /** Variant that records [antecedents] — bool literals (false in the current state)
     *  whose collective truth forced this lower-bound tightening. Used by the conflict
     *  analyzer to walk the implication graph backwards through int-domain factors. */
    fun tightenIntMin(v: Int, lo: Long, antecedents: IntArray?): Boolean = tightenIntMinImpl(v, lo, antecedents)

    /** Lower int `v`'s upper bound to [hi]; returns false on conflict. */
    fun tightenIntMax(v: Int, hi: Long): Boolean = tightenIntMaxImpl(v, hi, null)

    /** As [tightenIntMax] with explicit conflict [antecedents]. */
    fun tightenIntMax(v: Int, hi: Long, antecedents: IntArray?): Boolean = tightenIntMaxImpl(v, hi, antecedents)

    /** Pin int `v` to [value]; returns false on conflict. */
    fun setInt(v: Int, value: Long): Boolean = setIntImpl(v, value, null)

    /** As [setInt] with explicit conflict [antecedents]. */
    fun setInt(v: Int, value: Long, antecedents: IntArray?): Boolean = setIntImpl(v, value, antecedents)

    /** Punch a hole in `v`'s domain at [value]. Returns `true` on success (including the
     *  no-op case when [value] is already absent), `false` on conflict (would empty the
     *  domain). When [value] is at the current endpoint, this is equivalent to a
     *  bound-tighten by one; when it's interior, it transitions the domain to sparse
     *  representation. */
    fun excludeIntValue(v: Int, value: Long): Boolean = excludeIntValueImpl(v, value, null)

    /** Forbid [value] for int `v`; returns false on conflict. */
    fun excludeIntValue(v: Int, value: Long, antecedents: IntArray?): Boolean =
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

    /** Append a reversible cell to [UndoLog.revTrail] so its latest mutation is undone on the next
     *  [undoTo] past this point. Called by [RevInt] / [RevRef] / [RevIntArray] on each logged write. */
    internal fun logReversible(cell: Trailed) {
        undo.revTrail.add(cell)
    }

    /**
     * Lightweight per-level marker. Records only the *positions* a pop must rewind to: the
     * undo-log size and the three append-only stacks' sizes, plus snapshot copies of any
     * [SnapshottablePayload]s (the rare factors — Mdd / Table — whose incremental state
     * isn't recomputed from scratch on each `propagate`). Copying whole per-level state would
     * cost ~12 numVars-sized arrays per level; [undoTo] instead replays the undo log in
     * O(changes-since-mark).
     */
    class LevelMark internal constructor(
        internal val undoSize: Int,
        internal val ltdvSize: Int,
        internal val pinOrderSize: Int,
        internal val snapshottablePayloads: MutableIntObjectMap<SnapshottablePayload>,
        internal val revSize: Int,
        internal val atomUndoSize: Int,
    )

    /**
     * Run propagation until no factor can derive more. When [allFactors] is true, every
     * factor is enqueued initially (the usual one-shot path). When false, only factors
     * touching variables currently in the dirty queues are enqueued — for incremental use
     * by a session that just applied a pin and wants to extend the fixpoint.
     *
     * Returns `null` on success (state is at fixpoint); otherwise the conflict-levels set.
     *
     * [cancellation] is polled whenever a real token is supplied (i.e. not [Cancellation.Never]),
     * on both the full-propagation ([allFactors]) and the incremental per-node path. A targeted
     * fixpoint can itself wedge on a slow propagator over wide domains (a reified-linear bound
     * crawl tightens one step at a time across a Long-wide span), so the search path must poll too
     * to honour the deadline. When no token is supplied the gate below is a dead, perfectly-
     * predicted branch, so the BCP hot loop pays nothing on deadline-free solves. When the token
     * fires the drain stops early and returns `null`; the partial fixpoint is sound (it only
     * ever tightens), and the deadline that fired it makes the caller abort promptly anyway.
     */
    internal fun runToFixpoint(
        allFactors: Boolean,
        initialFactor: Int = -1,
        initialFactors: IntArray = EmptyIntArray,
        cancellation: Cancellation = Cancellation.Never,
        skipExpensiveBake: Boolean = false,
    ): IntArray? {
        // The native-SAT lane runs its own arena-packed two-watched-literal BCP; the atom store,
        // channeling flush, factor queue, and dirty-var drains below are all inert when there are no
        // integer variables, so it bypasses them entirely.
        nativeEngine?.let { return it.propagate(allFactors, cancellation) }
        // Clear conflict bookkeeping from any prior run — reusing the state across pushes
        // would otherwise mix old seeds into a new conflict's core.
        conflictSeedFactors.clear()
        // Register any channeling clauses queued since the last fixpoint (as atoms materialized,
        // including inside the just-finished conflict analysis) before snapshotting the factor
        // count — a safe boundary with no factor mid-fire.
        flushPendingChanneling()
        val factorCount = totalFactorCount
        propBegin(factorCount)
        val pollable = cancellation !== Cancellation.Never
        // Full propagation (bakes) is one-shot, not in the per-node BCP loop or a resumable slice, so
        // it polls from the first fire. Only the incremental per-node path
        // defers the poll past the fire floor, so a normal small fixpoint — and resumable slicing,
        // which pauses at decision granularity — is never cut mid-propagation.
        val floor = if (allFactors) 0 else cancelFloor
        var fireCount = 0
        if (allFactors) {
            for (fid in 0 until factorCount) propEnq(fid)
        } else {
            // Atom-lit watchers woken by int tightens before runToFixpoint was called are capped:
            // a stale id from before a forget/renumber may linger in the pre-run queue.
            drainDirtyIntoQueue(atomFactorCap = factorCount)
            // Optional seed — used by [PropagationSession.addLearnedClause] to force the
            // newly-stored learned clause to fire on the next propagation cycle (it would
            // otherwise sit dormant since the watcher index only wakes on false-going
            // literals, and a freshly-added clause's watches haven't been triggered yet).
            if (initialFactor in 0 until factorCount) propEnq(initialFactor)
            // Seed a batch of freshly-added mid-life factors so each fires once on the next cycle;
            // like [initialFactor], their watches haven't been triggered yet so they'd sit dormant.
            for (fid in initialFactors) if (fid in 0 until factorCount) propEnq(fid)
        }
        while (propQueue.isNotEmpty()) {
            // Only a single fixpoint that itself runs away — an O(span) reified-linear bound crawl
            // over a Long-wide domain, millions of fires deep — needs the deadline *inside*
            // propagation. A normal per-node fixpoint is tiny and pauses cleanly at the engine's
            // between-decision poll, so the fire floor leaves that path (and resumable slicing, which
            // pauses at decision granularity) untouched: the poll is never even consulted there.
            if (pollable) {
                if (fireCount >= floor && (fireCount and CANCEL_POLL_MASK) == 0 && cancellation()) {
                    runCancelled = true
                    return null
                }
                fireCount++
            }
            val fid = propQueue.removeFirst()
            propStamp[fid] = propGen - 1 // mark dequeued (≠ propGen) so it can re-enqueue
            val f = factorAt(fid)
            // Cheap bake: defer an expensive factor's first fire (heavy state build + sweep) to the
            // first search fire. It re-enqueues if a cheap factor later wakes it, and is skipped again.
            if (skipExpensiveBake && f.expensiveBake) continue
            currentLevel = effectiveLevelFor(f, fid)
            currentFactor = fid
            conflictLevels = null
            if (!f.propagate(this, fid)) {
                // The failing factor is always in the core, regardless of whether it
                // recorded a conflict via the impl methods (some factors return false
                // without calling pin/tighten — they just detected infeasibility from
                // the current state).
                seedConflictFactor(fid)
                return conflictLevels ?: factorVarsConflictLevels(fid)
            }
            // Post-fire wakes are always in range, so the atom-factor cap is inert here.
            drainDirtyIntoQueue(atomFactorCap = Int.MAX_VALUE)
        }
        return null
    }

    /** Drain the dirty bool/int vars and the atom-woken factors into the worklist — run once to
     *  seed an incremental fixpoint and again after every factor fire. [atomFactorCap] bounds the
     *  atom-woken factor ids (the seed path caps at the live factor count since a stale id can
     *  linger in the pre-run queue; post-fire wakes pass [Int.MAX_VALUE]). */
    private fun drainDirtyIntoQueue(atomFactorCap: Int) {
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
        while (atoms.dirtyFactors.isNotEmpty()) {
            val fid = atoms.dirtyFactors.removeFirst()
            if (fid in 0 until atomFactorCap) propEnq(fid)
        }
    }

    /**
     * Effective decision level for firing factor [f] (id [fid]). A Clause's effective level is the
     * max decision level over its literals; its `boolVars` is the deduplicated variable set of
     * those literals and its `intVars` is empty, so [maxLevelForVars] is redundant with
     * [maxLevelForClause] (a second O(arity) pass over the same variables on every fire — the BCP
     * hot path). Better still: a *pure-bool* clause only ever fires when a watched bool literal
     * just went false at the current decision level (bools are only pinned by decisions or clause
     * propagation, all stamped at the current level), so its effective level is exactly the
     * current decision level — no scan at all. Atom-lit clauses can fire on an atom that flipped
     * at a sub-decision level, so they keep the literal scan.
     */
    private fun effectiveLevelFor(f: Propagator, fid: Int): Int = when {
        f is ClausePropagator -> if (f.allLiteralsBool(problem.numBoolVars)) {
            levelToDecisionVar.size
        } else {
            maxLevelForClause(f.literals)
        }

        // A learned non-clause constraint (a cutting-planes pseudo-Boolean nogood) lives in
        // the learned store, not [problem.factors] / [MidlifeFactors.factors]; read its var footprint off
        // the propagator itself.
        fid >= baseFactorCount && !incremental && f is LearnedPropagator -> maxLevelForVars(f.boolVars, f.intVars)

        else -> {
            // A base factor reads its vars from the immutable problem; a mid-life presolve factor
            // (fid >= baseFactorCount, only in [incremental] mode) from [MidlifeFactors.factors].
            val factor = if (fid < baseFactorCount) problem.factors[fid] else midlife.factors[fid - baseFactorCount]
            maxLevelForVars(factor.boolVars, factor.intVars)
        }
    }

    /**
     * Add every factor that should fire on `v`'s newly-pinned value to `queue`, using the
     * split wakeup paths: occurrence-list for factors that don't watch literals, plus the
     * per-literal watcher index for those that do (currently Clauses). For watcher-using
     * factors only the literal that just transitioned to *false* triggers a fire — true
     * literals satisfy the clause, no propagation needed.
     */
    private fun enqueueForBoolChange(v: Int) {
        for (fid in nonBoolWatcherOcc[v]) propEnq(fid)
        // Mid-life presolve factors waking via occurrence lists; null (no overlay) otherwise.
        midlife.boolOccurrences?.let {
            val list = it[v]
            for (i in 0 until list.size) propEnq(list[i])
        }
        // The literal that just became false is the one whose polarity opposes the pin.
        // The var is assigned here (added to dirtyBools only after a successful pin), so read
        // the packed value bit directly instead of the boxing `boolValues[v]` accessor.
        val falseLit = Lit.make(v, !boolValueBits.get(v))
        val watchers = watches.byLit[falseLit]
        val blockers = watches.blockersByLit[falseLit]
        for (i in 0 until watchers.size) {
            // Blocking-literal short-cut: if the cached blocker for this watch is
            // already true, the factor is satisfied and waking it would be a no-op — skip
            // the enqueue and the clause dereference entirely. NO_BLOCKER falls through and
            // always fires, so factors without blockers keep their unconditional wakeup.
            val blocker = blockers[i]
            if (blocker != NO_BLOCKER && litTrue(blocker)) continue
            propEnq(watchers[i])
        }
    }

    /**
     * Record that int var [v] just changed: add it to [dirtyInts] and, when typed int-event
     * watchers are active, OR the [kindMask] (one or more `IntEvent.*_BIT`s) into [IntEventMachinery.dirtyKinds]`[v]`
     * so [enqueueForIntChange] wakes the right advisors. [IntEvent.FIXED_BIT] is added automatically
     * when the post-mutation domain is a singleton, so a mutator only passes the bound/value kind it
     * caused. No-op beyond the dirty enqueue when no factor subscribes (the mask array is empty).
     */
    internal fun markIntDirty(v: Int, kindMask: Int) {
        dirtyInts.addLast(v)
        if (intEvents.dirtyKinds.isEmpty()) return
        val d = intDomains[v]
        val mask = if (d.min == d.max) kindMask or IntEvent.FIXED_BIT else kindMask
        intEvents.dirtyKinds[v] = intEvents.dirtyKinds[v] or mask
    }

    /**
     * Enqueue every factor that should fire on `v`'s domain change, mirroring [enqueueForBoolChange]
     * on the int side: the occurrence-list factors that don't subscribe to typed events on `v`
     * ([com.eignex.klause.solver.Problem.nonIntEventWatcherIntOccurrences]), plus — for each
     * [IntEvent] kind that actually occurred (read from [IntEventMachinery.dirtyKinds]) — the advisors registered
     * in [IntEventMachinery.forEachWatcher]. The kind mask is cleared after dispatch so it doesn't leak into a
     * later change to the same variable. When no factor subscribes this reduces to the plain
     * occurrence-list walk over [com.eignex.klause.solver.Problem.intOccurrences].
     */
    private fun enqueueForIntChange(v: Int) {
        for (fid in nonIntEventWatcherOcc[v]) propEnq(fid)
        // Mid-life presolve factors waking via occurrence lists; null (no overlay) otherwise.
        midlife.intOccurrences?.let {
            val list = it[v]
            for (i in 0 until list.size) propEnq(list[i])
        }
        if (intEvents.dirtyKinds.isEmpty()) return
        val mask = intEvents.dirtyKinds[v]
        if (mask == 0) return
        intEvents.dirtyKinds[v] = 0
        var kind = 0
        while (kind < IntEvent.COUNT) {
            if (mask and (1 shl kind) != 0) {
                intEvents.forEachWatcher(IntEvent.pack(v, kind)) { fid ->
                    propEnq(fid)
                    accumulateDirtyVar(fid, v)
                }
            }
            kind++
        }
    }

    /** Record [v] in [fid]'s dirty-variable delta if [fid] consumes it ([IntEventMachinery.dirtyMark] dedups).
     *  No-op for non-consumers and when no factor in the problem consumes a delta. */
    private fun accumulateDirtyVar(fid: Int, v: Int) {
        if (intEvents.dirtyMark.isEmpty()) return
        val mark = intEvents.dirtyMark[fid] ?: return
        val vars = intEvents.dirtyVars[fid] ?: return
        if (mark.add(v)) vars.add(v)
    }

    /**
     * Drain and return the dirty-variable delta accumulated for [factorId] since it last drained —
     * the subscribed variables that fired, a superset of those actually changed since the consumer's
     * last fire (see [IntEventMachinery.dirtyVars]). A consumer ([Propagator.consumesIntEventDelta])
     * calls this on a fire and recovers the exact removed values by diffing its own reversible
     * baseline for these variables. Clears the accumulator (keeping it in lockstep with the
     * consumer's baseline update), so call it exactly when the baseline is advanced. Returns an empty
     * array for a non-consuming factor.
     */
    fun drainIntEventDirtyVars(factorId: Int): IntArray {
        if (intEvents.dirtyVars.isEmpty()) return EmptyIntArray
        val list = intEvents.dirtyVars[factorId] ?: return EmptyIntArray
        if (list.size == 0) return EmptyIntArray
        val out = list.toIntArray()
        list.clear()
        intEvents.dirtyMark[factorId]?.clear()
        return out
    }
}
