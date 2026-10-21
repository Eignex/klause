package com.eignex.klause.presolve

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RootWorkAccounting
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
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
 * Int-variable occurrence index over a pass's live factor list, in `Factor.intVars` CSR layout: the
 * factors mentioning var `v` are the dense indices `flat[offsets(v) until offsets(v + 1)]`, ascending.
 * [PresolveSession] maintains the underlying stable-id lists incrementally and derives this dense view
 * once per firing.
 */
class SharedIntOccurrence internal constructor(internal val offsets: IntArray, internal val flat: IntArray)

/**
 * Owns the persistent state of an incremental presolve run so the round engine never rebuilds a
 * `Problem` per firing pass. Holds the working factor set as a stable-id, append-only list (a
 * tombstoned slot becomes `null` and its id is never reused) and one persistent [PropagationState]
 * sized to the original variable count — valid for the whole run because presolve never renumbers
 * variables.
 *
 * A pass emits a [PresolveDelta]; [apply] drops/adds factors and pushes the pass's domain narrowings
 * into the live state, then re-propagates incrementally from just that delta via the existing
 * dirty-variable/watcher machinery. Because the propagators are monotone the greatest fixpoint is
 * unique, so this reaches the same tightened domains a from-scratch bake over the final factor set
 * would — no per-pass `computeBaked`. [materialize] builds the heavyweight solver `Problem` once at
 * the end, with the single renumber/remap.
 */
