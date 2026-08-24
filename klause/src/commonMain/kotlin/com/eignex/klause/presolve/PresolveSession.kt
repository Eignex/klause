package com.eignex.klause.presolve

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.values
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * A pass's incremental change to the working problem: factors to drop (by stable id), factors to
 * add, and the pass's directly-derived domain tightenings.
 *
 * [domains] carries the pass's own narrowings (a dual-fixed bound, an affine substitution's range) as
 * a full int-domain array, or `null` when the pass leaves domains alone. Tightenings that follow from
 * the kept and added factors are re-derived by re-propagation and need not appear here — listing only
 * what a pass *cannot* have the propagator rediscover keeps the delta minimal and the fixpoint intact.
 */
internal class PresolveDelta(
    val droppedIds: IntArray = EmptyIntArray,
    val addedFactors: List<Factor> = emptyList(),
    val domains: Array<IntDomain>? = null,
) {
    val isEmpty: Boolean
        get() = droppedIds.isEmpty() && addedFactors.isEmpty() && domains == null
}

/**
 * Int-variable occurrence index over a pass's live factor list, in the [Factor.intVars] CSR layout the
 * affine / dup-columns candidate searches consume: the factors mentioning var `v` are the dense indices
 * `flat[offsets(v) until offsets(v + 1)]`, in ascending dense-index order — matching what a fresh
 * per-pass rebuild over `problem.factors` would produce. [PresolveSession] maintains the underlying
 * stable-id lists incrementally on each delta and derives this dense view once per firing, so a
 * non-firing round pays no O(factors) rebuild.
 */
class SharedIntOccurrence internal constructor(internal val offsets: IntArray, internal val flat: IntArray)

/**
 * Owns the persistent state of an incremental presolve run so the round engine never rebuilds a
 * [Problem] per firing pass. Holds the working factor set as a stable-id, append-only list (a
 * tombstoned slot becomes `null` and its id is never reused) and one persistent [PropagationState]
 * sized to the original variable count — valid for the whole run because presolve never renumbers
 * variables.
 *
 * A pass emits a [PresolveDelta]; [apply] drops/adds factors and pushes the pass's domain narrowings
 * into the live state, then re-propagates incrementally from just that delta via the existing
 * dirty-variable/watcher machinery. Because the propagators are monotone the greatest fixpoint is
 * unique, so this reaches the same tightened domains a from-scratch bake over the final factor set
 * would — no per-pass `computeBaked`. [materialize] builds the heavyweight solver [Problem] once at
 * the end, with the single renumber/remap.
 */
internal class PresolveSession(private val base: Problem, private val bakeConfig: BakeConfig = BakeConfig.NONE) {

    // Working factor set indexed by stable id: `[0, base.factors.size)` are the originals, appended
    // ids are pass-added factors. `null` marks a tombstoned (dropped) id; ids are never renumbered.
    // Refilled in place on a [reseed] (a domain-widening pass the incremental path can't express).
    private val factors: ArrayList<Factor?> = ArrayList<Factor?>(base.factors.size).apply { addAll(base.factors) }

    // The problem the current [state] is baked over: [base] initially, a widening pass's output after a
    // [reseed]. Its factor count is the stable-id boundary between base and mid-life factors.
    private var stateProblem: Problem = base

