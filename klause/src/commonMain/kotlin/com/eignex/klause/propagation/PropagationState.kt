package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.monotonicNanos
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.Bits
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.MutableLongIntMap
import kotlin.time.TimeSource

internal const val NO_CARVE: Long = Long.MIN_VALUE

// Lit ids are non-negative, so -1 is a safe sentinel; the watcher always fires.
internal const val NO_BLOCKER: Int = -1

// Power of two minus one so the poll gate is a single `and`.
private const val CANCEL_POLL_MASK: Int = 1023

// Fires a runToFixpoint must exceed before its cancellation poll engages, so only a runaway fixpoint (an O(span)
// bound crawl) pays for it. A multiple of CANCEL_POLL_MASK + 1 so the aligned poll lands on it. A floor counts
// fires while a deadline is spent in work, so atom-allocating fires can overrun it; BacktrackParams has a per-run
// override.
internal const val PROPAGATION_CANCEL_FLOOR: Int = 1 shl 20

/**
 * Mutable working state passed to [Propagator.propagate]: pinned bools, tightened int domains and a decision level
 * per pinned variable. Decisions bump the level; implied pins inherit the deepest level among the variables the
 * firing factor reads. On contradiction, the failing factor's levels become
 * [PropagationResult.Unsat.conflictLevels].
 */
