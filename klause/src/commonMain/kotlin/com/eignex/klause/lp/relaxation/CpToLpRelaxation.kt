package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.ir.values
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.ExactRelaxationBuilder
import com.eignex.klause.lp.HullFlags
import com.eignex.klause.lp.LinearLpProjection
import com.eignex.klause.lp.LpAuxiliaryColumn
import com.eignex.klause.lp.NumericalLinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.SharedIndexChoices
import com.eignex.klause.lp.SourceRowSlackBuilder
import com.eignex.klause.lp.cut.CircuitArcModel
import com.eignex.klause.lp.cut.CircuitSeparator
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.emitLpRelaxation
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.scaled
import com.eignex.klause.lp.linearSourceSlack
import com.eignex.klause.lp.lpHullEnabled
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBinary
import com.eignex.klause.lp.statesBothBounds
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.subExact
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextUp
import kotlin.time.TimeSource

/**
 * Walks `Problem.factors` and emits an [LpModel] relaxation for the LP-emittable factor types,
 * pulling variable bounds live from the current search node.
 *
 * ## What is encoded
 *  - [Linear]: one row, `LE`/`GE`/`EQ` mapped directly; `NE` is not LP-relaxable and is skipped.
 *  - [Cardinality], [Clause], [PseudoBoolean]: linear rows over the Boolean fan-in. A positive
 *    literal contributes `x_b`, a negative literal `1 − x_b`; the constant folds into the row's
 *    right-hand side.
 *  - [ReifiedLinear]: indicator rows via tight big-M.
 *  - [ArrayMinMax]: the extremum's envelope — `result ≥ xs(i)` for `max`, `result ≤ xs(i)` for
 *    `min`, one row per operand. Sound; the tight face (result equals some operand) is not a
 *    single LP cut, so only the envelope side is emitted.
 *  - The [LinearObjective] (always minimization): a cost on each variable's column. Every variable
 *    with a nonzero objective coefficient gets a column even if no constraint mentions it, so the
 *    LP objective is the complete relaxed objective and its optimum is a valid bound.
 *
 * The column-heavy hulls (Element, Table, NValue, Regular, Mdd, GCC counts, Circuit arcs, the
 * scheduling rows, McCormick products, Boolean RLT) are each gated by a constructor flag.
 *
 * ## What is skipped
 *  Factors without an LP projection (AllDifferent and the other hard globals) contribute nothing; they
 *  are handled by cut generation or Lagrangian relaxation. A missing constraint only loosens the
 *  relaxation, it never makes the bound unsound.
 *
 * ## Live bounds
 *  Integer columns take `[min, max]` from [RelaxationDomains.intDomain]; a Boolean column takes
 *  `[1, 1]` / `[0, 0]` when its variable is already pinned this node, else `[0, 1]`. Pinning a
 *  Boolean column collapses every big-M indicator that mentions it to the exact constraint.
 */
