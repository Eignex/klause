package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Bits
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList

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

        /** Current value of bool [v], or null if unassigned. */
        operator fun get(v: Int): Boolean? = if (boolAssigned.get(v)) boolValueBits.get(v) else null

        /** Assign bool [v] to [value], or null to unassign. */
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

    /** Populated on contradiction; the driver reads it to form [PropagationResult.Unsat]. */
    @Suppress("DoubleMutabilityForCollection") // lazily allocated on conflict
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
    @Suppress("DoubleMutabilityForCollection") // lazily allocated on conflict
    internal var conflictSeedFactors: MutableSet<Int>? = null

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

    /** Per-factor mutable payload slots (reference-typed). */
    val refPayload: MutableList<Any?> get() = _refPayload

    /** Learned clauses accumulated during search (LCG-style nogoods produced by
     *  [ConflictAnalyzer]). Their factor ids live in `[problem.numFactors, totalFactorCount)` —
     *  treat them like any other [Clause] via [factorAt];
     *  they participate in propagation through [boolWatchersByLit] just like static
     *  clauses. Survives [restore] (clauses are facts about the original problem, not
     *  trail state); pruned by [forgetLearnedClauses]. */
    private val _learnedClauses: ArrayList<Clause> = ArrayList()

    /** Clauses learned during conflict analysis. */
    val learnedClauses: List<Clause> get() = _learnedClauses

    /** LBD (Literal Block Distance) per learned clause, parallel to [_learnedClauses].
     *  Glucose-style glue metric: lower = more re-usable. Forgetting policies key on
     *  this to decide which clauses to drop. */
    private val learnedLbds: IntArrayList = IntArrayList()

    /** `problem.numFactors + learnedClauses.size`. Use this instead of `problem.numFactors`
     *  when iterating or sizing per-factor scratch in the engine. */
    val totalFactorCount: Int get() = problem.numFactors + _learnedClauses.size

    /** Unified factor accessor; routes static factor ids to [Problem.factors] and learned
     *  factor ids (≥ `problem.numFactors`) to [learnedClauses]. */
    fun factorAt(fid: Int): Factor = if (fid < problem.numFactors) {
        problem.factors[fid]
    } else {
        _learnedClauses[fid - problem.numFactors]
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
    fun addLearnedClause(clause: Clause, lbd: Int): Int {
        val newFid = totalFactorCount
        _learnedClauses.add(clause)
        learnedLbds.add(lbd)
        _refPayload.add(null)
        val watchers = clause.initialBoolWatchers
        if (watchers != null) {
            for (lit in watchers) installLitWatch(lit, newFid)
        }
        return newFid
    }

    /** Read-only view of LBDs for tests / introspection. Parallel to [learnedClauses]. */
    fun learnedClauseLbd(learnedIndex: Int): Int = learnedLbds[learnedIndex]

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
                w++
            }
        }
        while (_learnedClauses.size > newCount) _learnedClauses.removeAt(_learnedClauses.size - 1)
        learnedLbds.truncateTo(newCount)

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

    /** Truth of the atom at allocation time. `true` = atom holds (bound met),
     *  `false` = atom is currently violated (bound not met). Never null — allocation
     *  is only ever requested for atoms whose truth can be derived from current
     *  domains. */
    internal val atomValue: IntArrayList = IntArrayList() // 1 / 0

    /** Decision level at which the atom became true (or 0 for atoms that were already
     *  true at problem-bake time). Mirrors [boolLevel] for atoms. */
    internal val atomLevel: IntArrayList = IntArrayList()

    /** Per-atom antecedents — bool literals (or other atom literals via their virtual
     *  ids) whose collective truth forced this atom. Mirrors [boolAntecedents]. */
    internal val atomAntecedents: ArrayList<IntArray?> = ArrayList()

    /** Reverse lookup: packed key `(intVar << 33) | (kind << 32) | (threshold + INT_MAX)`
     *  → atomId. Allows O(1) re-allocation checks. */
    private val atomByKey: HashMap<Long, Int> = HashMap()

    /** Per-atom-lit watcher list — factor ids that fire when this atom-lit transitions
     *  to false. Mirrors [boolWatchersByLit] for atoms; keyed by atom-lit id rather than
     *  fixed-array indexed because atoms are allocated dynamically. */
    internal val atomWatchersByLit: HashMap<Int, IntArrayList> = HashMap()

    /** For each int variable, the atoms whose truth depends on it — used to recompute
     *  atom truth and fire watchers after a successful tighten / exclude. */
    private val atomsByIntVar: HashMap<Int, IntArrayList> = HashMap()

    /** Factor ids woken by atom-lit transitions during the current propagation step.
     *  Drained alongside dirty-int / dirty-bool processing in [runToFixpoint]. */
    private val dirtyAtomFactors: IntArrayDeque =
        IntArrayDeque(initialCapacity = 8)

    private fun atomKey(intVar: Int, kind: Int, threshold: Int): Long {
        // Threshold can be negative; bias by Int.MIN_VALUE to keep it non-negative within
        // the lower 32 bits. Kind takes bit 32; intVar takes bits 33..63.
        val biased = threshold.toLong() - Int.MIN_VALUE.toLong()
        return (intVar.toLong() shl 33) or (kind.toLong() shl 32) or biased
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

    /** Encode a *positive* atom-lit (the atom holds) directly as a [Lit]-style id. */
    fun atomLitGe(intVar: Int, threshold: Int): Int = Lit.make(atomVarGe(intVar, threshold), true)

    /** Literal for the bound atom `intVar ≤ threshold`. */
    fun atomLitLe(intVar: Int, threshold: Int): Int = Lit.make(atomVarLe(intVar, threshold), true)

    /** Literal for the value atom `intVar = value`. */
    fun atomLitEq(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), true)

    /** Literal for the value atom `intVar ≠ value`. */
    fun atomLitNe(intVar: Int, value: Int): Int = Lit.make(atomVarEq(intVar, value), false)

    /** True iff [v] is an atom-id (past the bool var space). Used by the conflict
     *  analyzer to dispatch between bool-trail and atom-table lookups. */
    fun isAtomVar(v: Int): Boolean = v >= problem.numBoolVars

    /** Translate a virtual atom-var id back to its 0-based atom index. */
    fun atomIdOf(v: Int): Int = v - problem.numBoolVars

    /** Current truth of an atom — derived fresh from `intDomains`, not the
     *  snapshot-at-allocation [atomValue]. Returns `null` when undetermined (the bound
     *  isn't either side-decided yet). Used by [litTrue] / [litFalse] / [pinLit]. */
    fun atomCurrentTruth(atomId: Int): Boolean? =
        atomTruthOf(atomIntVar[atomId], atomKind[atomId], atomThreshold[atomId])

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

    /** True iff [lit] is currently `true` (returns false when undetermined). */
    fun litTrue(lit: Int): Boolean = litTruth(lit) == true

    /** True iff [lit] is currently `false` (returns false when undetermined). */
    fun litFalse(lit: Int): Boolean = litTruth(lit) == false

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
        val key = atomKey(intVar, kind, threshold)
        atomByKey[key]?.let { return problem.numBoolVars + it }
        val id = atomIntVar.size
        atomIntVar.add(intVar)
        atomKind.add(kind)
        atomThreshold.add(threshold)
        atomAntecedents.add(null) // overwritten below if truth is determined
        // Compute initial truth (may be null = undetermined).
        val truth = atomTruthOf(intVar, kind, threshold)
        if (truth == null) {
            atomValue.add(2) // 2 = undetermined sentinel
            atomLevel.add(0)
        } else {
            atomValue.add(if (truth) 1 else 0)
            atomLevel.add(intLevel[intVar])
            atomAntecedents[id] = when (kind) {
                0 -> intMinAntecedents[intVar]

                1 -> intMaxAntecedents[intVar]

                2 -> {
                    // Eq atom at alloc: true (singleton {k}) cited by both bounds; false
                    // (k below min or above max) cited by the side that excludes it; hole
                    // case falls back to null (treated as structural leaf by analyzer).
                    val d = intDomains[intVar]
                    if (truth == true) {
                        composeIntVarAtomAntecedents(intArrayOf(intVar))
                    } else {
                        when {
                            threshold < d.min -> intMinAntecedents[intVar]
                            threshold > d.max -> intMaxAntecedents[intVar]
                            else -> null
                        }
                    }
                }

                else -> null
            }
        }
        atomByKey[key] = id
        val list = atomsByIntVar.getOrPut(intVar) { IntArrayList(initialCapacity = 2) }
        list.add(id)
        return problem.numBoolVars + id
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

    /** After a successful [tightenIntMinImpl] / [tightenIntMaxImpl] / [excludeIntValueImpl]
     *  on int var [v], recompute the truth of every atom that depends on [v]. Atoms whose
     *  truth flipped get their level / antecedents updated to the current tightening's,
     *  and watchers on the now-false atom-lit are scheduled to fire. */
    private fun propagateAtomsForVar(v: Int, ant: IntArray?) {
        val atoms = atomsByIntVar[v] ?: return
        for (i in 0 until atoms.size) {
            val atomId = atoms[i]
            val newT = atomCurrentTruth(atomId) ?: continue
            val oldRaw = atomValue[atomId]
            val newRaw = if (newT) 1 else 0
            if (oldRaw == newRaw) continue // already at this truth
            // Truth changed (either from the sentinel "unknown" or from the opposite
            // boolean value). Update the snapshot and fire the now-false lit's watchers.
            atomValue[atomId] = newRaw
            atomLevel[atomId] = currentLevel
            atomAntecedents[atomId] = ant
            val falseLit = Lit.make(problem.numBoolVars + atomId, !newT)
            val w = atomWatchersByLit[falseLit] ?: continue
            for (j in 0 until w.size) dirtyAtomFactors.addLast(w[j])
        }
    }

    /** Install [fid] as a watcher of [lit]. Dispatches between [boolWatchersByLit]
     *  (bool var space) and [atomWatchersByLit] (atom var space). */
    internal fun installLitWatch(lit: Int, fid: Int) {
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            boolWatchersByLit[lit].add(fid)
        } else {
            val list = atomWatchersByLit.getOrPut(lit) { IntArrayList(initialCapacity = 2) }
            list.add(fid)
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
    private val undoTag = IntArrayList()
    private val undoVar = IntArrayList()
    private val undoLevel = IntArrayList() // int: prior intLevel
    private val undoMinReason = IntArrayList() // int: prior intMinReason
    private val undoMaxReason = IntArrayList() // int: prior intMaxReason
    private val undoDomain = ArrayList<IntDomain?>() // int: prior intDomains[v]
    private val undoMinAnt = ArrayList<IntArray?>() // int: prior intMinAntecedents
    private val undoMaxAnt = ArrayList<IntArray?>() // int: prior intMaxAntecedents

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
    }

    /** Capture int var [v]'s full prior state. Must be called *before* the mutation. */
    private fun logIntChange(v: Int) {
        undoTag.add(1)
        undoVar.add(v)
        undoLevel.add(intLevel[v])
        undoMinReason.add(intMinReason[v])
        undoMaxReason.add(intMaxReason[v])
        undoDomain.add(intDomains[v])
        undoMinAnt.add(intMinAntecedents[v])
        undoMaxAnt.add(intMaxAntecedents[v])
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
    }

    /** Current undo-log size. A [LevelMark] captures this; iterating [undoVarAt] /
     *  [undoIsBoolAt] over `[base, undoTop)` enumerates exactly the variables mutated since
     *  position `base` — used by [PropagationSession] to compute the implied-fact diff of a
     *  push incrementally instead of scanning every variable. */
    val undoTop: Int get() = undoTag.size

    /** Variable id recorded by undo record [i]. */
    fun undoVarAt(i: Int): Int = undoVar[i]

    /** True iff undo record [i] is a bool pin (vs. an int-domain change). */
    fun undoIsBoolAt(i: Int): Boolean = undoTag[i] == 0

    init {
        for (fid in 0 until problem.numFactors) {
            val watchers = problem.factors[fid].initialBoolWatchers ?: continue
            for (lit in watchers) installLitWatch(lit, fid)
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
        // Remove from old (swap-remove via a tight hoisted-local scan inside IntArrayList).
        val oldV = Lit.variable(oldLit)
        if (oldV < problem.numBoolVars) {
            boolWatchersByLit[oldLit].removeValue(factorId)
        } else {
            atomWatchersByLit[oldLit]?.removeValue(factorId)
        }
        // Install on new.
        installLitWatch(newLit, factorId)
    }

    init {
        seeded = seedAssumptions(assumptions)
    }

    /** Push every pin in [a] as a fresh decision; return `false` (so [seeded] becomes
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

    /** Force bool [v] to [value]; returns false on conflict. */
    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolImpl(v, value, antecedents = null)

    /** Variant that records [antecedents] — the literals whose truth values implied this
     *  pin. Lets the conflict analyzer reconstruct the propagation chain backwards. Pass
     *  `null` (the default no-arg form) when the factor doesn't track antecedents — that's
     *  fine, the analyzer just treats this pin as a leaf in the implication graph. */
    fun pinBool(v: Int, value: Boolean, antecedents: IntArray?): Boolean = pinBoolImpl(v, value, antecedents)

    /** Raise int [v]'s lower bound to [lo]; returns false on conflict. */
    fun tightenIntMin(v: Int, lo: Int): Boolean = tightenIntMinImpl(v, lo, null)

    /** Variant that records [antecedents] — bool literals (false in the current state)
     *  whose collective truth forced this lower-bound tightening. Used by the conflict
     *  analyzer to walk the implication graph backwards through int-domain factors. */
    fun tightenIntMin(v: Int, lo: Int, antecedents: IntArray?): Boolean = tightenIntMinImpl(v, lo, antecedents)

    /** Lower int [v]'s upper bound to [hi]; returns false on conflict. */
    fun tightenIntMax(v: Int, hi: Int): Boolean = tightenIntMaxImpl(v, hi, null)

    /** As [tightenIntMax] with explicit conflict [antecedents]. */
    fun tightenIntMax(v: Int, hi: Int, antecedents: IntArray?): Boolean = tightenIntMaxImpl(v, hi, antecedents)

    /** Pin int [v] to [value]; returns false on conflict. */
    fun setInt(v: Int, value: Int): Boolean = setIntImpl(v, value, null)

    /** As [setInt] with explicit conflict [antecedents]. */
    fun setInt(v: Int, value: Int, antecedents: IntArray?): Boolean = setIntImpl(v, value, antecedents)

    /** Punch a hole in [v]'s domain at [value]. Returns `true` on success (including the
     *  no-op case when [value] is already absent), `false` on conflict (would empty the
     *  domain). When [value] is at the current endpoint, this is equivalent to a
     *  bound-tighten by one; when it's interior, it transitions the domain to sparse
     *  representation. */
    fun excludeIntValue(v: Int, value: Int): Boolean = excludeIntValueImpl(v, value, null)

    /** Forbid [value] for int [v]; returns false on conflict. */
    fun excludeIntValue(v: Int, value: Int, antecedents: IntArray?): Boolean =
        excludeIntValueImpl(v, value, antecedents)

    private fun pinBoolImpl(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
        val cur = boolValues[v]
        if (cur != null) {
            if (cur == value) return true
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
        boolValues[v] = value
        boolLevel[v] = currentLevel
        boolReason[v] = currentFactor
        boolAntecedents[v] = antecedents
        boolPinOrder.add(v)
        dirtyBools.addLast(v)
        return true
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
        intDomains[v] = d.withMinAtLeast(lo)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMinReason[v] = currentFactor
        intMinAntecedents[v] = antecedents
        dirtyInts.addLast(v)
        propagateAtomsForVar(v, antecedents)
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
        intDomains[v] = d.withMaxAtMost(hi)
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        intMaxReason[v] = currentFactor
        intMaxAntecedents[v] = antecedents
        dirtyInts.addLast(v)
        propagateAtomsForVar(v, antecedents)
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
        if (undoLogging) logIntChange(v)
        if (currentFactor >= 0) propagations++
        val newDomain = d.excludeValue(value)
        intDomains[v] = newDomain
        intLevel[v] = maxOf(intLevel[v], currentLevel)
        // Reason attribution: which side (min/max) "moved" depends on where the hole
        // landed. Pure interior holes don't shift either endpoint; in that case the
        // current factor still becomes the relevant reason for any future propagator
        // walking back through this variable.
        if (newDomain.min != d.min) {
            intMinReason[v] = currentFactor
            intMinAntecedents[v] = antecedents
        }
        if (newDomain.max != d.max) {
            intMaxReason[v] = currentFactor
            intMaxAntecedents[v] = antecedents
        }
        dirtyInts.addLast(v)
        propagateAtomsForVar(v, antecedents)
        return true
    }

    private fun seedConflictFactor(fid: Int) {
        if (fid < 0) return
        val s = conflictSeedFactors ?: HashSet<Int>().also { conflictSeedFactors = it }
        s.add(fid)
    }

    private fun setIntImpl(v: Int, value: Int, antecedents: IntArray?): Boolean =
        tightenIntMinImpl(v, value, antecedents) && tightenIntMaxImpl(v, value, antecedents)

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
     *  set `currentLevel` before each factor invocation.
     *
     *  No variable's level can exceed the number of decisions pushed so far ([cap]); once
     *  the running max reaches that ceiling, the remaining vars can't raise it, so we stop
     *  early. This is an exact short-circuit (same result, fewer reads) — it mainly trims
     *  the scan for large-arity global constraints that fire often during search. */
    fun maxLevelForVars(boolVars: IntArray, intVars: IntArray): Int {
        val cap = levelToDecisionVar.size
        var max = 0
        for (v in boolVars) {
            // boolVars may include atom-var ids when a Clause has atom-lits; dispatch.
            val l = if (v < problem.numBoolVars) {
                boolLevel[v]
            } else {
                atomLevel[v - problem.numBoolVars]
            }
            if (l > max) {
                max = l
                if (max >= cap) return max
            }
        }
        for (v in intVars) {
            val l = intLevel[v]
            if (l > max) {
                max = l
                if (max >= cap) return max
            }
        }
        return max
    }

    /** Variant that also folds in atom-lit levels for a Clause's literals — used for
     *  learned clauses that reference atom-vars, where the relevant decision level isn't
     *  captured by [boolVars] / [intVars] alone. */
    fun maxLevelForClause(literals: IntArray): Int {
        val cap = levelToDecisionVar.size
        var max = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            val l = if (v < problem.numBoolVars) boolLevel[v] else atomLevel[v - problem.numBoolVars]
            if (l > max) {
                max = l
                if (max >= cap) return max
            }
        }
        return max
    }

    /** Collect every decision level touched by [boolVars] / [intVars] — the factor's view of
     *  who's responsible. Used when a factor returns `false` without explicitly setting
     *  [conflictLevels].
     *
     *  Atom-lit dispatch: [boolVars] may legitimately contain virtual atom-var ids when the
     *  failing factor is a learned Clause whose literals reference atom-lits (encoded as
     *  `Lit.make(v, ...)` with `v >= problem.numBoolVars`). Those map into [atomLevel],
     *  not [boolLevel] — mirrors the [maxLevelForVars] dispatch a few lines above. */
    fun collectLevelsForVars(boolVars: IntArray, intVars: IntArray): Set<Int> {
        val out = HashSet<Int>()
        val numBool = problem.numBoolVars
        for (v in boolVars) {
            val l = if (v < numBool) boolLevel[v] else atomLevel[v - numBool]
            if (l > 0) out.add(l)
        }
        for (v in intVars) {
            val l = intLevel[v]
            if (l > 0) out.add(l)
        }
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
                // Skip atom-encoded literal ids (≥ numBoolVars) — they're int-bound
                // atoms whose causation is captured through intMinReason / intMaxReason
                // on the underlying int var, expanded below for this factor's intVars.
                if (v >= problem.numBoolVars) continue
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
        internal val atomCount: Int,
        internal val snapshottablePayloads: Map<Int, SnapshottablePayload>,
    )

    /** Capture a [LevelMark] at the current state. Cheap: four ints plus a snapshotCopy of
     *  each [SnapshottablePayload]. The map is allocated only when at least one payload is
     *  present (Table / Mdd factors); the common no-payload case shares [emptyPayloads] and
     *  never allocates per push. */
    fun mark(): LevelMark {
        @Suppress("DoubleMutabilityForCollection") // lazily allocated when a snapshot is taken
        var payloads: HashMap<Int, SnapshottablePayload>? = null
        for (i in _refPayload.indices) {
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
            atomCount = atomIntVar.size,
            snapshottablePayloads = payloads ?: emptyPayloads,
        )
    }

    /**
     * Rewind the state to [mark] by replaying the undo log from the top down to
     * [LevelMark.undoSize], then truncating the append-only stacks (decision vars, pin
     * order, atoms) and restoring snapshottable payloads. Replays in reverse so a var
     * narrowed several times since the mark lands on its mark-time value. Transient
     * bookkeeping (dirty queues, conflict seeds, current level/factor) is cleared — the
     * caller only ever marks / undoes between propagation cycles, when those are idle.
     */
    fun undoTo(mark: LevelMark) {
        var i = undoTag.size - 1
        while (i >= mark.undoSize) {
            when (undoTag[i]) {
                0 -> { // bool pin — prior state is always unassigned
                    val v = undoVar[i]
                    boolValues[v] = null
                    boolLevel[v] = -1
                    boolReason[v] = -1
                    boolAntecedents[v] = null
                }

                else -> { // int change — restore the full recorded prior int-var state
                    val v = undoVar[i]
                    intDomains[v] = requireNotNull(undoDomain[i])
                    intLevel[v] = undoLevel[i]
                    intMinReason[v] = undoMinReason[i]
                    intMaxReason[v] = undoMaxReason[i]
                    intMinAntecedents[v] = undoMinAnt[i]
                    intMaxAntecedents[v] = undoMaxAnt[i]
                }
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
        // Atoms allocated after the mark are removed wholesale; their virtual var ids and
        // watcher registrations are dropped to avoid dangling.
        while (atomIntVar.size > mark.atomCount) {
            val id = atomIntVar.size - 1
            val intVar = atomIntVar[id]
            val key = atomKey(intVar, atomKind[id], atomThreshold[id])
            atomByKey.remove(key)
            atomsByIntVar[intVar]?.let { list ->
                for (j in 0 until list.size) {
                    if (list[j] == id) {
                        list.removeAt(j)
                        break
                    }
                }
            }
            atomWatchersByLit.remove(Lit.make(problem.numBoolVars + id, true))
            atomWatchersByLit.remove(Lit.make(problem.numBoolVars + id, false))
            atomIntVar.truncateTo(id)
            atomKind.truncateTo(id)
            atomThreshold.truncateTo(id)
            atomValue.truncateTo(id)
            atomLevel.truncateTo(id)
            atomAntecedents.removeAt(atomAntecedents.size - 1)
        }
        // Re-derive surviving atoms' truth from the now-restored int domains. atomLevel /
        // atomAntecedents are left to drift (advisory, like watches) — same as the old
        // snapshot scheme, which never restored them either.
        for (atomId in 0 until atomIntVar.size) {
            val t = atomCurrentTruth(atomId) ?: continue
            atomValue[atomId] = if (t) 1 else 0
        }
        dirtyAtomFactors.clear()
        dirtyBools.clear()
        dirtyInts.clear()
        conflictLevels = null
        conflictSeedFactors = null
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
    internal fun runToFixpoint(allFactors: Boolean, initialFactor: Int = -1): Set<Int>? {
        // Clear conflict bookkeeping from any prior run — reusing the state across pushes
        // would otherwise mix old seeds into a new conflict's core.
        conflictSeedFactors = null
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
     * Add every factor that should fire on [v]'s newly-pinned value to [queue], using the
     * split wakeup paths: occurrence-list for factors that don't watch literals, plus the
     * per-literal watcher index for those that do (currently Clauses). For watcher-using
     * factors only the literal that just transitioned to *false* triggers a fire — true
     * literals satisfy the clause, no propagation needed.
     */
    private fun enqueueForBoolChange(v: Int) {
        for (fid in problem.nonBoolWatcherBoolOccurrences[v]) propEnq(fid)
        // The literal that just became false is the one whose polarity opposes the pin.
        // boolValues[v] is non-null here (the var was added to dirtyBools only after a
        // successful pin); read it directly.
        val falseLit = Lit.make(v, !requireNotNull(boolValues[v]))
        val watchers = boolWatchersByLit[falseLit]
        for (i in 0 until watchers.size) propEnq(watchers[i])
    }
}