class PropagationState private constructor(
    /** Propagation-engine projection of the problem being propagated. */
    val projection: PropagationProblem,
    assumptions: Assumptions,
    // Supports mid-life factors appended and tombstoned between presolve rounds, with the int-event machinery
    // forced live. A search state never adds presolve factors.
    internal val incremental: Boolean = false,
    // Runs propagation through [NativeSatState] when the problem is eligible, leaving the general watch indices
    // unregistered.
    internal val nativeSat: Boolean = false,
    // Ignored on problems with integer variables: order-literal atoms have no PB reason.
    internal val pbLearning: Boolean = false,
    prepared: PropagationBuffers?,
) {
    constructor(
        projection: PropagationProblem,
        assumptions: Assumptions,
        incremental: Boolean = false,
        nativeSat: Boolean = false,
        pbLearning: Boolean = false,
    ) : this(projection, assumptions, incremental, nativeSat, pbLearning, null)
    constructor(
        problem: Problem,
        assumptions: Assumptions,
        incremental: Boolean = false,
        nativeSat: Boolean = false,
        pbLearning: Boolean = false,
    ) : this(PropagationProblem(problem), assumptions, incremental, nativeSat, pbLearning)

    /** Immutable model data addressed by this state. */
    val problem: Problem get() = projection.problem

    internal var cumulativeUnarySubsets: Boolean = false

    private val usesNativeSat = nativeSat && projection.isNativeSatEligible

    // [boolValueBits] holds the value when [boolAssigned] is set; packed bits are denser than `Array<Boolean?>`.
    internal val boolAssigned: Bits = prepared?.boolAssigned ?: (Bits(problem.numBoolVars))
    internal val boolValueBits: Bits = prepared?.boolValueBits ?: (Bits(problem.numBoolVars))

    // Native BCP reads one byte per lookup instead of testing two packed bitsets. All pin and
    // undo writes mirror the canonical bits: 0 is open, 1 is false, and 2 is true.
    internal val nativeBoolTruth: ByteArray? = if (usesNativeSat) ByteArray(problem.numBoolVars) else null

    /** Indexable view of the Boolean assignment: reads `null` when unassigned, and writing `null` unassigns. */
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
                nativeBoolTruth?.set(v, 0)
                return
            }
            if (value) boolValueBits.set(v) else boolValueBits.clear(v)
            boolAssigned.set(v)
            nativeBoolTruth?.set(v, (if (value) 2 else 1).toByte())
        }
    }

    /** Read/write view over the current Boolean assignment. */
    val boolValues: BoolView = BoolView()

    /**
     * The root domains this state was seeded from, read by propagators for a variable's original bounds. A
     * [BakedProblem] hands over its folded array, so seeding a state per probe costs no copy.
     */
    val rootDomains: Array<IntDomain> =
        prepared?.rootDomains ?: ((problem as? BakedProblem)?.foldedIntDomains ?: problem.finiteIntDomains())

    /** Per-int current domain (copy of [rootDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = prepared?.intDomains ?: (Array(problem.numIntVars) { rootDomains[it] })

    // Primitive ring buffers avoid autoboxing on every push and poll.
    internal val dirtyBools: IntArrayDeque =
        prepared?.dirtyBools ?: (IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8)))
    internal val dirtyInts: IntArrayDeque =
        prepared?.dirtyInts ?: (IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8)))

    internal val intEvents: IntEventMachinery = prepared?.intEvents ?: (IntEventMachinery(projection, incremental))

    // [propStamp] marks queued factors with a generation stamp, so a reset bumps [propGen] instead of zeroing an
    // array per pin. Dequeuing writes `propGen - 1`, letting a factor re-enqueue within the same run.
    internal val propQueue: IntArrayDeque =
        prepared?.propQueue ?: IntArrayDeque(
            initialCapacity = if (usesNativeSat) 8 else problem.numFactors.coerceAtLeast(8),
        )
    private var propStamp: IntArray =
        prepared?.propStamp ?: (IntArray(if (usesNativeSat) 8 else problem.numFactors.coerceAtLeast(8)))
    private var propGen: Int = 0
    internal var propagationSchedule: PropagationSchedule? = null

    // Grows [propStamp] as learned clauses raise the factor count, and wraps [propGen] safely on overflow.
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
        propagationSchedule?.begin()
    }

    internal fun propEnq(fid: Int) {
        if (retiredLearnedFactor(fid)) {
            work++
            return
        }
        if (propStamp[fid] != propGen) {
            propStamp[fid] = propGen
            propQueue.addLast(fid)
        }
    }

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    /** Decision level when each bool was first pinned (-1 = unpinned). */
    val boolLevel: IntArray = prepared?.boolLevel ?: (IntArray(problem.numBoolVars) { -1 })

    /** Deepest decision level contributing to this int var's current domain (-1 = untouched). */
    val intLevel: IntArray = prepared?.intLevel ?: (IntArray(problem.numIntVars) { -1 })

    // Level at which the current lower (resp. upper) bound was established; [intLevel] is the max of the two.
    // -1 means the bound is still at its root value.
    internal val intMinLevel: IntArray = prepared?.intMinLevel ?: (IntArray(problem.numIntVars) { -1 })
    internal val intMaxLevel: IntArray = prepared?.intMaxLevel ?: (IntArray(problem.numIntVars) { -1 })

    // Interior-hole carve history: an eq atom materialized after the carve reads its level and reason here. Bound
    // moves need none, since order literals carry their own.
    internal val holeHistAnt: Array<ArrayList<IntArray?>?> = prepared?.holeHistAnt ?: (arrayOfNulls(problem.numIntVars))

    // Per int var, the undo-log positions of its bound-move records on the current path, oldest first, so a
    // bound's history is searched without walking the whole log. Appended by [logIntChange], popped as the
    // undo replay passes each record.
    internal val boundMoves: Array<IntArrayList?> = prepared?.boundMoves ?: (arrayOfNulls(problem.numIntVars))

    // The bounds each of those moves started from, parallel to [boundMoves]: a search over a bound's history reads
    // these contiguous values, not the prior-domain snapshot of every record it probes, scattered over the heap.
    internal val boundMovePriorMin: Array<LongArrayList?> =
        prepared?.boundMovePriorMin ?: (arrayOfNulls(problem.numIntVars))
    internal val boundMovePriorMax: Array<LongArrayList?> =
        prepared?.boundMovePriorMax ?: (arrayOfNulls(problem.numIntVars))

    // Built lazy reasons, keyed by their marker array (identity); see [reasonOf].
    // Diagnostic iteration must visit entries without scanning spare hash-table capacity.
    internal val lazyReasonMemo = LinkedHashMap<IntArray, IntArray?>()
    internal val holeHistVal: Array<LongArrayList?> = prepared?.holeHistVal ?: (arrayOfNulls(problem.numIntVars))
    internal val holeHistIndex: Array<MutableLongIntMap?> = prepared?.holeHistIndex ?: arrayOfNulls(problem.numIntVars)
    internal val holeHistLvl: Array<IntArrayList?> = prepared?.holeHistLvl ?: (arrayOfNulls(problem.numIntVars))

    // The undo-log size when each carve was recorded, so a lazy reason can tell a hole that predates its
    // deduction from one carved after it ([carvedAt]).
    internal val holeHistPos: Array<IntArrayList?> = prepared?.holeHistPos ?: (arrayOfNulls(problem.numIntVars))

    // Untrailed unconditional root carves retain their exact membership and position across every pop.
    internal val rootHoleHist: Array<RootHoleHistory?> = prepared?.rootHoleHist ?: arrayOfNulls(problem.numIntVars)
    internal var survivorRestrictionHistory: MutableMap<Int, RevRef<SurvivorRestrictionHistory?>>? = null

    // Per Boolean variable, the undo-log size when its current pin was logged, so a lazy reason can tell a pin
    // that predates its deduction from a later one ([boolPinnedAt]). Meaningful only while the variable is pinned.
    internal val boolPinPos = prepared?.boolPinPos ?: (IntArray(problem.numBoolVars))

    // Index `lvl-1` holds a bool var id or `numBoolVars + intVar`.
    internal val levelToDecisionVar: IntArrayList =
        IntArrayList()

    /** Number of decisions pushed so far. Equals the maximum level. */
    val numDecisions: Int get() = levelToDecisionVar.size

    private val composeAntecedentSeen: LongHashSet = LongHashSet()

    /**
     * Bound-atom antecedents for a constraint-wide reason over [vars]: `¬[v ≥ d.min]` for each lower bound
     * tightened past the root, likewise for upper bounds. Per-bound atoms give 1UIP and minimization finer
     * resolution than the Boolean union they would unfold to. `null` when no bound is tightened, a level-0 leaf.
     */
    fun composeIntVarAtomAntecedents(vars: IntArray): IntArray? {
        // Reused and not re-entrant within one propagate, so this hot reason path allocates and boxes nothing.
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

    internal var currentLevel: Int = 0
        set(value) {
            field = value
            deferredLevelClause = null
        }

    // An atom-literal clause's level is a scan of its literals, and most of its fires only check or move a watch,
    // so the driver leaves it to [settleClauseLevel]: the clause calls it before it pins or fails, while the state
    // is still the one it fired on. Until then [currentLevel] holds the live decision count, an upper bound.
    private var deferredLevelClause: IntArray? = null

    internal fun forgetDeferredClause(literals: IntArray) {
        if (deferredLevelClause === literals) deferredLevelClause = null
    }

    internal fun settleClauseLevel(literals: IntArray) {
        if (deferredLevelClause === literals) currentLevel = maxLevelForClause(literals)
    }

    @Suppress("DoubleMutabilityForCollection") // lazily allocated on conflict
    internal var conflictLevels: IntArray? = null

    /** Factor that most recently forced each bool, `-1` for a decision or assumption. */
    val boolReason: IntArray = prepared?.boolReason ?: (IntArray(problem.numBoolVars) { -1 })

    /** Factor that most recently tightened this int var's lower bound, `-1` for a decision or the initial domain. */
    val intMinReason: IntArray = prepared?.intMinReason ?: (IntArray(problem.numIntVars) { -1 })

    /** Mirror of [intMinReason] for the upper bound. */
    val intMaxReason: IntArray = prepared?.intMaxReason ?: (IntArray(problem.numIntVars) { -1 })

    internal var integerRowLearning = false
    internal var integerRowAdmission = true
    internal var integerRowReuse = true
    internal var integerRowsDerived = 0L
    internal var integerRowAdmissionsDeclined = 0L
    internal var integerRowAnalysisWork = 0L
    internal var integerRowRetainedSeedUses = 0L
    internal var integerRowRetainedEndpointUses = 0L
    internal var integerRowAttachmentWork = 0L
    internal var integerRowPropagationWork = 0L
    internal var integerRowPostInitialFires = 0L
    internal var integerRowPostInitialUsefulFires = 0L
    internal var arithmeticLearningLimits = ArithmeticLearningLimits()
    internal var factorAttribution: FactorAttribution? = null
    internal var historicalTableReasons = false
    internal var reasonPreflight = false
    internal var historicalTableReason: HistoricalTableReason? = null
    internal var arithmeticLearningTrace: ArithmeticLearningTrace? = null
        get() = field?.takeIf { it.accepting || it.collectDeclines }
    internal var integerLearningTrailAtLevel: ((Int) -> Int?)? = null

    // -1 between factor invocations, so decisions and seed pins record reason -1.
    internal var currentFactor: Int = -1

    // Factor-forced assignments only; decisions and seed pins do not count.
    internal var propagations: Long = 0L

    internal var work: Long = 0L
    internal var workLimit: Long = Long.MAX_VALUE
    internal var preparationIndexWork: Long = 0L

    internal fun preparationStatistics(): Map<String, Long> = if (preparationIndexWork == 0L) emptyMap()
    else mapOf("propagationPreparationIndexWork" to preparationIndexWork)
    internal val finiteWork = FinitePropagationWork()
    internal var finiteMutationInterruptible = false
    internal var finiteMaintenanceActive = false
    internal var pendingFiniteMutation: FiniteDomainCompletion? = null
    internal var propagationNanos: Long = 0L

    // Sticky, since the deadline is monotone. The engine returns `BudgetCapped` rather than reading the partial
    // fixpoint as a solved leaf, which would be an unsound SAT.
    internal var runCancelled: Boolean = false

    // Overridable so a test can force the runaway-fixpoint path without a million-fire propagation.
    internal var cancelFloor: Int = PROPAGATION_CANCEL_FLOOR

    // Factors directly implicated in a contradiction; [extractConflictFactors] walks back from them. Reused and
    // primitive because the conflict path is hot.
    internal val conflictSeedFactors: IntHashSet = IntHashSet()

    internal val levelScratch: IntHashSet = IntHashSet()

    internal val conflictFactorScratch = ConflictFactorScratch()

    // Reused: a wide pseudo-Boolean factor rebuilds the same antecedent set for each literal it pins, and a
    // per-call set would dominate its root bake.
    internal val pbAntecedentSeen: IntHashSet = IntHashSet()
    internal val pbAntecedentBuf: IntArrayList = IntArrayList()

    // Var whose pin a decision tried to contradict, -1 when none. Lets the analyzer learn from a
    // decision-vs-prior-pin conflict instead of backtracking chronologically.
    internal var lastDecisionConflictVar: Int = -1

    // Failure clauses or premise variables are private to the state and independent of incremental payloads.
    private val propagatorFailureStore = lazy(LazyThreadSafetyMode.NONE) { mutableMapOf<Propagator, IntArray?>() }
    internal val propagatorFailures: MutableMap<Propagator, IntArray?> by propagatorFailureStore

    // Retained DivMod cells have a state-wide allowance; replaced payloads reuse their reservation.
    // Tombstoning may leave unused reservation, which only weakens subsequent cache admission.
    internal var divModSupportCells: Int = 0

    internal var mddPathWeightCells: Int = 0

    internal fun clearPropagatorFailures() {
        if (propagatorFailureStore.isInitialized()) propagatorFailures.clear()
    }

    // Per-factor mutable scratch. Its drift across snapshot / restore is intentional: advisory
    // watch state self-corrects on each fire, avoiding snapshot copies of level-aware state.

    private class PayloadOwner(var values: ArrayList<Any?>)
    private val payloadOwner: PayloadOwner by lazy(LazyThreadSafetyMode.NONE) {
        PayloadOwner(prepared?.refPayloadStore ?: ArrayList<Any?>(problem.numFactors).apply {
            repeat(problem.numFactors) { add(null) }
        })
    }
    internal var refPayloadStore: ArrayList<Any?>
        get() = payloadOwner.values
        set(value) {
            payloadOwner.values = value
        }

    // Lets [mark] visit only snapshottable slots instead of every factor per pin. They belong to static factors,
    // so their ids never move under learned-clause forgetting.
    internal val snapshottableIndices: IntHashSet = IntHashSet()

    /** Per-factor mutable payload slots; writes go through this view so snapshottable payloads stay tracked. */
    val refPayload: MutableList<Any?> by lazy(LazyThreadSafetyMode.NONE) {
        object : AbstractMutableList<Any?>() {
            override val size: Int
                get() = if (!incremental && nativeEngine == null) baseFactorCount + learned.size
                else refPayloadStore.size
            override fun get(index: Int): Any? =
                if (!incremental && index >= baseFactorCount && nativeEngine == null) {
                    learned.store.payload(index - baseFactorCount)
                } else refPayloadStore[index]
            override fun add(index: Int, element: Any?) {
                refPayloadStore.add(index, element)
            }
            override fun removeAt(index: Int): Any? = refPayloadStore.removeAt(index)
            override fun set(index: Int, element: Any?): Any? {
                if (index !in 0 until size) throw IndexOutOfBoundsException("factor payload $index outside $size")
                if (element is SnapshottablePayload) {
                    snapshottableIndices.add(
                        index,
                    )
                } else {
                    snapshottableIndices.remove(index)
                }
                return if (!incremental && index >= baseFactorCount && nativeEngine == null) {
                    learned.store.setPayload(index - baseFactorCount, element)
                } else refPayloadStore.set(index, element)
            }
        }
    }

    internal val learned: LearnedClauseDb = LearnedClauseDb(
        binaryClauseCount = prepared?.binaryClauseCount ?: run {
            var n = 0
            for (f in problem.factors) if (f is Clause && f.literals.size == 2) n++
            n
        },
        initializeRows = !usesNativeSat && !incremental,
        rowOwner = this,
    )
    internal val selectedRetirements = LearnedRetirementRequests()
    internal var nextLearnedAdmissionKey = 0L

    // The native SAT lane does not use CP propagators or occurrence indices. Keep their tables
    // deferred so a clause-only state does not retain both representations of every factor.
    internal val baseFactors: Array<out Propagator> by lazy(LazyThreadSafetyMode.NONE) { projection.propagators }
    internal val baseFactorCount: Int = problem.factors.size

    private val nonBoolWatcherOcc: Array<IntArray> by lazy(LazyThreadSafetyMode.NONE) {
        projection.nonBoolWatcherBoolOccurrences
    }
    private val nonIntEventWatcherOcc: Array<IntArray> by lazy(LazyThreadSafetyMode.NONE) {
        projection.nonIntEventWatcherIntOccurrences
    }

    internal val learnedClauses: List<LearnedPropagator> get() = learned.initializedStore ?: emptyList()

    /** True iff any binary clause is known — the gate for binary-resolution minimization. */
    val hasBinaryClauses: Boolean get() = learned.binaryClauseCount > 0

    internal val midlife: MidlifeFactors = MidlifeFactors()

    /** Base, learned and mid-life factor count; size per-factor engine scratch by it, not `problem.numFactors`. */
    val totalFactorCount: Int get() = problem.numFactors + learned.size + midlife.store.size

    private val watchIndex = lazy(LazyThreadSafetyMode.NONE) {
        prepared?.watches ?: BoolWatcherIndex(problem.numBoolVars, baseFactorCount = baseFactorCount)
    }
    internal val watches: BoolWatcherIndex get() = watchIndex.value
    internal val initializedWatches: BoolWatcherIndex?
        get() = if (watchIndex.isInitialized()) watchIndex.value else null

    /**
     * Reasons for implied Boolean pins, as eager antecedent literals or [lazyReason] markers. Null denotes a
     * decision, assumption or deduction with no recorded reason, a leaf in conflict analysis.
     */
    val boolAntecedents: Array<IntArray?> = prepared?.boolAntecedents ?: (arrayOfNulls(problem.numBoolVars))

    /**
     * Literals that forced the current lower bound of `intDomains[v]`; null when none were recorded, which
     * conflict analysis treats as a leaf. The int-side mirror of [boolAntecedents].
     */
    val intMinAntecedents: Array<IntArray?> = prepared?.intMinAntecedents ?: (arrayOfNulls(problem.numIntVars))

    /** Mirror of [intMinAntecedents] for the upper-bound side. */
    val intMaxAntecedents: Array<IntArray?> = prepared?.intMaxAntecedents ?: (arrayOfNulls(problem.numIntVars))

    internal val atoms: AtomStore = prepared?.atoms ?: (AtomStore(problem.numIntVars))

    // Registered by [flushPendingChanneling] at a safe boundary, never mid-analysis where allocAtom also fires.
    internal val pendingChanneling: ArrayList<IntArray> = ArrayList()

    /** Allocate or look up the atom `[intVar ≥ threshold]` and return its virtual variable id, for [Lit.make]. */
    fun atomVarGe(intVar: Int, threshold: Long): Int = allocAtom(intVar, kind = AtomKind.GE, threshold = threshold)

    /** Allocate or look up the atom `[intVar ≤ threshold]`. */
    fun atomVarLe(intVar: Int, threshold: Long): Int = allocAtom(intVar, kind = AtomKind.LE, threshold = threshold)

    /** Allocate or look up the atom `[intVar = value]`; its negative literal encodes `[intVar ≠ value]`. */
    fun atomVarEq(intVar: Int, value: Long): Int = allocAtom(intVar, kind = AtomKind.EQ, threshold = value)

    /** True iff bool [v] is assigned; a box-free alternative to `boolValues[v]`, paired with [boolValueAt]. */
    fun boolAssignedAt(v: Int): Boolean = boolAssigned.get(v)

    /** Stored value of bool [v]; meaningful only when [boolAssignedAt] is true. */
    fun boolValueAt(v: Int): Boolean = boolValueBits.get(v)

    /** Truth of a bool or atom literal, `null` when undetermined. */
    fun litTruth(lit: Int): Boolean? {
        work++
        val v = Lit.variable(lit)
        val raw: Boolean? = if (v < problem.numBoolVars) {
            boolValues[v]
        } else {
            atomCurrentTruth(atomIdOf(v))
        }
        if (raw == null) return null
        return Lit.evaluate(lit, raw)
    }

    /** True iff [lit] is currently `true`; false when undetermined. Bool literals read the packed bits unboxed. */
    fun litTrue(lit: Int): Boolean {
        work++
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            return boolAssigned.get(v) && (boolValueBits.get(v) == Lit.isPositive(lit))
        }
        return litTruth(lit) == true
    }

    /** True iff [lit] is currently `false`; false when undetermined. */
    fun litFalse(lit: Int): Boolean {
        work++
        val v = Lit.variable(lit)
        if (v < problem.numBoolVars) {
            return boolAssigned.get(v) && (boolValueBits.get(v) != Lit.isPositive(lit))
        }
        return litTruth(lit) == false
    }

    /** Assign [lit] true, recording [antecedents] (the forcing clause's other literals); false on conflict. */
    fun pinLit(lit: Int, antecedents: IntArray? = null): Boolean {
        attributeReason(antecedents)
        val v = Lit.variable(lit)
        val pos = Lit.isPositive(lit)
        if (v < problem.numBoolVars) return pinBoolImpl(v, pos, antecedents)
        return pinAtomLit(atomIdOf(v), pos, antecedents)
    }

    // A forcing clause records itself as the atom's reason, so conflict analysis resolves through real clauses.
    internal fun pinAtomLit(atomId: Int, newT: Boolean, antecedents: IntArray?): Boolean {
        val target = if (newT) 1 else 2
        val cur = atoms.truth[atomId]
        if (cur == target) return true // already established on this path
        val intVar = atoms.intVar[atomId]
        val k = atoms.threshold[atomId]
        // The stored 0 is a cache miss, not proof of being unassigned: an entailed atom materialized after its
        // bound crossed also reads 0, and re-stamping it here would mis-date it and corrupt 1UIP's levels. Stamp
        // only when the domain does not decide the literal; otherwise the reconcile is a no-op or conflicts.
        if (cur == 0 && atomTruthOf(intVar, atoms.kind[atomId], k) == null) {
            // Recorded before the reconcile, so the narrowing's [wakeAtom] finds the truth set and keeps this
            // clause as the reason.
            recordAtomTruthChange(atomId)
            boolPinOrder.add(problem.numBoolVars + atomId)
            atoms.truth[atomId] = target
            atoms.lvl[atomId] = currentLevel
            atoms.ant[atomId] = antecedents
            // No wake here: the reconcile's own [wakeAtom] fires the watchers. Waking twice would let channeling
            // clauses re-pin adjacent same-var atoms out of order, a coupling 1UIP resolves into an unsound nogood.
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

    // Bool pins and atom establishments in pin order; 1UIP resolves against the most recently pinned variable.
    internal val boolPinOrder: IntArrayList =
        IntArrayList(initialCapacity = problem.numBoolVars.coerceAtLeast(8))

    /** Called with each variable [undoTo] unassigns; null when unsubscribed. */
    var unassignListener: ((Int) -> Unit)? = null
    internal var retractingListener: ((Int) -> Unit)? = null

    internal val undo: UndoLog = UndoLog()

    // Shared by marks taken with no live payload; read-only.
    internal val emptyPayloads: MutableIntObjectMap<SnapshottablePayload> = MutableIntObjectMap(1)

    // Created on the first conflict; its scratch persists across conflicts.
    internal val conflictAnalyzer: ConflictAnalyzer by lazy(LazyThreadSafetyMode.NONE) { ConflictAnalyzer(this) }

    /** When false, mutators skip undo-log recording, so one-shot propagation and the bake fixpoint pay nothing. */
    var undoLogging: Boolean = false

    // Incremental bool part of the trivial objective lower bound: `Σ_b contribution(b)` with
    // `contribution(b) = pinned ? (value ? w : 0) : min(w, 0)`. Pinning only raises it, so it lives on the
    // reversible trail instead of an O(numBoolVars) rescan per node; the integer part stays the caller's scan.
    private var objBoolWeights: LongArray? = null
    private var objBoolBaseConst: Long = 0L
    private var objBoolPenalty: RevLong? = null

    internal val objectiveBoolBoundInstalled: Boolean get() = objBoolWeights != null

    // A no-op unless no decisions are retained and it is not yet installed; the baseline sums contributions that
    // cannot be undone. Returns false when it declined (not at root, or overflow), so the caller rescans.
    internal fun installObjectiveBoolBound(weights: LongArray): Boolean {
        if (objBoolWeights != null) return true
        if (levelToDecisionVar.size != 0) return false
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

    // The penalty `value ? max(w,0) : max(−w,0)` is never negative; the reversible cell restores it on backtrack.
    internal fun bumpObjectiveBoolBound(v: Int, value: Boolean) {
        val weights = objBoolWeights ?: return
        if (v >= weights.size) return
        val w = weights[v]
        if (w == 0L) return
        val penalty = if (value) (if (w > 0L) w else 0L) else (if (w < 0L) -w else 0L)
        if (penalty != 0L) objBoolPenalty?.let { it.set(it.value + penalty) }
    }

    // Excludes the objective constant and integer terms; valid only once installed.
    internal fun objectiveBoolLowerBound(): Long = objBoolBaseConst + (objBoolPenalty?.value ?: 0L)

    /** Current undo-log size; [PropagationSession] diffs a push by the variables mutated since a position. */
    val undoTop: Int get() = undo.size

    // Declared after [boolPinOrder] and the clause arena, which its construction reads.
    internal val nativeEngine: NativeSatState? =
        if (usesNativeSat) NativeSatState(this) else null

    // Seed reason stashed on a native conflict, so 1UIP needs no factor lookup.
    internal var nativeConflictReason: IntArray? = null

    init {
        // The native lane uses arena watches and skips general base-factor registration.
        if (prepared == null && nativeEngine == null) {
            for (fid in 0 until problem.numFactors) registerFactor(fid, projection.propagators[fid], preparation = true)
            reserveWatchPositions()
        }
    }

    internal companion object {
        fun preparation(projection: PropagationProblem, pbLearning: Boolean): Iterator<PropagationState?> = sequence {
            var buffers: PropagationBuffers? = null
            for (next in PropagationBuffers.preparation(projection)) {
                if (next != null) buffers = next
                yield(null)
            }
            val state = PropagationState(projection, Assumptions.None, false, false, pbLearning, checkNotNull(buffers))
            yield(null)
            var visits = 0
            for (fid in projection.propagators.indices) {
                val propagator = projection.propagators[fid]
                val watchers = propagator.initialBoolWatchers
                if (watchers != null) {
                    val blockers = propagator.initialBoolWatcherBlockers
                    for (i in watchers.indices) {
                        state.installLitWatch(watchers[i], fid, blockers?.getOrNull(i) ?: NO_BLOCKER,
                            preparation = true)
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
                    }
                }
                if (state.intEvents.deltaOn && propagator.consumesIntEventDelta) {
                    state.intEvents.dirtyVars[fid] = IntArrayList()
                    state.intEvents.dirtyMark[fid] = IntHashSet()
                }
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            state.reserveWatchPositions()
            yield(state)
        }.iterator()
    }

    private fun reserveWatchPositions() {
        if (problem.numBoolVars > 0) preparationIndexWork += watches.pos.reserve(baseFactorCount, 8)
    }

    // Shared by construction and by an incremental session registering a factor mid-run.
    private fun registerFactor(fid: Int, propagator: Propagator, preparation: Boolean = false) {
        val watchers = propagator.initialBoolWatchers
        if (watchers != null) {
            val blockers = propagator.initialBoolWatcherBlockers
            for (i in watchers.indices) {
                installLitWatch(watchers[i], fid, blockers?.getOrNull(i) ?: NO_BLOCKER, preparation)
            }
        }
        // Base factors' int-event subscriptions are prebuilt into the CSR at [IntEventMachinery]
        // construction; only mid-life factors (fid past the base count) subscribe here, into the overflow.
        if (intEvents.on && fid >= baseFactorCount) {
            propagator.initialIntEventWatches?.let { events ->
                for (packed in events) work += intEvents.subscribeMidlife(packed, fid)
            }
        }
        if (intEvents.deltaOn && propagator.consumesIntEventDelta) {
            intEvents.dirtyVars[fid] = IntArrayList()
            intEvents.dirtyMark[fid] = IntHashSet()
        }
    }

    internal fun factorAliveAt(fid: Int): Boolean = fid !in midlife.tombstoned

    // Incremental mode only. Does not fire the factor; the session drives a [runToFixpoint] afterwards.
    internal fun addMidlifeFactor(factor: Factor): Int {
        require(incremental) { "mid-life factors require an incremental PropagationState" }
        val prop = factor.propagatorProjection(provider =
            projection.selection?.providers?.propagation ?: BuiltInPropagationProjectionProvider)
        val fid = totalFactorCount
        midlife.store.add(prop)
        midlife.factors.add(factor)
        refPayloadStore.add(null)
        // Grown in lockstep so a mid-life consumer's slot exists before [registerFactor] populates it.
        if (intEvents.deltaOn) {
            intEvents.dirtyVars.add(null)
            intEvents.dirtyMark.add(null)
        }
        if (prop is ClausePropagator && prop.literals.size == 2) learned.binaryClauseCount++
        registerFactor(fid, prop)
        registerMidlifeOccurrences(fid, factor, prop)
        return fid
    }

    // The id is retired, never reused: a later add must not inherit a stale payload. The factor's watcher and
    // occurrence entries linger and wake a no-op [NoPropagator].
    internal fun tombstoneFactor(fid: Int) {
        require(incremental) { "tombstoning requires an incremental PropagationState" }
        if (!midlife.tombstoned.add(fid)) return
        refPayloadStore[fid] = null
    }

    // A factor with per-literal bool watchers is excluded from every bool overlay; on the int side a var is
    // excluded only when the factor subscribes to a typed event on it.
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
        if (prepared == null) seeded = seedAssumptions(assumptions)
    }

    /** Force bool `v` to [value]; returns false on conflict. */
    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolImpl(v, value, antecedents = null)

    /** As [pinBool], recording [antecedents]: literals, false now, that implied the pin. */
    fun pinBool(v: Int, value: Boolean, antecedents: IntArray?): Boolean {
        attributeReason(antecedents)
        return pinBoolImpl(v, value, antecedents)
    }

    /** Raise int `v`'s lower bound to [lo]; returns false on conflict. */
    fun tightenIntMin(v: Int, lo: Long): Boolean = tightenIntMinImpl(v, lo, null)

    /** As [tightenIntMin], recording [antecedents]: literals, false now, that forced the tightening. */
    fun tightenIntMin(v: Int, lo: Long, antecedents: IntArray?): Boolean {
        attributeReason(antecedents)
        return tightenIntMinImpl(v, lo, antecedents)
    }

    /** Lower int `v`'s upper bound to [hi]; returns false on conflict. */
    fun tightenIntMax(v: Int, hi: Long): Boolean = tightenIntMaxImpl(v, hi, null)

    /** As [tightenIntMax] with explicit conflict [antecedents]. */
    fun tightenIntMax(v: Int, hi: Long, antecedents: IntArray?): Boolean {
        attributeReason(antecedents)
        return tightenIntMaxImpl(v, hi, antecedents)
    }

    /** Pin int `v` to [value]; returns false on conflict. */
    fun setInt(v: Int, value: Long): Boolean = setIntImpl(v, value, null)

    /** As [setInt] with explicit conflict [antecedents]. */
    fun setInt(v: Int, value: Long, antecedents: IntArray?): Boolean {
        attributeReason(antecedents)
        return setIntImpl(v, value, antecedents)
    }

    /** Punch a hole in `v`'s domain at [value]; returns false only when that empties the domain. */
    fun excludeIntValue(v: Int, value: Long): Boolean = excludeIntValueImpl(v, value, null)

    /** Forbid [value] for int `v`; returns false on conflict. */
    fun excludeIntValue(v: Int, value: Long, antecedents: IntArray?): Boolean {
        attributeReason(antecedents)
        return excludeIntValueImpl(v, value, antecedents)
    }

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

    // Callers mark and undo only between propagation cycles, when the dirty queues are empty.

    /**
     * Opt-in for [refPayload] entries with level-sensitive incremental state, such as a table's sparse set of
     * valid tuples; other payloads drift across push and pop.
     */
    interface SnapshottablePayload {
        /** Deep-copy this payload for trail snapshotting. */
        fun snapshotCopy(): SnapshottablePayload
    }

    internal fun logReversible(cell: Trailed) {
        undo.revTrail.add(cell)
    }

    /**
     * Per-level marker: the append-only stack sizes a pop rewinds to, plus snapshot copies of any
     * [SnapshottablePayload]s. [undoTo] replays the undo log in O(changes since the mark).
     */
    class LevelMark internal constructor(
        internal val undoSize: Int,
        internal val ltdvSize: Int,
        internal val pinOrderSize: Int,
        internal val snapshottablePayloads: MutableIntObjectMap<SnapshottablePayload>,
        internal val revSize: Int,
        internal val atomUndoSize: Int,
    )

    // [allFactors] enqueues every factor; otherwise only those touching dirty variables. Returns null at a
    // fixpoint, else the conflict levels. A real [cancellation] token is polled on both paths, since a targeted
    // fixpoint can wedge on a bound crawl across a Long-wide span; without one the gate is a dead branch. A fired
    // token returns null early, and the partial fixpoint is sound because it only tightens.
    internal fun runToFixpoint(
        allFactors: Boolean,
        initialFactor: Int = -1,
        initialFactors: IntArray = emptyIntArray(),
        cancellation: ExecutionControl = ExecutionControl.Never,
        skipExpensiveBake: Boolean = false,
    ): IntArray? {
        val start = monotonicNanos()
        finiteWork.cancellation = cancellation
        try {
            return drainFixpoint(allFactors, initialFactor, initialFactors, cancellation, skipExpensiveBake)
        } finally {
            while (propQueue.isNotEmpty()) deferLearnedAttachment(propQueue.removeFirst())
            propagationNanos += monotonicNanos() - start
        }
    }

    internal fun inprocessingFixpoint(initialFactor: Int, cancellation: ExecutionControl): IntArray? {
        val start = monotonicNanos()
        val previousFloor = cancelFloor
        cancelFloor = 0
        finiteWork.cancellation = cancellation
        try {
            if (initialFactor >= 0 || nativeEngine != null) {
                return drainFixpoint(false, initialFactor, emptyIntArray(), cancellation, false)
            }
            return fireQueue(cancellation !== ExecutionControl.Never, 0, cancellation, false)
        } finally {
            cancelFloor = previousFloor
            propagationNanos += monotonicNanos() - start
        }
    }

    private var rootPreparationStarted = false

    internal fun beginRootFixpoint(): Iterator<Unit> = sequence {
        check(nativeEngine == null)
        check(!rootPreparationStarted)
        rootPreparationStarted = true
        conflictSeedFactors.clear()
        flushPendingChanneling()
        propBegin(totalFactorCount)
        for (fid in 0 until totalFactorCount) {
            propEnq(fid)
            if ((fid + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
        }
    }.iterator()

    internal fun beginSeedFixpoint(): Iterator<Unit> = sequence {
        check(nativeEngine == null && rootPreparationStarted)
        check(!incremental)
        conflictSeedFactors.clear()
        flushPendingChanneling()
        propBegin(totalFactorCount)
        var visits = 0
        while (true) {
            val v = pollDirtyBool()
            if (v < 0) break
            for (fid in nonBoolWatcherOcc[v]) {
                work++
                propEnq(fid)
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
            }
            val falseLit = Lit.make(v, !boolValueBits.get(v))
            val watchers = watches.byLit[falseLit]
            val blockers = watches.blockersByLit[falseLit]
            for (i in 0 until watchers.size) {
                work++
                val blocker = blockers[i]
                if (blocker == NO_BLOCKER || !litTrue(blocker)) propEnq(watchers[i])
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
            }
            if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
        }
        while (true) {
            val v = pollDirtyInt()
            if (v < 0) break
            for (fid in nonIntEventWatcherOcc[v]) {
                work++
                propEnq(fid)
                if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
            }
            if (intEvents.on) {
                val mask = intEvents.dirtyKinds[v]
                intEvents.dirtyKinds[v] = 0
                for (kind in 0 until IntEvent.COUNT) {
                    if (mask and (1 shl kind) == 0) continue
                    val packed = IntEvent.pack(v, kind)
                    for (k in intEvents.baseOffsets[packed] until intEvents.baseOffsets[packed + 1]) {
                        val fid = intEvents.baseFlat[k]
                        work++
                        propEnq(fid)
                        accumulateDirtyVar(fid, v)
                        if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
                    }
                }
            }
            if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
        }
        while (atoms.dirtyFactors.isNotEmpty()) {
            val fid = atoms.dirtyFactors.removeFirst()
            if (fid in 0 until totalFactorCount) propEnq(fid)
            if (++visits % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(Unit)
        }
    }.iterator()

    internal val rootFixpointPending: Boolean get() = propQueue.isNotEmpty()

    internal val rootFixpointStarted: Boolean get() = rootPreparationStarted

    internal fun advanceRootFixpoint(cancellation: ExecutionControl): IntArray? {
        check(rootPreparationStarted)
        val start = monotonicNanos()
        finiteWork.cancellation = cancellation
        try {
            return fireQueue(
                cancellation !== ExecutionControl.Never, 0, cancellation, false,
                maxFires = PROPAGATION_PREPARATION_BATCH_SIZE, cancelIsPause = true,
            )
        } finally {
            propagationNanos += monotonicNanos() - start
        }
    }

    private fun drainFixpoint(
        allFactors: Boolean,
        initialFactor: Int,
        initialFactors: IntArray,
        cancellation: ExecutionControl,
        skipExpensiveBake: Boolean,
    ): IntArray? {
        // The atom store, channeling flush, factor queue and dirty-var drains are inert without integer
        // variables, so the native lane bypasses them.
        nativeEngine?.let { native ->
            val attribution = factorAttribution ?: return native.propagate(allFactors, cancellation)
            val before = work
            val pins = propagations
            val start = TimeSource.Monotonic.markNow()
            val conflict = native.propagate(allFactors, cancellation)
            val family = 1 shl FactorFamily.Clause.ordinal
            attribution.note(family, FactorAttribution.Metric.Fires)
            attribution.note(family, FactorAttribution.Metric.Work, work - before)
            attribution.note(family, FactorAttribution.Metric.Nanos, start.elapsedNow().inWholeNanoseconds)
            attribution.note(family, FactorAttribution.Metric.Deductions, propagations - pins)
            if (conflict != null) attribution.note(family, FactorAttribution.Metric.Conflicts)
            if (runCancelled) attribution.note(family, FactorAttribution.Metric.Interrupted)
            return conflict
        }
        // Old seeds would otherwise leak into a new conflict's core.
        conflictSeedFactors.clear()
        // Register channeling clauses queued since the last fixpoint, including during conflict analysis, at
        // this safe boundary before snapshotting the factor count.
        flushPendingChanneling()
        val factorCount = totalFactorCount
        propBegin(factorCount)
        val pollable = cancellation !== ExecutionControl.Never
        // Full propagation is one-shot, so it polls from the first fire. The per-node path defers the poll past
        // the fire floor, so a small fixpoint and resumable slicing are never cut mid-propagation.
        val floor = if (allFactors) 0 else cancelFloor
        seedQueue(allFactors, factorCount, initialFactor, initialFactors)
        for (i in 0 until learned.pendingAttach.size) {
            val fid = learned.pendingAttach[i]
            learned.store[fid - baseFactorCount, GeneralLearnedRows.STAGES] =
                learned.store[fid - baseFactorCount, GeneralLearnedRows.STAGES] and 4.inv()
            propEnq(fid)
        }
        learned.pendingAttach.clear()
        if (learned.unitsPending) {
            for (i in 0 until learned.units.size) propEnq(learned.units[i])
            learned.unitsPending = false
        }
        return fireQueue(pollable, floor, cancellation, skipExpensiveBake)
    }

    private fun seedQueue(allFactors: Boolean, factorCount: Int, initialFactor: Int, initialFactors: IntArray) {
        if (allFactors) {
            for (fid in 0 until factorCount) propEnq(fid)
            return
        }
        // Capped: a stale id from before a forget can linger in the pre-run queue.
        drainDirtyIntoQueue(atomFactorCap = factorCount)
        // A freshly added clause's watches have not been triggered, so without this seed it would sit dormant.
        if (initialFactor in 0 until factorCount) propEnq(initialFactor)
        // Seed a batch of freshly-added mid-life factors so each fires once on the next cycle;
        // like [initialFactor], their watches haven't been triggered yet so they'd sit dormant.
        for (fid in initialFactors) if (fid in 0 until factorCount) propEnq(fid)
    }

    // The fire loop is its own method so the on-stack compile a long fixpoint triggers covers it alone, not the
    // seeding passes ahead of it.
    private fun fireQueue(
        pollable: Boolean,
        floor: Int,
        cancellation: ExecutionControl,
        skipExpensiveBake: Boolean,
        maxFires: Int = Int.MAX_VALUE,
        cancelIsPause: Boolean = false,
    ): IntArray? {
        if (runCancelled) return null
        var fireCount = 0
        val completingMutation = pendingFiniteMutation != null
        try {
            completePendingFiniteMutation()
        } catch (_: FinitePropagationInterrupted) {
            runCancelled = !cancelIsPause
            return null
        }
        if (completingMutation) drainDirtyIntoQueue(atomFactorCap = Int.MAX_VALUE)
        while (propQueue.isNotEmpty()) {
            if (runCancelled) return null
            if (maxFires != Int.MAX_VALUE && fireCount >= maxFires) return null
            // Only a runaway fixpoint (an O(span) bound crawl, millions of fires) needs the deadline inside
            // propagation; normal per-node fixpoints and resumable slicing stay below the fire floor.
            if (pollable) {
                if (fireCount >= floor && (fireCount and CANCEL_POLL_MASK) == 0 && cancellation()) {
                    if (!cancelIsPause) runCancelled = true
                    return null
                }
            }
            fireCount++
            val schedule = propagationSchedule
            val fid = if (schedule == null) propQueue.removeFirst() else {
                val selected = schedule.remove(propQueue) { factorAt(it).propagationFamily() }
                work += schedule.selectionWork
                selected
            }
            propStamp[fid] = propGen - 1 // mark dequeued (≠ propGen) so it can re-enqueue
            if (retiredLearnedFactor(fid)) {
                work++
                continue
            }
            val f = factorAt(fid)
            // Cheap bake: defer an expensive factor's first fire (heavy state build + sweep) to the
            // first search fire. It re-enqueues if a cheap factor later wakes it, and is skipped again.
            if (skipExpensiveBake && f.expensiveBake) continue
            val attribution = factorAttribution
            val family = if (attribution == null) 0 else attribution.family(f)
            val before = work
            val pins = propagations
            val start = if (attribution == null) null else TimeSource.Monotonic.markNow()
            work++
            currentLevel = effectiveLevelFor(f, fid)
            if (f is ClausePropagator && !f.allLiteralsBool(problem.numBoolVars)) deferredLevelClause = f.literals
            noteLearnedAssertion(fid)
            currentFactor = fid
            conflictLevels = null
            val propagated = try {
                finiteMutationInterruptible = f is InterruptiblePropagator
                f.propagate(this, fid)
            } catch (_: FinitePropagationInterrupted) {
                attribution?.note(family, FactorAttribution.Metric.Interrupted)
                (f as? InterruptiblePropagator)?.interrupted(this, fid)
                propEnq(fid)
                if (pendingFiniteMutation != null) runCancelled = !cancelIsPause
                else if (!cancelIsPause) runCancelled = true
                conflictLevels = null
                return null
            } finally {
                finiteMutationInterruptible = false
                schedule?.observe(f.propagationFamily(), work - before)
                attribution?.let {
                    it.note(family, FactorAttribution.Metric.Fires)
                    it.note(family, FactorAttribution.Metric.Work, work - before)
                    it.note(family, FactorAttribution.Metric.Deductions, propagations - pins)
                    it.note(family, FactorAttribution.Metric.Nanos,
                        requireNotNull(start).elapsedNow().inWholeNanoseconds)
                }
            }
            if (runCancelled) {
                attribution?.note(family, FactorAttribution.Metric.Interrupted)
                propEnq(fid)
                return null
            }
            if (!propagated) {
                attribution?.note(family, FactorAttribution.Metric.Conflicts)
                // The failing factor is always in the core, even when it failed without pinning anything.
                seedConflictFactor(fid)
                return conflictLevels ?: factorVarsConflictLevels(fid)
            }
            // Post-fire wakes are always in range, so the atom-factor cap is inert here.
            drainDirtyIntoQueue(atomFactorCap = Int.MAX_VALUE)
        }
        return null
    }

    // [atomFactorCap] bounds atom-woken ids: a stale id from before a forget can linger in the pre-run queue.
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

    // A pure-bool clause fires only on a literal falsified at the current level, so it takes that level without a
    // scan on the BCP hot path. Atom-literal clauses defer their literal scan to [settleClauseLevel].
    private fun effectiveLevelFor(f: Propagator, fid: Int): Int = when {
        f is ClausePropagator -> levelToDecisionVar.size

        // A learned non-clause constraint lives in the learned store; read its footprint off the propagator.
        fid >= baseFactorCount && !incremental && f is LearnedPropagator -> maxLevelForVars(f.boolVars, f.intVars)

        else -> {
            // A mid-life presolve factor reads its vars from [MidlifeFactors.factors].
            val factor = if (fid < baseFactorCount) problem.factors[fid] else midlife.factors[fid - baseFactorCount]
            maxLevelForVars(factor.boolVars, factor.intVars)
        }
    }

    private fun enqueueForBoolChange(v: Int) {
        work += nonBoolWatcherOcc[v].size
        for (fid in nonBoolWatcherOcc[v]) propEnq(fid)
        // Mid-life presolve factors waking via occurrence lists; null (no overlay) otherwise.
        midlife.boolOccurrences?.let {
            val list = it[v]
            work += list.size
            for (i in 0 until list.size) propEnq(list[i])
        }
        // The literal opposing the pin just became false; the var is assigned, so read the packed bit unboxed.
        val falseLit = Lit.make(v, !boolValueBits.get(v))
        val watchers = watches.byLit[falseLit]
        val blockers = watches.blockersByLit[falseLit]
        work += watchers.size
        for (i in 0 until watchers.size) {
            // A true blocker means the factor is satisfied: skip the wake and the clause dereference.
            val blocker = blockers[i]
            if (blocker != NO_BLOCKER && litTrue(blocker)) continue
            propEnq(watchers[i])
        }
    }

    // [IntEvent.FIXED_BIT] is added for a singleton result, so a mutator passes only the kind it caused.
    internal fun markIntDirty(v: Int, kindMask: Int) {
        dirtyInts.addLast(v)
        if (intEvents.dirtyKinds.isEmpty()) return
        val d = intDomains[v]
        val mask = if (d.min == d.max) kindMask or IntEvent.FIXED_BIT else kindMask
        intEvents.dirtyKinds[v] = intEvents.dirtyKinds[v] or mask
    }

    // The kind mask is cleared after dispatch so it does not leak into a later change to the same variable.
    private fun enqueueForIntChange(v: Int) {
        work += nonIntEventWatcherOcc[v].size
        for (fid in nonIntEventWatcherOcc[v]) propEnq(fid)
        // Mid-life presolve factors waking via occurrence lists; null (no overlay) otherwise.
        midlife.intOccurrences?.let {
            val list = it[v]
            work += list.size
            for (i in 0 until list.size) propEnq(list[i])
        }
        if (intEvents.dirtyKinds.isEmpty()) return
        val mask = intEvents.dirtyKinds[v]
        if (mask == 0) return
        intEvents.dirtyKinds[v] = 0
        var kind = 0
        while (kind < IntEvent.COUNT) {
            if (mask and (1 shl kind) != 0) {
                intEvents.forEachWatcher(IntEvent.pack(v, kind), { work += it }) { fid ->
                    work++
                    propEnq(fid)
                    accumulateDirtyVar(fid, v)
                }
            }
            kind++
        }
    }

    private fun accumulateDirtyVar(fid: Int, v: Int) {
        if (fid >= intEvents.dirtyMark.size) return
        val mark = intEvents.dirtyMark[fid] ?: return
        val vars = intEvents.dirtyVars[fid] ?: return
        if (mark.add(v)) vars.add(v)
    }

    /**
     * Drain the dirty-variable delta for [factorId]: a superset of the variables changed since its last drain,
     * which a [Propagator.consumesIntEventDelta] consumer diffs against its own reversible baseline. Call exactly
     * when that baseline advances. Empty for a non-consumer.
     */
    fun drainIntEventDirtyVars(factorId: Int): IntArray {
        if (intEvents.dirtyVars.isEmpty()) return emptyIntArray()
        val list = intEvents.dirtyVars[factorId] ?: return emptyIntArray()
        if (list.size == 0) return emptyIntArray()
        val out = list.toIntArray()
        list.clear()
        intEvents.dirtyMark[factorId]?.clear()
        return out
    }
}