    // Seed the persistent state directly from the base's already-computed root fixpoint
    // ([Problem.baked], produced once at base construction) rather than re-propagating every factor
    // inside the presolve window: the seeded pins/bounds are applied through the atom-consistent
    // mutation API and settle at that same greatest fixpoint. A [PropagationResult.Unsat] base seeds
    // nothing (the init below adopts its infeasibility).
    private var state = PropagationState(
        base,
        (base.baked as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None,
        incremental = true,
    )

    /** Set once the base bake or a delta re-propagation derives a root contradiction. */
    var infeasible: Boolean = false
        private set

    // Domains as of the last point the problem was still feasible. On infeasibility the reported /
    // materialized domains are these, not the partially-tightened live ones — mirroring the fresh path,
    // where `foldIntoDomains` skips on an Unsat bake and the reported span stays at the pre-conflict
    // domains. Snapshotted at each mutation entry (before its narrowings) and initialised to the base.
    private var lastFeasibleDomains: Array<IntDomain> = Array(base.numIntVars) { base.intDomains[it] }

    // Stable ids of the live factors [passInput] last returned, parallel to that view's factor list, so
    // a [PassDelta]'s droppedIndices (into that list) map back to the stable ids [apply] tombstones.
    private var liveIds: IntArray = EmptyIntArray

    // Int-variable occurrence index keyed by stable factor id: `intOcc[v]` holds the stable ids of every
    // live factor whose [Factor.intVars] contains `v`, in ascending-stable-id (= append) order. Built
    // once from the base factors and maintained O(delta) in [apply] — an added factor's stable id is
    // appended to its vars' lists; a tombstoned factor's id stays (filtered on read, its slot is null).
    // The affine / dup-columns per-round candidate search reads the dense view derived in [passInput],
    // never rebuilding an occurrence index of its own.
    // Allocated lazily per variable: a model can declare millions of integer variables while only a few
    // thousand ever occur in a factor (WordGolf-scale word-grid instances), so allocating a list for every
    // declared variable up front is the dominant construction cost there. A `null` slot is an empty list.
    private val intOcc: Array<IntArrayList?> = arrayOfNulls(base.numIntVars)

    // The dense [SharedIntOccurrence] view [passInput] last handed out, and whether the factor set has
    // changed since it was built. Rebuilt (O(occurrences)) only once per firing; a non-firing stretch of
    // passes reuses it, so a round that changes nothing pays no occurrence-index rebuild at all.
    private var occView: SharedIntOccurrence? = null
    private var occDirty: Boolean = true

    // The [Problem] view [passInput] last built, reused until the working state changes. A pass that finds
    // nothing to do emits an empty delta and never calls [applyDelta], so the state is byte-for-byte what
    // the previous pass already saw — the next pass gets the same view instead of rebuilding the live-factor
    // list and domain snapshot. On a large model iterated to a fixpoint over many rounds, most pass calls
    // fire nothing, so this removes the bulk of the per-pass input reconstruction the round engine repeats.
    private var cachedInput: Problem? = null
    private var inputDirty: Boolean = true

    init {
        for (id in base.factors.indices) recordOccurrences(id, base.factors[id])
        // The base [Problem] already ran its root bake at construction (outside presolve). If that
        // proved infeasibility, adopt it directly — re-propagating the whole factor set just to
        // rediscover a known root conflict is pure waste, and catastrophic on wide domains (a 2M-span
        // infeasible model spends seconds re-deriving what `base.baked` already holds). Otherwise
        // establish the fixpoint on the persistent state, priming the watcher/dirty machinery for
        // incremental re-propagation (the base's folded domains are copied in, so it settles there).
        if (base.baked is PropagationResult.Unsat) {
            infeasible = true
        } else if (state.runToFixpoint(allFactors = false) != null) {
            infeasible = true
        }
    }

    /** Append stable factor id [id] to the occurrence list of each int var factor [f] mentions, so the
     *  index carries [f] once per occurrence in its [Factor.intVars] (mirroring a fresh CSR rebuild). */
    private fun recordOccurrences(id: Int, f: Factor) {
        for (v in f.intVars) (intOcc[v] ?: IntArrayList(0).also { intOcc[v] = it }).add(id)
    }

    // Append-only logs of every factor add / drop, in application order, so a pass that reads them at a
    // saved [ChangeMark] can replay just the factors that changed since — the basis for an inter-round
    // incremental pass (re-examine the delta instead of rescanning the whole live set each firing). A
    // drop records both the id and the factor object (captured before its slot is nulled), since the
    // dropped factor's structural content is needed to retract it from a pass's persistent index.
    private val addedLog = IntArrayList(0)
    private val droppedFactorLog = ArrayList<Factor>()

    // Bumped whenever a reseed rebuilds the stable-id space from scratch ([reseedFromDelta]); a
    // [ChangeMark] taken before a reseed cannot be replayed (its ids name different factors afterwards),
    // so a pass holding a stale mark must fall back to a full scan.
    private var reseedEpoch = 0

    /** A read position into the change logs: the counts of adds and drops seen so far. A pass saves one
     *  after each run and asks for the changes since it on the next, so a firing sees exactly the factors
     *  other passes changed in between — including across rounds the pass itself was version-skipped. */
    class ChangeMark internal constructor(internal val added: Int, internal val dropped: Int, internal val epoch: Int)

    /** The current change-log position. */
    fun changeMark(): ChangeMark = ChangeMark(addedLog.size, droppedFactorLog.size, reseedEpoch)

    /** Whether [mark] predates a reseed, so the changes since it can't be replayed and the holder must
     *  rebuild from the full live set instead of applying an incremental delta. */
    fun markStale(mark: ChangeMark): Boolean = mark.epoch != reseedEpoch

    /** Stable ids of the factors added since [mark], in application order. */
    fun addedIdsSince(mark: ChangeMark): IntArray = IntArray(addedLog.size - mark.added) { addedLog[mark.added + it] }

    /** The factor objects dropped since [mark], in application order (captured at drop time). */
    fun droppedFactorsSince(mark: ChangeMark): List<Factor> =
        droppedFactorLog.subList(mark.dropped, droppedFactorLog.size)

    /** The live factor at stable id [id], or `null` if it was tombstoned. */
    fun factorAt(id: Int): Factor? = factors[id]

    /** Distinct integer variables mentioned by any factor added or dropped since [mark] — the variables
     *  whose affine candidacy a re-run must re-examine (an untouched variable's factors are unchanged, so
     *  its candidacy is unchanged since the previous run's fixpoint). */
    fun touchedIntVarsSince(mark: ChangeMark): IntArray {
        val vars = IntHashSet()
        for (i in mark.added until addedLog.size) {
            val f = factors[addedLog[i]] ?: continue
            for (v in f.intVars) vars.add(v)
        }
        for (i in mark.dropped until droppedFactorLog.size) {
            for (v in droppedFactorLog[i].intVars) vars.add(v)
        }
        return vars.toIntArray()
    }

    /** Live (non-tombstoned) factors in stable-id order — the current working constraint set. */
    fun liveFactors(): List<Factor> = factors.filterNotNull()

    /** Number of live (non-tombstoned) factors. */
    val liveFactorCount: Int get() = factors.count { it != null }

    /** Cheap problem-complexity measure mirroring [Presolver]'s fresh-path `complexity`: live constraint
     *  count plus total int-domain span. Drives the round engine's diminishing-returns abort. */
    fun complexity(): Long {
        var c = liveFactorCount.toLong()
        for (v in 0 until base.numIntVars) {
            val d = state.intDomains[v]
            c += d.max - d.min
        }
        return c
    }

    /** The current tightened domain of int var [v] on the persistent state. */
    fun intDomainOf(v: Int): IntDomain = state.intDomains[v]

    /** Current value of bool var [v] on the persistent state, or `null` if still free. */
    fun boolValueOf(v: Int): Boolean? = state.boolValues[v]

    /**
     * Apply [delta] incrementally: tombstone dropped ids, append added factors, push the pass's domain
     * narrowings, then re-propagate from just the delta. Returns `false` iff this proved infeasibility
     * (a conflict on a pushed narrowing or during re-propagation); the session latches [infeasible].
     */
    fun apply(delta: PresolveDelta): Boolean {
        if (!infeasible) snapshotFeasibleDomains()
        if (delta.droppedIds.isNotEmpty() || delta.addedFactors.isNotEmpty()) occDirty = true
        for (id in delta.droppedIds) {
            factors[id]?.let { droppedFactorLog.add(it) }
            state.tombstoneFactor(id)
            factors[id] = null
        }
        val addedIds = IntArrayList(delta.addedFactors.size)
        for (f in delta.addedFactors) {
            val fid = state.addMidlifeFactor(f) // fid == factors.size at this point
            addedIds.add(fid)
            factors.add(f)
            addedLog.add(fid)
            recordOccurrences(fid, f)
        }
        // Once infeasible, the factor changes above are still recorded (the materialized problem's bake
        // resurfaces the infeasibility) but the conflicted state must not be re-propagated. Thread the
        // pass's output domains forward as the reported domains so a later pass sees this pass's
        // narrowings — mirroring the fresh path, which carries each pass's fold-skipped domains to the
        // next, and keeping domain-pinning passes (dual fixing) converging on an infeasible problem.
        if (infeasible) {
            delta.domains?.let { d -> lastFeasibleDomains = Array(base.numIntVars) { d[it] } }
            return false
        }
        var ok = pushDomainNarrowings(delta.domains)
        if (ok && state.runToFixpoint(allFactors = false, initialFactors = addedIds.toIntArray()) != null) ok = false
        if (!ok) infeasible = true
        return ok
    }

    /** Push the per-variable diff of [domains] against the live state into the state's mutation API so
     *  dependent factors re-wake. `null` (no domain change) is a no-op. Returns false on a conflict. */
    private fun pushDomainNarrowings(domains: Array<IntDomain>?): Boolean {
        if (domains == null) return true
        for (v in domains.indices) {
            val target = domains[v]
            val cur = state.intDomains[v]
            if (target.min == cur.min && target.max == cur.max && target.values.size == cur.values.size) continue
            if (!state.tightenIntMin(v, target.min)) return false
            if (!state.tightenIntMax(v, target.max)) return false
            if (!pushInteriorHoles(v, target)) return false
        }
        return true
    }

    /** Remove from int var [v] the interior values the pass carved out of [target] — those still present
     *  on the state but absent from the target (rare; bound tightenings dominate). Returns false on a
     *  conflict, and skips the value scan entirely when the state domain already has no extra values. */
    private fun pushInteriorHoles(v: Int, target: IntDomain): Boolean {
        if (target.values.size == state.intDomains[v].values.size) return true
        for (value in target.min..target.max) {
            if (value in target || value !in state.intDomains[v]) continue
            if (!state.excludeIntValue(v, value)) return false
        }
        return true
    }

    /**
     * A cheap [BakedProblem] for a pass to read: the live factors and the state's current folded domains,
     * built already-folded so it never bakes or inverts occurrences. The passes read only
     * `factors` and `intDomains`, so nothing deferred is ever forced — construction is O(live factors).
     * Records [liveIds] parallel to the returned factor list so the next [applyDelta] maps the delta's
     * factor indices back to stable ids.
     */
    fun passInput(): Problem {
        cachedInput?.let { if (!inputDirty) return it }
        val live = ArrayList<Factor>(factors.size)
        val ids = IntArrayList(factors.size)
        for (id in factors.indices) {
            factors[id]?.let {
                live.add(it)
                ids.add(id)
            }
        }
        liveIds = ids.toIntArray()
        val input = BakedProblem(
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            // Once infeasible, expose the clean pre-conflict domains — not the partially-tightened live ones
            // a conflicted re-propagation left — so a later pass sees what the fresh path (fold skipped on an
            // Unsat bake) would and fires identically. While feasible, share the state's live domain array
            // directly: an alreadyFolded view never copies it, and a pass only reads it within one
            // firing (the next delta rebuilds this view), so the per-firing O(numIntVars) copy is avoided.
            intDomains = if (infeasible) lastFeasibleDomains else state.intDomains,
            factors = live,
            alreadyFolded = true,
            numRealVars = base.numRealVars,
            realLower = base.realLower,
            realUpper = base.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = base.intBounds.openLowerBits,
            packedOpenIntHi = base.intBounds.openUpperBits,
        )
        cachedInput = input
        inputDirty = false
        return input
    }

    /** The int-variable occurrence index over the factor list [passInput] last returned — the dense CSR
     *  the affine / dup-columns candidate search would otherwise rebuild itself. Derived lazily here: only
     *  the passes that read it ask, so a firing pass that changes the factor set does not force an
     *  O(occurrences) rebuild the non-consuming passes between it and the next consumer would waste. Call
     *  only after [passInput] (it fixes the [liveIds] the dense view is keyed against). */
    fun passOccurrence(): SharedIntOccurrence {
        rebuildOccViewIfDirty()
        return requireNotNull(occView)
    }

    /** Derive the dense [SharedIntOccurrence] over the current [liveIds] from the stable-id [intOcc]
     *  lists, but only when the factor set changed since the last build. Ascending stable id maps to
     *  ascending dense index (both follow stable-id order), so a var's factors come out in the same
     *  ascending-dense-index order a fresh `for f in factors for v in f.intVars` CSR would produce —
     *  byte-identical to the per-pass rebuild the passes drop. Tombstoned ids (dense = -1) are skipped. */
    private fun rebuildOccViewIfDirty() {
        if (!occDirty && occView != null) return
        val denseOf = IntArray(factors.size) { -1 }
        for (dense in liveIds.indices) denseOf[liveIds[dense]] = dense
        val offsets = IntArray(base.numIntVars + 1)
        for (v in 0 until base.numIntVars) {
            var live = 0
            val list = intOcc[v]
            if (list != null) for (k in 0 until list.size) if (denseOf[list[k]] >= 0) live++
            offsets[v + 1] = offsets[v] + live
        }
        val flat = IntArray(offsets[base.numIntVars])
        val cursor = offsets.copyOf()
        for (v in 0 until base.numIntVars) {
            val list = intOcc[v] ?: continue
            for (k in 0 until list.size) {
                val dense = denseOf[list[k]]
                if (dense >= 0) flat[cursor[v]++] = dense
            }
        }
        occView = SharedIntOccurrence(offsets, flat)
        occDirty = false
    }

    /**
     * Fold a pass's [PassDelta] into the session. Its [PassDelta.droppedIndices] index the factor list
     * [passInput] last returned, so they are mapped through [liveIds] to stable ids before running the
     * existing [apply] logic (widen→reseed, infeasible threading, incremental re-propagation). Returns
     * `false` iff this proved infeasibility.
     */
    fun applyDelta(delta: PassDelta): Boolean {
        val stableDropped = IntArray(delta.droppedIndices.size) { liveIds[delta.droppedIndices[it]] }
        // A domain *widen* (dup-columns' aggregate variable) can't be reached by monotone re-propagation,
        // so it triggers a from-scratch reseed — but only while feasible; on an already-infeasible problem
        // the widen is moot and the factor changes are tracked through [apply].
        if (!infeasible && delta.domains != null && widensAnyDomain(delta.domains)) {
            // A reseed reassigns [state], so the cached [passInput] view (which aliased the old domain array) is stale.
            inputDirty = true
            return reseedFromDelta(stableDropped, delta.addedFactors, delta.domains)
        }
        val wasInfeasible = infeasible
        val result = apply(PresolveDelta(stableDropped, delta.addedFactors, delta.domains))
        // The cached [passInput] view aliases the live factor list and the shared [state.intDomains] array,
        // so a domains-only narrowing (which mutates that array in place) leaves it valid. Invalidate only
        // when the factor set changed — the live list and [liveIds] must be rebuilt — or when feasibility
        // flipped, since [passInput] then swaps to [lastFeasibleDomains].
        if (delta.droppedIndices.isNotEmpty() || delta.addedFactors.isNotEmpty() || infeasible != wasInfeasible) {
            inputDirty = true
        }
        return result
    }

    /** Whether [domains] widens any variable past the live state — a value the state currently excludes
     *  becomes allowed. Monotone re-propagation only narrows, so a widen needs a [reseedFromDelta] instead. */
    private fun widensAnyDomain(domains: Array<IntDomain>): Boolean {
        for (v in 0 until base.numIntVars) {
            val cur = state.intDomains[v]
            val target = domains[v]
            if (target.min < cur.min || target.max > cur.max || target.values.size > cur.values.size) return true
        }
        return false
    }

    /** Rebuild the persistent state from scratch over the delta's output factor set + [domains] (a fresh
     *  eager bake). Used only when a pass widens a domain; correct because it reproduces the exact
     *  from-scratch problem for that transition. The output factor set is the live factors minus
     *  [stableDropped], in stable-id order, followed by [added] — the same set [apply] would land on. */
    private fun reseedFromDelta(stableDropped: IntArray, added: List<Factor>, domains: Array<IntDomain>): Boolean {
        val dropped = if (stableDropped.isEmpty()) null else stableDropped.toHashSet()
        val out = ArrayList<Factor>(factors.size)
        for (id in factors.indices) {
            val f = factors[id] ?: continue
            if (dropped == null || id !in dropped) out.add(f)
        }
        out.addAll(added)
        val eager = PresolveShared.rebuildProblem(base, out, Array(base.numIntVars) { domains[it] }, bakeConfig)
        stateProblem = eager
        factors.clear()
        factors.addAll(eager.factors)
        // The stable-id space was rebuilt from scratch, so the occurrence lists — and any pass's replayable
        // change history — must be too; bumping the epoch forces a stale-marked pass to rescan.
        for (v in 0 until base.numIntVars) intOcc[v]?.clear()
        for (id in eager.factors.indices) recordOccurrences(id, eager.factors[id])
        addedLog.clear()
        droppedFactorLog.clear()
        reseedEpoch++
        occDirty = true
        state = PropagationState(eager, Assumptions.None, incremental = true)
        if (state.runToFixpoint(allFactors = true) != null) {
            infeasible = true
            return false
        }
        return true
    }

    /** Capture the live domains as the last-feasible snapshot, taken before a mutation's narrowings so
     *  that if the mutation proves infeasible the reported domains are the pre-conflict ones. */
    private fun snapshotFeasibleDomains() {
        lastFeasibleDomains = Array(base.numIntVars) { state.intDomains[it] }
    }

    /** Materialize the final solver [Problem] once: the live factors and the tightened int domains. On
     *  infeasibility the pre-conflict [lastFeasibleDomains] are used (the fresh path likewise skips
     *  folding an Unsat bake); otherwise the state's fully-folded domains.
     *
     *  With no root-bake probing enabled (the common incremental case) the state domains are already the
     *  greatest fixpoint, so the result is an already-folded [BakedProblem]: its `baked` stays lazy and is
     *  computed by the solver at solve time, not inside the presolve window — the session's own [infeasible]
     *  flag surfaces the infeasibility to the caller without forcing that bake. When probing *is* enabled it
     *  must be re-derived over the final factor set (a [RootBaker.reseed] the already-folded view would skip),
     *  so the eager rebuild path is taken. */
    fun materialize(): Problem {
        val domains = if (infeasible) lastFeasibleDomains else Array(base.numIntVars) { state.intDomains[it] }
        if (bakeConfig.anyEnabled) {
            return PresolveShared.rebuildProblem(stateProblem, liveFactors(), domains, bakeConfig)
        }
        return BakedProblem(
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            intDomains = domains,
            factors = liveFactors(),
            alreadyFolded = true,
            numRealVars = base.numRealVars,
            realLower = base.realLower,
            realUpper = base.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = base.intBounds.openLowerBits,
            packedOpenIntHi = base.intBounds.openUpperBits,
        )
    }
}
