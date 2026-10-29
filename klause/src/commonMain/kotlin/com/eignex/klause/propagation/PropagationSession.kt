package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.ValueOrderingStatsSink
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.SharingMemorySnapshot
import kotlin.math.ln

/** Variable kind discriminator for [PropagationSession.popUntilUnpinned]. */
enum class VarKind {
    /** A Boolean variable. */
    Bool,

    /** An integer variable. */
    Int,
}

/**
 * Stateful propagator with a decision trail. Push pins via [pinBool] / [pinInt]; each push
 * increments [decisionLevel] and propagates incrementally (only factors whose vars changed
 * are revisited). On [PropagationResult.Unsat], the result carries `conflictLevels` so a
 * CSP-style DFS sampler can backjump directly past the deepest non-conflicting level.
 *
 * One persistent [PropagationState] is reused across pushes. Each successful push records a
 * [PropagationState.LevelMark]; [popLast] / [popToLevel] rewind to the target level's mark
 * by replaying the state's undo log in O(changes), no re-propagation. Conflict on push
 * reverts the state to the last good mark before returning Unsat — the session's trail is
 * left at the pre-push level.
 *
 * Not thread-safe. One consumer per session.
 */
class PropagationSession private constructor(
    /** The problem being propagated. */
    val problem: Problem,
    private var cancellation: Cancellation,
    propagationCancelFloor: Int,
    nativeSat: Boolean,
    pbLearning: Boolean,
    /** Engine projection shared by this session and its state. */
    val projection: PropagationProblem,
    preparedState: PropagationState? = null,
    rootConflict: IntArray? = null,
    preparedBuffers: PropagationSessionBuffers? = null,
    preparedMark: PropagationState.LevelMark? = null,
    initialAssumptions: Assumptions = Assumptions.None,
    cumulativeUnarySubsets: Boolean = false,
    attribution: Boolean = false,
    historicalTables: Boolean = false,
    costAware: Boolean = false,
    reasonPreflight: Boolean = false,
) {
    /** Construct a private propagation projection over [problem]. */
    constructor(
        problem: Problem,
        cancellation: Cancellation = Cancellation.Never,
        propagationCancelFloor: Int = PROPAGATION_CANCEL_FLOOR,
        nativeSat: Boolean = false,
        pbLearning: Boolean = false,
    ) : this(problem, cancellation, propagationCancelFloor, nativeSat, pbLearning, PropagationProblem(problem))

    internal constructor(
        projection: PropagationProblem,
        cancellation: Cancellation = Cancellation.Never,
        propagationCancelFloor: Int = PROPAGATION_CANCEL_FLOOR,
        nativeSat: Boolean = false,
        pbLearning: Boolean = false,
        cumulativeUnarySubsets: Boolean = false,
        attribution: Boolean = false,
        historicalTables: Boolean = false,
        costAware: Boolean = false,
        reasonPreflight: Boolean = false,
    ) : this(projection.problem, cancellation, propagationCancelFloor, nativeSat, pbLearning, projection,
        cumulativeUnarySubsets = cumulativeUnarySubsets, attribution = attribution, historicalTables = historicalTables,
        costAware = costAware, reasonPreflight = reasonPreflight)

    internal fun checkSelectedProjection(
        checks: SelectedProjectionChecks? = projection.selection?.originalChecks,
    ): ComponentCheck = checks?.check(this, cancellation) ?: ComponentCheck.Feasible

    internal fun recordProjectionCheck(checker: PropagationSession) {
        state.work += checker.work
        state.propagationNanos += checker.propagationNanos
    }

    private var retainedState: PropagationState? =
        preparedState ?: PropagationState(
            projection,
            initialAssumptions,
            nativeSat = nativeSat,
            pbLearning = pbLearning,
        ).also {
            it.cancelFloor = propagationCancelFloor
            it.cumulativeUnarySubsets = cumulativeUnarySubsets
            if (attribution) it.factorAttribution = FactorAttribution()
            it.historicalTableReasons = historicalTables
            if (costAware) it.propagationSchedule = PropagationSchedule()
            it.reasonPreflight = reasonPreflight
        }
    private val state: PropagationState get() = checkNotNull(retainedState) { "propagation session is released" }
    private var retiredWork = 0L
    private var retiredPropagationNanos = 0L
    private var retiredPropagationCount = 0L
    private var retiredLifecycle: Map<String, Long> = emptyMap()

    internal fun releaseRetainedState() {
        val owner = retainedState ?: return
        retiredWork = owner.work
        retiredPropagationNanos = owner.propagationNanos
        retiredPropagationCount = owner.propagations
        retiredLifecycle = learnedLifecycle()
        retainedState = null
        levelStates = emptyArray()
        levelTop = 0
        sharedLearningMemory = null
        sharedLearningPosition = null
        retainedSharingMemory = null
        retainedSharingSize = null
        retainedInprocessingMemory = null
        simplifiedRootSources = null
        pendingRootSourceRetirement = null
    }

    /** Subscribe to per-variable unassign events fired by every backtrack/undo; see
     *  [PropagationState.undoTo]. Combined-index encoding: bool id `v`, int id `numBoolVars + v`. */
    var unassignListener: ((Int) -> Unit)?
        get() = state.unassignListener
        set(value) {
            state.unassignListener = value
        }

    internal var retractingListener: ((Int) -> Unit)?
        get() = state.retractingListener
        set(value) {
            state.retractingListener = value
        }

    /** `levelStates[L]` is the [PropagationState.LevelMark] right after level `L`'s
     *  fixpoint. Index 0 = post-bake. Array-backed stack with explicit [levelTop]; grows by
     *  doubling. Marks are tiny (five ints + rare payload copies) — no pooling needed. */
    private var levelStates: Array<PropagationState.LevelMark?> = arrayOfNulls(8)
    private var levelTop: Int = 0
    private fun levelLast(): PropagationState.LevelMark = requireNotNull(levelStates[levelTop - 1])
    private fun levelPush(m: PropagationState.LevelMark) {
        if (levelTop == levelStates.size) levelStates = levelStates.copyOf(levelStates.size * 2)
        levelStates[levelTop++] = m
    }
    private fun levelPop() {
        levelTop--
        levelStates[levelTop] = null
    }
    private fun levelTruncateAfterRoot() {
        for (i in 1 until levelTop) levelStates[i] = null
        levelTop = 1
    }

    // Decision pins, primitive-encoded to keep a push allocation- and boxing-free.
    // [boolPinned] holds -1 (free) / 0 (false) / 1 (true) per bool var; [intPinnedSet] + [intPinnedVal]
    // hold the int decisions. Only
    // *decision* pins live here — propagation-implied facts are read from [state]. [trail]
    // is the decision stack used as a LIFO, each entry encoded as `v` (bool) or
    // `numBoolVars + v` (int), matching PropagationState's level encoding.
    private val boolPinned: IntArray = preparedBuffers?.boolPinned ?: (IntArray(problem.numBoolVars) { -1 })
    private val intPinnedSet: BooleanArray = preparedBuffers?.intPinnedSet ?: (BooleanArray(problem.numIntVars))
    private val intPinnedVal: LongArray = preparedBuffers?.intPinnedVal ?: (LongArray(problem.numIntVars))
    private val intPinnedLevel: IntArray = IntArray(problem.numIntVars)
    private val trail: IntArrayList = IntArrayList()
    private var seededRestrictions: DeducedRestrictions = DeducedRestrictions.None
    private var scopedLearnedAssertion = false
    private var simplifiedRootSources: MutableSet<Int>? = null
    private var inprocessingRootPending = false
    internal data class RootSourceRetirement(val factor: Int, val literals: IntArray)
    internal var pendingRootSourceRetirement: RootSourceRetirement? = null
        private set

    internal fun retainRootSourceRetirement(factor: Int, literals: IntArray) {
        check(factor in 0 until problem.numFactors && pendingRootSourceRetirement == null)
        pendingRootSourceRetirement = RootSourceRetirement(factor, literals)
    }

    internal fun resumeInprocessingRoot(): Boolean = withRequiredInprocessingCancellation {
        if (!inprocessingRootPending) return@withRequiredInprocessingCancellation false
        check(decisionLevel == 0 && seededRestrictions.isEmpty && !scopedLearnedAssertion)
        if (cancelRequested) return@withRequiredInprocessingCancellation true
        state.runCancelled = false
        val conflict = state.inprocessingFixpoint(-1, cancellation)
        if (conflict != null) bakedUnsat = PropagationResult.Unsat(conflictLevels = conflict)
        inprocessingRootPending = state.runCancelled
        if (!inprocessingRootPending) levelStates[levelTop - 1] = state.mark()
        true
    }

    internal val canSimplifyRoot: Boolean
        get() = decisionLevel == 0 && seededRestrictions.isEmpty && !scopedLearnedAssertion &&
            !fixpointCancelled && !isUnsatAtRoot

    internal val rootClauseCount: Int get() = problem.numFactors + learnedClauseCount

    internal var learnedClauseRevision: Long = 0L
        private set

    internal fun learnedClauseLiteralCount(index: Int): Int = state.nativeEngine?.literalCountOf(index)
        ?: (state.learned.store[index] as? ClausePropagator)?.literals?.size ?: 0

    internal fun chargeInprocessingWork(count: Long) {
        state.work += count
    }

    internal fun rootClauseLiterals(factor: Int, limit: Int): IntArray? {
        if (simplifiedRootSources?.contains(factor) == true) return null
        if (factor < problem.numFactors) {
            val literals = if (usesNativeSat) (problem.factors[factor] as? Clause)?.literals
                else (projection.propagators[factor] as? ClausePropagator)?.literals
            if (literals == null || literals.size > limit) return null
            state.work += literals.size
            if (!literals.all { lit -> Lit.variable(lit) < problem.numBoolVars }) {
                return null
            }
            return literals
        }
        val index = factor - problem.numFactors
        if (!isLearnedClause(index) || learnedClausePermanent(index)) return null
        val size = learnedClauseLiteralCount(index)
        if (size > limit) return null
        state.work += size.toLong() * (if (usesNativeSat) 2L else 1L)
        val literals = learnedClauseLiterals(index)
        if (!literals.all { lit -> Lit.variable(lit) < problem.numBoolVars }) {
            return null
        }
        return literals
    }

    internal fun addRootDerivedClause(
        clause: Clause,
        lbd: Int,
        origin: Int,
        sourceReplacement: Boolean,
    ): PropagationResult = withRequiredInprocessingCancellation {
        check(canSimplifyRoot)
        val base = state.undoTop
        val fid = state.addLearnedClause(clause, lbd, permanent = sourceReplacement, origin = origin)
        state.factorAttribution?.let {
            if (state.nativeEngine == null) it.admit(state.factorAt(fid))
            else if (it.admissionFamilies != 0) it.note(it.admissionFamilies, FactorAttribution.Metric.Learned)
        }
        val conflict = state.inprocessingFixpoint(fid, cancellation)
        inprocessingRootPending = state.runCancelled
        if (conflict != null) {
            return@withRequiredInprocessingCancellation revertAndUnsat(conflict).also { bakedUnsat = it }
        }
        if (!inprocessingRootPending) levelStates[levelTop - 1] = state.mark()
        if (inprocessingRootPending) PropagationResult.Implied.EMPTY else impliedSince(base)
    }

    internal fun retireRootSource(factor: Int, literals: IntArray) {
        check(canSimplifyRoot && factor < problem.numFactors)
        val retired = simplifiedRootSources ?: HashSet<Int>().also { simplifiedRootSources = it }
        retired.add(factor)
        val native = state.nativeEngine
        if (native != null) native.retireRootSource(factor)
        else for (literal in literals) {
            if (state.watches.pos.getOrDefault(state.packWatch(factor, literal), -1) >= 0) {
                state.removeBoolWatch(factor, literal)
            }
        }
        if (pendingRootSourceRetirement?.factor == factor) pendingRootSourceRetirement = null
    }

    private val impliedIntUndo: IntArray = preparedBuffers?.impliedIntUndo ?: (IntArray(problem.numIntVars) { -1 })
    private fun encBool(v: Int): Int = v
    private fun encInt(v: Int): Int = problem.numBoolVars + v
    private fun trailIsBool(enc: Int): Boolean = enc < problem.numBoolVars

    /** The clause forbidding the exact total assignment `(bools, ints)`: one literal per
     *  variable demanding it differ. Blocking a found solution's full assignment (rather than
     *  the decisions that led to it) is order-independent — the same assignment reached later
     *  through a different decision sequence is still excluded. */
    fun assignmentNogood(bools: BooleanArray, ints: LongArray): IntArray {
        val out = IntArray(bools.size + ints.size)
        var j = 0
        for (v in bools.indices) out[j++] = Lit.make(v, !bools[v])
        for (v in ints.indices) out[j++] = Lit.make(state.atomVarEq(v, ints[v]), false)
        return out
    }

    internal fun equalityLit(varId: Int, value: Long, positive: Boolean = true): Int =
        Lit.make(state.atomVarEq(varId, value), positive)

    /** Literal `x_v ≥ threshold` ([positive]) or its negation — for LP/energetic explanation
     *  clauses, whose reason atoms are absolute variable bounds. */
    fun boundGeLit(v: Int, threshold: Long, positive: Boolean): Int = Lit.make(state.atomVarGe(v, threshold), positive)

    /** Literal `x_v ≤ threshold` ([positive]) or its negation. See [boundGeLit]. */
    fun boundLeLit(v: Int, threshold: Long, positive: Boolean): Int = Lit.make(state.atomVarLe(v, threshold), positive)

    /** Whether the deadline token has fired. Read by the heuristics that run propagation *inside* one
     *  decision — see [com.eignex.klause.backtrack.selector.probeAndOrder], which spends a fixpoint per
     *  candidate value — since the engine only polls between nodes and cannot see past them. */
    internal val cancelRequested: Boolean get() = cancellation()

    internal fun extensionalDensities(variable: Int, values: LongArray, maxVisits: Int = 65_536): DoubleArray? {
        if (fixpointCancelled || state.propQueue.isNotEmpty() || state.dirtyBools.isNotEmpty() ||
            state.dirtyInts.isNotEmpty()
        ) return null
        val occurrences = projection.intOccurrences[variable]
        var remaining = maxVisits.coerceIn(0, 65_536)
        val visit = {
            state.checkFiniteWork()
            remaining-- > 0
        }
        var scores: DoubleArray? = null
        state.finiteWork.begin()
        try {
            for (fid in occurrences) {
                if (!visit()) return null
                val provider = projection.propagators[fid] as? ExtensionalSupportProvider ?: continue
                val counts = provider.supportCounts(state, fid, variable, values, visit) ?: return null
                val combined = scores ?: DoubleArray(values.size).also { scores = it }
                for (i in values.indices) combined[i] += ln(counts.supporting[i].toDouble() / counts.total)
            }
        } catch (_: FinitePropagationInterrupted) {
            probeCancelled = true
            return null
        }
        return scores
    }

    /** True once a fixpoint was cut short by the [cancellation] deadline (sticky). The engine must
     *  then abort to `BudgetCapped` rather than read the partial state as a solved leaf. */
    val fixpointCancelled: Boolean get() = state.runCancelled

    /** Set when a value probe saw the deadline fire (see [probeFixpoint] and
     *  [com.eignex.klause.backtrack.selector.probeAndOrder]). The brancher reads and clears it per node and
     *  asks the run to poll before committing: another decision would spend its own multi-second
     *  fixpoint past the deadline.
     *  Not sticky — a probe leaves no partial state behind, so a paused arm resumes on this session. */
    internal var probeCancelled: Boolean = false

    // Initial point pins can refute this root while the underlying model remains feasible.
    private var bakedUnsat: PropagationResult.Unsat? = null
    private var interruptedReseed = false

    internal val rootWork: Long
    internal val rootPropagationNanos: Long
    internal val work: Long get() = retainedState?.work ?: retiredWork
    internal val valueOrdering = ValueOrderingStatsSink()
    internal fun visitValueScore(): Boolean {
        if (cancellation()) {
            probeCancelled = true
            return false
        }
        state.work++
        return true
    }
    internal val propagationNanos: Long get() = retainedState?.propagationNanos ?: retiredPropagationNanos

    private var requiredInprocessingCancellation: Cancellation? = null

    internal fun <T> withInprocessingCancellation(token: Cancellation, operation: () -> T): T {
        val previous = requiredInprocessingCancellation
        if (previous == null) requiredInprocessingCancellation = cancellation
        return try {
            withCancellation(token, operation)
        } finally {
            requiredInprocessingCancellation = previous
        }
    }

    private fun <T> withRequiredInprocessingCancellation(operation: () -> T): T =
        usingCancellation(requiredInprocessingCancellation ?: cancellation, operation)

    internal fun <T> withCancellation(token: Cancellation, operation: () -> T): T = usingCancellation(
        if (token === cancellation || token === Cancellation.Never) cancellation else cancellation or token,
        operation,
    )

    private fun <T> usingCancellation(token: Cancellation, operation: () -> T): T {
        val previous = cancellation
        val finite = state.finiteWork.cancellation
        cancellation = token
        state.finiteWork.cancellation = cancellation
        return try {
            operation()
        } finally {
            cancellation = previous
            state.finiteWork.cancellation = finite
        }
    }

    init {
        val conflict = if (preparedState == null) {
            if (!state.seeded) state.conflictLevels ?: EmptyIntArray
            else state.runToFixpoint(allFactors = true, cancellation = cancellation)
        } else {
            check(
                state.rootFixpointStarted && !state.runCancelled &&
                    (rootConflict != null || !state.rootFixpointPending),
            )
            rootConflict
        }
        if (conflict != null) {
            bakedUnsat = PropagationResult.Unsat(
                state.extractConflictBools(conflict),
                state.extractConflictInts(conflict),
                conflict,
                state.extractConflictFactors(),
            )
        }
        rootWork = state.work
        rootPropagationNanos = state.propagationNanos
        // The bake-time fixpoint runs with logging off (it never backtracks). Undo logging starts
        // before the first push, so the level-0 mark has undoSize 0 and undoing to it rewinds every
        // search mutation back to this post-bake baseline.
        state.undoLogging = true
        levelPush(preparedMark ?: state.mark())
    }

    internal companion object {
        // Point pins belong to a disposable verification root, never a reusable search root.
        fun forPointCheck(problem: BakedProblem, point: Assumptions, cancellation: Cancellation): PropagationSession =
            PropagationSession(
                problem, cancellation, PROPAGATION_CANCEL_FLOOR, false, false, PropagationProblem(problem),
                initialAssumptions = point,
            )

        fun preparation(
            state: PropagationState,
            cancellation: Cancellation,
            conflict: IntArray?,
        ): Iterator<PropagationSession?> = sequence {
            var buffers: PropagationSessionBuffers? = null
            for (next in PropagationSessionBuffers.preparation(state.problem)) {
                if (next != null) buffers = next
                yield(null)
            }
            var mark: PropagationState.LevelMark? = null
            val marks = state.prepareMark()
            while (mark == null) {
                mark = marks.next()
                if (mark == null) yield(null)
            }
            yield(PropagationSession(
                state.problem, cancellation, state.cancelFloor, state.nativeSat, state.pbLearning,
                state.projection, state, conflict, checkNotNull(buffers), mark,
            ))
        }.iterator()

        fun prepared(state: PropagationState, cancellation: Cancellation, conflict: IntArray?): PropagationSession =
            PropagationSession(
                state.problem, cancellation, state.cancelFloor, state.nativeSat, state.pbLearning,
                state.projection, state, conflict,
            )
    }

    internal fun prepareSeed(assumptions: Assumptions): Iterator<PropagationResult?> = sequence {
        bakedUnsat?.let {
            yield(it)
            return@sequence
        }
        check(trail.size == 0 && levelTop == 1)
        seededRestrictions = assumptions.deductions
        for (i in assumptions.boolKeys.indices) {
            for (result in preparePin(true, assumptions.boolKeys[i], if (assumptions.boolValues[i]) 1L else 0L)) {
                if (result is PropagationResult.Unsat) {
                    yield(result.copy(conflictLevels = IntArray(i + 1) { it + 1 }))
                    return@sequence
                }
                yield(null)
            }
        }
        for (i in assumptions.intKeys.indices) {
            for (result in preparePin(false, assumptions.intKeys[i], assumptions.intValues[i])) {
                if (result is PropagationResult.Unsat) {
                    yield(result.copy(conflictLevels = IntArray(assumptions.numBools + i + 1) { it + 1 }))
                    return@sequence
                }
                yield(null)
            }
        }
        for (result in seedRestrictions(assumptions.deductions)) {
            if (result is PropagationResult.Unsat) {
                yield(result)
                return@sequence
            }
            yield(null)
        }
        yield(PropagationResult.Implied.EMPTY)
    }.iterator()

    private fun preparePin(boolean: Boolean, v: Int, value: Long): Sequence<PropagationResult?> = sequence {
        val alreadyPinned = if (boolean) {
            boolPinned[v] == if (value != 0L) 1 else 0
        } else {
            intPinnedSet[v] && intPinnedVal[v] == value
        }
        if (alreadyPinned) {
            yield(null)
            return@sequence
        }
        val pinned = if (boolean) state.pinBoolAsDecision(v, value != 0L) else state.setIntAsDecision(v, value)
        if (!pinned) {
            yield(revertAndUnsat(state.conflictLevels ?: EmptyIntArray))
            return@sequence
        }
        val queue = state.beginSeedFixpoint()
        while (queue.hasNext()) {
            queue.next()
            yield(null)
        }
        do {
            val conflict = state.advanceRootFixpoint(cancellation)
            if (conflict != null) {
                yield(revertAndUnsat(conflict))
                return@sequence
            }
            if (state.rootFixpointPending) yield(null)
        } while (state.rootFixpointPending)
        if (boolean) {
            boolPinned[v] = if (value != 0L) 1 else 0
            trail.add(encBool(v))
        } else {
            intPinnedSet[v] = true
            intPinnedVal[v] = value
            intPinnedLevel[v] = trail.size + 1
            trail.add(encInt(v))
        }
        var mark: PropagationState.LevelMark? = null
        val marks = state.prepareMark()
        while (mark == null) {
            mark = marks.next()
            if (mark == null) yield(null)
        }
        levelPush(mark)
        yield(null)
    }

    /** Current decision level — number of pins on the trail. 0 = no decisions (post-bake). */
    val decisionLevel: Int get() = trail.size

    /**
     * True when bake-time propagation already proved the problem Unsat. The bake fixpoint stops at
     * the first conflict, so the post-bake domains are then a *partial* propagation state — callers
     * that read domains without pinning (e.g. the LP rounding probe snapshotting an assignment)
     * must treat the session as conflicted rather than trust them.
     */
    val isUnsatAtRoot: Boolean get() = bakedUnsat != null

    /** Cumulative count of factor-forced assignments across this session — backs the
     *  `propagations` solve stat. Monotonic; the engine reads deltas around each pin. */
    val propagationCount: Long get() = retainedState?.propagations ?: retiredPropagationCount

    /** Current bool value: pinned by decision OR forced by propagation. `null` = free. */
    fun boolValue(v: Int): Boolean? = state.boolValues[v]

    /** Current int domain after propagation. Always non-empty unless the session is Unsat. */
    fun intDomain(v: Int): IntDomain = state.intDomains[v]

    private var leafColumns: LeafColumns? = null

    /**
     * Leave [columns] to leaf completion: [needsBoolDecision] and [needsIntDecision] stop offering them, and a
     * leaf reads `false` or the domain minimum for each one still free. Null decides every column again.
     */
    internal fun completeAtLeaves(columns: LeafColumns?) {
        leafColumns = columns
    }

    /** True while Boolean column [v] is free and a traversal still has to decide it. */
    internal fun needsBoolDecision(v: Int): Boolean = state.boolValues[v] == null && leafColumns?.bool(v) != true

    /** True while integer column [v] admits more than one value and a traversal still has to decide it. */
    internal fun needsIntDecision(v: Int): Boolean = !state.intDomains[v].isFixed && leafColumns?.int(v) != true

    internal val explanationState: PropagationState get() = state

    /**
     * Variables fixed at the root, however deep the search stands: bools assigned at decision level 0 and ints down
     * to one value with no deeper decision behind their domain. Visits every variable.
     */
    fun rootFixedVariableCount(): Int {
        var fixed = 0
        for (v in 0 until problem.numBoolVars) if (state.boolValues[v] != null && state.boolLevel[v] <= 0) fixed++
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max && state.intLevel[v] <= 0) fixed++
        }
        return fixed
    }

    /** Variables fixed right now: bools with a value and ints down to one. Visits every variable. */
    fun fixedVariableCount(): Int {
        var fixed = 0
        for (v in 0 until problem.numBoolVars) if (state.boolValues[v] != null) fixed++
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) fixed++
        }
        return fixed
    }

    /**
     * Domain integer column [v] was seeded with, before any decision on this trail.
     *
     * What a consumer needing an instance-wide scale or a "narrowed past the root" test reads;
     * [intDomain] answers the same question only while the trail is empty.
     */
    fun rootIntDomain(v: Int): IntDomain = state.rootDomains[v]

    /** Install (once, at the root) the incremental objective bool lower bound over [weights]; returns
     *  true while it is live. See [PropagationState.installObjectiveBoolBound]. */
    fun installObjectiveBoolBound(weights: LongArray): Boolean = state.installObjectiveBoolBound(weights)

    /** O(1) incremental bool part of the trivial objective lower bound; valid only once
     *  [installObjectiveBoolBound] has returned true. */
    fun objectiveBoolLowerBound(): Long = state.objectiveBoolLowerBound()

    /**
     * Seed with an initial assumption set. Resets any prior trail to level 0 first, then
     * pushes each assumption in iteration order (bools, ints, then domain restrictions). Each gets its own
     * decision level. Returns the cumulative implied set beyond the seed pins (or Unsat).
     * A seed refutation retains its complete applied prefix: propagated levels alone can
     * omit earlier assumptions that contributed through a reason chain.
     * Cancellation stops before the next pin and sets [fixpointCancelled]; the applied
     * prefix is incomplete and cannot certify the full assumption set.
     */
    fun seed(assumptions: Assumptions): PropagationResult {
        bakedUnsat?.let { return it }
        state.undoTo(requireNotNull(levelStates[0]))
        if (levelTop > 1) levelTruncateAfterRoot()
        clearPins()
        seededRestrictions = assumptions.deductions

        // Iterating the primitive arrays directly (vs. forEachBool / forEachInt) lets the first
        // Unsat `return` without a captured flag.
        val bk = assumptions.boolKeys
        val bv = assumptions.boolValues
        for (i in bk.indices) {
            if (state.runCancelled || cancellation()) {
                state.runCancelled = true
                return computeImplied()
            }
            val r = pushBool(bk[i], bv[i])
            if (r is PropagationResult.Unsat) return r.copy(conflictLevels = IntArray(i + 1) { it + 1 })
        }
        val ik = assumptions.intKeys
        val iv = assumptions.intValues
        for (i in ik.indices) {
            if (state.runCancelled || cancellation()) {
                state.runCancelled = true
                return computeImplied()
            }
            val r = pushInt(ik[i], iv[i])
            if (r is PropagationResult.Unsat) {
                return r.copy(conflictLevels = IntArray(bk.size + i + 1) { it + 1 })
            }
        }
        for (result in seedRestrictions(assumptions.deductions)) {
            if (result is PropagationResult.Unsat) return result
        }
        return computeImplied()
    }

    private fun seedRestrictions(restrictions: DeducedRestrictions): Sequence<PropagationResult> = sequence {
        for (i in restrictions.intMinKeys.indices) {
            if (seedCancelled()) return@sequence
            yield(pinIntAtLeast(restrictions.intMinKeys[i], restrictions.intMinValues[i]))
        }
        for (i in restrictions.intMaxKeys.indices) {
            if (seedCancelled()) return@sequence
            yield(pinIntAtMost(restrictions.intMaxKeys[i], restrictions.intMaxValues[i]))
        }
        for (i in restrictions.intHoleVarIds.indices) {
            if (seedCancelled()) return@sequence
            val variable = restrictions.intHoleVarIds[i]
            yield(pushRestriction(variable) { state.excludeIntValue(variable, restrictions.intHoleValues[i]) })
        }
        for (i in restrictions.intSetKeys.indices) {
            if (seedCancelled()) return@sequence
            val variable = restrictions.intSetKeys[i]
            yield(pushRestriction(variable) {
                state.restrictIntToSurvivors(variable, restrictions.intSetSurvivorsAt(i))
            })
        }
    }

    private fun seedCancelled(): Boolean {
        if (state.runCancelled) return true
        if (!cancellation()) return false
        state.runCancelled = true
        return true
    }

    private fun pushRestriction(variable: Int, apply: () -> Boolean): PropagationResult {
        val base = state.undoTop
        val applied = applyDecision {
            state.levelToDecisionVar.add(encInt(variable))
            state.currentLevel = state.levelToDecisionVar.size
            state.currentFactor = -1
            state.lastDecisionConflictVar = -1
            apply()
        }
        if (applied == false) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = if (applied == null) null else {
            state.runToFixpoint(allFactors = false, cancellation = cancellation)
        }
        if (conflict != null) return revertAndUnsat(conflict)
        trail.add(encInt(variable))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /**
     * Re-seed to [desired] by diffing against the live pin trail instead of clearing to root and
     * re-pushing every pin (as [seed] does). The longest bottom prefix of the trail whose pins already
     * agree with [desired] is kept — its propagation stands untouched — and only the divergent suffix is
     * popped, then the still-missing pins are pushed. When successive fragments free a small fraction of
     * the variables, the shared complement stays pinned across calls, so the work scales with the changed
     * pins rather than the total variable count.
     *
     * The outcome is identical to [seed] with the same [desired]: on return the pinned set is exactly
     * [desired], one decision level per pin. Any leftover search decisions sit above the seed prefix and,
     * differing from [desired], are popped like any other mismatch. Returns the cumulative implied set
     * beyond the pins, or the first [PropagationResult.Unsat] a push hits.
     * An interrupted push retracts to the completed prefix and sets [fixpointCancelled]; a later
     * call retries the missing pins under its current cancellation token.
     */
    fun reseedFrom(desired: Assumptions): PropagationResult {
        bakedUnsat?.let { return it }
        if (interruptedReseed) {
            state.runCancelled = false
            interruptedReseed = false
        }
        if (!seededRestrictions.isEmpty || !desired.deductions.isEmpty) {
            return seed(desired).also { if (state.runCancelled) interruptedReseed = true }
        }
        var keep = 0
        while (keep < trail.size) {
            val e = trail[keep]
            val agrees = if (trailIsBool(e)) {
                desired.boolValueOrNull(e) == (boolPinned[e] == 1)
            } else {
                val iv = e - problem.numBoolVars
                intPinnedSet[iv] && intPinnedLevel[iv] == keep + 1 && desired.intValueOrNull(iv) == intPinnedVal[iv]
            }
            if (!agrees) break
            keep++
        }
        popToLevel(keep)

        // Push every desired pin the kept prefix does not already stand — a var still pinned here is one
        // that survived the diff at its agreed value, so skipping it keeps its level intact.
        val bk = desired.boolKeys
        val bv = desired.boolValues
        for (i in bk.indices) {
            if (boolPinned[bk[i]] != -1) continue
            if (stopReseeding()) return PropagationResult.Implied.EMPTY
            val r = pushBool(bk[i], bv[i])
            if (r is PropagationResult.Unsat) return r
            if (state.runCancelled) return retractInterruptedSeed()
        }
        val ik = desired.intKeys
        val iv = desired.intValues
        for (i in ik.indices) {
            if (intPinnedSet[ik[i]]) continue
            if (stopReseeding()) return PropagationResult.Implied.EMPTY
            val r = pushInt(ik[i], iv[i])
            if (r is PropagationResult.Unsat) return r
            if (state.runCancelled) return retractInterruptedSeed()
        }
        return computeImplied()
    }

    private fun stopReseeding(): Boolean {
        if (state.runCancelled) return true
        if (!cancellation()) return false
        interruptedReseed = true
        state.runCancelled = true
        return true
    }

    private fun retractInterruptedSeed(): PropagationResult {
        // An equality can stop between its two bounds; only completed pins may survive a reseed diff.
        popLast()
        interruptedReseed = true
        return PropagationResult.Implied.EMPTY
    }

    /** Drop every decision pin, restoring the primitive pin arrays to "free". Iterates the
     *  trail (the decided vars) rather than clearing the whole arrays. */
    private fun clearPins() {
        for (i in 0 until trail.size) {
            val e = trail[i]
            if (trailIsBool(e)) {
                boolPinned[e] = -1
            } else {
                val variable = e - problem.numBoolVars
                intPinnedSet[variable] = false
                intPinnedLevel[variable] = 0
            }
        }
        trail.clear()
    }

    /** Push one bool pin at a fresh decision level. Returns newly-implied facts (diff). */
    fun pinBool(v: Int, value: Boolean): PropagationResult {
        bakedUnsat?.let { return it }
        return pushBool(v, value)
    }

    /** Push one int pin at a fresh decision level. */
    fun pinInt(v: Int, value: Long): PropagationResult {
        bakedUnsat?.let { return it }
        return pushInt(v, value)
    }

    /** [pinBool] as a probe — see [probeFixpoint]. */
    internal fun probeBool(v: Int, value: Boolean): PropagationResult? =
        probeFixpoint { pushBool(v, value, reportImplied = false) }

    /** [pinInt] as a probe — see [probeFixpoint]. */
    internal fun probeInt(v: Int, value: Long): PropagationResult? =
        probeFixpoint { pushInt(v, value, reportImplied = false) }

    /**
     * One probe pin: the fixpoint polls the deadline from its first fire (no [PROPAGATION_CANCEL_FLOOR]
     * wait, since a probe over a wide domain is exactly the runaway that floor hides), and a cut fixpoint
     * reverts the pin and yields `null`.
     *
     * The revert lands on the previous level's *complete* fixpoint, so — unlike a cut search pin — no
     * under-propagated state survives the call and [fixpointCancelled] stays clear: the session is left
     * usable and a paused arm can resume on it. [probeCancelled] carries the deadline sighting instead.
     */
    private inline fun probeFixpoint(push: () -> PropagationResult): PropagationResult? {
        bakedUnsat?.let { return it }
        if (state.runCancelled) return null
        val floor = state.cancelFloor
        state.cancelFloor = 0
        val r = try {
            push()
        } finally {
            state.cancelFloor = floor
        }
        if (!state.runCancelled) return r
        state.runCancelled = false
        probeCancelled = true
        // A cut fixpoint reports no conflict, so the probe's pin is standing on a partial state; an Unsat
        // one already reverted itself.
        if (r !is PropagationResult.Unsat) popLast()
        return null
    }

    /**
     * Decide `v ≤ hi` at a fresh decision level (a single-bound decision). Unlike [pinInt]
     * this narrows only the upper bound, so the decision contributes a single 1UIP literal
     * to any conflict it seeds — letting CDCL learn an asserting clause where an equality
     * pin (two same-level bound atoms) could not. The complementary branch is [pinIntAtLeast].
     */
    fun pinIntAtMost(v: Int, hi: Long): PropagationResult {
        bakedUnsat?.let { return it }
        val base = state.undoTop
        val pinned = applyDecision { state.setIntMaxAsDecision(v, hi) }
        if (pinned == false) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = if (pinned == null) null else {
            state.runToFixpoint(allFactors = false, cancellation = cancellation)
        }
        if (conflict != null) return revertAndUnsat(conflict)
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /** Decide `v ≥ lo` at a fresh decision level. See [pinIntAtMost]. */
    fun pinIntAtLeast(v: Int, lo: Long): PropagationResult {
        bakedUnsat?.let { return it }
        val base = state.undoTop
        val pinned = applyDecision { state.setIntMinAsDecision(v, lo) }
        if (pinned == false) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = if (pinned == null) null else {
            state.runToFixpoint(allFactors = false, cancellation = cancellation)
        }
        if (conflict != null) return revertAndUnsat(conflict)
        trail.add(encInt(v))
        levelPush(state.mark())
        return impliedSince(base)
    }

    /**
     * Register a learned [clause] and immediately propagate it. Used by the
     * BacktrackSolver after a CDB backjump to make the analyzer's 1UIP clause stick:
     * the clause stays alive for the rest of the session and participates in every
     * future propagation cycle through [BoolWatcherIndex.byLit]. Returns
     * the propagation result of asserting it — typically [PropagationResult.Implied]
     * with the UIP literal forced, or [PropagationResult.Unsat] if the assertion
     * cascades into another conflict (the engine handles that as a fresh CDB round).
     *
     * Unlike [pinBool] / [pinInt], this does *not* open a new trail level — the
     * learned clause is a constraint over existing variables, not a decision. So no
     * snapshot is pushed and no decision counter is bumped.
     */
    fun addLearnedClause(clause: Clause, lbd: Int, permanent: Boolean = false): PropagationResult {
        if (permanent) scopedLearnedAssertion = true
        return registerAndPropagate(base = state.undoTop) { state.addLearnedClause(clause, lbd, permanent) }
    }

    /** [addLearnedClause] for a clause derived from one imported from [origin], which keeps the credit. */
    internal fun addLearnedClause(clause: Clause, lbd: Int, origin: Int): PropagationResult =
        registerAndPropagate(base = state.undoTop) { state.addLearnedClause(clause, lbd, permanent = false, origin) }

    /**
     * Register a learned pseudo-Boolean constraint `Σ weightsᵢ·literalsᵢ ≥ degree` and
     * immediately propagate it, exactly as [addLearnedClause] does for a clause. Used by the engine's
     * cutting-planes backjump to make the learned PB constraint stick and force its asserting literal.
     */
    fun addLearnedPb(
        weights: LongArray,
        literals: IntArray,
        degree: Long,
        lbd: Int,
        permanent: Boolean = false,
    ): PropagationResult {
        if (permanent) scopedLearnedAssertion = true
        return registerAndPropagate(base = state.undoTop) {
            state.addLearnedPb(weights, literals, degree, lbd, permanent)
        }
    }

    /** Enable bounded mandatory integer-row learning for subsequent finite conflicts. */
    fun enableIntegerRowLearning() {
        check(!state.incremental)
        state.integerRowLearning = true
        state.integerLearningTrailAtLevel = { level ->
            if (level in 0 until levelTop) levelStates[level]?.undoSize else null
        }
    }

    internal fun addLearnedIntegerRow(row: IntegerConflictRow, lbd: Int): PropagationResult =
        registerAndPropagate(base = state.undoTop) { state.addLearnedIntegerRow(row, lbd) }

    internal fun addLearnedIntegerRow(learned: ConflictAnalyzer.AnalysisResult.LearnedIntegerRow): PropagationResult {
        val publicationBase = state.undoTop
        val result = addLearnedIntegerRow(learned.row, learned.lbd)
        if (result is PropagationResult.Unsat || fixpointCancelled) return result
        val fallback = learned.fallbackClause
        return registerAndPropagate(base = publicationBase) {
            state.addLearnedClause(Clause(fallback.literals), fallback.lbd)
        }
    }

    internal fun learnedConstraintSatisfied(learned: ConflictAnalyzer.AnalysisResult.LearnedConstraint): Boolean =
        when (learned) {
            is ConflictAnalyzer.AnalysisResult.Learned -> learned.literals.any { litTruth(it) == true }
            is ConflictAnalyzer.AnalysisResult.LearnedPb -> {
                var trueWeight = 0L
                for (index in learned.literals.indices) {
                    if (litTruth(learned.literals[index]) == true) trueWeight += learned.weights[index]
                }
                trueWeight >= learned.degree
            }
            is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow -> try {
                (learned.row.guard != Lit.NONE && state.litTruth(learned.row.guard) == false) ||
                    learned.row.maximum(state) <= learned.row.bound
            } catch (_: CheckedLongOverflowException) {
                false
            } catch (_: FinitePropagationInterrupted) {
                state.runCancelled = true
                false
            }
        }

    /** Shared body of [addLearnedClause] / [addLearnedPb]: register the constraint via [register], fire it
     *  once, and either surface the cascaded conflict or re-baseline the level with the asserted facts. */
    private inline fun registerAndPropagate(base: Int, register: () -> Int): PropagationResult {
        bakedUnsat?.let { return it }
        val newFid = register()
        state.factorAttribution?.let {
            if (state.nativeEngine == null) it.admit(state.factorAt(newFid))
            else if (it.admissionFamilies != 0) it.note(it.admissionFamilies, FactorAttribution.Metric.Learned)
        }
        val conflict = state.runToFixpoint(allFactors = false, initialFactor = newFid, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        // The asserted facts are implied by the decisions up to the current level, so they join the
        // level's baseline: re-snapshot the top mark. Otherwise the next failed pin's revert — which
        // restores to the top mark — silently rewinds them while the constraint stays registered, and the
        // search can re-derive the same conflict forever.
        if (state.runCancelled) return PropagationResult.Implied.EMPTY
        levelStates[levelTop - 1] = state.mark()
        return impliedSince(base)
    }

    /**
     * Assert a permanent objective bound on an integer variable as a propagating unit
     * clause: `[v <= hi]` ([atMost]) or `[v >= lo]` (`atMost = false`). Branch-and-bound
     * calls this on each new incumbent so the bound is pushed into the objective variable
     * and the constraint defining it (linear, product, element, a global's output — any
     * structure) propagates the tightening back into the decision variables. This is
     * objective propagation, strictly stronger than a passive lower-bound
     * check, and it gives objective pruning even for non-linear-defined objectives that the
     * predicate path cannot bound. Permanent: survives forgetting and persists for the rest
     * of the session; later incumbents assert progressively tighter (subsuming) units.
     * Returns the propagation result, [PropagationResult.Unsat] if the bound is already
     * infeasible at the root (the remaining objective space is empty).
     */
    fun assertObjectiveBound(v: Int, bound: Long, atMost: Boolean): PropagationResult {
        val atom = if (atMost) state.atomVarLe(v, bound) else state.atomVarGe(v, bound)
        return addLearnedClause(Clause(intArrayOf(Lit.make(atom, true))), lbd = 1, permanent = true)
    }

    /**
     * Apply a domain reduction inferred at the **current** decision level — for LP reduced-cost
     * fixing. Like [addLearnedClause] and unlike the `pin*` decisions, this opens **no** new
     * level: the tightening is folded into the current level's baseline (re-snapshotted mark), so
     * [popLast] of that level undoes it. That is exactly right for a subtree-local inference, which
     * is valid only under this node's bounds and the current incumbent and must vanish on backtrack.
     *
     * The reduction is recorded with no antecedents, so conflict analysis treats its bound atom as a
     * level leaf (like a decision). That is sound: a conflict it triggers is a real infeasibility of
     * the problem under the path bounds plus this tightening, so any learned nogood is globally
     * valid; the reduction itself never escapes its level.
     */
    fun implyIntAtMost(v: Int, hi: Long): PropagationResult = implyAtCurrentLevel { state.tightenIntMax(v, hi) }

    /** Lower-bound reduced-cost reduction at the current level. See [implyIntAtMost]. */
    fun implyIntAtLeast(v: Int, lo: Long): PropagationResult = implyAtCurrentLevel { state.tightenIntMin(v, lo) }

    /** Boolean reduced-cost fixing at the current level. See [implyIntAtMost]. */
    fun implyBool(v: Int, value: Boolean): PropagationResult = implyAtCurrentLevel { state.pinBool(v, value) }

    /**
     * Like [implyIntAtLeast]/[implyIntAtMost], but records [reason] as the antecedent of the new bound
     * atom so conflict analysis can resolve *through* the tightening instead of treating it as a leaf.
     * [reason] is a set of currently-false literals — the negated seated bounds whose
     * conjunction (with the always-valid constraints) implies the bound — so the implicit reason clause
     * `(new bound atom) ∨ reason` is globally valid. The antecedent is journaled and undone on
     * backtrack like any other; the tightening itself still folds into the current level.
     */
    fun implyIntAtLeastWithReason(v: Int, lo: Long, reason: IntArray): PropagationResult =
        implyAtCurrentLevel { state.tightenIntMin(v, lo, reason) }

    /** Upper-bound counterpart of [implyIntAtLeastWithReason]. */
    fun implyIntAtMostWithReason(v: Int, hi: Long, reason: IntArray): PropagationResult =
        implyAtCurrentLevel { state.tightenIntMax(v, hi, reason) }

    private fun implyAtCurrentLevel(apply: () -> Boolean): PropagationResult {
        if (decisionLevel == 0) scopedLearnedAssertion = true
        bakedUnsat?.let { return it }
        val base = state.undoTop
        state.currentFactor = -1
        state.currentLevel = decisionLevel
        if (!apply()) {
            // No CP factor ran, so its last fire cannot supply this external deduction's conflict clause.
            state.lastDecisionConflictVar = -1
            return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        }
        val conflict = state.runToFixpoint(allFactors = false, cancellation = cancellation)
        if (conflict != null) return revertAndUnsat(conflict)
        // Fold into the current level's baseline so a later same-level revert keeps it, and so
        // popLast of this level — and only this level — discards it. Mirrors addLearnedClause.
        if (state.runCancelled) return PropagationResult.Implied.EMPTY
        levelStates[levelTop - 1] = state.mark()
        return impliedSince(base)
    }

    /** Request a bounded learned-store compaction, preserving permanent, live and historical reasons.
     * Cancelled or unfunded preparation leaves the generation intact. */
    fun forgetLearnedClauses(keep: (learnedIndex: Int, lbd: Int) -> Boolean) {
        val before = learnedClauseCount
        state.forgetLearnedClauses(cancelled = { cancelRequested }, keep = keep)
        if (learnedClauseCount != before) learnedClauseRevision++
    }

    /** Unified truth of a bool or atom literal — null when undetermined. */
    fun litTruth(lit: Int): Boolean? = state.litTruth(lit)

    /**
     * The clause that forced the current pin of Boolean variable [v], as `pinned ∨ antecedents`, or
     * null when the pin was a decision or came from a factor that records no antecedents.
     *
     * This is the same antecedent set native first-UIP analysis walks, exposed so a shared analyzer
     * can resolve through a CP-implied literal instead of stopping at it.
     */
    fun boolReasonClause(v: Int): IntArray? {
        val antecedents = state.reasonOf(state.boolAntecedents[v]) ?: return null
        val assigned = state.boolValues[v] ?: return null
        val reason = IntArray(antecedents.size + 1)
        reason[0] = Lit.make(v, assigned)
        antecedents.copyInto(reason, destinationOffset = 1)
        return reason
    }

    /** Run 1UIP conflict analysis from an externally supplied all-false conflict clause (e.g. an LP
     *  Farkas certificate) to obtain a learned clause and backjump level. See
     *  [ConflictAnalyzer.analyzeConflictClause]. */
    internal fun analyzeConflictClause(conflictClause: IntArray): ConflictAnalyzer.AnalysisResult =
        state.conflictAnalyzer.analyzeConflictClause(conflictClause)

    /** True when this session runs the native-SAT lane; its learned clauses live in the arena store,
     *  not the general database, so clause-database introspection routes differently. */
    val usesNativeSat: Boolean get() = state.nativeEngine != null

    private var sharingAccountingWork = 0L
    internal val ownershipWork: Long get() = work - sharingAccountingWork
    internal fun chargeSharingWork(count: Long) {
        state.work += count
        sharingAccountingWork += count
    }

    internal val sharingIdentity = Any()

    internal var sharedLearningMemory: ((SharingMemoryObservation) -> Unit)? = null
    internal var sharedLearningPosition: (() -> Any)? = null
    internal var retainedSharingMemory: ((SharingMemoryObservation) -> Unit)? = null
    internal var retainedSharingSize: (() -> Long)? = null
    internal var retainedInprocessingMemory: ((SharingMemoryObservation) -> Unit)? = null

    internal fun reclaimLearnedMemory(
        softLimitBytes: Long,
        maxWork: Long = 65_536L,
        maxScratchBytes: Long = 1_048_576L,
        releaseBuffers: () -> Boolean = { false },
        publicationPending: Boolean = false,
    ): LearnedReclamation {
        require(softLimitBytes >= 0L && maxWork >= 0L && maxScratchBytes >= 0L)
        val start = work
        val scratchEntries = minOf(maxScratchBytes / 64L, Int.MAX_VALUE.toLong()).toInt()
        fun sample() = sharingMemory((maxWork - (work - start)).coerceAtLeast(0L), scratchEntries)
        fun bytes(values: Map<String, Long>) = values.getValue("ownedPrimitiveBytes") +
            values.getValue("ownedReferenceSlots") * Long.SIZE_BYTES
        val before = sample().completeValues ?: return LearnedReclamation(
            if (cancelRequested) ReclamationStatus.CANCELLED else ReclamationStatus.DEFERRED, work = work - start)
        val beforeBytes = bytes(before)
        val floor = before.getValue("protectedPrimitiveBytes") +
            before.getValue("protectedReferenceSlots") * Long.SIZE_BYTES
        if (beforeBytes <= softLimitBytes) return LearnedReclamation(ReclamationStatus.WITHIN_LIMIT,
            beforeBytes, beforeBytes, floor, work - start)
        if (fixpointCancelled || inprocessingRootPending || publicationPending) return LearnedReclamation(
            ReclamationStatus.DEFERRED, beforeBytes, beforeBytes, floor, work - start,
            protectedLimitExceeded = floor > softLimitBytes)
        releaseBuffers()
        val count = learnedClauseCount
        val published = state.forgetLearnedClauses(
            (maxWork - (work - start)).coerceAtLeast(0L), maxScratchBytes, { cancelRequested }, { _, _ -> false })
        if (learnedClauseCount != count) learnedClauseRevision++
        if (!published) return LearnedReclamation(
            if (cancelRequested) ReclamationStatus.CANCELLED else ReclamationStatus.DEFERRED,
            beforeBytes, protectedBytes = floor, work = work - start)
        val after = sample().completeValues
        val afterBytes = after?.let(::bytes)
        val afterFloor = after?.let {
            it.getValue("protectedPrimitiveBytes") + it.getValue("protectedReferenceSlots") * Long.SIZE_BYTES
        }
        return LearnedReclamation(
            when {
                learnedClauseCount < count || (afterBytes != null && afterBytes < beforeBytes) ->
                    ReclamationStatus.RECLAIMED
                afterFloor != null && afterFloor > softLimitBytes -> ReclamationStatus.PROTECTED_FLOOR
                else -> ReclamationStatus.DEFERRED
            }, beforeBytes, afterBytes, afterFloor ?: floor, work - start,
            protectedLimitExceeded = (afterFloor ?: floor) > softLimitBytes)
    }

    internal fun sharingMemory(
        maxWork: Long = SharingMemoryObservation.DEFAULT_WORK,
        maxScratchEntries: Int = SharingMemoryObservation.DEFAULT_SCRATCH_ENTRIES,
    ): SharingMemorySnapshot {
        if (retainedState == null) return SharingMemorySnapshot(
            com.eignex.klause.util.SharingMemoryStatus.STALE, emptyMap(), 0L, 0)
        val sample = SharingMemoryObservation(
            maxWork, maxScratchEntries, { cancelRequested }, { chargeSharingWork(1L) },
        )
        return sample.snapshot {
            state.sharingMemory(sample)
            sharedLearningMemory?.invoke(sample)
            retainedSharingMemory?.invoke(sample)
            retainedInprocessingMemory?.invoke(sample)
            simplifiedRootSources?.let { sample.ownedReferences(it, it.size.toLong(), true) }
            pendingRootSourceRetirement?.let { retirement ->
                sample.ownedReferences(retirement, 1L, true)
                val literals = retirement.literals
                val original = (problem.factors[retirement.factor] as? Clause)?.literals
                sample.owned(literals, literals.size.toLong() * Int.SIZE_BYTES, true, shared = literals === original)
            }
            sample.record(mapOf(
                "sharedLearningObserved" to if (sharedLearningMemory == null) 0L else 1L,
                "rootRetiredSourceReferenceSlots" to (simplifiedRootSources?.size?.toLong() ?: 0L),
                "rootPendingSourceReferenceSlots" to if (pendingRootSourceRetirement == null) 0L else 1L,
            ))
        }
    }

    internal fun learnedLifecycle(): Map<String, Long> = retainedState?.let {
        it.learned.lifecycle.snapshot() + (it.factorAttribution?.snapshot() ?: emptyMap()) +
            (it.propagationSchedule?.snapshot() ?: emptyMap())
    } ?: retiredLifecycle

    internal fun configureAttribution(
        enabled: Boolean,
        historicalTables: Boolean,
        costAware: Boolean = false,
        reasonPreflight: Boolean = false,
    ) {
        state.factorAttribution = if (enabled) state.factorAttribution ?: FactorAttribution() else null
        state.historicalTableReasons = historicalTables
        state.propagationSchedule = if (costAware) state.propagationSchedule ?: PropagationSchedule() else null
        state.reasonPreflight = reasonPreflight
    }

    internal inline fun <T> withAttribution(families: Int, action: () -> T): T {
        val attribution = explanationState.factorAttribution
        val previous = attribution?.admissionFamilies ?: 0
        attribution?.admissionFamilies = families
        try {
            return action()
        } finally {
            attribution?.admissionFamilies = previous
        }
    }

    /** Current learned-constraint count, including PB constraints and integer rows. Used to invoke
     *  [forgetLearnedClauses] based on `BacktrackParams.maxLearnedClauses`. */
    val learnedClauseCount: Int get() = retainedState?.let { it.nativeEngine?.count ?: it.learnedClauses.size } ?: 0

    /** LBD of the learned clause at [learnedIndex]. */
    fun learnedClauseLbd(learnedIndex: Int): Int = state.learnedClauseLbd(learnedIndex)

    /** True iff learned clause [learnedIndex] survives every forgetting pass. */
    fun learnedClausePermanent(learnedIndex: Int): Boolean = state.learnedClausePermanent(learnedIndex)

    /** The arm learned clause [learnedIndex] was imported from, or [OWN_ORIGIN] for one learned here. */
    internal fun learnedClauseOrigin(learnedIndex: Int): Int = state.learnedClauseOrigin(learnedIndex)

    /** The learned clause at [learnedIndex]. Read by the engine's vivification pass and the
     *  glue-clause export, both clause-only. Fails loudly on a non-clause learned constraint rather than
     *  through a bare cast, so a pseudo-Boolean nogood reaching a clause-only pass names the site that
     *  needs a clause filter instead of surfacing an opaque ClassCastException. */
    internal fun learnedClauseAt(learnedIndex: Int): ClausePropagator =
        state.learnedClauses[learnedIndex] as? ClausePropagator
            ?: error(
                "learned constraint at $learnedIndex is not a clause; vivification and glue export are clause-only",
            )

    /** Literals of learned clause [learnedIndex]. Routes to the native-SAT store when active (its
     *  clauses aren't in the general database), else through [learnedClauseAt]. */
    internal fun learnedClauseLiterals(learnedIndex: Int): IntArray =
        state.nativeEngine?.literalsOf(learnedIndex) ?: learnedClauseAt(learnedIndex).literals

    /** True iff the learned constraint at [learnedIndex] is a clause (not a pseudo-Boolean constraint) —
     *  the clause-only passes (vivification, glue export) skip the rest. Native-lane entries are all
     *  clauses. */
    internal fun isLearnedClause(learnedIndex: Int): Boolean =
        state.nativeEngine != null || state.learnedClauses[learnedIndex] is ClausePropagator

    /** Three-tier DB tier of learned clause [learnedIndex]. */
    internal fun learnedClauseTier(learnedIndex: Int): ClauseTier = state.learnedClauseTier(learnedIndex)

    /** Set the three-tier DB tier of learned clause [learnedIndex]. */
    internal fun setLearnedClauseTier(learnedIndex: Int, tier: ClauseTier) =
        state.setLearnedClauseTier(learnedIndex, tier)

    /** True iff learned clause [learnedIndex] was used (conflict or unit) since the last reduction. */
    fun learnedClauseUsedSinceReduction(learnedIndex: Int): Boolean =
        state.learnedClauseUsedSinceReduction(learnedIndex)

    /** Clear the reuse flag of learned clause [learnedIndex] (called for survivors after a reduction). */
    fun clearLearnedClauseUsed(learnedIndex: Int) = state.clearLearnedClauseUsed(learnedIndex)

    /**
     * Export this session's **glue** learned clauses — those with LBD ≤ [maxLbd] and length ≤
     * [maxLen] — in session-portable [SharedClause] form, for cross-arm sharing. Int-bound atom
     * literals are decoded to `(intVar, kind, threshold, sign)` via the atom registry; boolean
     * literals travel as-is. Cheap: the glue set is small by construction. (See [ClauseExchange].)
     */
    fun exportGlueClauses(maxLbd: Int, maxLen: Int, skipPermanent: Boolean = false): List<SharedClause> =
        checkNotNull(exportGlueClausesBounded(maxLbd, maxLen, skipPermanent) { false })

    internal fun exportGlueClausesBounded(
        maxLbd: Int,
        maxLen: Int,
        skipPermanent: Boolean,
        alreadyShared: (Long) -> Boolean? = { false },
        cancelled: () -> Boolean,
    ): List<SharedClause>? {
        val out = ArrayList<SharedClause>()
        var tokens = LongArray(0)
        for (i in 0 until learnedClauseCount) {
            if (cancelled()) return null
            // Permanent clauses are the search-conditioned assertions — the incumbent objective bound
            // (assertObjectiveBound) and blocking nogoods — not globally-valid resolvents. A caller
            // learning under assumptions/incumbent (LNS repair) must skip them or it poisons peers.
            if (skipPermanent && learnedClausePermanent(i)) continue
            if (!isLearnedClause(i)) continue // pseudo-Boolean nogoods aren't clause-portable
            // Another arm's clause, or one inprocessing derived from it, is that arm's to publish.
            if (learnedClauseOrigin(i) != OWN_ORIGIN) continue
            val lbd = learnedClauseLbd(i)
            if (lbd > maxLbd) continue
            if (learnedClauseLiteralCount(i) > maxLen) continue
            val native = state.nativeEngine
            val literals = if (native == null) learnedClauseAt(i).literals else null
            val length = learnedClauseLiteralCount(i)
            if (tokens.size < length) tokens = LongArray(length)
            for (offset in 0 until length) {
                val lit = native?.literalOf(i, offset) ?: checkNotNull(literals)[offset]
                val variable = Lit.variable(lit)
                tokens[offset] = if (variable < problem.numBoolVars) lit.toLong() else {
                    val atom = variable - problem.numBoolVars
                    SharedClause.atomToken(state.atoms.intVar[atom].toLong(), state.atoms.kind[atom].ordinal.toLong(),
                        state.atoms.threshold[atom], if (Lit.isPositive(lit)) 0L else 1L)
                }
            }
            val key = SharedClause.contentKey(tokens, length)
            when (alreadyShared(key)) {
                true -> continue
                null -> return null
                false -> Unit
            }
            out.add(asSharedClause(literals ?: checkNotNull(native).literalsOf(i), lbd, key))
        }
        return out.takeUnless { cancelled() }
    }

    /**
     * One clause's [lits] (this session's literals) in session-portable [SharedClause] form: boolean
     * literals travel as-is, int-bound atom literals decode to `(intVar, kind, threshold, sign)` via the
     * atom registry. The decode shared by [exportGlueClauses] and the direct publish of globally-valid
     * nogoods (LP Farkas certificates), which bypass the LBD glue filter.
     */
    internal fun asSharedClause(lits: IntArray, lbd: Int): SharedClause = asSharedClause(lits, lbd, null)

    private fun asSharedClause(lits: IntArray, lbd: Int, key: Long?): SharedClause {
        val numBool = problem.numBoolVars
        var boolCount = 0
        for (lit in lits) if (Lit.variable(lit) < numBool) boolCount++
        val bools = if (boolCount == 0) EmptyIntArray else IntArray(boolCount)
        val quads = if (boolCount == lits.size) EmptyLongArray else LongArray((lits.size - boolCount) * SharedClause.QUAD)
        var bi = 0
        var qi = 0
        for (lit in lits) {
            val v = Lit.variable(lit)
            if (v < numBool) {
                bools[bi++] = lit
            } else {
                val atomId = v - numBool
                quads[qi++] = state.atoms.intVar[atomId].toLong()
                quads[qi++] = state.atoms.kind[atomId].ordinal.toLong()
                quads[qi++] = state.atoms.threshold[atomId]
                quads[qi++] = if (Lit.isPositive(lit)) 0L else 1L
            }
        }
        return if (key == null) SharedClause(bools, quads, lbd) else SharedClause(bools, quads, lbd, key)
    }

    /**
     * Register an imported [shared] nogood (learned by another arm of the same problem) into this
     * session's learned DB, translating its int-atom literals into this session's lazily-allocated
     * atom space. Call at a restart or the post-assumption root of a compatible search scope.
     * Attachment evaluates existing root facts, including an immediate unit or conflict, at the
     * next fixpoint. Native SAT attachment can pin the unit during registration.
     *
     * [origin] names the arm the clause came from, a non-negative id, and is counted each time the clause is used;
     * see [drainImportUses].
     */
    fun importClause(shared: SharedClause, origin: Int = OWN_ORIGIN) {
        state.work += shared.boolLits.size.toLong() + shared.atomQuads.size
        val lits = IntArray(shared.boolLits.size + shared.atomQuads.size / SharedClause.QUAD)
        var j = 0
        for (l in shared.boolLits) lits[j++] = l
        var i = 0
        while (i < shared.atomQuads.size) {
            val intVar = shared.atomQuads[i].toInt()
            val kind = shared.atomQuads[i + 1].toInt()
            val threshold = shared.atomQuads[i + 2]
            val positive = shared.atomQuads[i + 3] == 0L
            val virtualVar = when (kind) {
                0 -> state.atomVarGe(intVar, threshold)
                1 -> state.atomVarLe(intVar, threshold)
                else -> state.atomVarEq(intVar, threshold)
            }
            lits[j++] = Lit.make(virtualVar, positive)
            i += SharedClause.QUAD
        }
        val fid = state.addLearnedClause(Clause(lits), shared.lbd, permanent = false, origin = origin)
        if (state.nativeEngine == null) state.learned.pendingAttach.add(fid)
    }

    /**
     * Hand [action] how often clauses imported from each origin were used since the last drain, and reset the
     * counts: a clause counts once per reduction window in which it detects a conflict or forces a unit.
     */
    fun drainImportUses(action: (origin: Int, uses: Long) -> Unit) {
        state.learned.importUses.drain(action)
        state.nativeEngine?.importUses?.drain(action)
    }

    private fun pushBool(v: Int, value: Boolean, reportImplied: Boolean = true): PropagationResult {
        val want = if (value) 1 else 0
        if (boolPinned[v] == want) return PropagationResult.Implied.EMPTY
        val base = state.undoTop
        val pinned = applyDecision { state.pinBoolAsDecision(v, value) }
        if (pinned == false) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = if (pinned == null) null else {
            state.runToFixpoint(allFactors = false, cancellation = cancellation)
        }
        if (conflict != null) return revertAndUnsat(conflict)
        boolPinned[v] = want
        trail.add(encBool(v))
        levelPush(state.mark())
        return if (reportImplied) impliedSince(base) else PropagationResult.Implied.EMPTY
    }

    private fun pushInt(v: Int, value: Long, reportImplied: Boolean = true): PropagationResult {
        if (intPinnedSet[v] && intPinnedVal[v] == value) return PropagationResult.Implied.EMPTY
        val base = state.undoTop
        val pinned = applyDecision { state.setIntAsDecision(v, value) }
        if (pinned == false) return revertAndUnsat(state.conflictLevels ?: EmptyIntArray)
        val conflict = if (pinned == null) null else {
            state.runToFixpoint(allFactors = false, cancellation = cancellation)
        }
        if (conflict != null) return revertAndUnsat(conflict)
        intPinnedSet[v] = true
        intPinnedVal[v] = value
        intPinnedLevel[v] = trail.size + 1
        trail.add(encInt(v))
        levelPush(state.mark())
        return if (reportImplied) impliedSince(base) else PropagationResult.Implied.EMPTY
    }

    private fun applyDecision(operation: () -> Boolean): Boolean? {
        state.finiteWork.cancellation = cancellation
        state.finiteMutationInterruptible = true
        return try {
            operation()
        } catch (_: FinitePropagationInterrupted) {
            state.runCancelled = true
            null
        } finally {
            state.finiteMutationInterruptible = false
        }
    }

    /**
     * Build the Unsat result from [levels] (which references the *failed-push* level
     * encoding still on the state), then restore the pre-push snapshot. Extraction must
     * happen before restore, since restore wipes the level-to-var mapping.
     */
    private fun revertAndUnsat(levels: IntArray): PropagationResult.Unsat {
        // Must extract factors *before* restoring — restore wipes the seed + reason arrays.
        val factors = state.extractConflictFactors()
        // Run 1UIP analysis BEFORE restore — the analyzer walks `state.boolAntecedents` /
        // `state.boolPinOrder` / `state.boolLevel`, all of which restore would rewind.
        // Only applicable when a factor's `propagate` triggered the conflict (so
        // `currentFactor >= 0`); seed-assumption conflicts don't have a clause-form
        // antecedent to seed analysis with.
        val learned: ConflictAnalyzer.AnalysisResult? = run {
            val nativeReason = state.nativeConflictReason
            val failingFid = state.currentFactor
            when {
                // Native lane: the conflicting clause's literals are stashed directly (its factor id
                // does not index the general learned store), so seed 1UIP from them.
                nativeReason != null -> state.conflictAnalyzer.analyzeConflictClause(nativeReason)

                failingFid >= 0 -> state.conflictAnalyzer.analyze(failingFid)

                state.lastDecisionConflictVar >= 0 ->
                    state.conflictAnalyzer.analyzeDecisionConflict(state.lastDecisionConflictVar)

                else -> null
            }
        }
        // Conflict-reason variable sets for activity heuristics (VSIDS / dom-wdeg). When
        // 1UIP analysis produced a clause, prefer the canonical CDCL bump set — every
        // variable seen while walking the implication graph — over the coarse "decision
        // vars at the conflict levels" extraction. Sharper conflict focus ⇒ fewer conflicts.
        val bools: IntArray
        val ints: IntArray
        if (learned is ConflictAnalyzer.AnalysisResult.LearnedConstraint) {
            bools = state.conflictAnalyzer.lastBumpBoolVars().toIntArray()
            ints = state.conflictAnalyzer.lastBumpIntVars().toIntArray()
        } else {
            bools = state.extractConflictBools(levels)
            ints = state.extractConflictInts(levels)
        }
        state.undoTo(levelLast())
        return PropagationResult.Unsat(bools, ints, levels, factors, learned)
    }

    /** Pop the most-recently-pushed pin. No-op if the trail is empty. */
    fun popLast() {
        if (trail.isEmpty()) return
        popToLevel(trail.size - 1)
    }

    /**
     * Pop until [decisionLevel] equals [level]. The restore replays the undo log in
     * O(changes since that level's mark). Used by DFS samplers to backjump.
     */
    fun popToLevel(level: Int) {
        require(level in 0..trail.size) {
            "popToLevel($level): out of range [0, ${trail.size}]"
        }
        while (trail.size > level) {
            val e = trail[trail.size - 1]
            trail.removeAt(trail.size - 1)
            if (trailIsBool(e)) {
                boolPinned[e] = -1
            } else {
                val variable = e - problem.numBoolVars
                if (intPinnedLevel[variable] > level) {
                    intPinnedSet[variable] = false
                    intPinnedLevel[variable] = 0
                }
            }
            levelPop()
        }
        state.undoTo(levelLast())
    }

    /** Pop until `v` of [kind] is no longer pinned. No-op if `v` is already unpinned. */
    fun popUntilUnpinned(kind: VarKind, v: Int) {
        val pinned = when (kind) {
            VarKind.Bool -> boolPinned[v] != -1
            VarKind.Int -> intPinnedSet[v]
        }
        if (!pinned) return
        val target = if (kind == VarKind.Bool) encBool(v) else encInt(v)
        while (trail.size > 0) {
            val top = trail[trail.size - 1]
            popLast()
            if (top == target && (kind == VarKind.Bool || !intPinnedSet[v])) break
        }
    }

    /** Snapshot the current pin set as an [Assumptions]. Maps are fresh copies, in trail
     *  (decision) order. */
    fun currentAssumptions(): Assumptions {
        val bools = LinkedHashMap<Int, Boolean>()
        val ints = LinkedHashMap<Int, Long>()
        for (i in 0 until trail.size) {
            val e = trail[i]
            if (trailIsBool(e)) {
                bools[e] = boolPinned[e] == 1
            } else {
                val iv = e - problem.numBoolVars
                if (intPinnedSet[iv] && intPinnedLevel[iv] == i + 1) ints[iv] = intPinnedVal[iv]
            }
        }
        return Assumptions(bools = bools, ints = ints)
    }

    /** The (kind, var) decision at [level] (1-based), or `null` if [level] is out of range. */
    fun decisionAt(level: Int): Pair<VarKind, Int>? {
        if (level !in 1..trail.size) return null
        val e = trail[level - 1]
        return if (trailIsBool(e)) VarKind.Bool to e else VarKind.Int to (e - problem.numBoolVars)
    }

    /**
     * Build a fresh [PropagationResult.Implied] from the propagation state, excluding
     * already-pinned vars. Iterates the var spaces in ascending id order so the resulting
     * primitive arrays are naturally sorted — no separate sort step required. Used only for
     * [seed]'s full-implied return (the hot push path uses the incremental [impliedSince]).
     */
    private fun computeImplied(): PropagationResult.Implied {
        val bKeys = IntArrayList(initialCapacity = 8)
        val bVals = ArrayList<Boolean>()
        for (v in 0 until problem.numBoolVars) {
            val b = state.boolValues[v] ?: continue
            if (boolPinned[v] != -1) continue
            bKeys.add(v)
            bVals.add(b)
        }
        val iKeys = IntArrayList(initialCapacity = 8)
        val iVals = LongArrayList(initialCapacity = 8)
        for (v in 0 until problem.numIntVars) {
            val d = state.intDomains[v]
            if (d.min == d.max) {
                if (intPinnedSet[v]) continue
                iKeys.add(v)
                iVals.add(d.min)
            }
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toLongArray(),
        )
    }

    /**
     * Newly-implied facts of a push (its "diff"): the variables mutated since undo position
     * [base] that are now *determined* (bool assigned / int domain singleton) and aren't
     * themselves decision pins. Read straight off the state's undo log in O(changes) rather
     * than scanning every variable. Keys come out sorted ascending, as
     * [PropagationResult.Implied]'s binary-search lookups require.
     */
    private fun impliedSince(base: Int): PropagationResult.Implied {
        if (state.runCancelled) return PropagationResult.Implied.EMPTY
        val top = state.undoTop
        if (top <= base) return PropagationResult.Implied.EMPTY
        val bRaw = IntArrayList()
        val iRaw = IntArrayList()
        for (i in base until top) {
            val v = state.undoVarAt(i)
            if (state.undoIsBoolAt(i)) {
                bRaw.add(v)
            } else if (!intPinnedSet[v] && impliedIntUndo[v] < 0 && state.undo.domain[i] != null) {
                // Interior carves preserve endpoints; the first full snapshot precedes every endpoint move.
                impliedIntUndo[v] = i
                iRaw.add(v)
            }
        }
        val bKeys = IntArrayList()
        val bVals = ArrayList<Boolean>()
        if (bRaw.size > 0) {
            val sorted = bRaw.toIntArray()
            sorted.sort()
            var prev = -1
            for (v in sorted) {
                if (v == prev) continue
                prev = v
                if (boolPinned[v] != -1) continue // decision var — excluded from implied
                val b = state.boolValues[v] ?: continue // must be determined
                bKeys.add(v)
                bVals.add(b)
            }
        }
        val iKeys = IntArrayList()
        val iVals = LongArrayList()
        var minKeys = EmptyIntArray
        var minValues = EmptyLongArray
        var maxKeys = EmptyIntArray
        var maxValues = EmptyLongArray
        if (iRaw.size > 0) {
            val sorted = iRaw.toIntArray()
            sorted.sort()
            val mins = IntArrayList()
            val minVals = LongArrayList()
            val maxs = IntArrayList()
            val maxVals = LongArrayList()
            for (v in sorted) {
                val prior = requireNotNull(state.undo.domain[impliedIntUndo[v]])
                impliedIntUndo[v] = -1
                val d = state.intDomains[v]
                if (d.min == d.max) {
                    iKeys.add(v)
                    iVals.add(d.min)
                } else {
                    if (d.min > prior.min) {
                        mins.add(v)
                        minVals.add(d.min)
                    }
                    if (d.max < prior.max) {
                        maxs.add(v)
                        maxVals.add(d.max)
                    }
                }
            }
            minKeys = mins.toIntArray()
            minValues = minVals.toLongArray()
            maxKeys = maxs.toIntArray()
            maxValues = maxVals.toLongArray()
        }
        return PropagationResult.Implied(
            boolKeys = bKeys.toIntArray(),
            boolValues = BooleanArray(bVals.size) { bVals[it] },
            intKeys = iKeys.toIntArray(),
            intValues = iVals.toLongArray(),
            intMinKeys = minKeys,
            intMinValues = minValues,
            intMaxKeys = maxKeys,
            intMaxValues = maxValues,
        )
    }
}