internal class CpToLpRelaxation(
    private val problem: Problem,
    private val objective: LinearObjective?,
    // Constant Element arrays use their joint convex hull, variable arrays bounded big-M result links.
    private val elementHull: Boolean = false,
    // One selector column per allowed tuple: the exact convex hull, O(numTuples) columns.
    private val tableHull: Boolean = false,
    // Energetic makespan row for each Cumulative / Disjunctive whose makespan variable is verified.
    private val cumulative: Boolean = false,
    // Projects each constant-size Diffn onto both axes as a cumulative for the same energetic row; fires
    // only when an axis extent provably bounds every task end.
    private val diffn: Boolean = false,
    // Time-indexed `x_{i,t}` relaxation, O(n·H) columns, so hard-gated on horizon and cell count.
    private val cumulativeTimeIndexed: Boolean = false,
    // One-hot per-value "used" indicators, O(Σ|domain|) columns.
    private val nValueHull: Boolean = false,
    // Layer-expanded DFA flow hull, the exact hull of the accepting strings; O(len·states·alphabet) columns.
    private val regularHull: Boolean = false,
    // Layered flow hull of the accepting paths, so a cost-MDD's cost variable gets an exact lower bound.
    private val mddHull: Boolean = false,
    /** One-hot selector model for each count-variable [GlobalCardinality], an exact count bound;
     *  O(Σ|domain|) columns. */
    private val gccCountHull: Boolean = false,
    // Only the factors connected to the objective through linear/Boolean constraints, without big-M
    // ReifiedLinear rows: for scheduling, the critical-path bound. Forces the column-heavy features off.
    private val objectiveCone: Boolean = false,
    // Only the factors touching LP-only real columns. Propagation already enforces the Boolean/integer
    // structure, so for a feasibility prune clause rows add no strength and only grow the basis.
    private val realResidual: Boolean = false,
    /** Arc-indicator relaxation of each [Circuit]; a non-sub circuit also records a [CircuitArcModel] for
     *  [CircuitSeparator]'s subtour cuts (a subcircuit's cutset structure differs). O(arcs) columns. */
    private val circuitArcs: Boolean = false,
    /** McCormick envelope of each [Product] `result = a·b`; four rows per product. */
    private val productMcCormick: Boolean = false,
    private val booleanRlt: Boolean = false,
    /** Tight face of each [ArrayMinMax] in big-M form: one-hot selectors force `result = xs(i)`, bounding
     *  the extremum from the tight side as well as the envelope side. O(|xs|) columns.
     *  Anderson, Huchette, Ma, Tjandraatmadja and Vielma, "Strong mixed-integer programming
     *  formulations for trained neural networks", Math. Programming 183 (2020),
     *  https://doi.org/10.1007/s10107-020-01474-5. */
    private val linMaxTightFace: Boolean = false,
    // Factors whose HULL rows the root pruner found add no root strength; they keep only CORE rows.
    private val suppressedHullFactors: Set<Int> = emptySet(),
    internal val auxiliarySources: LpAuxiliarySources = LpAuxiliarySources(),
    deferred: Boolean = false,
    private val projections: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
    elementIndexes: Set<Int>? = null,
    private val templateStats: LpStatsSink? = null,
    private val reuseSourceTemplates: Boolean = true,
    private val preparationCancellation: ExecutionControl = ExecutionControl.Never,
    private val reuseTemplateIndexes: Boolean = true,
    private val reuseTemplateMetadata: Boolean = false,
    private val earnTemplatePreparation: Boolean = false,
    private val omitBoundRows: Boolean = false,
    private val onTemplateWork: (Long) -> Unit = {},
) {
    private val elementIndexes = elementIndexes?.toSet()
    private val linearProjection = LinearLpProjection()
    internal var preparationTemplates: LpPreparationTemplates? = null
        private set

    internal fun observeBoundTemplates(count: Int) {
        templateStats?.observeTemplateUse(exactBounds = count)
    }

    internal fun observeTemplateWork(work: Long) {
        onTemplateWork(work)
    }

    private fun prepareTemplates(cancellation: ExecutionControl) {
        if (!reuseSourceTemplates || projections == null) return
        preparationTemplates = null
        val start = TimeSource.Monotonic.markNow()
        var work = 0L
        try {
            preparationTemplates = projections.prepareLpTemplates(objective, cancellation,
                reuseTemplateIndexes, reuseTemplateMetadata, earned = earnTemplatePreparation,
                compileBoundRows = omitBoundRows) {
                work = it
                onTemplateWork(it)
            }
        } finally {
            val templates = preparationTemplates
            templateStats?.observeTemplatePreparation(
                start.elapsedNow().inWholeNanoseconds, work, prepared = templates != null,
                factorsReused = templates?.reusedFactors ?: 0,
                sourceRebound = templates?.reboundSource ?: false,
                indexesReused = templates?.reusedIndexes ?: false,
                boundsReused = templates?.reusedBounds ?: 0,
                componentsReused = templates?.reusedComponents ?: 0,
                componentsDirty = templates?.let { it.components.size - it.reusedComponents } ?: 0,
                retainedUnits = templates?.storageUnits ?: 0L,
            )
        }
        if (cancellation()) throw LpAssemblyCancelled()
    }

    private fun recordConstruction(relaxation: LpRelaxation, cancellation: ExecutionControl) {
        if (!reuseSourceTemplates || !earnTemplatePreparation || projections == null ||
            preparationTemplates != null
        ) return
        if (problem.numRealVars != 0 || !projections.builtInRelaxation) return
        val model = relaxation.model
        if (model.n == 0 || model.doubleView != null || model.exactState != null) return
        val extent = model.n.toLong() + model.m + model.csc.rowIdx.size
        var work = 0L
        val started = TimeSource.Monotonic.markNow()
        var recorded = false
        try {
            recorded = projections.recordLpConstruction(extent, cancellation) {
                work += it
                onTemplateWork(it)
            }
        } finally {
            templateStats?.observeTemplateFunding(started.elapsedNow().inWholeNanoseconds, work, extent, recorded)
        }
        if (cancellation()) throw LpAssemblyCancelled()
    }

    // Null when disabled or no plan applies.
    private var cumulativeRelaxation: CumulativeRelaxation? = null
    private val builtInFamilySource: Problem by lazy {
        if (projections?.builtInRelaxation != false) problem else problem.withFactors(
            problem.factors.filterIndexed { id, _ -> projections.usesBuiltInRelaxation(id) }.toTypedArray(),
        )
    }

    // Row-side half of LpRelaxation.persistentEligible: no row's coefficients depend on live domains. The
    // objective cone, energetic rows and live-M reified rows are excluded. Auxiliary-column hulls stay
    // candidates: their rows are fixed and the live restriction rides on column bounds the presence rule
    // re-binds; an aux column without a presence rule keeps the relaxation off the persistent path.
    private var structurallyPersistent: Boolean = false

    val persistentLayoutCandidate: Boolean get() = structurallyPersistent && problem.numRealVars == 0

    init {
        projections?.requireSource(problem)
        if (!deferred) preparation().forEach { }
    }

    fun preparation(): Iterator<Unit> = sequence {
        if (cumulative || diffn) {
            val owner = CumulativeRelaxation(
                builtInFamilySource, includeCumulative = cumulative, includeDiffn = diffn, deferred = true,
            )
            owner.preparation().forEach { yield(Unit) }
            cumulativeRelaxation = owner.takeIf { it.applicable }
        }
        structurallyPersistent = !objectiveCone && !cumulative && !diffn && projections?.builtInRelaxation != false
        if (structurallyPersistent) for (fid in problem.factors.indices) {
            val factor = problem.factors[fid]
            if (factor !is Linear && hasLiveDomainCoefficients(factor)) {
                structurallyPersistent = false
                break
            }
            if ((fid + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        }
    }.iterator()

    private fun hasLiveDomainCoefficients(factor: Factor): Boolean =
        factor is ReifiedLinear || factor is ReifiedPseudoBoolean || factor is ReifiedCardinality ||
            factor is ReifiedRealLinear || factor is RealElement || factor is LinearIndicator

    private val cone: FactorConeProjection? by lazy {
        if (objectiveCone) FactorConeProjection(problem, objective) else null
    }

    // Per-hull dense-tableau caps, shared with LpAutoConfig's size guard so it estimates each enabled hull
    // against the same thresholds the builders skip at.
    internal companion object {
        // Gating on candidate arcs (LP columns) rather than nodes lets large sparse routing graphs through.
        const val MAX_CIRCUIT_ARCS: Int = 1024

        // Widest knapsack row Boolean RLT multiplies out; each adds O(width) product columns.
        const val MAX_RLT_ROW: Int = 8

        const val MAX_RLT_COLUMNS: Int = 256

        // Latest deadline − earliest start.
        const val MAX_TI_HORIZON: Int = 512

        // `x_{i,t}` columns per time-indexed Cumulative.
        const val MAX_TI_COLS: Int = 4096
    }

    fun build(
        session: PropagationSession,
        extraCuts: List<Cut> = emptyList(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): LpRelaxation = build(SessionDomains(session), extraCuts, cancellation)

    // With RootDomains this builds a root relaxation without running the bake fixpoint.
    fun build(
        domains: RelaxationDomains,
        extraCuts: List<Cut> = emptyList(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): LpRelaxation = build(domains, extraCuts, cancellation, null)

    fun build(
        domains: RelaxationDomains,
        extraCuts: List<Cut>,
        cancellation: ExecutionControl,
        onDiscardedAssembly: ((Long) -> Unit)?,
    ): LpRelaxation {
        val token = cancellation or preparationCancellation
        prepareTemplates(token)
        val assembler = Assembler(domains, cancellation = token)
        var complete = false
        return try {
            assembler.assemble(extraCuts).also {
                recordConstruction(it, token)
                complete = true
            }
        } finally {
            if (!complete) onDiscardedAssembly?.invoke(assembler.constructionSize)
        }
    }

    fun prepareBuild(
        domains: RelaxationDomains,
        cancellation: ExecutionControl,
        onWork: (Long) -> Unit,
        onComplete: (LpRelaxation) -> Unit,
    ): Iterator<Unit> = sequence {
        // The caller supplies a containing lifetime because this builder spans invocations.
        prepareTemplates(cancellation)
        Assembler(domains, cancellation = cancellation).preparation(emptyList(), onWork) { relaxation ->
            recordConstruction(relaxation, cancellation)
            onComplete(relaxation)
        }
            .forEach { yield(Unit) }
    }.iterator()

    private val timeIndexedViews by lazy {
        if (cumulativeTimeIndexed && !objectiveCone) schedulingViews(builtInFamilySource) else emptyList()
    }

    val emissionRegions: List<LpEmissionRegion> by lazy {
        buildList {
            add(LpEmissionRegion(LpEmissionKind.OBJECTIVE))
            if (!objectiveCone) {
                if (circuitArcs) {
                    for (id in problem.factors.indices) {
                        if (problem.factors[id] is Circuit && projections?.usesBuiltInRelaxation(id) != false) {
                            add(LpEmissionRegion(LpEmissionKind.CIRCUIT, id))
                        }
                    }
                }
                cumulativeRelaxation?.plans?.indices?.forEach {
                    add(LpEmissionRegion(LpEmissionKind.ENERGETIC, it))
                }
                timeIndexedViews.indices.forEach { add(LpEmissionRegion(LpEmissionKind.TIME_INDEXED, it)) }
            }
            val coneL = cone
            for (id in problem.factors.indices) {
                val factor = problem.factors[id]
                if (coneL != null && !coneL.contains(id)) continue
                if (realResidual && !touchesReals(factor)) continue
                add(LpEmissionRegion(LpEmissionKind.FACTOR, id))
            }
            if (booleanRlt) add(LpEmissionRegion(LpEmissionKind.BOOLEAN_RLT))
        }
    }

    fun emitRegion(
        region: LpEmissionRegion,
        domains: RelaxationDomains,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): LpEmission = emitRegion(region, domains, cancellation, null)

    fun emitRegion(
        region: LpEmissionRegion,
        domains: RelaxationDomains,
        cancellation: ExecutionControl,
        onWork: ((Long) -> Unit)?,
    ): LpEmission {
        if (preparationTemplates == null) prepareTemplates(cancellation or preparationCancellation)
        val tracked = LpEmissionDomains(domains)
        val assembler = Assembler(
            tracked,
            cancellation = cancellation,
            region = region,
            columnDomains = RootDomains(problem),
        )
        var completed: LpRelaxation? = null
        return try {
            val relaxation = assembler.assemble(emptyList()).also { completed = it }
            LpEmission(region, relaxation, tracked.dependencies())
        } finally {
            val model = completed?.model
            val work = if (model == null) assembler.constructionSize else {
                model.n.toLong() + model.m + (model.doubleView?.colVal?.size ?: model.csc.rowIdx.size)
            }
            onWork?.invoke(work)
        }
    }

    // The float filter's persistent gated residual model, built from root boxes with every reified row
    // emitted regardless of pins. Only a pure-real residual qualifies: an integer column's live lower bound
    // would fold a changing constant into the rhs and break the rhs-only rebind. Exact certificates keep
    // running on the per-node build.
    fun buildGatedResidual(cancellation: ExecutionControl = ExecutionControl.Never): LpRelaxation? {
        if (!realResidual || problem.numIntVars > 0 || problem.factors.any { it is ContinuousProduct }) return null
        return Assembler(RootDomains(problem), gated = true, cancellation = cancellation).assemble(emptyList())
    }

    private fun intCost(i: Int): Long = objective?.intCoefficientValues?.getOrElse(i) { 0L } ?: 0L

    private fun boolCost(b: Int): Long = objective?.boolWeightValues?.getOrElse(b) { 0L } ?: 0L

    private fun realCost(r: Int): Double = objective?.realCoefficientValues?.getOrElse(r) { 0.0 } ?: 0.0

    private fun touchesReals(factor: Factor): Boolean = factor.variables.reals.isNotEmpty()

    /** Per-build state; the [RelaxationBuilder] each factor projection emits into. */
    private inner class Assembler(
        private val domains: RelaxationDomains,
        private val gated: Boolean = false,
        private val cancellation: ExecutionControl = ExecutionControl.Never,
        private val region: LpEmissionRegion? = null,
        private val columnDomains: RelaxationDomains = domains,
    ) : ExactRelaxationBuilder, SharedIndexChoices, LpBoundRowBuilder, SourceRowSlackBuilder {
        private val builder = LpBuilder()
        val constructionSize: Long get() = builder.constructionSize
        private val exactRows = ExactRelaxationRows()
        private var templateRows = 0
        private var templateBounds = 0
        private val columnBounds = if (omitBoundRows && preparationTemplates?.hasBoundConditions == true &&
            region == null && !gated
        ) {
            val slots = problem.numIntVars.toLong() * 2L
            try {
                cancellation.charge(slots)
                onTemplateWork(slots)
                checkCancellation()
                LongArray(problem.numIntVars) to LongArray(problem.numIntVars)
            } finally {
                templateStats?.observeTemplateBoundStorageWork(slots)
            }
        } else null
        private val intCol = IntArray(if (region == null) problem.numIntVars else 0) { -1 }
        private val boolCol = IntArray(if (region == null) problem.numBoolVars else 0) { -1 }
        private val realCol = IntArray(if (region == null) problem.numRealVars else 0) { -1 }
        private val sparseIntCol = MutableIntIntMap()
        private val sparseBoolCol = MutableIntIntMap()
        private val sparseRealCol = MutableIntIntMap()
        private val colVarId = IntArrayList()
        private val colIsBool = IntArrayList() // 0 = int, 1 = bool; densified at the end

        // LP column -> real var id it stands for, or -1 for int/bool/aux columns. Lets the leaf verdict
        // read each LP-only continuous column's value back onto its real variable.
        private val colRealId = IntArrayList()
        private val colRealSign = IntArrayList()

        // Primary column -> negative-part column of a split lower-unbounded real variable.
        private val realNegOf = MutableIntIntMap()
        private val realUpperRows = HashMap<Int, Int>()

        // Persistent re-binding rule for an auxiliary column: colReq(c) holds flat (intVar, value) membership
        // pairs and colPresentUpper(c) the upper bound while all hold, else the column is pinned to 0.
        // null/0 for CP-var columns and for aux columns without a rule, which keep the relaxation off the
        // persistent path.
        private val colReq = ArrayList<LongArray?>()
        private val colPresence = ArrayList<CutAuxiliaryDefinition?>()
        private val colPresentUpper = LongArrayList()
        private val indexChoices = HashMap<Pair<Int, Long>, Int>()
        private val indexChoiceBlocks = HashSet<Pair<Int, List<Long>>>()

        override fun indexChoiceBlock(index: Int, values: LongArray): Boolean =
            indexChoiceBlocks.add(index to values.toList())

        override fun indexChoiceColumn(index: Int, value: Long): Int = indexChoices.getOrPut(index to value) {
            // Negative producer keys reserve source-level choices outside factor-local namespaces.
            addAuxColumn(
                0L, if (value in liveDomain(index)) 1L else 0L,
                presence = longArrayOf(index.toLong(), value),
                definition = LpAuxiliaryColumn(listOf(value), 1L, true),
                sourceRole = listOf(-1L, index.toLong(), value),
            )
        }

        private val circuitModels = ArrayList<CircuitArcModel>()

        // Whether the current factor may emit HULL rows; a disabled family contributes only CORE rows.
        private var currentHullEnabled = true

        private var currentFactorId = -1
        private var currentEmissionRoute = 0L

        // The factor behind each row emitted so far; see LpRelaxation.rowFactorIds.
        private val rowFactor = IntArrayList()

        private fun attributeRows(factorId: Int) {
            while (rowFactor.size < builder.rowCount) rowFactor.add(factorId)
        }

        // Factors that emitted a HULL row, in factor order: the root pruner's candidates.
        private val hullFactorIds = LinkedHashSet<Int>()

        // Read by each hull factor's lpHullEnabled, so the driver never matches factor types.
        private val hullFlags = HullFlags(
            element = elementHull,
            table = tableHull,
            nValue = nValueHull,
            regular = regularHull,
            mdd = mddHull,
            gccCount = gccCountHull,
            arrayMinMax = linMaxTightFace,
            product = productMcCormick,
        )

        // presence names the (intVar, value) memberships that must all hold for the column to reach hi; with
        // it the column can be re-bound on the persistent path.
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?, definition: LpAuxiliaryColumn?): Int =
            addAuxColumn(lo, hi, presence, definition)

        private fun addAuxColumn(
            lo: Long,
            hi: Long,
            presence: LongArray?,
            definition: LpAuxiliaryColumn?,
            sourceRole: List<Long>? = null,
        ): Int {
            checkCancellation()
            if (definition != null) {
                require(
                    presence != null && lo == 0L && hi in 0L..definition.presentUpper &&
                        currentFactorId >= 0 && presence.size % 2 == 0 &&
                        presence.indices.step(2).all { presence[it] in 0L until problem.numIntVars.toLong() },
                )
            }

            val c = builder.addVar(lo, hi, cost = 0L, tag = -1)
            colVarId.add(-1)
            colIsBool.add(0)
            colRealId.add(-1)
            colRealSign.add(1)
            colReq.add(presence?.copyOf())
            colPresentUpper.add(definition?.presentUpper ?: hi)
            colPresence.add(
                definition?.let {
                    CutAuxiliaryDefinition(
                        sourceRole ?: (listOf(currentFactorId.toLong(), currentEmissionRoute) + it.role),
                        requireNotNull(presence).toList(),
                        it.presentUpper,
                        it.integralExtension,
                    )
                },
            )
            return c
        }

        // Integer solutions of the degree and channel rows are permutations; the subtour separator removes
        // subtours. The column layout uses the root box so it is identical across nodes.
        private fun buildCircuitArcs(factor: Circuit) = buildArcModel(factor.succ, selfLoops = false, sec = true)

        // The self-loop y(i,i) means node i is excluded. No subtour model is registered: the Hamiltonian
        // SEC is unsound for a subcircuit, where an all-excluded subset has no leaving arc.
        private fun buildSubcircuitArcs(factor: Circuit) = buildArcModel(factor.succ, selfLoops = true, sec = false)

        private fun buildArcModel(succ: IntArray, selfLoops: Boolean, sec: Boolean) {
            val n = succ.size
            if (n < 2) return
            // The layout enumerates each successor's root box, and the degree rows then confine the
            // column to the heads enumerated. Over a side the model left open that box is a search
            // restriction, so the rows would refute successors the model admits — decline instead.
            if (!problem.statesBothBounds(succ)) return
            val arcCount = candidateArcCount(succ, selfLoops, MAX_CIRCUIT_ARCS, ::rootDomain)
            if (arcCount == 0 || arcCount > MAX_CIRCUIT_ARCS) return
            val tails = IntArrayList()
            val heads = IntArrayList()
            val cols = IntArrayList()
            val inColsByHead = Array(n) { IntArrayList() }
            for (i in 0 until n) {
                val live = columnDomains.intDomain(succ[i])
                val outCols = IntArrayList()
                val chanCols = IntArrayList()
                val chanCoef = IntArrayList()
                eachCandidateArcHead(rootDomain(succ[i]), i, n, selfLoops) { jn ->
                    val present = live.contains(jn.toLong())
                    // Present exactly while head j stays in succ(i)'s live domain, so the persistent
                    // relaxation can re-bind the column.
                    val col = auxColumn(
                        0L,
                        if (present) 1L else 0L,
                        presence = longArrayOf(succ[i].toLong(), jn.toLong()),
                        definition = LpAuxiliaryColumn(listOf(i.toLong(), jn.toLong()), 1L, true),
                    )
                    outCols.add(col)
                    chanCols.add(col)
                    chanCoef.add(jn)
                    tails.add(i)
                    heads.add(jn)
                    cols.add(col)
                    inColsByHead[jn].add(col)
                }
                if (outCols.isEmpty()) return // degenerate: no candidate successor — leave to propagation
                builder.addRow(outCols.toIntArray(), LongArray(outCols.size) { 1L }, Relation.EQ, 1L)
                // Σ_j j·y_ij − succ(i) = 0.
                val cCols = IntArray(chanCols.size + 1)
                val cVals = LongArray(chanCols.size + 1)
                for (k in 0 until chanCols.size) {
                    cCols[k] = chanCols[k]
                    cVals[k] = chanCoef[k].toLong()
                }
                cCols[chanCols.size] = intColumn(succ[i])
                cVals[chanCols.size] = -1L
                builder.addRow(cCols, cVals, Relation.EQ, 0L)
            }
            // In-degree: Σ_i y_ij = 1 for each node j that is some arc's head.
            for (j in 0 until n) {
                val inCols = inColsByHead[j]
                if (!inCols.isEmpty()) {
                    builder.addRow(inCols.toIntArray(), LongArray(inCols.size) { 1L }, Relation.EQ, 1L)
                }
            }
            if (sec) circuitModels.add(CircuitArcModel(n, tails.toIntArray(), heads.toIntArray(), cols.toIntArray()))
        }

        // A side the front-end could not bound is an artefact of the search box, so where domains honor open
        // sides the column spans its true range and Farkas refutations or OBBT bounds hold over the
        // unbounded model. Only the upper side opens: a lower-open column would need realColumn's
        // x⁺ − x⁻ split, since a −2⁶² shift folds into rhs doubles whose spacing swallows ordinary
        // right-hand sides. A closed side only forgoes global validity, never soundness.
        override fun intColumn(intVar: Int): Int {
            checkCancellation()
            var c = if (region == null) intCol[intVar] else sparseIntCol.getOrDefault(intVar, -1)
            if (c == -1) {
                val dom = columnDomains.intDomain(intVar)
                val intBounds = problem.intBounds
                val openHi = columnDomains.honorsOpenSides && !intBounds.hasUpper(intVar) && intBounds.hasLower(intVar)
                val template = preparationTemplates?.bound(intVar)?.takeIf {
                    it.lower == dom.min && it.upper == dom.max &&
                        it.statedLower == intBounds.hasLower(intVar) && it.statedUpper == intBounds.hasUpper(intVar)
                }
                if (template != null) templateBounds++
                val lower = if (openHi) intBounds.lower(intVar) else template?.lower ?: dom.min
                val upper = template?.upper ?: dom.max
                columnBounds?.let {
                    it.first[intVar] = lower
                    it.second[intVar] = upper
                }
                c = if (openHi) {
                    builder.addOpenAboveVar(lower, intCost(intVar), tag = intVar)
                } else {
                    builder.addVar(lower, upper, intCost(intVar), tag = intVar)
                }
                if (region == null) intCol[intVar] = c else sparseIntCol.put(intVar, c)
                colVarId.add(intVar)
                colIsBool.add(0)
                colRealId.add(-1)
                colRealSign.add(1)
                colReq.add(null)
                colPresence.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        // No CP variable backs the column, so persistent rebinding and reduced-cost fixing skip it. Finite
        // lower bounds become column shifts; a lower-open variable splits as x = x⁺ − x⁻ with mirrored
        // coefficients and costs and a finite upper bound as the row x⁺ − x⁻ ≤ hi. The probe stand-in is
        // only a box bound: as a shift it would erase small right-hand sides through cancellation.
        override fun realColumn(realVar: Int): Int {
            var c = if (region == null) realCol[realVar] else sparseRealCol.getOrDefault(realVar, -1)
            if (c == -1) {
                val lo = problem.realLower[realVar]
                val hi = problem.realUpper[realVar]
                if (lo.isFinite()) {
                    c = builder.addRealVar(
                        lower = lo,
                        upper = if (hi.isFinite()) hi else null,
                        cost = realCost(realVar),
                    )
                    registerRealCol(realVar, sign = 1)
                } else {
                    c = builder.addRealVar(lower = 0.0, upper = null, cost = realCost(realVar))
                    registerRealCol(realVar, sign = 1)
                    val neg = builder.addRealVar(lower = 0.0, upper = null, cost = -realCost(realVar))
                    registerRealCol(realVar, sign = -1)
                    realNegOf.put(c, neg)
                    if (hi.isFinite()) {
                        realUpperRows[builder.rowCount] = realVar
                        builder.addRealRow(intArrayOf(c, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
                    }
                }
                if (region == null) realCol[realVar] = c else sparseRealCol.put(realVar, c)
            }
            return c
        }

        private fun registerRealCol(realVar: Int, sign: Int) {
            colVarId.add(-1)
            colIsBool.add(0)
            colRealId.add(realVar)
            colRealSign.add(sign)
            colReq.add(null)
            colPresence.add(null)
            colPresentUpper.add(0L)
        }

        override fun boolColumn(boolVar: Int): Int {
            checkCancellation()
            var c = if (region == null) boolCol[boolVar] else sparseBoolCol.getOrDefault(boolVar, -1)
            if (c == -1) {
                val pinned = columnDomains.boolValue(boolVar)
                val lo = if (pinned == true) 1L else 0L
                val hi = if (pinned == false) 0L else 1L
                c = builder.addVar(lo, hi, boolCost(boolVar), tag = boolVar)
                if (region == null) boolCol[boolVar] = c else sparseBoolCol.put(boolVar, c)
                colVarId.add(boolVar)
                colIsBool.add(1)
                colRealId.add(-1)
                colRealSign.add(1)
                colReq.add(null)
                colPresence.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        // auxCol = -1 means no aux term. Only rows whose constants baked in live bounds pass global = false.
        @Suppress("LongParameterList")
        private fun addIntRow(
            vars: IntArray,
            coeffs: LongArray,
            auxCol: Int,
            auxCoeff: Long,
            rel: Relation,
            rhs: Long,
            global: Boolean = true,
            premises: LpRowPremises? = null,
        ) {
            val extra = if (auxCol >= 0) 1 else 0
            val cols = IntArray(vars.size + extra)
            val vals = LongArray(vars.size + extra)
            for (k in vars.indices) {
                cols[k] = intColumn(vars[k])
                vals[k] = coeffs[k]
            }
            if (auxCol >= 0) {
                cols[vars.size] = auxCol
                vals[vars.size] = auxCoeff
            }
            builder.addRow(cols, vals, rel, rhs, global, premises)
        }

        // A negative literal counts w·(1 − x_b); its constant w moves to the rhs. Null weights are unit.
        private fun addBoolRow(literals: IntArray, weights: LongArray?, rel: Relation, rhs: Long) {
            val cols = IntArray(literals.size)
            val vals = LongArray(literals.size)
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = weights?.get(k) ?: 1L
                cols[k] = boolColumn(Lit.variable(lit))
                if (Lit.isPositive(lit)) {
                    vals[k] = w
                } else {
                    vals[k] = -w
                    constant = addExact(constant, w)
                }
            }
            builder.addRow(cols, vals, rel, subExact(rhs, constant))
        }

        private fun checkCancellation() {
            if (cancellation()) throw LpAssemblyCancelled()
        }

        fun assemble(extraCuts: List<Cut>): LpRelaxation {
            var result: LpRelaxation? = null
            preparation(extraCuts, {}, { result = it }).forEach { }
            return checkNotNull(result)
        }

        fun preparation(
            extraCuts: List<Cut>,
            onWork: (Long) -> Unit,
            onComplete: (LpRelaxation) -> Unit,
        ): Iterator<Unit> = sequence {
            var charged = 0L
            fun charge() {
                val size = constructionSize
                if (size > charged) onWork(size - charged)
                charged = size
            }
            try {
                val result = assembleBody(extraCuts, ::charge)
                charge()
                onComplete(result)
            } finally {
                charge()
            }
        }.iterator()

        private suspend fun SequenceScope<Unit>.assembleBody(
            extraCuts: List<Cut>,
            checkpoint: () -> Unit,
        ): LpRelaxation {
            checkCancellation()
            // Materialize objective-only variables, real columns included, so the relaxed objective is
            // complete and its optimum a valid bound.
            objective?.takeIf { emits(LpEmissionKind.OBJECTIVE) }?.let { obj ->
                for (i in obj.intCoefficientValues.indices) {
                    if (obj.intCoefficientValues[i] != 0L) intColumn(i)
                    if ((i + 1) % CONSTRUCTION_BATCH_SIZE == 0) {
                        checkpoint()
                        yield(Unit)
                    }
                }
                for (b in obj.boolWeightValues.indices) {
                    if (obj.boolWeightValues[b] != 0L) boolColumn(b)
                    if ((b + 1) % CONSTRUCTION_BATCH_SIZE == 0) {
                        checkpoint()
                        yield(Unit)
                    }
                }
                for (r in obj.realCoefficientValues.indices) {
                    if (obj.realCoefficientValues[r] != 0.0) realColumn(r)
                    if ((r + 1) % CONSTRUCTION_BATCH_SIZE == 0) {
                        checkpoint()
                        yield(Unit)
                    }
                }
            }
            // Per-factor hulls come from the main loop below; only relaxations not owned by one factor live
            // here. Cone mode is the minimal linear+Boolean probe and skips them.
            if (!objectiveCone) {
                if (circuitArcs && emits(LpEmissionKind.CIRCUIT)) {
                    for (factorId in factorIndices()) {
                        val factor = problem.factors[factorId]
                        checkCancellation()
                        currentFactorId = factorId
                        currentEmissionRoute = 1L
                        if (factor is Circuit && projections?.usesBuiltInRelaxation(factorId) != false) {
                            if (factor.subcircuit) buildSubcircuitArcs(factor) else buildCircuitArcs(factor)
                        }
                        attributeRows(factorId)
                        if ((factorId + 1) % CONSTRUCTION_BATCH_SIZE == 0) {
                            checkpoint()
                            yield(Unit)
                        }
                    }
                }
                if (emits(LpEmissionKind.ENERGETIC)) {
                    cumulativeRelaxation?.let {
                        cumulativeRows(if (region == null) it.plans else listOf(it.plans[region.index]))
                    }
                }
                if (cumulativeTimeIndexed && emits(LpEmissionKind.TIME_INDEXED)) {
                    val views = if (region == null) timeIndexedViews else listOf(timeIndexedViews[region.index])
                    for (view in views) buildCumulativeTimeIndexed(view)
                }
                attributeRows(-1)
            }

            val coneL = cone
            for (factorId in if (emits(LpEmissionKind.FACTOR)) factorIndices() else emptyList()) {
                val factor = problem.factors[factorId]
                checkCancellation()
                if (factorId > 0 && factorId % CONSTRUCTION_BATCH_SIZE == 0) {
                    checkpoint()
                    yield(Unit)
                }
                if (coneL != null && !coneL.contains(factorId)) continue
                if (realResidual && !touchesReals(factor)) continue
                currentFactorId = factorId
                currentEmissionRoute = 0L
                if (gated && factor is ReifiedRealLinear && projections?.usesBuiltInRelaxation(factorId) != false) {
                    emitGatedReified(factor)
                    attributeRows(factorId)
                    continue
                }
                // Factors without an LP projection contribute nothing here; separators and the blocks
                // above handle them.
                currentHullEnabled = factorId !in suppressedHullFactors && !objectiveCone &&
                    factor.lpHullEnabled(hullFlags, elementIndexes)
                val omitted = preparationTemplates?.emit(problem, factorId, this@Assembler, linearProjection) ?: -1
                if (omitted >= 0) {
                    templateRows += (preparationTemplates?.factors?.get(factorId)?.rows?.size ?: 0) - omitted
                    if (omitted > 0) templateStats?.observeTemplateUse(rowsOmitted = omitted)
                    attributeRows(factorId)
                    continue
                } else if (projections == null) {
                    factor.emitLpRelaxation(this@Assembler, linearProjection)
                } else {
                    projections.emitRelaxation(factorId, this@Assembler, linearProjection)
                }
                attributeRows(factorId)
            }

            if (booleanRlt && emits(LpEmissionKind.BOOLEAN_RLT)) buildBooleanRlt()
            attributeRows(-1)

            val kinds = BooleanArray(colIsBool.size) { colIsBool[it] == 1 }
            val colVarIds = IntArray(colVarId.size) { colVarId[it] }
            val reals = IntArray(colRealId.size) { colRealId[it] }
            val signs = IntArray(colRealSign.size) { colRealSign[it] }
            val presence = colPresence.toList()
            val cutSources = if (region == null && extraCuts.any { it.provenance?.conclusion != null }) {
                cpCutSources(problem, colVarIds, kinds, reals, signs, emptyMap(), presence, auxiliarySources, domains) {
                    builder.sourceBounds(it) to BigFraction.ZERO
                }
            } else {
                null
            }
            val cutParents = HashMap<Int, CutProvenance>()
            for (candidate in extraCuts) {
                val proof = candidate.provenance
                val conclusion = proof?.conclusion
                val cut = if (conclusion != null) {
                    val sources = cutSources ?: continue
                    if (candidate.global != proof.global) continue
                    val active = if (!proof.global && cutProofApplies(proof, problem, domains)) {
                        sources.withActivePremises(proof.facts.filter { !it.global }.mapTo(HashSet()) { it.premise })
                    } else {
                        sources
                    }
                    SourceCut(conclusion.expression, conclusion.relation, conclusion.rhs, proof, candidate.family)
                        .toCut(active).orNull()
                        ?: continue
                } else {
                    if (proof?.auxiliaryDefinitions?.any { (source, definition) ->
                            !auxiliarySources.matches(source, definition) || definition !in colPresence
                        } == true || proof?.let { !cutProofApplies(it, problem, domains) } == true
                    ) {
                        continue
                    }
                    candidate
                }
                if (cut.cols.all { it in 0 until builder.varCount }) {
                    val index = builder.rowCount
                    cut.provenance?.let { cutParents[index] = it }
                    builder.addRow(cut.cols, cut.coeffs, cut.rel, cut.rhs, cut.global)
                    cut.sourceRowSlack(problem)?.let { carryRowSlack(index, it) }
                }
            }

            attributeRows(-1)
            checkpoint()
            yield(Unit)
            checkCancellation()
            val built = builder.build(Sense.MINIMIZE)
            val model = exactRows.project(built, cancellation) ?: throw LpAssemblyCancelled()
            val reqs = colReq.toTypedArray()
            val presentUpper = LongArray(colPresentUpper.size) { colPresentUpper[it] }
            // Persistent re-binding needs every column re-derivable from the live session: CP-var-backed or
            // an aux column with a presence rule. Global cuts are fixed rows over existing columns.
            val eligible = structurallyPersistent && model.n > 0 && extraCuts.all { it.global } &&
                colVarIds.indices.all { colVarIds[it] >= 0 || reqs[it] != null }
            return LpRelaxation(
                model = model,
                colVarId = colVarIds,
                colIsBool = kinds,
                objectiveConstant = objective?.constant ?: 0L,
                intColOf = intCol.copyOf(),
                boolColOf = boolCol.copyOf(),
                circuitArcs = circuitModels,
                persistentEligible = eligible,
                colReq = reqs,
                colPresence = presence,
                colPresentUpper = presentUpper,
                hullFactorIds = hullFactorIds.toIntArray(),
                rowFactorIds = rowFactor.toIntArray(),
                colRealId = reals,
                colRealSign = signs,
                sourceMap = cutSources?.withParentRows(cutParents) ?: if (region == null) cpCutSources(
                    model,
                    problem,
                    colVarIds,
                    kinds,
                    reals,
                    signs,
                    cutParents,
                    presence,
                    auxiliarySources,
                    domains,
                ) else null,
                gatedRows = gatedRowList.toIntArray(),
                gatedAux = gatedAuxList.toIntArray(),
                gatedWhenTrue = BooleanArray(gatedWhenTrueList.size) { gatedWhenTrueList[it] == 1 },
                realUpperRows = realUpperRows.toMap(),
                preparationTemplates = preparationTemplates,
            ).also { templateStats?.observeTemplateUse(templateRows, templateBounds) }
        }

        private fun emits(kind: LpEmissionKind): Boolean = region == null || region.kind == kind

        private fun factorIndices(): Iterable<Int> = region?.let { listOf(it.index) } ?: problem.factors.indices

        // Parallel (row, aux, whenTrue) entries of the gated residual, read by gatedEnforcement.
        private val gatedRowList = IntArrayList()
        private val gatedAuxList = IntArrayList()
        private val gatedWhenTrueList = IntArrayList()

        // Emits both the atom row and its complement so the row set is pin-independent; a node only marks
        // which of the pair its pins enforce. Premises stay the activating literal, so certificates cite
        // exactly as on the per-node build.
        private fun emitGatedReified(f: ReifiedRealLinear) {
            val cols = IntArray(f.vars.size + f.realVars.size)
            val coeffs = DoubleArray(cols.size)
            for (i in f.vars.indices) {
                cols[i] = intColumn(f.vars[i])
                coeffs[i] = f.intCoeffs[i]
            }
            for (j in f.realVars.indices) {
                val c = realColumn(f.realVars[j])
                if (c < 0) return // builder has no real-column backing (e.g. a presolve fake)
                cols[f.vars.size + j] = c
                coeffs[f.vars.size + j] = f.realCoeffs[j]
            }
            // Columns (and any lazy split-pair rows) are allocated, so each realRow below lands as exactly
            // the row index recorded before it.
            gatedRowList.add(builder.rowCount)
            gatedAuxList.add(f.aux)
            gatedWhenTrueList.add(1)
            val premise = intArrayOf(Lit.make(f.aux, true))
            val slack = if (f.strict) null else sourceRowSlack(f.linearRows.single())
            if (slack == null) realRow(cols, coeffs, f.op, f.bound, f.strict, premise)
            else derivedRealRow(cols, coeffs, f.op, f.bound, slack, premise)
            gatedRowList.add(builder.rowCount)
            gatedAuxList.add(f.aux)
            gatedWhenTrueList.add(0)
            val flipped = if (f.op == LinearOp.LE) LinearOp.GE else LinearOp.LE
            realRow(cols, coeffs, flipped, f.bound, !f.strict, intArrayOf(Lit.make(f.aux, false)))
        }

        // Boolean RLT: multiplying a 0/1 knapsack row Σₖ aₖ·xₖ ≤ b (aₖ > 0) by xᵢ ≥ 0 gives the valid
        // Σₖ aₖ·wₖᵢ ≤ b·xᵢ, each product wₖᵢ = xₖ·xᵢ an auxiliary column under the binary McCormick
        // envelope. Every integer solution satisfies the added rows, so the LP only tightens.
        // Sherali and Adams, "A hierarchy of relaxations between the continuous and convex hull
        // representations for zero-one programming problems", SIAM J. Discrete Math. 3 (1990),
        // https://doi.org/10.1137/0403036.
        private fun buildBooleanRlt() {
            var rltColumns = 0
            for ((factorId, f) in problem.factors.withIndex()) {
                if (projections?.usesBuiltInRelaxation(factorId) == false) continue
                currentFactorId = factorId
                currentEmissionRoute = 2L
                if (rltColumns >= MAX_RLT_COLUMNS) break
                if (f !is Linear || f.op != LinearOp.LE) continue
                val row = f.integerConstants ?: continue
                if (f.vars.size < 2 || f.vars.size > MAX_RLT_ROW) continue
                if (row.bound < 0 || f.vars.indices.any { row.coeff(it) <= 0 }) continue
                if (f.vars.any { !problem.statesBinary(it) }) continue
                val sourceSlack = sourceRowSlack(f, includeAbsolute = true)
                if (sourceSlack != null && !hasBinaryDeclarations(f.vars)) continue
                val b = row.bound
                for (iIdx in f.vars.indices) {
                    if (rltColumns >= MAX_RLT_COLUMNS) break
                    val xi = f.vars[iIdx]
                    val xiCol = intColumn(xi)
                    val rltRow = MutableIntLongMap() // coalesces the wᵢᵢ = xᵢ term with the −b·xᵢ term
                    for (kIdx in f.vars.indices) {
                        val xk = f.vars[kIdx]
                        val a = row.coeff(kIdx)
                        val wCol = if (kIdx == iIdx) {
                            xiCol // wᵢᵢ = xᵢ²= xᵢ
                        } else {
                            val xkCol = intColumn(xk)
                            val present = columnDomains.intDomain(xk).contains(1L) &&
                                columnDomains.intDomain(xi).contains(1L)
                            val w = auxColumn(
                                0L,
                                if (present) 1L else 0L,
                                presence = longArrayOf(xk.toLong(), 1L, xi.toLong(), 1L),
                                definition = LpAuxiliaryColumn(listOf(kIdx.toLong(), iIdx.toLong()), 1L, true),
                            )
                            rltColumns++
                            builder.addRow(intArrayOf(w, xkCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xₖ
                            builder.addRow(intArrayOf(w, xiCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xᵢ
                            builder.addRow(intArrayOf(w, xkCol, xiCol), longArrayOf(1L, -1L, -1L), Relation.GE, -1L)
                            w
                        }
                        rltRow.addTo(wCol, a)
                    }
                    rltRow.addTo(xiCol, -b) // Σ aₖwₖᵢ ≤ b·xᵢ
                    val colsList = IntArrayList()
                    rltRow.forEach { k, _ -> colsList.add(k) }
                    val cols = colsList.toIntArray()
                    val index = builder.rowCount
                    builder.addRow(cols, LongArray(cols.size) { rltRow.getOrDefault(cols[it], 0L) }, Relation.LE, 0L)
                    sourceSlack?.let { carryRowSlack(index, rltRowSlack(f, iIdx, it)) }
                }
            }
        }

        private fun hasBinaryDeclarations(variables: IntArray): Boolean = variables.all {
            problem.intBounds.lower(it) == 0L && problem.intBounds.upper(it) == 1L
        }

        private fun rltRowSlack(factor: Linear, selected: Int, source: LpRowSlack): LpRowSlack {
            val row = requireNotNull(factor.integerConstants)
            var negativeWeight = maxOf(0L, row.bound - row.coeff(selected)).toDouble().nextUp()
            for (index in factor.vars.indices) {
                if (index != selected) negativeWeight = (negativeWeight + row.coeff(index).toDouble().nextUp()).nextUp()
            }
            // The extension max(0, min(x(i), x(k))) admits declaration crossings below zero.
            return LpRowSlack(DoubleArray(source.size + 1) {
                if (it == source.size) negativeWeight else source.multiplier(it)
            }, DoubleArray(source.size + 1) {
                if (it == source.size) 0.0 else source.magnitude(it)
            })
        }

        // capacity·M ≥ rhs per plan, rhs recomputed from the live earliest starts so the row tightens as
        // branching raises starts; non-global rows carry those starts as premises. One row per plan keeps
        // the row count structural.
        private fun cumulativeRows(plans: List<CumulativeRelaxation.AreaPlan>) {
            for (plan in plans) {
                val spec = requireNotNull(cumulativeRelaxation).rowSpec(plan, domains)
                builder.addRow(
                    intArrayOf(intColumn(plan.makespanVar)),
                    longArrayOf(plan.capacity),
                    Relation.GE,
                    spec.rhs,
                    spec.global,
                    spec.premises,
                )
            }
        }

        private fun relationOf(op: LinearOp): Relation? = when (op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            LinearOp.NE -> null // not LP-relaxable
        }

        // A HULL row that passes marks its factor as a root-pruner removal candidate.
        private fun skipRow(contribution: Contribution): Boolean {
            if (contribution != Contribution.HULL) return false
            if (!currentHullEnabled) return true
            hullFactorIds.add(currentFactorId)
            return false
        }

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addIntRow(intVars, coeffs, auxCol = -1, auxCoeff = 0L, rel = rel, rhs = bound)
        }

        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addBoolRow(literals, weights, rel, bound)
        }

        override fun hullEnabled(): Boolean = currentHullEnabled

        override fun liveDomain(intVar: Int): IntDomain = domains.intDomain(intVar)

        override fun boundRowImplied(variable: Int, relation: LinearOp, threshold: Long): Boolean {
            val snapshot = columnBounds ?: return false
            try {
                cancellation.charge(1L)
                onTemplateWork(1L)
                checkCancellation()
                val root = problem.rootDomainOf(variable)
                val bounds = problem.intBounds
                val rootImplied = when (relation) {
                    LinearOp.LE -> bounds.hasUpper(variable) && root.max <= threshold
                    LinearOp.GE -> bounds.hasLower(variable) && root.min >= threshold
                    LinearOp.EQ -> bounds.hasLower(variable) && bounds.hasUpper(variable) &&
                        root.min == threshold && root.max == threshold
                    LinearOp.NE -> false
                }
                if (!rootImplied) return false
                intColumn(variable)
                val lower = snapshot.first[variable]
                val upper = snapshot.second[variable]
                val implied = when (relation) {
                    LinearOp.LE -> upper <= threshold
                    LinearOp.GE -> lower >= threshold
                    LinearOp.EQ -> lower == threshold && upper == threshold
                    LinearOp.NE -> false
                }
                checkCancellation()
                return implied
            } finally {
                templateStats?.observeTemplateUse(boundChecks = 1)
            }
        }

        override fun liveBool(boolVar: Int): Boolean? = domains.boolValue(boolVar)

        override fun rootDomain(intVar: Int): IntDomain = problem.rootDomainOf(intVar)

        override fun realMagnitude(realVar: Int): Double {
            checkCancellation()
            val magnitude = max(abs(problem.realLower[realVar]), abs(problem.realUpper[realVar]))
            return expandedMagnitude(magnitude)
        }

        override fun sourceRowSlack(row: LinearRow, includeAbsolute: Boolean): LpRowSlack? {
            val policy = problem.semantics.effective.feasibilityTolerance ?: return null
            val absoluteOnly = policy.relative.numerator.isZero()
            if (absoluteOnly && !includeAbsolute) return null
            val hasReals = (0 until row.size).any { Term.isReal(row.ref(it)) }
            if (!problem.semantics.relaxesRows(if (hasReals) 1 else 0)) return null
            return if (absoluteOnly) LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(0.0))
            else row.linearSourceSlack(this)
        }

        override fun sourceRow(row: LinearRow, scale: BigFraction, emit: () -> Unit) {
            val index = builder.rowCount
            emit()
            if (builder.rowCount != index + 1) return
            val slack = sourceRowSlack(row) ?: return
            carryRowSlack(index, if (scale == BigFraction.ONE) slack else slack.scaled(scale.toDouble().nextUp()))
        }

        override fun sourceIntMagnitude(intVar: Int): Double {
            checkCancellation()
            val bounds = problem.intBounds
            if (!bounds.hasLower(intVar) || !bounds.hasUpper(intVar)) return Double.POSITIVE_INFINITY
            val magnitude = max(abs(bounds.lower(intVar).toDouble()), abs(bounds.upper(intVar).toDouble()))
            val outward = if (magnitude == 0.0) 0.0 else magnitude.nextUp()
            return if (problem.semantics.relaxesRows(0)) expandedMagnitude(outward) else outward
        }

        override fun sourceSignBoundMagnitude(intVar: Int, upper: Boolean): Double? {
            checkCancellation()
            val bounds = problem.intBounds
            val value = if (upper) {
                if (!bounds.hasUpper(intVar)) return null
                bounds.upper(intVar).also { if (it > 0L) return null }
            } else {
                if (!bounds.hasLower(intVar)) return null
                bounds.lower(intVar).also { if (it < 0L) return null }
            }
            val magnitude = abs(value.toDouble())
            return if (magnitude == 0.0) 0.0 else magnitude.nextUp()
        }

        private fun expandedMagnitude(magnitude: Double): Double {
            val policy = problem.semantics.effective.feasibilityTolerance
            if (!magnitude.isFinite() || policy == null || policy.isExact) return magnitude
            val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator)
            val relative = BigFraction.of(policy.relative.numerator, policy.relative.denominator)
            val slack = LpBoundSlack(if (absolute.isZero) 0.0 else absolute.toDouble().nextUp(),
                if (relative.isZero) 0.0 else relative.toDouble().nextUp())
            return (magnitude + slack.at(magnitude).nextUp()).nextUp()
        }

        override fun statesLowerBound(intVar: Int): Boolean = problem.statesLowerBound(intVar)

        override fun statesUpperBound(intVar: Int): Boolean = problem.statesUpperBound(intVar)

        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) {
            checkCancellation()
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addSourceRow(columns, coeffs, rel, rhs)
        }

        override fun conditionedRow(
            columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, integer: Int, value: Long,
        ) {
            checkCancellation()
            val domain = domains.intDomain(integer)
            if (domain.min != value || domain.max != value) return
            val rel = relationOf(op) ?: return
            val premises = LpRowPremises(intArrayOf(integer, integer), booleanArrayOf(false, true),
                longArrayOf(value, value))
            addSourceRow(columns, coeffs, rel, rhs, premises)
        }

        private fun addSourceRow(
            columns: IntArray, coeffs: LongArray, relation: Relation, rhs: Long, premises: LpRowPremises? = null,
        ) {
            if (realNegOf.isEmpty() || columns.none { realNegOf.containsKey(it) }) {
                builder.addRow(columns, coeffs, relation, rhs, premises == null, premises)
                return
            }
            val mirrored = columns.indices.filter { realNegOf.containsKey(columns[it]) }
            if (mirrored.any { coeffs[it] == Long.MIN_VALUE }) return
            val expandedColumns = columns + IntArray(mirrored.size) {
                realNegOf.getOrDefault(columns[mirrored[it]], -1)
            }
            val expandedCoefficients = coeffs + LongArray(mirrored.size) { -coeffs[mirrored[it]] }
            builder.addRow(expandedColumns, expandedCoefficients, relation, rhs, premises == null, premises)
        }

        override fun exactRow(
            columns: IntArray, row: NumericalLinearRow, op: LinearOp, indicator: Int, value: Long,
        ) {
            checkCancellation()
            val rel = relationOf(op) ?: return
            val premises = if (indicator < 0) null else {
                val domain = liveDomain(indicator)
                if (domain.min != value || domain.max != value) return
                LpRowPremises(intArrayOf(indicator, indicator), booleanArrayOf(false, true), longArrayOf(value, value))
            }
            val mirrored = columns.indices.filter { realNegOf.containsKey(columns[it]) }
            val expanded = columns + IntArray(mirrored.size) { realNegOf.getOrDefault(columns[mirrored[it]], -1) }
            val coefficients = row.coefficients + mirrored.map { row.coefficients[it].negated() }
            exactRows.add(builder, expanded, coefficients, rel, row.rhs, premises)
        }

        override fun realRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
        ) = emitRealRow(columns, coeffs, op, rhs, strict, premiseLits)

        override fun derivedRealRow(
            columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double, slack: LpRowSlack,
        ) = derivedRealRow(columns, coeffs, op, rhs, slack, emptyIntArray())

        override fun derivedRealRow(
            columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double, slack: LpRowSlack,
            premiseLits: IntArray,
        ) {
            val index = builder.rowCount
            emitRealRow(columns, coeffs, op, rhs, false, premiseLits)
            carryRowSlack(index, slack)
        }

        private fun carryRowSlack(index: Int, slack: LpRowSlack) {
            if (builder.rowCount == index + 1 && problem.semantics.relaxesRows(problem.numRealVars)) {
                exactRows.carrySlack(index, slack)
            }
        }

        override fun realRowForIntValue(
            intVar: Int,
            value: Long,
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
        ) = emitRealRowForIntValue(intVar, value, columns, coeffs, op, rhs, null)

        override fun derivedRealRowForIntValue(
            intVar: Int, value: Long, columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double,
            slack: LpRowSlack,
        ) = emitRealRowForIntValue(intVar, value, columns, coeffs, op, rhs, slack)

        private fun emitRealRowForIntValue(
            intVar: Int, value: Long, columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double,
            slack: LpRowSlack?,
        ) {
            val domain = liveDomain(intVar)
            if (domain.min != value || domain.max != value) return
            val validity = LpRowPremises(intArrayOf(intVar, intVar), booleanArrayOf(false, true),
                longArrayOf(value, value))
            val index = builder.rowCount
            emitRealRow(columns, coeffs, op, rhs, false, emptyIntArray(), validity)
            if (slack != null) carryRowSlack(index, slack)
        }

        private fun emitRealRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
            validity: LpRowPremises? = null,
        ) {
            val rel = relationOf(op) ?: return
            if (realNegOf.isEmpty() || columns.none { realNegOf.containsKey(it) }) {
                builder.addRealRow(columns, coeffs, rel, rhs, strict, premiseLits, validity)
                return
            }
            // Mirror each split column's negative part with the negated coefficient.
            val extra = columns.count { realNegOf.containsKey(it) }
            val cols = IntArray(columns.size + extra)
            val vals = DoubleArray(columns.size + extra)
            var w = columns.size
            for (k in columns.indices) {
                cols[k] = columns[k]
                vals[k] = coeffs[k]
                val neg = realNegOf.getOrDefault(columns[k], -1).takeIf { it >= 0 } ?: continue
                cols[w] = neg
                vals[w] = -coeffs[k]
                w++
            }
            builder.addRealRow(cols, vals, rel, rhs, strict, premiseLits, validity)
        }

        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) {
            val rel = relationOf(op) ?: return
            val premises = if (global) null else derivePremises(columns, coeffs, maxSide)
            builder.addRow(columns, coeffs, rel, rhs, global, premises)
        }

        // Per integer column, the tightened live bound a non-global big-M row leans on: upper when the
        // coefficient sign matches maxSide, else lower.
        private fun derivePremises(columns: IntArray, coeffs: LongArray, maxSide: Boolean): LpRowPremises {
            val pv = IntArrayList()
            val pu = ArrayList<Boolean>()
            val pt = LongArrayList()
            for (j in columns.indices) {
                val col = columns[j]
                if (colIsBool[col] == 1 || colVarId[col] < 0) continue
                val c = coeffs[j]
                if (c == 0L) continue
                val v = colVarId[col]
                val dom = domains.intDomain(v)
                val dec = rootDomain(v)
                if ((c >= 0L) == maxSide) {
                    if (dom.max != dec.max) {
                        pv.add(v)
                        pu.add(true)
                        pt.add(dom.max)
                    }
                } else if (dom.min != dec.min) {
                    pv.add(v)
                    pu.add(false)
                    pt.add(dom.min)
                }
            }
            return LpRowPremises(pv.toIntArray(), BooleanArray(pu.size) { pu[it] }, pt.toLongArray())
        }
    }
}