internal class PresolveSession(
    private val base: BakedProblem,
    private val bakeConfig: BakeConfig = BakeConfig.NONE,
    private val cancellation: Cancellation = base.cancellation,
) {

    // Working factor set indexed by stable id: `[0, base.factors.size)` are the originals, appended
    // ids are pass-added factors. `null` marks a tombstoned (dropped) id; ids are never renumbered.
    // Refilled in place by [reseedFromDelta] (a domain-widening pass the incremental path can't express).
    private val factors: ArrayList<Factor?> = ArrayList<Factor?>(base.factors.size).apply { addAll(base.factors) }

    // The problem the current [state] is baked over: [base] initially, a widening pass's output after a
    // [reseedFromDelta]. Its factor count is the stable-id boundary between base and mid-life factors.
    private var stateProblem: BakedProblem = base

    // Seeded from the base's already-computed root fixpoint rather than re-propagating every factor
    // inside the presolve window: the seeded pins/bounds go through the atom-consistent mutation API and
    // settle at that same greatest fixpoint. A [PropagationResult.Unsat] base seeds nothing (the init
    // below adopts its infeasibility).
    private var state = PropagationState(
        PropagationProblem(base),
        (base.baked as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None,
        incremental = true,
    )

    private var accounting = RootWorkAccounting(cancellation, state)

    private fun propagate(allFactors: Boolean, initialFactors: IntArray = EmptyIntArray): IntArray? {
        state.cancelFloor = 0
        try {
            return state.runToFixpoint(allFactors, initialFactors = initialFactors, cancellation = accounting)
        } finally {
            accounting.flush()
        }
    }

    /** Set once the base bake or a delta re-propagation derives a root contradiction. */
    var infeasible: Boolean = false
        private set

    // Domains as of the last point the problem was still feasible. On infeasibility the reported /
    // materialized domains are these, not the partially-tightened live ones, matching a from-scratch
    // rebuild, which does not fold an Unsat bake. Snapshotted at each mutation entry (before its
    // narrowings) and initialised to the base.
    private var lastFeasibleDomains: Array<IntDomain> = base.rootIntDomains()

    // Stable ids of the live factors [passInput] last returned, parallel to that view's factor list, so
    // a [PassDelta]'s droppedIndices (into that list) map back to the stable ids [apply] tombstones.
    private var liveIds: IntArray = EmptyIntArray

    // Int-variable occurrence index keyed by stable factor id: `intOcc(v)` holds the stable ids of every
    // live factor whose `Factor.intVars` contains `v`, in ascending-stable-id (= append) order, maintained
    // O(delta) in [apply]. A tombstoned factor's id stays (filtered on read, its slot is null).
    // Allocated lazily per variable: a model can declare millions of integer variables while only a few
    // thousand ever occur in a factor, so allocating a list for every declared variable up front would
    // dominate construction. A `null` slot is an empty list.
    private val intOcc: Array<IntArrayList?> = arrayOfNulls(base.numIntVars)

    // The dense [SharedIntOccurrence] view last handed out, and whether the factor set has changed since
    // it was built. A round that changes nothing pays no occurrence-index rebuild.
    private var occView: SharedIntOccurrence? = null
    private var occDirty: Boolean = true

    // The view [passInput] last built, reused until the working state changes. Most pass calls on a large
    // model fire nothing, so this skips most per-pass input reconstruction.
    private var cachedInput: BakedProblem? = null
    private var inputDirty: Boolean = true
    private var aggregateEntryUnits: Long? = null

    internal fun entryUnits(token: Cancellation): Long {
        if (token.workMeter() == null) return 0L
        aggregateEntryUnits?.let { return it }
        val input = if (addedLog.isEmpty() && droppedFactorLog.isEmpty() && reseedEpoch == 0) base else passInput()
        return (token.presolveBudget()?.entryUnits(input.factors) ?: passBaseUnits(input.factors)).also {
            aggregateEntryUnits = it
        }
    }

    private fun updateEntryUnits(dropped: IntArray, added: List<Factor>) {
        var units = aggregateEntryUnits ?: return
        for (id in dropped) factors[id]?.let { units -= 1L + it.structuralKeyWeight }
        for (factor in added) units += 1L + factor.structuralKeyWeight
        cancellation.charge(dropped.size.toLong() + added.size)
        aggregateEntryUnits = units
    }

    init {
        for (id in base.factors.indices) recordOccurrences(id, base.factors[id])
        // A base whose root bake proved infeasibility is adopted directly: re-propagating the whole factor
        // set to rediscover a known root conflict can cost seconds on wide domains. Otherwise establish the
        // fixpoint on the persistent state, priming the watcher/dirty machinery for incremental
        // re-propagation.
        if (base.baked is PropagationResult.Unsat) {
            infeasible = true
        } else if (propagate(allFactors = false) != null) {
            infeasible = true
        }
    }

    /** Records [id] once per occurrence in [f]'s `Factor.intVars`, matching a from-scratch CSR rebuild. */
    private fun recordOccurrences(id: Int, f: Factor) {
        for (v in f.intVars) (intOcc[v] ?: IntArrayList(0).also { intOcc[v] = it }).add(id)
    }

    // Append-only logs of every factor add / drop, in application order, so a pass holding a saved
    // [ChangeMark] can replay just the factors that changed since instead of rescanning the live set. A
    // drop records the factor object (captured before its slot is nulled), since its structural content
    // is needed to retract it from a pass's persistent index.
    private val addedLog = IntArrayList(0)
    private val droppedFactorLog = ArrayList<Factor>()

    // Bumped whenever [reseedFromDelta] rebuilds the stable-id space; a [ChangeMark] taken before it
    // cannot be replayed (its ids name different factors afterwards), so its holder falls back to a full scan.
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

    internal fun anyTouchedFactor(
        mark: ChangeMark?,
        token: Cancellation,
        candidate: (Factor) -> Boolean,
    ): Boolean {
        if (mark == null || markStale(mark)) return true
        for (v in touchedIntVarsSince(mark)) {
            val occurrences = intOcc[v] ?: continue
            for (k in 0 until occurrences.size) {
                val factor = factors[occurrences[k]] ?: continue
                token.charge(1L)
                if (token()) return false
                if (candidate(factor)) return true
            }
        }
        return false
    }

    /** Live (non-tombstoned) factors in stable-id order — the current working constraint set. */
    fun liveFactors(): List<Factor> = factors.filterNotNull()

    /** Number of live factors. */
    var liveFactorCount: Int = base.factors.size
        private set

    /** Cheap problem-complexity measure: live constraint count plus total int-domain span. Drives the
     *  round engine's diminishing-returns abort. */
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
     * The Booleans the state has fixed, as deductions for a problem built over the live factors.
     *
     * A fixing outlives the factors that derived it: a pass can drop every factor that reads a fixed
     * Boolean while the state keeps its value, and a bake over the live factors alone would read it as
     * free. Every problem the session builds carries these, as it carries the state's int domains, so a
     * pass reasons over the same model its delta is propagated against.
     */
    private fun boolFixings(): PropagationResult {
        if (infeasible) return PropagationResult.Implied.EMPTY
        val keys = IntArrayList()
        for (v in 0 until base.numBoolVars) if (state.boolValues[v] != null) keys.add(v)
        if (keys.isEmpty()) return PropagationResult.Implied.EMPTY
        return PropagationResult.Implied(
            boolKeys = keys.toIntArray(),
            boolValues = BooleanArray(keys.size) { state.boolValues[keys[it]] == true },
            intKeys = EmptyIntArray,
            intValues = EmptyLongArray,
        )
    }

    /**
     * Apply [delta] incrementally: tombstone dropped ids, append added factors, push the pass's domain
     * narrowings, then re-propagate from just the delta. Returns `false` iff this proved infeasibility
     * (a conflict on a pushed narrowing or during re-propagation); the session latches [infeasible].
     */
    fun apply(delta: PresolveDelta): Boolean {
        updateEntryUnits(delta.droppedIds, delta.addedFactors)
        if (!infeasible) snapshotFeasibleDomains()
        if (delta.droppedIds.isNotEmpty() || delta.addedFactors.isNotEmpty()) occDirty = true
        for (id in delta.droppedIds) {
            factors[id]?.let {
                droppedFactorLog.add(it)
                liveFactorCount--
            }
            state.tombstoneFactor(id)
            factors[id] = null
        }
        val addedIds = IntArrayList(delta.addedFactors.size)
        for (f in delta.addedFactors) {
            accounting.construction(1L + f.structuralKeyWeight)
            val fid = state.addMidlifeFactor(f) // fid == factors.size at this point
            addedIds.add(fid)
            factors.add(f)
            liveFactorCount++
            addedLog.add(fid)
            recordOccurrences(fid, f)
        }
        // Once infeasible, the factor changes above are still recorded (the materialized problem's bake
        // resurfaces the infeasibility) but the conflicted state must not be re-propagated. Thread the
        // pass's output domains forward as the reported domains so a later pass sees this pass's
        // narrowings, keeping domain-pinning passes (dual fixing) converging on an infeasible problem.
        if (infeasible) {
            delta.domains?.let { d -> lastFeasibleDomains = Array(base.numIntVars) { d[it] } }
            accounting.flush()
            return false
        }
        var ok = pushDomainNarrowings(delta.domains)
        if (ok && propagate(allFactors = false, initialFactors = addedIds.toIntArray()) != null) ok = false
        accounting.flush()
        if (!ok) infeasible = true
        return ok
    }

    /** Push the per-variable diff of [domains] against the live state through the state's mutation API so
     *  dependent factors re-wake. Returns false on a conflict. */
    private fun pushDomainNarrowings(domains: Array<IntDomain>?): Boolean {
        if (domains == null) return true
        for (v in domains.indices) {
            val target = domains[v]
            val cur = state.intDomains[v]
            if (target.min == cur.min && target.max == cur.max && target.valueCount == cur.valueCount) continue
            if (!state.tightenIntMin(v, target.min)) return false
            if (!state.tightenIntMax(v, target.max)) return false
            if (!pushInteriorHoles(v, target)) return false
        }
        return true
    }

    /** Remove from int var [v] the interior values present on the state but absent from [target]. Returns
     *  false on a conflict; skips the value scan when the value counts already agree. */
    private fun pushInteriorHoles(v: Int, target: IntDomain): Boolean {
        if (target.valueCount == state.intDomains[v].valueCount) return true
        for (value in target.min..target.max) {
            if (value in target || value !in state.intDomains[v]) continue
            if (!state.excludeIntValue(v, value)) return false
        }
        return true
    }

    /**
     * A cheap [BakedProblem] for a pass to read: the live factors and the state's current folded domains,
     * built already-folded so it never bakes or inverts occurrences. The passes read only `factors` and
     * `intDomains`, so nothing deferred is forced. Records [liveIds] parallel to the returned factor list
     * so the next [applyDelta] maps the delta's factor indices back to stable ids.
     */
    fun passInput(): BakedProblem {
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
            settings = base.settings,
            semantics = base.semantics,
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            // Once infeasible, expose the pre-conflict domains, not the partially-tightened live ones a
            // conflicted re-propagation left, as a from-scratch rebuild (fold skipped on an Unsat bake) would.
            // While feasible, share the state's live domain array directly: an alreadyFolded view never
            // copies it, and a pass reads it only within one firing.
            intDomains = if (infeasible) lastFeasibleDomains else state.intDomains,
            factors = live,
            seedDeductions = boolFixings(),
            alreadyFolded = true,
            cancellation = cancellation,
            numRealVars = base.numRealVars,
            realLower = base.realLower,
            realUpper = base.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = base.intBounds.openLowerBits,
            packedOpenIntHi = base.intBounds.openUpperBits,
            projectionProviders = base.projectionProviders,
            projectionSelection = base.factorProjections?.let { captured ->
                { view -> captured.forEngineView(view) }
            },
        )
        cachedInput = input
        inputDirty = false
        return input
    }

    /** The int-variable occurrence index over the factor list [passInput] last returned. Derived lazily,
     *  so passes that never read it pay no O(occurrences) rebuild. Call only after [passInput] (it fixes
     *  the [liveIds] the dense view is keyed against). */
    fun passOccurrence(): SharedIntOccurrence {
        rebuildOccViewIfDirty()
        return requireNotNull(occView)
    }

    /** Derive the dense [SharedIntOccurrence] over the current [liveIds] from the stable-id [intOcc]
     *  lists when the factor set changed. Ascending stable id maps to ascending dense index, so a var's
     *  factors come out in the order a from-scratch CSR over `factors` would produce. */
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
     * [passInput] last returned, so they are mapped through [liveIds] to stable ids before [apply] (or
     * [reseedFromDelta] for a widening delta). Returns `false` iff this proved infeasibility.
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
            if (target.min < cur.min || target.max > cur.max || target.valueCount > cur.valueCount) return true
        }
        return false
    }

    /** Rebuild the persistent state from scratch over the delta's output factor set + [domains] (a fresh
     *  eager bake). Used only when a pass widens a domain; correct because it reproduces the exact
     *  from-scratch problem for that transition. The output factor set is the live factors minus
     *  [stableDropped], in stable-id order, followed by [added] — the same set [apply] would land on. */
    private fun reseedFromDelta(stableDropped: IntArray, added: List<Factor>, domains: Array<IntDomain>): Boolean {
        updateEntryUnits(stableDropped, added)
        val dropped = if (stableDropped.isEmpty()) null else stableDropped.toHashSet()
        val out = ArrayList<Factor>(factors.size)
        for (id in factors.indices) {
            val f = factors[id] ?: continue
            if (dropped == null || id !in dropped) out.add(f)
        }
        out.addAll(added)
        val fixings = boolFixings()
        val eager = PresolveShared.rebuildProblem(
            base,
            out,
            Array(base.numIntVars) { domains[it] },
            bakeConfig,
            seedDeductions = fixings,
            cancellation = cancellation,
        )
        stateProblem = eager
        factors.clear()
        factors.addAll(eager.factors)
        liveFactorCount = eager.factors.size
        // The stable-id space was rebuilt from scratch, so the occurrence lists — and any pass's replayable
        // change history — must be too; bumping the epoch forces a stale-marked pass to rescan.
        for (v in 0 until base.numIntVars) intOcc[v]?.clear()
        for (id in eager.factors.indices) recordOccurrences(id, eager.factors[id])
        addedLog.clear()
        droppedFactorLog.clear()
        reseedEpoch++
        occDirty = true
        accounting.flush()
        state = PropagationState(
            PropagationProblem(eager),
            (fixings as? PropagationResult.Implied)?.toAssumptions() ?: Assumptions.None,
            incremental = true,
        )
        accounting = RootWorkAccounting(cancellation, state)
        if (propagate(allFactors = true) != null) {
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

    /** Materialize the final solver problem once: the live factors and the tightened int domains. On
     *  infeasibility the pre-conflict [lastFeasibleDomains] are used; otherwise the state's folded domains.
     *
     *  With no root-bake probing enabled the state domains are already the greatest fixpoint, so the result
     *  is an already-folded [BakedProblem] whose `baked` stays lazy until solve time; the session's own
     *  [infeasible] flag surfaces infeasibility without forcing that bake. When probing is enabled it must be
     *  re-derived over the final factor set (a [RootBaker.reseed] the already-folded view would skip), so the
     *  eager rebuild path is taken. */
    fun materialize(): BakedProblem {
        val domains = if (infeasible) lastFeasibleDomains else Array(base.numIntVars) { state.intDomains[it] }
        if (bakeConfig.anyEnabled) {
            return PresolveShared.rebuildProblem(
                stateProblem,
                liveFactors(),
                domains,
                bakeConfig,
                seedDeductions = boolFixings(),
                cancellation = cancellation,
            )
        }
        return BakedProblem(
            settings = base.settings,
            semantics = base.semantics,
            numBoolVars = base.numBoolVars,
            numIntVars = base.numIntVars,
            intDomains = domains,
            factors = liveFactors(),
            seedDeductions = boolFixings(),
            alreadyFolded = true,
            cancellation = cancellation,
            numRealVars = base.numRealVars,
            realLower = base.realLower,
            realUpper = base.realUpper,
            // See PresolveShared.rebuildProblem: the open-side marks address a namespace presolve keeps.
            packedOpenIntLo = base.intBounds.openLowerBits,
            packedOpenIntHi = base.intBounds.openUpperBits,
            projectionProviders = base.projectionProviders,
            projectionSelection = base.factorProjections?.let { captured ->
                { view -> captured.forEngineView(view) }
            },
        )
    }
}
