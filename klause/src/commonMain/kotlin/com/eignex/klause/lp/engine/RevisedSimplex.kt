package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisBuildWork
import com.eignex.klause.simplex.basis.BasisFactorCopy
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisPhaseWork
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisRepairStop
import com.eignex.klause.simplex.basis.BasisSolveQuality
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.MAX_SCALAR_COLUMN_ENTRIES
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.simplex.basis.SymbolicLu
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.pollStop
import com.eignex.koblas.DenseVector
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import com.eignex.util.numbers.saturatedAdd
import kotlin.math.abs
import kotlin.time.TimeSource

// Double-precision guides only; the authoritative bound comes from exact certification of [basis].
internal class FloatLpResult(
    val basis: Basis,
    val objective: Double,
    val duals: DoubleArray,
    // Unshifted, length n.
    val primal: DoubleArray,
    val pivots: Int = 0,
    // Max (nnz factors) / nnz(B) across this solve's factorizations.
    val luMaxFill: Double = 0.0,
    // Max (nnz factors) / m².
    val luMaxDensity: Double = 0.0,
    // Density alone hides whether the dense basis was tiny or large.
    val luMaxDim: Int = 0,
    // 1 for a monolithic solve.
    val blocks: Int = 1,
    val warmStarted: Boolean = false,
    // 0 when the solve continued on kept factors.
    val refactorizations: Int = 0,
    // False for an iterate stopped short: dual feasibility still makes it a valid bound and its coordinates can guide
    // a checked candidate, but no reduced-cost fixing, tableau cut or infeasibility claim rests on it.
    val optimal: Boolean = true,
    val exactState: LpExactState? = null,
)

// Updates folded into the basis before it is rebuilt; bounds fill and rounding drift.
internal const val DEFAULT_REFACTOR_UPDATE_LIMIT: Int = 64

// Bounded-variable dual simplex in revised form: a [BasisSolver] owns the factors and updates, and the columns stay
// in sparse CSC. May return null (non-convergence, dual unboundedness, singular basis); candidates need independent
// certification against the source. A basis is named by index into `columns`, fixed for the solver's lifetime, so a
// pivot hands the solver the spike it already computed. [refactorUpdateLimit] caps updates beyond what the solver
// advises through [BasisUpdate.REFACTORIZE]. [iterationLimit] (0 derives one from size) and [workLimit] (0 is
// unbounded) may truncate a solve: every dual iterate is dual-feasible, so stopping short still bounds, while a
// truncated primal iterate is withheld.
// Devex pricing and Harris's two-pass ratio test: P. M. J. Harris, "Pivot selection methods of the Devex LP code",
// Math. Program. 5 (1973), https://doi.org/10.1007/BF01580108. Dual steepest edge: J. J. Forrest and D. Goldfarb,
// "Steepest-edge simplex algorithms for linear programming", Math. Program. 57 (1992),
// https://doi.org/10.1007/BF01581089. Bound-flipping ratio test: I. Maros, "A generalized dual phase-2 simplex
// algorithm", Eur. J. Oper. Res. 149 (2003), https://doi.org/10.1016/S0377-2217(02)00448-4.
internal class RevisedSimplex(
    private var model: LpModel,
    cancellation: ExecutionControl = ExecutionControl.Never,
    private val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    private val iterationLimit: Int = 0,
    private val workLimit: Long = 0L,
    private val trackDegeneracy: Boolean = false,
    private val basisSolverFactory: ((SparseMatrix) -> BasisSolver)? = null,
    private val pricing: LpPricingOptions = LpPricingOptions(),
    private val reuseRationalOrder: Boolean = true,
    private val scalingOptions: LpScalingOptions = LpScalingOptions(),
    private val primalPricingTolerance: Double = TOL,
    private val syntheticWorkEnabled: Boolean = false,
    private val phaseOneLongSteps: Boolean = true,
    private val phaseOneIncrementalValues: Boolean = true,
    private val singletonStart: Boolean = true,
    initialScaling: LpScalingView? = null,
    initialMatrix: SimplexMatrixView? = null,
    private val allowUnscaledFallback: Boolean = true,
    private val initialOrder: SymbolicLu? = null,
    private var initialFactorCopy: SimplexFactorCopy? = null,
    private val reuseProbeValues: Boolean = true,
) : TableauCutSolver,
    PersistentLpSolver,
    ResumableLpSolver,
    LpDirectionalCostSolver,
    LpDirectionalProbeSolver,
    LpDirectionalValueSolver {
    private var activeSolve: LpSolveCursor<FloatLpResult?>? = null
    private var solveStarted = false
    override val currentWork: Long get() = work.ops
    internal val lastDevexWeightCorrections: Int get() = observations.lastDevexWeightCorrections
    internal val lastHarrisMinistepSelections: Int get() = observations.lastHarrisMinistepSelections
    internal val lastTheoryPricingAttempts: Int get() = observations.lastTheoryPricingAttempts
    internal val lastTheoryPricingSamples: Int get() = observations.lastTheoryPricingSamples
    internal val lastTheoryPricingSuccessfulSamples: Int get() = observations.lastTheoryPricingSuccessfulSamples
    internal val lastTheoryPricingDeclines: Int get() = observations.lastTheoryPricingDeclines
    internal val lastTheoryPricingResourceStops: Int get() = observations.lastTheoryPricingResourceStops
    internal val lastTheoryPricingWorkOps: Long get() = observations.lastTheoryPricingWorkOps
    internal val lastTheoryPricingEstimatedFtranWorkOps: Long
        get() = observations.lastTheoryPricingEstimatedFtranWorkOps
    internal val lastTheoryPricingSelections: Int get() = observations.lastTheoryPricingSelections
    internal val lastTheorySelectedColumn: Int get() = observations.lastTheorySelectedColumn
    private val observations = SimplexObservations()
    internal var progressTrace: SimplexProgressTrace? =
        if (pricing.basisEntryLimit != Long.MAX_VALUE) SimplexProgressTrace() else null
        private set

    internal fun enableProgressTrace(profile: Boolean = false) {
        check(activeSolve == null)
        progressTrace = SimplexProgressTrace(profile)
    }

    private var profileSink: ((LpSolveMetrics, SimplexProgressTrace) -> Unit)? = null

    internal fun profileTo(sink: (LpSolveMetrics, SimplexProgressTrace) -> Unit) {
        enableProgressTrace(profile = true)
        profileSink = sink
    }

    private inline fun <T> profiled(phase: SimplexProfilePhase, block: () -> T): T = phaseMonitor.profiled(phase, block)

    private inline fun profiledCheckpoint(block: () -> Unit) {
        val trace = progressTrace?.takeIf { it.profile } ?: return block()
        val started = TimeSource.Monotonic.markNow()
        try {
            block()
        } finally {
            trace.checkpointNanos += started.elapsedNow().inWholeNanoseconds
        }
    }

    private fun traceProgress(phase: SimplexPhase, beta: DoubleArray) {
        val trace = progressTrace ?: return
        if (!trace.iteration(phase)) return
        var objective = model.objConstantD
        var violation = 0.0
        var maximum = 0.0
        var count = 0
        for (j in 0 until numVars) {
            val value = when (status[j]) {
                VarStatus.AT_LOWER, VarStatus.FIXED -> lowerAt(j)
                VarStatus.AT_UPPER -> upperAt(j)
                else -> 0.0
            }
            if (cost(j) != 0.0) objective += cost(j) * value
        }
        for (i in 0 until m) {
            val j = basicVar[i]
            objective += cost(j) * beta[i]
            val below = if (finiteLowerAt(j)) lowerAt(j) - beta[i] else 0.0
            val above = if (finiteUpperAt(j)) beta[i] - upperAt(j) else 0.0
            val amount = maxOf(0.0, below, above)
            if (amount > feasibilityTolerance) {
                violation += amount
                maximum = maxOf(maximum, amount)
                count++
            }
        }
        trace.samples.add(SimplexProgressSample(phase, observations.pivots, work.ops,
            objective, violation, maximum, count))
    }
    internal val lastNumericalMetrics: SimplexNumericalMetrics get() = observations.numericalMetrics
    override val lastTermination: LpFloatTermination? get() = observations.termination
    private var numericalFailure = false
    private val tolerance = TOL
    private val feasibilityTolerance = FEAS_TOL
    private fun cost(column: Int): Double = numerical.costD(column)
    private val solveControl = SimplexSolveControl(model.m, model.numVars, iterationLimit, workLimit, cancellation)
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars
    private val constructionState = model.exactState
    private var numerical = initialScaling?.also {
        require(it.source === model && (!it.applied || !allowUnscaledFallback))
    } ?: LpScalingView.create(model, scalingOptions)
    private var continuationAvailable = false
    private var stoppedContinuationBasis: Basis? = null

    private val pricingState = SimplexPricingState(m, numVars)
    private val workspace = SimplexWorkspace(m, numVars)
    private val dualRhs = workspace.dualRhs
    private val dualValues = workspace.dualValues
    private val qualityRhs = workspace.qualityRhs
    private val basicRhs = workspace.basicRhs
    private val dualBeta = workspace.dualBeta
    private val primalBeta = workspace.primalBeta
    private val alphaValues = workspace.alphaValues
    private val reconcileQueue = workspace.reconcileQueue

    // Logical columns explicit, so [basicVar] names exactly what the basis solver factorizes.
    private var columns: SparseMatrix = initialMatrix?.also {
        require(initialScaling != null && it.matches(numerical))
    }?.columns ?: lpColumns(numerical)

    // The CSC the seam holds, read for pricing rather than copied.
    private var colPtr: IntArray = initialMatrix?.colPtr ?: columns.copyColumnPointers()
    private var rowIdx: IntArray = initialMatrix?.rowIdx ?: columns.copyRowIndices()
    private var colVal: DoubleArray = initialMatrix?.colVal ?: columns.values

    // `columns` by row, so the pivot row from a hypersparse ρ = B⁻ᵀeᵣ costs only the rows it touches.
    private lateinit var rowCols: Array<IntArray>
    private lateinit var rowVals: Array<DoubleArray>

    private val basisLifecycle = SimplexBasisLifecycle(m, numVars)
    private val basicVar = basisLifecycle.basicVar
    private val status = basisLifecycle.status

    // Basis capture cannot recover an imported general status declaration, so it stays off through reuse.
    private var basisCaptureEligible = model.exactState == null
    override var solvedExactState: LpExactState? = null
        private set
    private val phaseMonitor = object : SimplexPhaseMonitor() {
        override val trace: SimplexProgressTrace? get() = progressTrace

        override fun sample(phase: SimplexPhase, beta: DoubleArray) = traceProgress(phase, beta)

        override suspend fun checkpoint(progress: SolveProgress) {
            if (activeSolve != null) pauseAtIteration(progress)
        }
    }

    private val primalOperations = object : PrimalBasisOperations {
        override fun basicValues(out: DoubleArray): DoubleArray = this@RevisedSimplex.basicValues(out)
        override fun transposeGradient(gradient: DoubleArray, out: DoubleArray) {
            btranDense(gradient, out, dualVec)
        }
        override fun objectiveDuals(): DoubleArray = duals()
        override fun dotColumn(values: DoubleArray, column: Int): Double = this@RevisedSimplex.dotColumn(values, column)
        override fun spike(column: Int): IndexedVector {
            this@RevisedSimplex.spike(column)
            return spikeVec
        }
        override fun foldPivot(row: Int, entering: Int, leaving: Int): PivotFold =
            this@RevisedSimplex.foldPivot(row, entering, leaving, withPivotEta = false)

        override fun refactorAtSafePoint(): PrimalPhaseRefactor? = when (refactorAtQualitySafePoint()) {
            null -> null
            SimplexBasisRefactor.UNCHANGED -> if (routineRefactor) {
                PrimalPhaseRefactor.UNCHANGED
            } else {
                PrimalPhaseRefactor.RESTART
            }
            SimplexBasisRefactor.BASIS_CHANGED -> PrimalPhaseRefactor.BASIS_CHANGED
            SimplexBasisRefactor.FAILED -> PrimalPhaseRefactor.FAILED
        }
    }
    private val primalPhases = PrimalSimplexPhases(
        basisLifecycle, workspace, solveControl, observations, primalOperations, phaseMonitor,
    )

    private val artificialBounds = SimplexArtificialBounds(status, observations)
    private val dualOperations = object : DualBasisOperations {
        override val updateCount: Int get() = solver().updateCount
        override fun basicValues(out: DoubleArray): DoubleArray = this@RevisedSimplex.basicValues(out)
        override fun objectiveDuals(): DoubleArray = duals()
        override fun dotColumn(values: DoubleArray, column: Int): Double = this@RevisedSimplex.dotColumn(values, column)
        override fun pivotalRow(row: Int): IndexedVector {
            this@RevisedSimplex.pivotalRow(row)
            return pivotEtaVec
        }
        override fun spike(column: Int): IndexedVector {
            this@RevisedSimplex.spike(column)
            return spikeVec
        }
        override fun refactorAtSafePoint(): SimplexBasisRefactor? = refactorAtQualitySafePoint()
        override fun recoverNumerically(): SimplexBasisRefactor = refactorize(LpRefactorReason.NUMERICAL_RECOVERY)
        override fun foldPivot(row: Int, entering: Int, leaving: Int): PivotFold =
            this@RevisedSimplex.foldPivot(row, entering, leaving, withPivotEta = true)
        override fun columnNnz(column: Int): Int = this@RevisedSimplex.columnNnz(column)
        override fun sampleBoundSupport(column: Int, leavingRow: Int, enforced: BooleanArray?): TheoryProbe =
            probeTheorySupport(column, leavingRow, enforced)
    }
    private val dualPhase = DualSimplexPhase(
        basisLifecycle, workspace, pricingState, artificialBounds, solveControl, observations,
        dualOperations, phaseMonitor, pricing,
    )

    private val basicValueCache = SimplexBasicValues()
    private var pendingProbeValues = false

    private val work = solveControl.work

    // Building a scaling view is preparation the next solve reports, apart from the iterations its work limit caps.
    private val pendingNumericalWork = LpWork().apply { add(numerical.metrics.work) }

    private val cancellation: ExecutionControl = solveControl.cancellation

    private var deferUnscaledSourceDiagnostics = false

    override fun deferUnscaledSourceDiagnostics() {
        deferUnscaledSourceDiagnostics = true
    }

    override val scalingMetrics: LpScalingMetrics
        get() = numerical.metrics.copy(
            sourcePrimalResidual = observations.sourcePrimalResidual,
            sourceBoundViolation = observations.sourceBoundViolation,
            sourceBasicDualResidual = observations.sourceBasicDualResidual,
        )
    internal val scaleVersion: Long get() = numerical.version

    private val basisRepairer = EngineBasisRepairer()
    private var refactorPolicy = RefactorPolicy(
        RefactorPolicyConfig(
            hardUpdateCap = refactorUpdateLimit,
            syntheticWorkEnabled = syntheticWorkEnabled,
        ),
    )
    private var pendingSolveQuality: BasisSolveQuality? = null

    internal val lastBasisRepairMetrics: BasisRepairMetrics get() = basisRepairer.metrics
    internal val lastRefactorPolicyMetrics: RefactorPolicyMetrics get() = refactorPolicy.metrics

    override val exactBasisCache = ExactBasisCache(if (reuseRationalOrder) ::proposedRationalOrder else null)
    private var rejectedExactBasis: IntArray? = null

    private fun proposedRationalOrder(authority: ExactBasisAuthority): RationalBasisOrder? {
        val meter = authority.meter
        val state = model.exactState
        val original = constructionState
        if (state == null || original == null) return null
        meter.phase = ExactBasisPhase.ORDER_IDENTITY
        val availabilityWork = if (m == 0) 0L else 1L
        meter.charge(availabilityWork)
        val current = basisLifecycle.current
        // Updated factors cannot offer LU pivots, independently of the requested identity.
        if (basisLifecycle.factorized && current != null && current.updateCount != 0) {
            meter.orderDecline = ExactBasisOrderDecline.UPDATED
            return null
        }
        meter.charge(2L * state.model.keySize + numVars + 8L * m - availabilityWork, 256L + 8L * numVars + 8L * m)
        if (authority.model.exactState !== state || !original.sameMatrix(state) ||
            !authority.headings.contentEquals(basicVar) || !basisLifecycle.factorized || !trackedHeadingsConsistent()
        ) {
            meter.orderDecline = ExactBasisOrderDecline.STALE
            return null
        }
        if (current == null) return null
        // Reserve the owner's snapshot, all four copy getters, translation and validation scratch.
        meter.phase = ExactBasisPhase.ORDER_EXPORT
        meter.charge(16L * m + numVars, 768L + 48L * m + 4L * numVars)
        val order = current.ordering() ?: return null
        meter.poll()
        val sourceColumns = order.columns
        val units = order.unitRows
        val rows = order.rows
        val slots = order.slots
        if (sourceColumns.size != m || units.size != m || rows.size != m || slots.size != m) {
            meter.orderDecline = ExactBasisOrderDecline.INVALID
            return null
        }
        val positions = IntArray(numVars) { -1 }
        for (slot in authority.headings.indices) positions[authority.headings[slot]] = slot
        val translated = IntArray(m)
        for (slot in 0 until m) {
            val column = sourceColumns[slot]
            val unit = units[slot]
            if ((column !in 0 until numVars || unit != -1) && (column != -1 || unit !in 0 until m)) {
                meter.orderDecline = ExactBasisOrderDecline.INVALID
                return null
            }
            val heading = if (column >= 0) column else n + unit
            if (heading != basicVar[slot] || slots[slot] !in 0 until m) {
                meter.orderDecline = ExactBasisOrderDecline.STALE
                return null
            }
        }
        for (pivot in 0 until m) {
            val slot = slots[pivot]
            val heading = if (sourceColumns[slot] >= 0) sourceColumns[slot] else n + units[slot]
            translated[pivot] = positions[heading]
        }
        return RationalBasisOrder(rows, translated)
    }

    override fun continuationBasis(model: LpModel): Basis? {
        if (activeSolve?.isAdvancing == false) return null
        if (!continuationAvailable) return null
        val state = model.exactState ?: return null
        if (this.model.exactState !== state || constructionState?.sameMatrix(state) != true ||
            state.conflict != null || (0 until numVars).any { !model.exactBounds(it).consistent }
        ) {
            return null
        }
        val stopped = stoppedContinuationBasis
        val headings = stopped?.basicVars ?: basicVar
        val seats = stopped?.status ?: status
        if (rejectedExactBasis?.contentEquals(
                headings,
            ) == true || !basisStatusConsistent(model, headings, seats)
        ) {
            return null
        }
        return Basis(headings.copyOf(), seats.copyOf(), captureEligible = false)
    }

    override fun rejectSingularBasis(model: LpModel, basis: Basis): Boolean {
        if (activeSolve != null) return false
        val matches = if (model.exactState == null) this.model === model else this.model.exactState === model.exactState
        if (!matches || !basis.basicVars.contentEquals(basicVar)) return false
        rejectedExactBasis = basis.basicVars.copyOf()
        invalidateUncertainBasisState()
        return true
    }

    // One carrier per role: a solver may reuse the form it kept when its own vector comes back to
    // [BasisSolver.update], which keeps an update at one FTRAN; a shared vector would defeat that.
    private val spikeVec = workspace.spikeVec
    private val pricingSpikeVec = workspace.pricingSpikeVec
    private val pivotEtaVec = workspace.pivotEtaVec
    private val rhsVec = workspace.rhsVec
    private val dualVec = workspace.dualVec

    // The work meter charges from this rather than the solver's fill, which differs between basis solvers; this
    // depends only on the model and the pivot path.
    private var nnzB = 0

    // Fed from the previous iteration's FTRAN and BTRAN results to steer the solver's sweep choice.
    private var ftranDensity = 1.0
    private var btranDensity = 1.0

    // Null on any failure other than dual unboundedness, so the caller only prunes on a genuine infeasibility.
    override var infeasibleBasis: Basis? = null
        private set
    override var infeasibleRow: Int = -1
        private set

    // ρ = B⁻ᵀeᵣ; null unless [solve] returned null on infeasibility.
    override var infeasibleRay: DoubleArray? = null
        private set

    // Uncharged: it informs the budgeting policy, and a meter that grew when the policy is on would measure itself.
    private fun recordDegeneracy(y: DoubleArray) {
        if (!trackDegeneracy) return
        var count = 0
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            if (abs(cost(j) - columnDot(y, j)) <= TOL) count++
        }
        observations.degenerateColumns = count
    }

    override val lastDegenerateColumns: Int get() = observations.degenerateColumns
    override val lastColumns: Int get() = numVars
    override val lastPivots: Int get() = observations.pivots
    override val lastRefactorizations: Int get() = observations.refactorizations
    override val lastWarmStarted: Boolean get() = observations.warmStarted
    override val lastWorkOps: Long get() = work.ops
    override val lastSingularRefactorizations: Int get() = observations.singularRefactorizations
    override val lastSmallPivotBails: Int get() = observations.smallPivotBails
    override val lastMetrics: LpSolveMetrics get() = observations.snapshot(work.ops)
    override fun pausedDualProposal(cancellation: ExecutionControl): DoubleArray? {
        if (activeSolve?.isAdvancing != false || cancellation()) return null
        val proposal = DoubleArray(m)
        var nonzero = false
        // Row multipliers can lag the basis; exact checking against the original model supplies authority.
        for (row in 0 until m) {
            if (row % 256 == 0 && cancellation()) return null
            val value = try {
                numerical.sourceDual(row, dualValues[row])
            } catch (_: LpScalingArithmeticException) {
                return null
            }
            if (!value.isFinite()) return null
            proposal[row] = value
            if (value != 0.0) nonzero = true
        }
        return proposal.takeIf { nonzero && !cancellation() }
    }

    override val trialProgress: Map<String, Long> get() {
        val samples = progressTrace?.samples ?: return emptyMap()
        return buildMap {
            put("ProgressSamples", samples.size.toLong())
            samples.firstOrNull()?.let { put("FirstViolatedBasics", it.violatedBasics.toLong()) }
            samples.lastOrNull()?.let {
                put("LastViolatedBasics", it.violatedBasics.toLong())
                put("ProgressPhase" + it.phase.name, 1L)
            }
            progressTrace?.let {
                put("DegenerateSteps", it.degenerateSteps.values.sumOf(Int::toLong))
                put("BoundFlips", it.boundFlips.values.sumOf(Int::toLong))
                put("PricedColumns", it.pricedColumns)
            }
            put("NumericalWork", work.ops)
            put("Pivots", lastPivots.toLong())
            lastTermination?.let { put("Termination" + it.name, 1L) }
        }
    }

    init {
        if (initialMatrix == null) rebuildRowView() else {
            rowCols = initialMatrix.rowCols
            rowVals = initialMatrix.rowVals
        }
    }

    private fun matrixView(): SimplexMatrixView =
        SimplexMatrixView(numerical, columns, colPtr, rowIdx, colVal, rowCols, rowVals)

    private fun rebuildRowView() {
        // Counting-sort the column view by row for hypersparse pivotal-row assembly.
        val counts = IntArray(m)
        for (k in rowIdx.indices) counts[rowIdx[k]]++
        rowCols = Array(m) { IntArray(counts[it]) }
        rowVals = Array(m) { DoubleArray(counts[it]) }
        val cursor = IntArray(m)
        for (j in 0 until numVars) {
            for (k in colPtr[j] until colPtr[j + 1]) {
                val i = rowIdx[k]
                val at = cursor[i]++
                rowCols[i][at] = j
                rowVals[i][at] = colVal[k]
            }
        }
    }

    private fun columnNnz(j: Int): Int = colPtr[j + 1] - colPtr[j]

    // Costs the column's nonzeros rather than m, since an indexed vector clears only what it stored.
    private fun scatterColumn(j: Int, into: IndexedVector) {
        work.add(columnNnz(j))
        into.scatterStored(rowIdx, colPtr[j], colVal, colPtr[j], columnNnz(j))
    }

    // Uncharged pricing reads the current owner so an unscaled fallback cannot retain a retired CSC generation.
    internal fun columnDot(y: DoubleArray, j: Int): Double {
        if (j >= n) return y[j - n]
        val from = colPtr[j]
        val until = colPtr[j + 1]
        if (until - from <= MAX_SCALAR_COLUMN_ENTRIES) {
            var value = 0.0
            for (at in from until until) value += colVal[at] * y[rowIdx[at]]
            return value
        }
        return koblas.dotColumn(columns, j, DenseVector.wrap(y))
    }

    private fun dotColumn(y: DoubleArray, j: Int): Double {
        work.add(columnNnz(j))
        return columnDot(y, j)
    }

    private fun solver(): BasisSolver = basisLifecycle.solver(columns, basisSolverFactory,
        pricing.basisEntryLimit, pricing.basisWorkLimit, initialOrder) { cancellation() }

    internal val hasInitialOrder: Boolean get() = initialOrder != null

    internal var hasImportedFactors: Boolean = false
        private set
    internal var hasImportedParentValues: Boolean = false
        private set
    internal var importedFactorEntries: Long = 0L
        private set
    private var factorCopyFailed = false

    internal fun probeBasisBuild(): BasisBuildWork? =
        basisLifecycle.current?.basisWork?.build

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        initialFactorCopy = null
        pendingProbeValues = false
        val pending = activeSolve
        activeSolve = null
        var cleanup: Throwable? = null
        try {
            // An advancing operation unwinds through its own failure path.
            if (pending?.isAdvancing != true) pending?.close()
        } catch (failure: Throwable) {
            cleanup = failure
        }
        continuationAvailable = false
        stoppedContinuationBasis = null
        exactBasisCache.clear()
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        val failure = basisLifecycle.retire()
        basicValueCache.invalidate()
        solvedExactState = null
        if (failure != null) {
            if (cleanup == null) cleanup = failure else if (cleanup !== failure) cleanup.addSuppressed(failure)
        }
        cleanup?.let { throw it }
    }

    private var repairStop: BasisRepairStop? = null
    private var repairCallbackFailure: Throwable? = null

    // A singular factorization goes to basis repair; [SimplexBasisRefactor.FAILED] leaves this engine unable to solve
    // until a later call succeeds.
    private fun refactorize(reason: LpRefactorReason): SimplexBasisRefactor = profiled(SimplexProfilePhase.REFACTOR) {
        if (repairStop != null) {
            observations.termination = repairTermination()
            return@profiled SimplexBasisRefactor.FAILED
        }
        val copied = initialFactorCopy
        initialFactorCopy = null
        if (copied != null) {
            nnzB = 0
            for (slot in basicVar.indices) nnzB += columnNnz(basicVar[slot])
            work.add(nnzB)
            if (reason != LpRefactorReason.WARM_START ||
                !copied.install(basisLifecycle, columns, n, pricing.basisEntryLimit, pricing.basisWorkLimit) {
                    cancellation()
                }
            ) {
                factorCopyFailed = true
                observations.termination = if (cancellation()) LpFloatTermination.CANCELLED
                else LpFloatTermination.NUMERICAL
                return@profiled SimplexBasisRefactor.FAILED
            }
            hasImportedFactors = true
            importedFactorEntries = checkNotNull(basisLifecycle.current).nnz.toLong()
            recordFactorization(checkNotNull(basisLifecycle.current), basisChanged = false)
            return@profiled SimplexBasisRefactor.UNCHANGED
        }
        observations.refactorizations++
        when (reason) {
            LpRefactorReason.INITIAL -> observations.initialRefactorizations++
            LpRefactorReason.WARM_START -> observations.warmStartRefactorizations++
            LpRefactorReason.SINGULAR_RECOVERY -> observations.singularRecoveryRefactorizations++
            LpRefactorReason.UPDATE_LIMIT -> observations.updateLimitRefactorizations++
            LpRefactorReason.BACKEND_REQUESTED -> observations.backendRequestedRefactorizations++
            LpRefactorReason.RECONCILE_RECOVERY -> observations.reconcileRecoveryRefactorizations++
            LpRefactorReason.NUMERICAL_RECOVERY -> observations.numericalRecoveryRefactorizations++
            LpRefactorReason.PRIMAL -> observations.primalRefactorizations++
        }
        nnzB = 0
        for (t in 0 until m) nnzB += columnNnz(basicVar[t])
        // The entries the elimination reads, not the fill it produces.
        work.add(nnzB)
        val solver = solver()
        val buildBefore = operationWork(solver)
        try {
            basisLifecycle.factorized = try {
                solver.refactorize(basicVar)
            } catch (_: BasisArithmeticException) {
                false
            } catch (_: ArithmeticException) {
                false
            }
            if (!basisLifecycle.factorized) {
                observations.singularRefactorizations++
                // Repair reports completed operations; a strict private query cannot fund an unknown overrun.
                if (solveControl.strictProbeWorkLimit > 0L) throw LpWorkExhausted()
                val remaining = solveControl.effectiveWorkLimit.takeIf { it > 0 }?.let { maxOf(0, it - work.ops) }
                val control = BasisRepairControl(cancellation, remaining)
                val recovery = try {
                    basisRepairer.recover(
                        solver,
                        basicVar,
                        n,
                        model.basisBoundStates(),
                        status,
                        model,
                        cancellation,
                        control,
                    )
                } finally {
                    work.add(control.spentWork)
                    repairStop = control.stop
                    repairCallbackFailure = control.callbackFailure
                    if (repairStop != null) observations.termination = repairTermination()
                }
                return@profiled when (recovery) {
                    is BasisRecoveryResult.Failed -> {
                        numericalFailure = true
                        if (recovery.decline == BasisRepairDecline.CANCELLED) {
                            repairStop = BasisRepairStop.CANCELLED
                            observations.termination = LpFloatTermination.CANCELLED
                        }
                        invalidateBasisDependentState()
                        SimplexBasisRefactor.FAILED
                    }

                    is BasisRecoveryResult.Recovered -> {
                        installRecoveredBasis(recovery.state)
                        recordFactorization(solver, basisChanged = true)
                        SimplexBasisRefactor.BASIS_CHANGED
                    }
                }
            }
            basisLifecycle.columns = basicVar.copyOf()
            basisLifecycle.unitRows = IntArray(m) { -1 }
            recordFactorization(solver, basisChanged = false)
            return@profiled SimplexBasisRefactor.UNCHANGED
        } finally {
            val buildAfter = operationWork(solver)
            val measured = if (buildBefore == null || buildAfter == null ||
                buildAfter.refactorization.attempts <= buildBefore.refactorization.attempts
            ) null else operationDelta(buildBefore, buildAfter) { it.refactorization.mergedWith(it.repair) }
            if (measured == null) observations.basisBuildUnmeasured++ else observations.basisBuildWork += measured
            if (buildBefore != null && buildAfter != null) {
                observations.basisBuildDeclines += maxOf(0L,
                    buildAfter.refactorization.declines - buildBefore.refactorization.declines) + maxOf(0L,
                    buildAfter.repair.declines - buildBefore.repair.declines)
            }
        }
    }

    private fun recordFactorization(solver: BasisSolver, basisChanged: Boolean) {
        numericalFailure = false
        observations.factorEntriesBuilt += solver.nnz.toLong()
        // Read off the solver, so unlike the work meter this measures the basis solver in play.
        if (m > 0 && nnzB > 0) {
            val held = solver.nnz.toDouble()
            val fill = held / nnzB
            if (fill > observations.maxLuFill) observations.maxLuFill = fill
            val density = held / (m.toDouble() * m.toDouble())
            if (density > observations.maxLuDensity) {
                observations.maxLuDensity = density
                observations.maxLuDim = m
            }
        }
        refactorPolicy.recordFactorization(
            solver.nnz,
            solver.basisWork?.build?.installedBuildUnits,
            solver.rcond,
            basisChanged,
        )
        pendingSolveQuality = null
    }

    private fun installRecoveredBasis(recovered: EngineBasisState) {
        recovered.headings.copyInto(basicVar)
        recovered.statuses.copyInto(status)
        basisLifecycle.columns = recovered.ownerColumns
        basisLifecycle.unitRows = recovered.ownerUnitRows
        basisLifecycle.factorized = true
        nnzB = 0
        for (slot in 0 until m) nnzB += columnNnz(basicVar[slot])
        invalidateBasisDependentState()
        basisLifecycle.factorized = true
        basisLifecycle.kept = true
    }

    private fun invalidateBasisDependentState() {
        exactBasisCache.clear()
        basicValueCache.invalidate()
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        ftranDensity = 1.0
        btranDensity = 1.0
        resetGamma()
        basisLifecycle.kept = false
    }

    // nnz(B) plus m per folded update: the model's shape and pivot path, never the solver's fill. An empty
    // right-hand side traverses nothing.
    private fun chargeSolve(rhs: IndexedVector, operation: SimplexBasisOperation) {
        if (rhs.count == 0) return
        val charge = nnzB.toLong() + (basisLifecycle.current?.updateCount ?: 0).toLong() * m
        work.add(charge)
        progressTrace?.basisSolve(operation, charge)
    }

    private fun operationDelta(
        before: BasisOperationWork?,
        after: BasisOperationWork?,
        phase: (BasisOperationWork) -> BasisPhaseWork,
    ): Long? {
        if (before == null || after == null || !before.complete || !after.complete || before.saturated ||
            after.saturated
        ) {
            return null
        }
        val start = phase(before).units
        val end = phase(after).units
        return if (end >= start) end - start else null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun operationWork(solver: BasisSolver): BasisOperationWork? = try {
        solver.basisOperationWork
    } catch (_: Throwable) {
        null
    }

    private fun sampleSolveQuality(
        solver: BasisSolver,
        rhs: DoubleArray,
        solution: IndexedVector,
        transpose: Boolean,
        cadenceChecked: Boolean = false,
    ) {
        if ((!cadenceChecked && !shouldSampleQuality()) || (cadenceChecked && cancellation())) return
        val quality = try {
            solver.solveQuality(rhs, solution, transpose)
        } catch (_: BasisArithmeticException) {
            BasisSolveQuality(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        } catch (_: ArithmeticException) {
            BasisSolveQuality(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        }
        val pending = pendingSolveQuality
        if (pending == null || quality.relativeResidual > pending.relativeResidual ||
            !quality.relativeResidual.isFinite()
        ) {
            pendingSolveQuality = quality
        }
    }

    private fun shouldSampleQuality(): Boolean = refactorPolicy.shouldSample(cancelled = false) && !cancellation()

    private fun denseColumn(column: Int): DoubleArray = qualityRhs.also { dense ->
        dense.fill(0.0)
        val from = colPtr[column]
        val until = colPtr[column + 1]
        if (until - from <= MAX_SCALAR_COLUMN_ENTRIES) {
            for (at in from until until) dense[rowIdx[at]] = colVal[at]
        } else {
            koblas.scatter(columns.columnView(column), DenseVector.wrap(dense))
        }
    }

    internal fun sparseAxpy(destination: DoubleArray, alpha: Double, column: Int) {
        val from = colPtr[column]
        val until = colPtr[column + 1]
        if (until - from <= MAX_SCALAR_COLUMN_ENTRIES) {
            for (at in from until until) destination[rowIdx[at]] += alpha * colVal[at]
        } else {
            koblas.axpy(alpha, columns.columnView(column), DenseVector.wrap(destination))
        }
    }

    private fun ftranDense(
        b: DoubleArray,
        out: DoubleArray,
        carrier: IndexedVector,
        boundUpdateFtran: Boolean = false,
    ): DoubleArray {
        requireFiniteRhs(b)
        carrier.scatter(b)
        chargeSolve(carrier, SimplexBasisOperation.DENSE_FTRAN)
        val solver = solver()
        val before = operationWork(solver)
        profiled(SimplexProfilePhase.FTRAN) { solver.ftran(carrier, expectedDensity = 1.0) }
        refactorPolicy.recordBasisSolve(
            operationDelta(before, operationWork(solver)) { it.ftran },
            boundUpdateFtran,
        )
        sampleSolveQuality(solver, b, carrier, transpose = false)
        return carrier.gather(out)
    }

    private fun requireFiniteRhs(b: DoubleArray) {
        for (x in b) if (!x.isFinite()) throw BasisArithmeticException("nonfinite basis right-hand side")
    }

    private fun btranDense(b: DoubleArray, out: DoubleArray, carrier: IndexedVector): DoubleArray {
        requireFiniteRhs(b)
        carrier.scatter(b)
        chargeSolve(carrier, SimplexBasisOperation.DENSE_BTRAN)
        val solver = solver()
        val before = operationWork(solver)
        profiled(SimplexProfilePhase.BTRAN) { solver.btran(carrier, expectedDensity = 1.0) }
        refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.btran })
        sampleSolveQuality(solver, b, carrier, transpose = true)
        return carrier.gather(out)
    }

    // Left exactly as the solver filled it, so [foldPivot] can hand it back without another solve.
    private fun spike(q: Int) {
        scatterColumn(q, spikeVec)
        chargeSolve(spikeVec, SimplexBasisOperation.ENTERING_FTRAN)
        val solver = solver()
        val before = operationWork(solver)
        profiled(SimplexProfilePhase.FTRAN) { solver.ftran(spikeVec, ftranDensity) }
        refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.ftran })
        if (shouldSampleQuality()) {
            sampleSolveQuality(solver, denseColumn(q), spikeVec, transpose = false, cadenceChecked = true)
        }
        ftranDensity = spikeVec.density
    }

    // Ratio tests choosing by strictly better step, or lowest index under Bland's rule, resolve near-ties in visit
    // order; reading densely keeps that ascending by row. [spikeVec] keeps the indexed form for [foldPivot].
    private fun spikeDense(q: Int, out: DoubleArray): DoubleArray {
        spike(q)
        return spikeVec.gather(out)
    }

    // ρ = B⁻ᵀeᵣ, left as the solver filled it.
    private fun pivotalRow(r: Int) {
        pivotEtaVec.unit(r)
        chargeSolve(pivotEtaVec, SimplexBasisOperation.PIVOTAL_BTRAN)
        val solver = solver()
        val before = operationWork(solver)
        profiled(SimplexProfilePhase.BTRAN) { solver.btran(pivotEtaVec, btranDensity) }
        refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.btran })
        if (shouldSampleQuality()) {
            val rhs = qualityRhs.also {
                it.fill(0.0)
                it[r] = 1.0
            }
            sampleSolveQuality(solver, rhs, pivotEtaVec, transpose = true, cadenceChecked = true)
        }
        btranDensity = pivotEtaVec.density
    }

    // [basicVar] must already name the new basis, since a rebuild here factorizes it. [withPivotEta] offers
    // [pivotEtaVec], which a dual pivot has in hand and a primal one does not.
    private fun foldPivot(r: Int, q: Int, leaving: Int, withPivotEta: Boolean): PivotFold {
        work.add(m)
        nnzB += columnNnz(q) - columnNnz(leaving)
        val solver = solver()
        val before = operationWork(solver)
        val outcome = profiled(SimplexProfilePhase.UPDATE) {
            solver.update(r, q, spikeVec, if (withPivotEta) pivotEtaVec else null)
        }
        if (outcome != BasisUpdate.SINGULAR) {
            refactorPolicy.recordAcceptedUpdate(operationDelta(before, operationWork(solver)) { it.update })
        }
        // APPLIED leaves the factors fit to carry on; REFACTORIZE leaves them fit but worn, and SINGULAR
        // parted them from the basis so only a rebuild recovers. The policy rebuilds on either of the
        // latter, and on an APPLIED once its update cap, cost or residual trigger fires.
        if (outcome != BasisUpdate.SINGULAR) {
            exactBasisCache.clear()
            basisLifecycle.columns[r] = q
            basisLifecycle.unitRows[r] = -1
        }
        val trigger = refactorPolicy.chooseAtSafePoint(
            solver.updateCount,
            solver.nnz,
            backendRequested = outcome == BasisUpdate.REFACTORIZE,
            backendSingular = outcome == BasisUpdate.SINGULAR,
            quality = pendingSolveQuality.also { pendingSolveQuality = null },
        )
        if (trigger == null) return PivotFold.UPDATED
        val reason = trigger.refactorReason()
        return when (refactorize(reason)) {
            SimplexBasisRefactor.UNCHANGED -> PivotFold.REBUILT
            SimplexBasisRefactor.BASIS_CHANGED -> PivotFold.BASIS_CHANGED
            SimplexBasisRefactor.FAILED -> PivotFold.FAILED
        }
    }

    private fun duals(): DoubleArray {
        // Zero objective (the gated feasibility filter): the duals solve `Bᵀy = 0`, so the whole
        // BTRAN — a full pass over the factors, once per iteration — is a zero vector.
        if (allZeroCost) {
            dualValues.fill(0.0)
            return dualValues
        }
        for (i in 0 until m) dualRhs[i] = cost(basicVar[i])
        return btranDense(dualRhs, dualValues, dualVec)
    }

    private val allZeroCost: Boolean get() = (0 until numVars).all { cost(it) == 0.0 }
    private fun resetGamma() = pricingState.resetReference()

    // Bound tightening keeps a parent's optimal basis dual-feasible, so a warm solve resumes in a few pivots. A
    // mismatched or singular warm basis falls back to a cold start, so reuse is sound whatever its origin.
    override fun solve(warm: Basis?): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        val progress = SolveProgress()
        return immediateLpSolve {
            runNumericalSolve(progress) { reset -> solveCore(warm, reuse = false, reset = reset, progress = progress) }
        }
    }

    override fun openSolve(allowance: LpFloatAllowance?, retainFundingStop: Boolean): LpSolveCursor<FloatLpResult?> {
        check(activeSolve == null) { "LP solve is suspended" }
        return LpSolveCursor<FloatLpResult?>(completed = { activeSolve = null }) {
            val previous = solveControl.floatAllowance
            solveControl.floatAllowance = allowance
            solveControl.retainFundingStop = retainFundingStop
            val reuse = solveStarted
            val progress = SolveProgress()
            try {
                runNumericalSolve(progress) { reset ->
                    solveCore(null, reuse = reuse, reset = reset, progress = progress)
                }
            } finally {
                solveControl.floatAllowance = previous
                solveControl.retainFundingStop = false
            }
        }.also { activeSolve = it }
    }

    override fun promoteSolve(allowance: LpFloatAllowance): Boolean {
        val previous = solveControl.floatAllowance ?: return false
        if (activeSolve == null || allowance.work < previous.work || allowance.iterations < previous.iterations) {
            return false
        }
        solveControl.floatAllowance = allowance
        return true
    }

    private suspend fun pauseAtIteration(progress: SolveProgress) {
        val cursor = activeSolve ?: return
        solveControl.chargeWork()
        profiledCheckpoint { cursor.checkpoint(work.ops) }
        while (solveControl.retainFundingStop && !cancellation()) {
            val stop = when {
                solveControl.workExhausted() -> LpFloatTermination.WORK
                progress.iterations >= solveControl.maxIterations -> LpFloatTermination.PIVOTS
                else -> break
            }
            observations.termination = stop
            profiledCheckpoint { cursor.pauseAt(work.ops) }
            observations.termination = null
        }
    }

    // An unenforced row's slack is pivoted into the basis and never leaves or re-enters, so the row only defines it.
    // Only [enforced] and the rhs change between calls, so the kept factorization carries over. Sound only for an
    // all-zero objective, where every basis is dual-feasible; with nothing kept this is a cold start, whose all-slack
    // basis already has every unenforced slack basic.
    override fun resolveGated(enforced: BooleanArray): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        val progress = SolveProgress()
        return immediateLpSolve {
            runNumericalSolve(progress) { reset ->
                solveCore(null, reuse = true, enforced = enforced, reset = reset, progress = progress)
            }
        }
    }

    override fun prepareLogicals(token: ExecutionControl): Basis? = prepareSourceBasis(null, token)

    override fun prepareBasis(basis: Basis, token: ExecutionControl): Basis? = prepareSourceBasis(basis, token)

    override fun retainedBasis(): Basis? {
        if (activeSolve != null) return null
        if (!basisLifecycle.kept || !basisLifecycle.factorized || basicVar.distinct().size != m ||
            status.count { it == VarStatus.BASIC } != m ||
            basicVar.any { it !in status.indices || status[it] != VarStatus.BASIC }
        ) {
            return null
        }
        return Basis(basicVar.copyOf(), status.copyOf(), captureEligible = false)
    }

    private fun prepareSourceBasis(warm: Basis?, token: ExecutionControl): Basis? {
        if (activeSolve != null) return null
        observations.termination = null
        continuationAvailable = false
        stoppedContinuationBasis = null
        exactBasisCache.clear()
        resetSolveState(false)
        basisLifecycle.kept = false
        basicValueCache.invalidate()
        if (model.exactState == null || token()) return null
        solveControl.stopToken = token
        return try {
            val imported = warm != null && tryWarmStart(warm)
            if (!imported) coldStart()
            var prepared = refactorize(if (imported) LpRefactorReason.WARM_START else LpRefactorReason.INITIAL)
            if (prepared == SimplexBasisRefactor.FAILED && imported && repairStop == null && !token() &&
                (workLimit == 0L || work.ops < workLimit)
            ) {
                coldStart()
                prepared = refactorize(LpRefactorReason.SINGULAR_RECOVERY)
            }
            if (prepared == SimplexBasisRefactor.FAILED || token() ||
                (workLimit > 0L && work.ops > workLimit)
            ) {
                null
            } else {
                basisLifecycle.kept = true
                Basis(basicVar.copyOf(), status.copyOf(), captureEligible = false)
            }
        } catch (primary: BasisArithmeticException) {
            if (repairCallbackFailure === primary) rethrowRepairCallback(primary)
            close()
            null
        }
    }

    override fun adopt(state: LpExactState, token: ExecutionControl): Boolean {
        if (activeSolve != null) return false
        observations.termination = null
        val current = model.exactState ?: return false
        if (current === state) {
            if (token.pollStop()) return false
            solveControl.stopToken = token
            return true
        }
        if (!current.sameMatrix(state) || token.pollStop()) return false
        val next = state.ownerWorkingModel(LpProjectionMeter(cancellation = token)) ?: return false
        if (token.pollStop()) return false
        continuationAvailable = false
        stoppedContinuationBasis = null
        refreshNumerical(next)
        solveControl.stopToken = token
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        return true
    }

    // Retires scaled factors atomically on decline.
    private fun refreshNumerical(next: LpModel) {
        val refreshed = numerical.refresh(next)
        if (refreshed != null) {
            model = next
            numerical = refreshed
            pendingNumericalWork.add(refreshed.metrics.work)
            return
        }
        installUnscaledFallback(next)
    }

    internal fun adoptProbe(next: LpModel, source: LpScalingView, token: ExecutionControl): Boolean {
        val current = model.exactState ?: return false
        val state = next.exactState ?: return false
        if (activeSolve != null || allowUnscaledFallback || !current.sameMatrix(state) || token()) return false
        if (source.colPtr !== numerical.colPtr || source.rowIdx !== numerical.rowIdx ||
            source.colVal !== numerical.colVal || source.rowExponents !== numerical.rowExponents ||
            source.columnExponents !== numerical.columnExponents
        ) return false
        val refreshed = source.refresh(next) ?: return false
        if (token()) return false
        continuationAvailable = false
        stoppedContinuationBasis = null
        model = next
        numerical = refreshed
        pendingNumericalWork.add(refreshed.metrics.work)
        solveControl.stopToken = token
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        return true
    }

    private fun installUnscaledFallback(next: LpModel) {
        val fallback = LpScalingView.identityAfterFallback(next, numerical.metrics)
        val failure = basisLifecycle.retire()
        model = next
        numerical = fallback
        pendingNumericalWork.add(fallback.metrics.work)
        columns = lpColumns(numerical)
        colPtr = columns.copyColumnPointers()
        rowIdx = columns.copyRowIndices()
        colVal = columns.values
        rebuildRowView()
        refactorPolicy = RefactorPolicy(
            RefactorPolicyConfig(
                hardUpdateCap = refactorUpdateLimit,
                syntheticWorkEnabled = syntheticWorkEnabled,
            ),
        )
        // Artificial sides are scaled values of the retired view.
        artificialBounds.clear()
        invalidateBasisDependentState()
        if (failure != null) throw failure
    }

    private fun invalidateUncertainBasisState() {
        continuationAvailable = false
        stoppedContinuationBasis = null
        basisLifecycle.invalidate()
        invalidateBasisDependentState()
    }

    override val basisLifecycleWork: BasisOperationWork?
        get() = basisLifecycle.operationWork

    private fun trackedHeadingsConsistent(): Boolean {
        if (basisLifecycle.columns.size != m || basisLifecycle.unitRows.size != m || basicVar.distinct().size != m) {
            return false
        }
        if (!basisStatusConsistent(model, basicVar, status)) return false
        return basicVar.indices.all { slot ->
            val column = basisLifecycle.columns[slot]
            val unit = basisLifecycle.unitRows[slot]
            (column >= 0) != (unit >= 0) &&
                (if (column >= 0) column else n + unit) == basicVar[slot]
        }
    }

    private fun basisStatusConsistent(source: LpModel, headings: IntArray, seats: Array<VarStatus>): Boolean {
        if (headings.size != source.m || seats.size != source.numVars || headings.distinct().size != source.m) {
            return false
        }
        if (headings.any { it !in seats.indices || seats[it] != VarStatus.BASIC }) return false
        if (seats.count { it == VarStatus.BASIC } != source.m) return false
        return seats.indices.all { column ->
            when (seats[column]) {
                VarStatus.BASIC -> true
                VarStatus.AT_LOWER -> source.hasFiniteLower(column)
                VarStatus.AT_UPPER -> source.hasFiniteUpper(column)
                VarStatus.FIXED -> source.fixed(column)
                VarStatus.FREE -> !source.hasFiniteLower(column) && !source.hasFiniteUpper(column)
            }
        }
    }

    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
        withFloatAllowance(allowance) {
            val progress = SolveProgress()
            immediateLpSolve {
                runNumericalSolve(progress) { reset ->
                    solveCore(null, reuse = true, reset = reset, progress = progress)
                }
            }
        }

    override fun <T> withFloatAllowance(allowance: LpFloatAllowance?, solve: () -> T): T {
        check(activeSolve == null) { "LP solve is suspended" }
        val previous = solveControl.floatAllowance
        solveControl.floatAllowance = allowance
        return try {
            solve()
        } finally {
            solveControl.floatAllowance = previous
        }
    }

    override fun takePendingPreparationWork(): Long = pendingNumericalWork.ops.also { pendingNumericalWork.reset() }

    private fun clearNumericalPublication() {
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        basicValueCache.invalidate()
    }

    private fun remainingStop(progress: SolveProgress): LpFloatTermination? = when {
        cancellation() || repairStop == BasisRepairStop.CANCELLED -> LpFloatTermination.CANCELLED
        repairStop == BasisRepairStop.WORK -> LpFloatTermination.WORK
        repairStop == BasisRepairStop.UNKNOWN_WORK -> LpFloatTermination.OTHER
        solveControl.workExhausted() -> LpFloatTermination.WORK
        progress.iterations >= solveControl.maxIterations -> LpFloatTermination.PIVOTS
        else -> null
    }

    private fun repairTermination(): LpFloatTermination = if (cancellation()) {
        LpFloatTermination.CANCELLED
    } else {
        when (repairStop) {
            BasisRepairStop.CANCELLED -> LpFloatTermination.CANCELLED
            BasisRepairStop.WORK -> LpFloatTermination.WORK
            BasisRepairStop.UNKNOWN_WORK, null -> LpFloatTermination.OTHER
        }
    }

    private fun resourcesRemain(progress: SolveProgress): Boolean {
        val stop = remainingStop(progress) ?: return true
        observations.termination = stop
        return false
    }

    private fun stopped(reason: LpFloatTermination, result: FloatLpResult? = null): FloatLpResult? {
        // A stop leaves the seated basis for continuation, which reads it against the real bounds.
        if (artificialBounds.active) {
            artificialBounds.clear()
            repairNonbasicStatuses()
        }
        observations.termination = reason
        return result
    }

    private suspend fun runNumericalSolve(
        progress: SolveProgress,
        block: suspend (reset: Boolean) -> FloatLpResult?,
    ): FloatLpResult? {
        val previous = progressTrace?.takeIf { it.profile }
        val checkpoints = previous?.checkpointNanos ?: 0L
        val started = previous?.let { TimeSource.Monotonic.markNow() }
        try {
            return runChargedSolve(progress, block)
        } finally {
            observations.lastPreparationOps = saturatedAdd(observations.lastPreparationOps,
                takePendingPreparationWork())
            solveControl.chargeWork()
            val trace = progressTrace
            if (started != null && trace != null) {
                val excluded = trace.checkpointNanos - if (trace === previous) checkpoints else 0L
                trace.recordPhase(SimplexProfilePhase.SOLVE,
                    maxOf(0L, started.elapsedNow().inWholeNanoseconds - excluded))
                profileSink?.invoke(lastMetrics, trace)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runChargedSolve(
        progress: SolveProgress,
        block: suspend (reset: Boolean) -> FloatLpResult?,
    ): FloatLpResult? {
        observations.termination = null
        observations.numericalMetrics = SimplexNumericalMetrics()
        numericalFailure = false
        return try {
            var result = numericalSolve(progress, block, allowUnscaledFallback = allowUnscaledFallback)
            if (result?.optimal != true &&
                (progress.iterations >= solveControl.maxIterations || solveControl.workExhausted())
            ) {
                observations.numericalMetrics.capExits++
            }
            if (rejectedExactBasis?.contentEquals(result?.basis?.basicVars ?: basicVar) == true) {
                clearNumericalPublication()
                basisLifecycle.kept = false
                result = null
                observations.termination = LpFloatTermination.OTHER
            }
            if (result == null && infeasibleRay == null && numericalFailure) {
                basisLifecycle.factorized = false
                basisLifecycle.kept = false
            }
            observations.termination = when {
                result?.optimal == true -> LpFloatTermination.OPTIMAL_CANDIDATE
                observations.termination != null -> observations.termination
                result != null -> LpFloatTermination.OTHER
                infeasibleRay != null -> LpFloatTermination.INFEASIBLE_CANDIDATE
                numericalFailure -> LpFloatTermination.NUMERICAL
                else -> LpFloatTermination.OTHER
            }
            result
        } catch (primary: Throwable) {
            observations.termination = null
            try {
                close()
            } catch (cleanup: Throwable) {
                primary.addSuppressed(cleanup)
            }
            throw primary
        }
    }

    // A checked basis failure cannot supply a terminal claim or a factorization safe to keep.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun numericalSolve(
        progress: SolveProgress,
        block: suspend (reset: Boolean) -> FloatLpResult?,
        allowUnscaledFallback: Boolean,
        reset: Boolean = true,
    ): FloatLpResult? = try {
        stoppedContinuationBasis = null
        continuationAvailable = true
        numericalFailure = false
        val candidate = block(reset)
        if (candidate == null && allowUnscaledFallback && numerical.applied &&
            infeasibleRay == null && numericalFailure && resourcesRemain(progress)
        ) {
            installUnscaledFallback(model)
            return numericalSolve(progress, block, false, reset = false)
        }
        val result = if (model.exactState != null && cancellation()) {
            observations.termination = LpFloatTermination.CANCELLED
            solvedExactState = null
            optimalBasis = null
            optimalPrimal = null
            null
        } else {
            candidate
        }
        result.also {
            if (model.exactState != null && it == null && solvedExactState == null) {
                basicValueCache.invalidate()
                basisLifecycle.kept = false
                infeasibleBasis = null
                infeasibleRow = -1
                infeasibleRay = null
            }
        }
    } catch (_: LpWorkExhausted) {
        // Admission can stop between a new heading and its factor update; retire all partial numerical state.
        val cancelled = cancellation()
        close()
        observations.termination = if (cancelled) LpFloatTermination.CANCELLED else LpFloatTermination.WORK
        null
    } catch (primary: LpScalingArithmeticException) {
        if (repairCallbackFailure === primary) rethrowRepairCallback(primary)
        if (allowUnscaledFallback && numerical.applied && resourcesRemain(progress)) {
            installUnscaledFallback(model)
            numericalSolve(progress, block, false, reset = false)
        } else {
            retireAfterArithmeticFailure(primary)
        }
    } catch (primary: BasisArithmeticException) {
        if (repairCallbackFailure === primary) rethrowRepairCallback(primary)
        if (allowUnscaledFallback && numerical.applied && resourcesRemain(progress)) {
            installUnscaledFallback(model)
            numericalSolve(progress, block, false, reset = false)
        } else {
            retireAfterArithmeticFailure(primary)
        }
    } catch (primary: Throwable) {
        try {
            close()
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
        }
        throw primary
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rethrowRepairCallback(primary: Throwable): Nothing {
        try {
            close()
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
        }
        throw primary
    }

    @Suppress("TooGenericExceptionCaught")
    private fun retireAfterArithmeticFailure(primary: ArithmeticException): FloatLpResult? {
        observations.termination = LpFloatTermination.NUMERICAL
        val continuation = continuationBasis(model)
        try {
            close()
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
            throw primary
        }
        stoppedContinuationBasis = continuation
        continuationAvailable = continuation != null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        basicValueCache.invalidate()
        solvedExactState = null
        return null
    }

    private fun resetSolveState(warmAttempted: Boolean) {
        factorCopyFailed = false
        hasImportedParentValues = false
        progressTrace?.let { progressTrace = SimplexProgressTrace(it.profile) }
        solveStarted = true
        artificialBounds.clear()
        repairStop = null
        repairCallbackFailure = null
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        observations.reset(warmAttempted)
        solveControl.resetWork()
        observations.lastPreparationOps = takePendingPreparationWork()
        observations.warmStarted = false
    }

    private suspend fun solveCore(
        warm: Basis?,
        reuse: Boolean,
        enforced: BooleanArray? = null,
        reset: Boolean = true,
        progress: SolveProgress = SolveProgress(),
    ): FloatLpResult? {
        // Per-solve state: the infeasibility certificate slots and counters must not leak across a
        // persistent instance's solves.
        if (reset) resetSolveState(reuse || warm != null)
        if (!reset && !resourcesRemain(progress)) return null
        if (model.exactState != null &&
            (enforced != null || cancellation() || model.exactState?.conflict != null)
        ) {
            if (cancellation()) observations.termination = LpFloatTermination.CANCELLED
            return null
        }
        var kept = reuse && basisLifecycle.kept && basisLifecycle.factorized
        basisLifecycle.kept = false
        // A kept factorization implies the basis it factorizes is still seated, so that is the warmest
        // start there is; a warm basis alone still pays for a factorization.
        observations.warmStarted = if (reset) kept else observations.warmStarted || kept
        // A warm basis can be singular; the slack cold start never is.
        if (!kept) {
            artificialBounds.clear()
            if (warm == null) {
                coldStart()
            } else {
                if (!tryWarmStart(warm)) coldStart() else observations.warmStarted = true
            }
            if (refactorize(
                    if (observations.warmStarted) LpRefactorReason.WARM_START else LpRefactorReason.INITIAL,
                ) == SimplexBasisRefactor.FAILED
            ) {
                if (repairStop != null || factorCopyFailed) return null
                // The warm basis factorized singular, so the solve runs from the slack start after all.
                coldStart()
                observations.warmStarted = false
                if (refactorize(LpRefactorReason.SINGULAR_RECOVERY) == SimplexBasisRefactor.FAILED) return null
            }
        }
        val seeded = pendingProbeValues && hasImportedFactors
        if (seeded) kept = true
        pendingProbeValues = false
        if (enforced != null) {
            // Every unenforced row's slack must be basic before the main loop. A failed reconciliation
            // resets to the all-slack cold start, where the invariant holds trivially.
            when (reconcileUnenforced(enforced)) {
                IterationResult.CONTINUE -> Unit

                IterationResult.RESTART -> return restartDual(enforced, progress)

                IterationResult.BASIS_CHANGED -> return restartDual(enforced, progress)

                IterationResult.FAILED -> {
                    if (repairStop != null) return null
                    coldStart()
                    if (refactorize(LpRefactorReason.RECONCILE_RECOVERY) == SimplexBasisRefactor.FAILED) return null
                }
            }
        }
        if (model.exactState != null) repairNonbasicStatuses()
        val before = basicValueCache.model?.exactState
        val after = model.exactState
        val objectiveOnly = kept && before != null && after != null && before.sameMatrix(after) &&
            before.boundRevision == after.boundRevision && before.popRevision == after.popRevision &&
            before.rowRevision == after.rowRevision && before.model.objective != after.model.objective
        if (objectiveOnly || (kept && before != null && basicValueCache.primalOnly)) {
            if (before.model.objective != after?.model?.objective) {
                observations.objectiveWarmAttempts++
                observations.objectiveWarmHits++
            }
            return solvePrimalCore(null, reuse = true, reset = false, progress = progress)
        }
        val sameObjective = before?.model?.objective == after?.model?.objective
        val sameSeats = basicValueCache.status?.contentEquals(status) == true
        if ((observations.warmStarted || after != null) &&
            (!kept || !sameObjective || !sameSeats) && !dualFeasible()
        ) {
            val pivotsBeforeOpening = observations.pivots
            when (trySingletonStart(kept, progress)) {
                IterationResult.CONTINUE -> Unit
                IterationResult.BASIS_CHANGED, IterationResult.RESTART -> return restartDual(enforced, progress)
                IterationResult.FAILED -> return null
            }
            val changedOpening = observations.pivots > pivotsBeforeOpening
            if (!changedOpening || !dualFeasible()) {
                // Only the slack start is boxed: a warm or kept basis carries a pivot path the primal can
                // continue, and boxing it would trade that for a cold dual.
                val slackStart = !kept && !observations.warmStarted && after != null
                if (!slackStart || !installArtificialBounds()) {
                    if (kept && before != null && after != null && before.model.objective != after.model.objective) {
                        observations.objectiveWarmAttempts++
                        observations.objectiveWarmRepairs++
                    }
                    return solvePrimalCore(null, reuse = true, reset = false, progress = progress)
                }
            }
        }
        resetGamma()
        // Bounds stay fixed for this solve; the leaving scan otherwise reads every basic column from the
        // exact model on every iteration. Artificial sides count as finite until they are dropped.
        val finiteLower = if (m > 0 && model.exactState != null) {
            BooleanArray(numVars) { finiteLowerAt(it) }
        } else {
            null
        }
        val finiteUpper = if (m > 0 && model.exactState != null) {
            BooleanArray(numVars) { finiteUpperAt(it) }
        } else {
            null
        }
        val beta = dualBeta
        // Cached basic values were seated on the real bounds, which an artificial seat does not match.
        val useCached = kept && model.exactState != null && !artificialBounds.active && basicValueCache.restore(
            model, numerical, status, beta, workspace, work, colPtr,
            accumulate = { rhs, delta, column -> sparseAxpy(rhs, delta, column) },
            solve = { rhs, change -> ftranDense(rhs, change, rhsVec, boundUpdateFtran = true) },
        )
        hasImportedParentValues = seeded && useCached
        return when (dualPhase.run(
            model, numerical, enforced, progress, finiteLower, finiteUpper, rowCols, rowVals, useCached,
        )) {
            DualPhaseResult.OPTIMAL -> optimal(beta)
            DualPhaseResult.RESTART -> restartDual(enforced, progress)
            DualPhaseResult.PRIMAL_HANDOFF -> handArtificialBasisToPrimal(progress)
            DualPhaseResult.NUMERICAL_FAILURE -> {
                numericalFailure = true
                null
            }
            DualPhaseResult.FAILED -> null
            DualPhaseResult.INFEASIBLE -> {
                infeasibleBasis = Basis(basicVar.copyOf(), status.copyOf(), captureEligible = basisCaptureEligible)
                infeasibleRow = dualPhase.infeasibleRow
                infeasibleRay = DoubleArray(m) { numerical.sourceDual(it, pivotEtaVec[it]) }
                basisLifecycle.kept = true
                basicValueCache.retain(model, numerical, status, beta)
                solvedExactState = model.exactState
                stopped(LpFloatTermination.INFEASIBLE_CANDIDATE)
            }
            DualPhaseResult.STOPPED -> stopped(
                checkNotNull(dualPhase.termination), if (dualPhase.publishStoppedBound) boundOnly(beta) else null,
            )
        }
    }

    private suspend fun restartDual(enforced: BooleanArray?, progress: SolveProgress): FloatLpResult? {
        if (cancellation()) return stopped(LpFloatTermination.CANCELLED)
        if (progress.restarts >= MAX_SOLVE_RESTARTS) return stopped(LpFloatTermination.NUMERICAL)
        progress.restarts++
        basisLifecycle.kept = true
        return solveCore(
            warm = null,
            reuse = true,
            enforced = enforced,
            reset = false,
            progress = progress,
        )
    }

    private fun refactorAtQualitySafePoint(): SimplexBasisRefactor? {
        val current = solver()
        val trigger = refactorPolicy.chooseAtSafePoint(
            current.updateCount,
            current.nnz,
            quality = pendingSolveQuality.also { pendingSolveQuality = null },
        ) ?: return null
        lastSafePointTrigger = trigger
        return refactorize(trigger.refactorReason())
    }

    // The trigger of the latest safe-point refactor: a routine cost trigger that leaves the basis unchanged needs no
    // recovery, while a residual one spends the shared restart budget.
    private var lastSafePointTrigger: EngineRefactorTrigger? = null

    private val routineRefactor: Boolean
        get() = when (lastSafePointTrigger) {
            EngineRefactorTrigger.HARD_UPDATE_CAP,
            EngineRefactorTrigger.FILL_GROWTH,
            EngineRefactorTrigger.SOLVE_WORK_GROWTH,
            EngineRefactorTrigger.SYNTHETIC_WORK,
            -> true

            else -> false
        }

    // b − Σ_{j nonbasic at upper} A_j·u_j.
    private fun adjustedRhs(out: DoubleArray) {
        work.add(m)
        for (i in 0 until m) out[i] = numerical.rhsD(i)
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val u = currentSeat(status[j], j)
            if (u == 0.0) continue
            work.add(2 * (colPtr[j + 1] - colPtr[j]))
            sparseAxpy(out, -u, j)
        }
    }

    private fun seat(source: LpScalingView, side: VarStatus, column: Int): Double = when (side) {
        VarStatus.AT_UPPER -> source.upperD(column)
        VarStatus.AT_LOWER, VarStatus.FIXED -> source.lowerD(column)
        VarStatus.FREE, VarStatus.BASIC -> 0.0
    }

    private fun sideStatus(column: Int, upper: Boolean): VarStatus =
        if (model.exactState != null && model.fixed(column)) {
            VarStatus.FIXED
        } else if (upper) {
            VarStatus.AT_UPPER
        } else {
            VarStatus.AT_LOWER
        }

    // Reads an artificial side where one is installed.
    private fun currentSeat(side: VarStatus, column: Int): Double = when (side) {
        VarStatus.AT_UPPER -> upperAt(column)
        VarStatus.AT_LOWER, VarStatus.FIXED -> lowerAt(column)
        VarStatus.FREE, VarStatus.BASIC -> 0.0
    }

    private fun defaultStatus(column: Int): VarStatus = when {
        model.exactState != null && model.fixed(column) -> VarStatus.FIXED

        model.hasFiniteLower(
            column,
        ) && (cost(column) >= 0.0 || !model.hasFiniteUpper(column)) -> VarStatus.AT_LOWER

        model.hasFiniteUpper(column) -> VarStatus.AT_UPPER

        else -> VarStatus.FREE
    }

    private fun repairNonbasicStatuses() {
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            status[j] = when {
                model.fixed(j) -> VarStatus.FIXED
                status[j] == VarStatus.AT_LOWER && finiteLowerAt(j) -> VarStatus.AT_LOWER
                status[j] == VarStatus.AT_UPPER && finiteUpperAt(j) -> VarStatus.AT_UPPER
                else -> defaultStatus(j)
            }
        }
    }

    private fun dualFeasible(): Boolean {
        val y = duals()
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            val reduced = cost(j) - dotColumn(y, j)
            if (!reduced.isFinite()) return recordDualFailure(j, reduced)
            when (status[j]) {
                VarStatus.AT_LOWER -> if (reduced < -tolerance) return recordDualFailure(j, reduced)
                VarStatus.AT_UPPER -> if (reduced > tolerance) return recordDualFailure(j, reduced)
                VarStatus.FREE -> if (abs(reduced) > tolerance) return recordDualFailure(j, reduced)
                else -> Unit
            }
        }
        return true
    }

    private var dualFailureColumn = -1

    private fun recordDualFailure(column: Int, reduced: Double): Boolean {
        dualFailureColumn = column
        progressTrace?.let { trace ->
            if (trace.dualStart == null) {
                val singleton = columnNnz(column) == 1
                val start = colPtr[column]
                trace.dualStart = SimplexDualStart(column, status[column], reduced,
                    finiteLowerAt(column), finiteUpperAt(column), columnNnz(column),
                    if (singleton) colVal[start] else null,
                    if (singleton) model.fixed(n + rowIdx[start]) else null)
            }
        }
        return false
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun trySingletonStart(kept: Boolean, progress: SolveProgress): IterationResult {
        if (!singletonStart || !kept || model.exactState == null ||
            basicValueCache.model != null || progress.iterations != 0L
        ) {
            return IterationResult.CONTINUE
        }
        val q = dualFailureColumn
        if (q !in 0 until n || status[q] != VarStatus.FREE || columnNnz(q) != 1) {
            return IterationResult.CONTINUE
        }
        val entry = colPtr[q]
        val row = rowIdx[entry]
        val leaving = n + row
        if (!model.fixed(leaving) || !colVal[entry].isFinite() || abs(colVal[entry]) < tolerance) {
            return IterationResult.CONTINUE
        }
        for (i in 0 until m) {
            work.add(1)
            if (basicVar[i] != n + i) return IterationResult.CONTINUE
        }
        if (!resourcesRemain(progress)) return IterationResult.FAILED
        // The singleton is a scalar multiple of the fixed logical, preserving nonsingularity.
        spike(q)
        if (!resourcesRemain(progress)) return IterationResult.FAILED
        if (abs(spikeVec[row]) < tolerance) return IterationResult.CONTINUE
        status[leaving] = sideStatus(leaving, upper = false)
        basicVar[row] = q
        status[q] = VarStatus.BASIC
        observations.pivots++
        progress.dualIterations++
        observations.numericalMetrics.singletonStarts++
        when (foldPivot(row, q, leaving, withPivotEta = false)) {
            PivotFold.UPDATED, PivotFold.REBUILT -> Unit
            PivotFold.BASIS_CHANGED -> return IterationResult.BASIS_CHANGED
            PivotFold.FAILED -> return IterationResult.FAILED
        }
        val y = duals()
        for (j in 0 until numVars) {
            if (j % CANCEL_POLL == 0 && !resourcesRemain(progress)) return IterationResult.FAILED
            work.add(1)
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            val reduced = cost(j) - dotColumn(y, j)
            when {
                reduced > tolerance && model.hasFiniteLower(j) -> status[j] = VarStatus.AT_LOWER
                reduced < -tolerance && model.hasFiniteUpper(j) -> status[j] = VarStatus.AT_UPPER
            }
        }
        return IterationResult.CONTINUE
    }

    private fun finiteLowerAt(column: Int): Boolean = artificialBounds.finiteLowerAt(model, column)
    private fun finiteUpperAt(column: Int): Boolean = artificialBounds.finiteUpperAt(model, column)
    private fun lowerAt(column: Int): Double = artificialBounds.lowerAt(numerical, column)
    private fun upperAt(column: Int): Double = artificialBounds.upperAt(numerical, column)

    private fun installArtificialBounds(): Boolean =
        artificialBounds.install(model, numerical, primalOperations, tolerance) {
            if (dualFeasible()) {
                true
            } else {
                artificialBounds.clear()
                repairNonbasicStatuses()
                false
            }
        }

    // The primal would only reseat artificial seats arbitrarily, so it restarts from the slack start.
    private suspend fun handArtificialBasisToPrimal(progress: SolveProgress): FloatLpResult? {
        observations.numericalMetrics.artificialHandoffs++
        artificialBounds.clear()
        coldStart()
        if (refactorize(LpRefactorReason.PRIMAL) == SimplexBasisRefactor.FAILED) return null
        return solvePrimalCore(null, reuse = true, reset = false, progress = progress)
    }

    // Evicting another unenforced slack re-queues it, bounded by a 2m guard; a singular spike or exhausted guard fails
    // and the caller cold-starts. Only called with an all-zero objective, so arbitrary evicted statuses keep the
    // basis dual-feasible.
    private fun reconcileUnenforced(enforced: BooleanArray): IterationResult {
        val alphaBuf = alphaValues
        var guard = 0
        var i = 0
        val requeued = reconcileQueue
        requeued.clear()
        var queuePosition = 0
        while (true) {
            val row = when {
                i < m -> i++
                queuePosition < requeued.size -> requeued[queuePosition++]
                else -> return IterationResult.CONTINUE
            }
            val sc = n + row
            if (enforced[row] || status[sc] == VarStatus.BASIC) continue
            if (guard++ > 2 * m) return IterationResult.FAILED
            val alpha = spikeDense(sc, alphaBuf)
            // Pivot the slack in where its spike is largest, preferring not to evict another
            // unenforced slack (which would only re-queue it).
            var r = -1
            var best = tolerance
            var rAny = -1
            var bestAny = tolerance
            for (t in 0 until m) {
                val mag = abs(alpha[t])
                if (mag > bestAny) {
                    bestAny = mag
                    rAny = t
                }
                val v = basicVar[t]
                if (!(v >= n && !enforced[v - n]) && mag > best) {
                    best = mag
                    r = t
                }
            }
            if (r == -1) r = rAny
            if (r == -1) {
                observations.smallPivotBails++
                numericalFailure = true
                return IterationResult.FAILED // singular spike: no pivotable row
            }
            val evicted = basicVar[r]
            if (evicted >= n && !enforced[evicted - n]) requeued.add(evicted - n)
            status[evicted] = VarStatus.AT_LOWER
            basicVar[r] = sc
            status[sc] = VarStatus.BASIC
            observations.pivots++
            // No pivotal row here: this is a designated primal-style pivot, so the solver computes the
            // transposed solve itself if its update needs one.
            when (foldPivot(r, sc, evicted, withPivotEta = false)) {
                PivotFold.UPDATED, PivotFold.REBUILT -> Unit
                PivotFold.BASIS_CHANGED -> return IterationResult.BASIS_CHANGED
                PivotFold.FAILED -> return IterationResult.FAILED
            }
        }
    }

    private fun probeTheorySupport(candidate: Int, leavingRow: Int, enforced: BooleanArray?): TheoryProbe {
        observations.lastTheoryPricingSamples++
        val solveCharge = nnzB.toLong() + (basisLifecycle.current?.updateCount ?: 0).toLong() * m
        addTheoryPricingWork(solveCharge)
        observations.lastTheoryPricingEstimatedFtranWorkOps += solveCharge
        observations.lastTheoryPricingWorkOps += columnNnz(candidate).toLong()
        scatterColumn(candidate, pricingSpikeVec)
        if (pricingResourceStopped()) return TheoryProbe.ResourceStopped
        val solver = solver()
        val before = operationWork(solver)
        var failed = false
        try {
            profiled(SimplexProfilePhase.FTRAN) { solver.ftran(pricingSpikeVec, ftranDensity) }
        } catch (_: BasisArithmeticException) {
            failed = true
        } catch (_: ArithmeticException) {
            failed = true
        } finally {
            refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.ftran })
        }
        if (pricingResourceStopped()) return TheoryProbe.ResourceStopped
        if (failed) return TheoryProbe.ArithmeticDeclined
        observations.lastTheoryPricingSuccessfulSamples++
        var support = 0
        pricingSpikeVec.forEachStored { row, value ->
            addTheoryPricingWork(1L)
            if (row != leavingRow && abs(value) >= THEORY_SUPPORT_TOLERANCE &&
                basicVariableIsNonFree(row, enforced)
            ) {
                support++
            }
        }
        return if (pricingResourceStopped()) TheoryProbe.ResourceStopped else TheoryProbe.Success(support)
    }

    private fun basicVariableIsNonFree(row: Int, enforced: BooleanArray?): Boolean {
        val variable = basicVar[row]
        if (enforced != null && variable >= n && !enforced[variable - n]) return false
        return model.hasFiniteLower(variable) || model.hasFiniteUpper(variable)
    }

    private fun addTheoryPricingWork(amount: Long) {
        work.add(amount)
        observations.lastTheoryPricingWorkOps += amount
    }

    private fun pricingResourceStopped(): Boolean = solveControl.pricingResourceStopped()

    private fun optimal(beta: DoubleArray): FloatLpResult = profiled(SimplexProfilePhase.OUTPUT) {
        basisLifecycle.kept = true
        solvedExactState = model.exactState
        basicValueCache.retain(model, numerical, status, beta)
        // Re-add the lower-bound shift the model folded out (c·lo), so [FloatLpResult.objective] is the
        // objective in original coordinates — matching the exact certify.
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = cost(j)
            if (c != 0.0 && status[j] != VarStatus.BASIC) obj += c * seat(numerical, status[j], j)
        }
        for (i in 0 until m) {
            val c = cost(basicVar[i])
            if (c != 0.0) obj += c * beta[i]
        }
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShiftD(j) + numerical.sourceCoordinate(j, seat(numerical, status[j], j))
        }
        for (i in 0 until m) {
            val v = basicVar[i]
            if (v < n) primal[v] = model.loShiftD(v) + numerical.sourceCoordinate(v, beta[i])
        }
        val basis = Basis(basicVar.copyOf(), status.copyOf(), captureEligible = basisCaptureEligible)
        optimalBasis = basis
        optimalPrimal = primal
        val scaledDuals = duals()
        recordDegeneracy(scaledDuals)
        val y = DoubleArray(m) { numerical.sourceDual(it, scaledDuals[it]) }
        if (!deferUnscaledSourceDiagnostics || numerical.applied) recordSourceResiduals(beta, y)
        return@profiled FloatLpResult(
            basis,
            model.objectiveD(obj),
            y.copyOf(),
            primal,
            observations.pivots,
            observations.maxLuFill,
            observations.maxLuDensity,
            luMaxDim = observations.maxLuDim,
            warmStarted = observations.warmStarted,
            refactorizations = observations.refactorizations,
            exactState = model.exactState,
        )
    }

    private fun recordSourceResiduals(beta: DoubleArray, sourceDuals: DoubleArray) {
        val started = TimeSource.Monotonic.markNow()
        try {
            computeSourceResiduals(beta, sourceDuals)
        } finally {
            observations.sourceResidualCalls++
            if (numerical.applied) observations.sourceResidualScaledCalls++
            observations.sourceResidualNanos += started.elapsedNow().inWholeNanoseconds
            progressTrace?.let {
                it.sourcePrimalResidual = observations.sourcePrimalResidual
                it.sourceBoundViolation = observations.sourceBoundViolation
                it.sourceBasicDualResidual = observations.sourceBasicDualResidual
            }
        }
    }

    private fun computeSourceResiduals(beta: DoubleArray, sourceDuals: DoubleArray) {
        val values = DoubleArray(numVars)
        for (j in 0 until numVars) {
            if (status[j] != VarStatus.BASIC) {
                values[j] = numerical.sourceCoordinate(j, seat(numerical, status[j], j))
            }
        }
        for (i in 0 until m) values[basicVar[i]] = numerical.sourceCoordinate(basicVar[i], beta[i])

        val residual = DoubleArray(m) { -model.rhsD(it) }
        for (j in 0 until n) {
            if (values[j] != 0.0) {
                model.forEachInColumnD(j) { row, coefficient -> residual[row] += coefficient * values[j] }
            }
        }
        for (i in 0 until m) residual[i] += values[n + i]
        observations.sourcePrimalResidual = residual.maxOfOrNull { abs(it) } ?: 0.0

        var boundViolation = 0.0
        for (j in 0 until numVars) {
            if (model.hasFiniteLower(j)) boundViolation = maxOf(boundViolation, model.lowerD(j) - values[j])
            if (model.hasFiniteUpper(j)) boundViolation = maxOf(boundViolation, values[j] - model.upperD(j))
        }
        observations.sourceBoundViolation = maxOf(0.0, boundViolation)

        var dualResidual = 0.0
        for (v in basicVar) {
            val dot = if (v < n) {
                var sum = 0.0
                model.forEachInColumnD(v) { row, coefficient -> sum += coefficient * sourceDuals[row] }
                sum
            } else {
                sourceDuals[v - n]
            }
            dualResidual = maxOf(dualResidual, abs(model.costD(v) - dot))
        }
        observations.sourceBasicDualResidual = dualResidual
        if (numerical.applied &&
            (!observations.sourcePrimalResidual.isFinite() ||
                !observations.sourceBoundViolation.isFinite() || !dualResidual.isFinite())
        ) {
            throw LpScalingArithmeticException()
        }
    }

    // Leaves [optimalBasis] and [optimalPrimal] alone, so tableau cut generation declines on its own.
    private fun truncated(beta: DoubleArray): FloatLpResult = profiled(SimplexProfilePhase.OUTPUT) {
        solvedExactState = model.exactState
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = cost(j)
            if (c != 0.0 && status[j] != VarStatus.BASIC) obj += c * seat(numerical, status[j], j)
        }
        for (i in 0 until m) {
            val c = cost(basicVar[i])
            if (c != 0.0) obj += c * beta[i]
        }
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShiftD(j) + numerical.sourceCoordinate(j, seat(numerical, status[j], j))
        }
        for (i in 0 until m) {
            val v = basicVar[i]
            if (v < n) primal[v] = model.loShiftD(v) + numerical.sourceCoordinate(v, beta[i])
        }
        val scaledDuals = duals()
        recordDegeneracy(scaledDuals)
        val y = DoubleArray(m) { numerical.sourceDual(it, scaledDuals[it]) }
        recordSourceResiduals(beta, y)
        return@profiled FloatLpResult(
            Basis(basicVar.copyOf(), status.copyOf(), captureEligible = basisCaptureEligible),
            model.objectiveD(obj),
            y.copyOf(),
            primal,
            observations.pivots,
            observations.maxLuFill,
            observations.maxLuDensity,
            luMaxDim = observations.maxLuDim,
            warmStarted = observations.warmStarted,
            refactorizations = observations.refactorizations,
            optimal = false,
            exactState = model.exactState,
        )
    }

    // A boxed iterate bounds only the boxed LP, a restriction of the real one, so it is withheld.
    private fun boundOnly(beta: DoubleArray): FloatLpResult? = if (artificialBounds.active) null else truncated(beta)

    private var optimalBasis: Basis? = null

    private var optimalPrimal: DoubleArray? = null

    internal fun probeObjective(token: ExecutionControl): Double? {
        val values = basicValueCache.values ?: return null
        if (optimalBasis == null || basicValueCache.model !== model ||
            basicValueCache.primalOnly || basicValueCache.numerical !== numerical
        ) {
            return null
        }
        val scale = model.exactState?.model?.objective?.scale?.approximation ?: return null
        if (!scale.isFinite() || scale <= 0.0) return null
        var objective = 0.0
        for (j in 0 until numVars) {
            if (j % 256 == 0 && token()) return null
            if (status[j] != VarStatus.BASIC) objective += cost(j) * seat(numerical, status[j], j)
        }
        for (i in 0 until m) {
            if (i % 256 == 0 && token()) return null
            objective += cost(basicVar[i]) * values[i]
        }
        return (objective / scale).takeIf { it.isFinite() }
    }

    private fun probeOrderWork(): Long = if (basisSolverFactory == null &&
        basisLifecycle.current?.orderHintDimension == m
    ) 24L * m + 64L else 0L

    private fun probeFactorCopy(): BasisFactorCopy? = if (basisSolverFactory == null && basisLifecycle.factorized) {
        basisLifecycle.current?.factorCopy?.takeIf { it.dimension == m }
    } else null

    private fun currentProbeValues(point: LpModel, basis: Basis): Boolean = reuseProbeValues &&
        point === model && optimalBasis === basis && basicValueCache.model === point &&
        basicValueCache.numerical === numerical && !basicValueCache.primalOnly &&
        basicValueCache.values?.size == m && basicValueCache.status?.size == numVars && optimalPrimal?.size == n

    private fun probeValueWork(point: LpModel, basis: Basis, columns: IntArray): Long {
        if (!currentProbeValues(point, basis) || columns.any {
                status[it] != VarStatus.BASIC || cost(it) == 0.0 || optimalPrimal?.get(it) == model.loShiftD(it)
            }
        ) return 0L
        val capture = 4L * m + 4L * numVars + 64L
        val solve = nnzB.toLong() + (basisLifecycle.current?.updateCount ?: 0).toLong() * m
        // Nonzero basic values and costs require both setup solves; basic bounds preserve the nonbasic seats.
        return capture.takeIf { it + numVars < 2L * solve + m } ?: 0L
    }

    private fun seedProbeValues(
        parent: RevisedSimplex,
        point: LpModel,
        source: LpScalingView,
        basis: Basis,
    ) {
        fun current(): Boolean = !cancellation() && parent.currentProbeValues(point, basis) &&
            parent.numerical === source && numerical.colPtr === source.colPtr &&
            numerical.rowIdx === source.rowIdx && numerical.colVal === source.colVal &&
            numerical.rowExponents === source.rowExponents && numerical.columnExponents === source.columnExponents
        if (!current()) return
        val values = parent.basicValueCache.values?.copyOf() ?: return
        if (!current()) return
        val seats = parent.basicValueCache.status?.copyOf() ?: return
        if (!values.all { it.isFinite() } || !current()) return
        if (!basis.basicVars.contentEquals(parent.basicVar) || !basis.status.contentEquals(parent.status) ||
            !seats.contentEquals(parent.status)
        ) return
        basicValueCache.seed(point, source, values, seats)
        pendingProbeValues = true
    }

    override fun directionalGains(
        pointModel: LpModel,
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: ExecutionControl,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalGain>? {
        fun decline(reason: LpProbeDecline): List<LpDirectionalGain>? {
            observe(LpProbeMetrics(0L, 0L, 0, reason))
            return null
        }
        val state = model.exactState ?: return decline(LpProbeDecline.OWNER)
        val basis = optimalBasis ?: return decline(LpProbeDecline.NO_OPTIMUM)
        val point = optimalPrimal ?: return decline(LpProbeDecline.NO_OPTIMUM)
        if (token()) return decline(LpProbeDecline.CANCELLED)
        if (allowance <= 0L) return decline(LpProbeDecline.ALLOWANCE)
        if (activeSolve != null || !basisLifecycle.factorized) return decline(LpProbeDecline.BUSY)
        if (pointModel.exactState !== state) return decline(LpProbeDecline.STALE_POINT)
        if (columns.isEmpty() || columns.size > 2 ||
            columns.size != splits.size || columns.any { it !in 0 until n } ||
            (columns.size == 2 && columns[0] == columns[1])
        ) return decline(LpProbeDecline.SPLIT)
        for (index in columns.indices) {
            val split = splits[index]
            if (split == Long.MAX_VALUE || !(split.toDouble() < point[columns[index]] &&
                    point[columns[index]] < (split + 1L).toDouble())
            ) return decline(LpProbeDecline.SPLIT)
        }
        val factorCopy = probeFactorCopy()
        val scan = 2L * numVars + m + 1L + if (factorCopy != null) m.toLong() else 0L
        if (scan >= allowance) return decline(LpProbeDecline.ALLOWANCE)
        observe(LpProbeMetrics(scan, 0L, 0))
        val parentObjective = probeObjective(token) ?: return decline(
            if (token()) LpProbeDecline.CANCELLED else LpProbeDecline.NO_OPTIMUM,
        )
        val probeMatrix = matrixView()
        val copy = factorCopy?.takeIf { basis.basicVars.contentEquals(basicVar) }
        val orderWork = if (copy == null) probeOrderWork() else 0L
        val copyWork = copy?.let { it.additionalWork + 6L * m + 64L } ?: 0L
        val valueWork = if (copy != null) probeValueWork(pointModel, basis, columns) else 0L
        val sourceNumerical = numerical
        return LpDirectionalProbes(state, basis, parentObjective,
            sourceView = sourceNumerical, orderPreparation = orderWork, factorPreparation = copyWork,
            factorEntries = copy?.entries ?: 0L,
            factorEntryLimit = pricing.basisEntryLimit,
            parentValuePreparation = valueWork,
            sharedBasisSource = copy?.sharesSource == true) { child, work, entries, view ->
            val order = if (orderWork > 0L) basisLifecycle.current?.orderHint() else null
            val factors = copy?.let {
                SimplexFactorCopy(probeMatrix.columns, it, basis.basicVars,
                    basisLifecycle.columns, basisLifecycle.unitRows)
            }
            RevisedSimplex(child, token, refactorUpdateLimit, iterationLimit, work, trackDegeneracy,
                basisSolverFactory, pricing.copy(basisEntryLimit = minOf(pricing.basisEntryLimit, entries),
                    basisWorkLimit = minOf(pricing.basisWorkLimit, work)), reuseRationalOrder, scalingOptions,
                primalPricingTolerance, syntheticWorkEnabled, phaseOneLongSteps, phaseOneIncrementalValues,
                singletonStart, initialScaling = view, initialMatrix = probeMatrix.takeIf { view != null },
                allowUnscaledFallback = view == null, initialOrder = order, initialFactorCopy = factors,
                reuseProbeValues = reuseProbeValues).also {
                if (valueWork > 0L) it.seedProbeValues(this@RevisedSimplex, pointModel, sourceNumerical, basis)
            }
        }.run(columns, splits, allowance - scan, token, observe)
    }

    internal fun boundProbeWork(allowance: Long) {
        require(allowance > 0L)
        check(activeSolve == null) { "LP solve is suspended" }
        solveControl.strictProbeWorkLimit = allowance
    }

    override fun directionalValues(
        source: LpProbeSource,
        columns: IntArray,
        splits: LongArray,
        allowance: Long,
        token: ExecutionControl,
        observe: (LpProbeMetrics) -> Unit,
    ): List<LpDirectionalValues>? {
        fun decline(reason: LpProbeDecline): List<LpDirectionalValues>? {
            observe(LpProbeMetrics(0L, 0L, 0, reason))
            return null
        }
        val pointModel = source.model
        val pointBasis = source.basis
        val state = model.exactState ?: return decline(LpProbeDecline.OWNER)
        if (token()) return decline(LpProbeDecline.CANCELLED)
        if (allowance <= 0L) return decline(LpProbeDecline.ALLOWANCE)
        if (activeSolve != null) return decline(LpProbeDecline.BUSY)
        if (pointModel.exactState !== state) return decline(LpProbeDecline.STALE_POINT)
        if (solvedExactState !== state) return decline(LpProbeDecline.NO_POINT)
        if (columns.isEmpty() || columns.size > 2 || columns.size != splits.size ||
            columns.any { it !in 0 until n } || splits.any { it == Long.MAX_VALUE } ||
            (columns.size == 2 && columns[0] == columns[1]) ||
            pointBasis.basicVars.size != m || pointBasis.status.size != numVars
        ) return decline(LpProbeDecline.SPLIT)
        val factorCopy = probeFactorCopy()
        val scan = 3L * numVars + 2L * m + 1L + if (factorCopy != null) m.toLong() else 0L
        if (scan >= allowance) return decline(LpProbeDecline.ALLOWANCE)
        observe(LpProbeMetrics(scan, 0L, 0))
        if (token()) return decline(LpProbeDecline.CANCELLED)
        probeBasisDecline(source, token)?.let { return decline(it) }
        val probeMatrix = matrixView()
        val copy = factorCopy?.takeIf { pointBasis.basicVars.contentEquals(basicVar) }
        val orderWork = if (copy == null) probeOrderWork() else 0L
        val copyWork = copy?.let { it.additionalWork + 6L * m + 64L } ?: 0L
        val valueWork = if (copy != null) probeValueWork(pointModel, pointBasis, columns) else 0L
        val sourceNumerical = numerical
        return LpDirectionalProbes(state, pointBasis, sourceView = sourceNumerical, orderPreparation = orderWork,
            factorPreparation = copyWork, factorEntries = copy?.entries ?: 0L,
            factorEntryLimit = pricing.basisEntryLimit,
            parentValuePreparation = valueWork,
            sharedBasisSource = copy?.sharesSource == true,
            factory = { child, work, entries, view ->
                val order = if (orderWork > 0L) basisLifecycle.current?.orderHint() else null
                val factors = copy?.let {
                    SimplexFactorCopy(probeMatrix.columns, it, pointBasis.basicVars,
                        basisLifecycle.columns, basisLifecycle.unitRows)
                }
                RevisedSimplex(child, token, refactorUpdateLimit, iterationLimit, work, trackDegeneracy,
                    basisSolverFactory, pricing.copy(basisEntryLimit = minOf(pricing.basisEntryLimit, entries),
                        basisWorkLimit = minOf(pricing.basisWorkLimit, work)), reuseRationalOrder, scalingOptions,
                    primalPricingTolerance, syntheticWorkEnabled, phaseOneLongSteps, phaseOneIncrementalValues,
                    singletonStart, initialScaling = view, initialMatrix = probeMatrix.takeIf { view != null },
                    allowUnscaledFallback = view == null, initialOrder = order, initialFactorCopy = factors,
                    reuseProbeValues = reuseProbeValues).also {
                    if (valueWork > 0L) it.seedProbeValues(this@RevisedSimplex, pointModel, sourceNumerical, pointBasis)
                }
            }).runValues(columns, splits, allowance - scan, token, observe)
    }

    private fun probeBasisDecline(source: LpProbeSource, token: ExecutionControl): LpProbeDecline? {
        val basis = source.basis
        val seen = BooleanArray(numVars)
        for ((slot, column) in basis.basicVars.withIndex()) {
            if (slot % 256 == 0 && token()) return LpProbeDecline.CANCELLED
            if (column !in seen.indices || seen[column] || basis.status[column] != VarStatus.BASIC) {
                return LpProbeDecline.SPLIT
            }
            seen[column] = true
        }
        for (column in basis.status.indices) {
            if (column % 256 == 0 && token()) return LpProbeDecline.CANCELLED
            val valid = when (basis.status[column]) {
                VarStatus.BASIC -> seen[column]
                VarStatus.AT_LOWER -> !seen[column] && source.model.hasFiniteLower(column)
                VarStatus.AT_UPPER -> !seen[column] && source.model.hasFiniteUpper(column)
                VarStatus.FIXED -> !seen[column] && source.model.fixed(column)
                VarStatus.FREE -> !seen[column] && !source.model.hasFiniteLower(column) &&
                    !source.model.hasFiniteUpper(column)
            }
            if (!valid) return LpProbeDecline.SPLIT
        }
        return null
    }

    override fun directionalCosts(
        pointModel: LpModel,
        columns: IntArray,
        allowance: Long,
        token: ExecutionControl,
        charge: (Long) -> Unit,
    ): List<LpDirectionalCost?>? {
        if (columns.isEmpty() || columns.size > 2 || allowance <= 0L || token() || activeSolve != null ||
            optimalBasis == null || !basisLifecycle.factorized ||
            (model !== pointModel && (model.exactState == null || model.exactState !== pointModel.exactState))
        ) return null
        val factors = basisLifecycle.current ?: return null
        if (nnzB < 0 || nnzB > colVal.size) return null
        // Basic columns are skipped before either CSC dot product.
        val nonbasicNnz = colVal.size.toLong() - nnzB
        val shareCosts = columns.size == 2 && nonbasicNnz > 2L * numVars
        val shared = if (shareCosts) 2L * numVars + nonbasicNnz else 0L
        val scans = if (shareCosts) nonbasicNnz else 2L * nonbasicNnz
        val perColumn = 6L * m + 8L * numVars + scans + nnzB + factors.updateCount.toLong() * m
        if (shared > allowance || perColumn > (allowance - shared) / columns.size ||
            columns.any { it !in 0 until n }
        ) return null
        val required = shared + columns.size * perColumn
        charge(required)
        if (token()) return null
        val row = IndexedVector(m)
        return try {
            val reducedCosts = if (shareCosts) directionalReducedCosts(token) ?: return null else null
            columns.map { column ->
                if (token()) return null
                val slot = basicVar.indexOf(column)
                if (slot < 0) null else {
                    row.unit(slot)
                    factors.btran(row, expectedDensity = 1.0)
                    if (token()) return null
                    directionalRowCosts(column, row, token, reducedCosts)
                }
            }
        } catch (_: BasisArithmeticException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun directionalReducedCosts(token: ExecutionControl): DoubleArray? {
        val costs = DoubleArray(numVars)
        for (j in 0 until numVars) {
            if (j % 256 == 0 && token()) return null
            if (status[j] != VarStatus.BASIC && !model.fixed(j)) {
                costs[j] = cost(j) - columnDot(dualValues, j)
            }
        }
        return costs
    }

    private fun directionalRowCosts(
        column: Int,
        row: IndexedVector,
        token: ExecutionControl,
        reducedCosts: DoubleArray?,
    ): LpDirectionalCost? {
        var down = Double.POSITIVE_INFINITY
        var up = Double.POSITIVE_INFINITY
        for (j in 0 until numVars) {
            if (j % 256 == 0 && token()) return null
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            var coefficient = 0.0
            for (k in colPtr[j] until colPtr[j + 1]) coefficient += row[rowIdx[k]] * colVal[k]
            val reducedCost = reducedCosts?.get(j) ?: (cost(j) - columnDot(dualValues, j))
            if (!coefficient.isFinite() || !reducedCost.isFinite()) return null
            val orientation = if (status[j] == VarStatus.AT_UPPER) -1.0 else 1.0
            val orientedCost = reducedCost * orientation
            if (orientedCost < -tolerance) return null
            val effect = coefficient * orientation
            if (status[j] == VarStatus.FREE) {
                if (abs(reducedCost) > tolerance) return null
                if (abs(effect) > tolerance) return LpDirectionalCost(0.0, 0.0)
            } else if (effect > tolerance) {
                down = minOf(down, maxOf(0.0, orientedCost) / effect)
            } else if (effect < -tolerance) {
                up = minOf(up, maxOf(0.0, orientedCost) / -effect)
            }
        }
        val scale = numerical.sourceCoordinate(column, 1.0) *
            (model.exactState?.model?.objective?.scale?.approximation ?: 1.0)
        if (!scale.isFinite() || scale <= 0.0 || !down.isFinite() || !up.isFinite()) return null
        return LpDirectionalCost(down / scale, up / scale)
    }

    override fun gomoryCuts(maxCuts: Int): List<Cut> {
        val basis = optimalBasis ?: return emptyList()
        val primal = optimalPrimal ?: return emptyList()
        return integerTableauCuts(model, basis, primal, maxCuts, mir = false)
    }

    override fun mirCuts(maxCuts: Int): List<Cut> {
        val basis = optimalBasis ?: return emptyList()
        val primal = optimalPrimal ?: return emptyList()
        return integerTableauCuts(model, basis, primal, maxCuts, mir = true)
    }

    // False (cold start) on a mismatch, a rejected basis, or a status the bounds cannot seat. A singular warm
    // factorization is caught by [solveCore]'s refactor fallback.
    private fun tryWarmStart(warm: Basis): Boolean {
        if (rejectedExactBasis?.contentEquals(warm.basicVars) == true) return false
        basisCaptureEligible = basisCaptureEligible && warm.captureEligible
        if (warm.basicVars.size != m || warm.status.size != numVars) return false
        for (t in 0 until m) if (warm.basicVars[t] !in 0 until numVars) return false
        if (warm.basicVars.distinct().size != m || warm.status.count { it == VarStatus.BASIC } != m) return false
        if (warm.basicVars.any { warm.status[it] != VarStatus.BASIC }) return false
        if (warm.status.indices.any { j ->
                when (warm.status[j]) {
                    VarStatus.BASIC -> false
                    VarStatus.AT_LOWER -> !model.hasFiniteLower(j)
                    VarStatus.AT_UPPER -> !model.hasFiniteUpper(j)
                    VarStatus.FIXED -> !model.fixed(j)
                    VarStatus.FREE -> model.hasFiniteLower(j) || model.hasFiniteUpper(j)
                }
            }
        ) {
            return false
        }
        warm.basicVars.copyInto(basicVar)
        warm.status.copyInto(status)
        return true
    }

    private fun coldStart() {
        exactBasisCache.clear()
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) {
            // A column with no finite upper has no upper seat to take, whatever its cost: seating it
            // there reads an upper the model does not have — for a genuinely open column, the stale
            // probe-derived slot — and starts the solve outside the feasible set.
            status[j] = defaultStatus(j)
        }
    }

    // All slacks basic, structurals at lower: primal-feasible whenever each rhs(i) is within its slack's bounds.
    private fun lowerStart() {
        exactBasisCache.clear()
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) status[j] = if (model.hasFiniteLower(j)) sideStatus(j, false) else defaultStatus(j)
    }

    private fun basicValues(out: DoubleArray): DoubleArray {
        adjustedRhs(basicRhs)
        return ftranDense(basicRhs, out, rhsVec)
    }

    private fun primalFeasible(beta: DoubleArray): Boolean {
        for (i in 0 until m) {
            val v = basicVar[i]
            if (model.hasFiniteLower(v) && beta[i] < numerical.lowerD(v) - feasibilityTolerance) return false
            if (model.hasFiniteUpper(v) && beta[i] > numerical.upperD(v) + feasibilityTolerance) return false
        }
        return true
    }

    // Minimizes w = Σ max(0, l_i − β_i) + max(0, β_i − u_i) with phase-1 duals π = B⁻ᵀγ, γ the violation sign. The
    // ratio test passes crossings that leave a negative slope. FAILED with w > 0 is only an infeasibility candidate.
    private suspend fun primalPhase1(progress: SolveProgress): IterationResult =
        profiled(SimplexProfilePhase.FEASIBILITY_REPAIR) {
            when (primalPhases.repair(model, numerical, progress, phaseOneLongSteps, phaseOneIncrementalValues)) {
                PrimalPhaseResult.COMPLETE -> IterationResult.CONTINUE
                PrimalPhaseResult.RESTART -> IterationResult.RESTART
                PrimalPhaseResult.BASIS_CHANGED -> IterationResult.BASIS_CHANGED
                PrimalPhaseResult.INFEASIBLE -> {
                    if (model.exactState != null) {
                        infeasibleRay = DoubleArray(m) { numerical.sourceDual(it, workspace.phaseOneDuals[it]) }
                        solvedExactState = model.exactState
                        basisLifecycle.kept = true
                        basicValueCache.retain(model, numerical, status, workspace.phaseOneBeta)
                    }
                    IterationResult.FAILED
                }
                PrimalPhaseResult.NUMERICAL_FAILURE -> {
                    numericalFailure = true
                    IterationResult.FAILED
                }
                PrimalPhaseResult.FAILED, PrimalPhaseResult.UNBOUNDED -> IterationResult.FAILED
            }
        }

    // Phase 2 prices by Dantzig's rule, switching to Bland's after a degenerate stall (R. G. Bland, "New finite
    // pivoting rules for the simplex method", Math. Oper. Res. 2 (1977), https://doi.org/10.1287/moor.2.2.103), and an
    // entering variable that reaches its opposite bound first just flips. Null on any failure, so the caller falls
    // back to the dual path; the result is certified downstream like [solve]'s.
    override fun solvePrimal(warm: Basis?): FloatLpResult? {
        check(activeSolve == null) { "LP solve is suspended" }
        val progress = SolveProgress()
        return immediateLpSolve {
            runNumericalSolve(progress) { reset -> solvePrimalCore(warm, reset = reset, progress = progress) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    private suspend fun solvePrimalCore(
        warm: Basis?,
        reuse: Boolean = false,
        reset: Boolean = true,
        progress: SolveProgress = SolveProgress(),
    ): FloatLpResult? {
        if (reset) resetSolveState(warm != null || reuse)
        // The primal runs on the real bounds only; repairing the statuses below reseats any artificial seat.
        artificialBounds.clear()
        if (!reset && !resourcesRemain(progress)) return null
        if (model.exactState != null && (cancellation() || model.exactState?.conflict != null)) {
            if (cancellation()) observations.termination = LpFloatTermination.CANCELLED
            return null
        }
        basisLifecycle.kept = false
        if (!reuse || !basisLifecycle.factorized) {
            if (warm == null || !tryWarmStart(warm)) {
                lowerStart()
            } else {
                observations.warmStarted = true
            }
            if (refactorize(LpRefactorReason.PRIMAL) == SimplexBasisRefactor.FAILED) {
                if (repairStop != null) return null
                lowerStart()
                observations.warmStarted = false
                if (refactorize(LpRefactorReason.SINGULAR_RECOVERY) == SimplexBasisRefactor.FAILED) return null
            }
        }
        if (model.exactState != null) repairNonbasicStatuses()
        val beta = basicValues(primalBeta)
        if (solveControl.workExhausted()) return stopped(LpFloatTermination.WORK)
        when (refactorAtQualitySafePoint()) {
            null -> Unit
            SimplexBasisRefactor.UNCHANGED -> if (routineRefactor) basicValues(beta) else return restartPrimal(progress)
            SimplexBasisRefactor.BASIS_CHANGED -> return restartPrimal(progress)
            SimplexBasisRefactor.FAILED -> return null
        }
        if (!primalFeasible(beta)) {
            when (primalPhase1(progress)) {
                IterationResult.CONTINUE -> Unit
                IterationResult.RESTART -> return restartPrimal(progress)
                IterationResult.BASIS_CHANGED -> return restartPrimal(progress)
                IterationResult.FAILED -> return null
            }
            basicValues(beta)
            if (!primalFeasible(beta)) return null // phase-1 could not reach feasibility
        }
        return when (primalPhases.optimize(model, numerical, progress, beta, primalPricingTolerance)) {
            PrimalPhaseResult.COMPLETE -> optimal(beta)
            PrimalPhaseResult.RESTART, PrimalPhaseResult.BASIS_CHANGED -> restartPrimal(progress)
            PrimalPhaseResult.UNBOUNDED -> {
                basisLifecycle.kept = true
                basicValueCache.retain(model, numerical, status, beta, primalOnly = true)
                solvedExactState = model.exactState
                stopped(LpFloatTermination.UNBOUNDED_CANDIDATE)
            }
            PrimalPhaseResult.NUMERICAL_FAILURE -> {
                numericalFailure = true
                null
            }
            PrimalPhaseResult.FAILED, PrimalPhaseResult.INFEASIBLE -> null
        }
    }

    private suspend fun restartPrimal(progress: SolveProgress): FloatLpResult? {
        if (cancellation()) return stopped(LpFloatTermination.CANCELLED)
        if (progress.restarts >= MAX_SOLVE_RESTARTS) return stopped(LpFloatTermination.NUMERICAL)
        progress.restarts++
        basisLifecycle.kept = true
        return solvePrimalCore(
            null,
            reuse = true,
            reset = false,
            progress = progress,
        )
    }

    private companion object {
        const val TOL: Double = 1e-7

        const val THEORY_SUPPORT_TOLERANCE: Double = 1e-7

        const val FEAS_TOL: Double = 1e-6

        const val CANCEL_POLL: Int = 32

        const val MAX_SOLVE_RESTARTS: Int = 4
    }
}

private enum class IterationResult {
    CONTINUE,
    RESTART,
    BASIS_CHANGED,
    FAILED,
}

private fun EngineRefactorTrigger.refactorReason(): LpRefactorReason = when (this) {
    EngineRefactorTrigger.BACKEND_SINGULAR -> LpRefactorReason.SINGULAR_RECOVERY

    EngineRefactorTrigger.BACKEND_REQUESTED -> LpRefactorReason.BACKEND_REQUESTED

    // The cost triggers end an update chain that has grown too dear, as the hard cap does; only a bad residual is
    // numerical recovery.
    EngineRefactorTrigger.HARD_UPDATE_CAP,
    EngineRefactorTrigger.FILL_GROWTH,
    EngineRefactorTrigger.SOLVE_WORK_GROWTH,
    EngineRefactorTrigger.SYNTHETIC_WORK,
    -> LpRefactorReason.UPDATE_LIMIT

    EngineRefactorTrigger.RESIDUAL -> LpRefactorReason.NUMERICAL_RECOVERY
}

// Slack column n + i is materialized as e_i because the basis seam names columns by index into this matrix. Rebuilt
// only when the unscaled fallback replaces the scaling view; anything replacing the matrix builds a fresh engine.
private fun lpColumns(model: LpScalingView): SparseMatrix {
    val m = model.m
    val n = model.n
    var nnz = m // one per slack column
    for (j in 0 until n) model.forEachInColumn(j) { _, _ -> nnz++ }
    val rows = IntArray(nnz)
    val cols = IntArray(nnz)
    val vals = DoubleArray(nnz)
    var k = 0
    for (j in 0 until n) {
        model.forEachInColumn(j) { i, v ->
            rows[k] = i
            cols[k] = j
            vals[k] = v
            k++
        }
    }
    for (i in 0 until m) {
        rows[k] = i
        cols[k] = n + i
        vals[k] = 1.0
        k++
    }
    return SparseMatrix.ofTriplets(m, n + m, rows, cols, vals)
}

internal class SimplexNumericalMetrics {
    var singletonStarts = 0
    var primalBlandEntries = 0
    var capExits = 0

    var artificialStarts = 0

    var artificialWidenings = 0

    var artificialHandoffs = 0

    var artificialReleases = 0
}
