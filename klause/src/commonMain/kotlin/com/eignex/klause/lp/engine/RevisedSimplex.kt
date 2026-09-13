package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisExtension
import com.eignex.klause.simplex.basis.BasisOperationWork
import com.eignex.klause.simplex.basis.BasisPhaseWork
import com.eignex.klause.simplex.basis.BasisSolveQuality
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.basis.RationalBasisOrder
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortBy
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import com.eignex.koblas.sparse.SparseSlices
import kotlin.math.abs

/**
 * Result of a [RevisedSimplex] solve: the optimal [basis] (to warm-start or exactly certify), the
 * float objective, and the dual vector `y` (one per row) used by the Neumaier–Shcherbina safe
 * bound. All values are double-precision; the authoritative bound comes from exact certification of
 * [basis], never from these.
 */
internal class FloatLpResult(
    val basis: Basis,
    val objective: Double,
    val duals: DoubleArray,
    /** Per-structural-variable primal value (unshifted, length `n`); the LP point. */
    val primal: DoubleArray,
    /** Dual-simplex pivots taken to reach this optimum (0 when the warm/cold start was already optimal). */
    val pivots: Int = 0,
    /** Max fill ratio `(nnz of the factors)/nnz B` over this solve's factorizations (#27 sparsity
     *  audit). Read off the basis solver, so it describes the backend that answered. */
    val luMaxFill: Double = 0.0,
    /** Max factor density `(nnz of the factors)/m²` — approaching 1.0 means they filled in to dense. */
    val luMaxDensity: Double = 0.0,
    /** Column components this solve decomposed into ([ComponentLpSolver]); 1 for a monolithic solve. */
    val blocks: Int = 1,
    /** Whether the solve started from a prior basis rather than the slack cold start. A warm basis saves
     *  pivots; it does not save the factorization, which [refactorizations] counts separately. */
    val warmStarted: Boolean = false,
    /** Basis factorizations this solve built. The floor is 1 per solve while each node constructs its
     *  own engine, so this is the direct measure of what carrying one across nodes would save. */
    val refactorizations: Int = 0,
    /**
     * Whether the solve reached a primal-feasible basis, i.e. the optimum.
     *
     * False for an iterate the solve stopped short of optimality — the dual simplex holds dual
     * feasibility from its first basis, so such an iterate still carries a valid *bound*, which is the
     * only thing it may be read for. It is not an optimum: no reduced-cost fixing, no tableau cuts, and
     * no claim of infeasibility rests on it.
     */
    val optimal: Boolean = true,
    val exactState: LpExactState? = null,
)

/** Updates folded into the basis before it is rebuilt; bounds fill and rounding drift. */
internal const val DEFAULT_REFACTOR_UPDATE_LIMIT: Int = 64

/**
 * Double-precision bounded-variable **dual** simplex in *revised* form: the basis is held as a
 * factorization (`O(nnz)` memory) and the constraint columns in sparse CSC,
 * instead of a full `m × (n+m)` dense tableau or an explicit dense `B⁻¹`.
 * The decision logic — slack cold start, most-violated leaving variable, dual ratio-test entering
 * variable — is the textbook bounded-variable dual simplex; only the linear algebra is revised
 * (FTRAN/BTRAN through the factors), so it scales to large sparse models without materializing an `m²`
 * structure.
 *
 * It is a heuristic that can return null (non-convergence / dual-unbounded /
 * singular basis); its [FloatLpResult.basis] is then certified exactly downstream, so float rounding is never
 * safety-critical.
 *
 * The basis itself is held by a [BasisSolver], which owns the factors, the pivot order and
 * the updates while this owns pricing, the ratio tests and the refactorization policy. A basis is named
 * by index into `columns`, whose columns are fixed for the solver's lifetime, so a pivot hands the solver the
 * spike it already computed for the ratio test rather than a rebuilt square matrix. The default is klause's
 * Kotlin factorization on every platform; an injected factory can supply another implementation.
 *
 * [refactorUpdateLimit] caps the updates folded in before the basis is rebuilt, bounding fill and
 * rounding drift beyond whatever the solver itself advises through [BasisUpdate.REFACTORIZE]. It is a
 * constructor knob so a caller whose pivots accumulate across solves can raise it, and so tests can
 * force a refactorization per pivot and compare.
 *
 * [iterationLimit] bounds the dual solve's pivots; 0 derives a limit from the model's size. A caller
 * solving one node of a search sets it low deliberately: the dual simplex is dual-feasible at every
 * basis it passes through, so stopping short still yields a valid bound, and on a model where the
 * optimum costs thousands of pivots the truncated bound is usually worth as much for a fraction of
 * the time. Only the dual solve honours it — a truncated *primal* iterate is primal-feasible rather
 * than dual-feasible, so it bounds nothing.
 *
 * [workLimit] is the same idea in the better unit ([LpWork]): pivots are not a measure of cost, since one
 * on a dense basis carrying many updates outweighs many cheap sparse steps, so a pivot budget means
 * something different on every model while a work budget does not. 0 leaves it unbounded. Both limits
 * apply when both are set; whichever binds first stops the solve.
 */
internal class RevisedSimplex(
    private var model: LpModel,
    private var cancellation: Cancellation = Cancellation.Never,
    private val refactorUpdateLimit: Int = DEFAULT_REFACTOR_UPDATE_LIMIT,
    private val iterationLimit: Int = 0,
    private val workLimit: Long = 0L,
    private val trackDegeneracy: Boolean = false,
    private val basisSolverFactory: ((SparseMatrix) -> BasisSolver)? = null,
    private val pricing: LpPricingOptions = LpPricingOptions(),
    private val reuseRationalOrder: Boolean = true,
    private val scalingOptions: LpScalingOptions = LpScalingOptions(),
) : TableauCutSolver,
    PersistentLpSolver {
    private var floatAllowance: LpFloatAllowance? = null
    private val effectiveWorkLimit: Long get() = floatAllowance?.work ?: workLimit
    private val effectiveIterationLimit: Int get() = floatAllowance?.iterations ?: iterationLimit
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars
    private val constructionState = model.exactState
    private var numerical = LpScalingView.create(model, scalingOptions)
    private var continuationAvailable = false
    private var stoppedContinuationBasis: Basis? = null

    /** Devex reference weights γ_i per basic row position (approximate ‖B⁻ᵀeᵢ‖²); all 1 at a fresh
     *  reference frame, reset on every refactorization. */
    private val gamma = DoubleArray(m) { 1.0 }
    private val dualRhs = DoubleArray(m)
    private val dualValues = DoubleArray(m)
    private val qualityRhs = DoubleArray(m)
    private val basicRhs = DoubleArray(m)
    private val boundChange = DoubleArray(m)
    private val pricingOrder = IntArray(numVars)
    private val pricingScratch = IntArray(numVars)
    private val dualBeta = DoubleArray(m)
    private val primalBeta = DoubleArray(m)
    private val phaseOneBeta = DoubleArray(m)
    private val phaseOneGradient = DoubleArray(m)
    private val phaseOneDuals = DoubleArray(m)
    private val alphaValues = DoubleArray(m)
    private val pivotRowEntries = DoubleArray(numVars)
    private val enteringRatios = DoubleArray(numVars)
    private val eligibleColumns = IntArrayList(numVars)
    private val eligibleOrdered = IntArray(numVars)
    private val theoryColumns = IntArrayList(numVars)
    private val touchedColumns = IntArray(numVars)
    private val columnEpochs = IntArray(numVars)
    private val reconcileQueue = IntArrayList(m)

    /**
     * The LP's columns with the logical ones explicit, fixed for this engine's lifetime.
     *
     * That fixity is what lets a basis be named by index: [basicVar] *is* what the basis solver
     * factorizes, so a refactorization hands over the choice of columns rather than a square matrix
     * assembled for the occasion.
     */
    private var columns: SparseMatrix = lpColumns(numerical)

    // The CSC of `columns` as flat arrays — the same structure the seam holds, read here for pricing rather
    // than copied into a second representation. Column j occupies colPtr(j) until colPtr(j+1).
    private var colPtr: IntArray = columns.copyColumnPointers()
    private var rowIdx: IntArray = columns.copyRowIndices()
    private var colVal: DoubleArray = columns.values

    // `columns` by row. The pivot row is formed from the nonzeros of ρ = B⁻ᵀeᵣ over these, so a hypersparse ρ
    // costs the rows it touches instead of a pass over every column — which is what makes a hypersparse
    // BTRAN worth having, since dotting ρ against all numVars columns would swamp it.
    private lateinit var rowCols: Array<IntArray>
    private lateinit var rowVals: Array<DoubleArray>

    private val basicVar = IntArray(m)
    private val status = Array(numVars) { VarStatus.BASIC }
    private var ownerColumns = IntArray(0)
    private var ownerUnitRows = IntArray(0)

    // v1 cannot recover an imported general status declaration; keep this conservative through reuse.
    private var basisCaptureEligible = model.exactState == null
    override var solvedExactState: LpExactState? = null
        private set
    private var cachedBeta: DoubleArray? = null
    private var cachedModel: LpModel? = null
    private var cachedNumerical: LpScalingView? = null
    private var cachedStatus: Array<VarStatus>? = null
    private var pivots = 0
    private var warmStarted = false
    private var refactorizations = 0
    private var initialRefactorizations = 0
    private var warmStartRefactorizations = 0
    private var singularRecoveryRefactorizations = 0
    private var updateLimitRefactorizations = 0
    private var backendRequestedRefactorizations = 0
    private var reconcileRecoveryRefactorizations = 0
    private var numericalRecoveryRefactorizations = 0
    private var primalRefactorizations = 0
    private var warmAttempts = 0
    private var objectiveWarmAttempts = 0
    private var objectiveWarmHits = 0
    private var objectiveWarmRepairs = 0
    internal var lastDevexWeightCorrections: Int = 0
        private set
    internal var lastHarrisMinistepSelections: Int = 0
        private set
    internal var lastTheoryPricingAttempts: Int = 0
        private set
    internal var lastTheoryPricingSamples: Int = 0
        private set
    internal var lastTheoryPricingSuccessfulSamples: Int = 0
        private set
    internal var lastTheoryPricingDeclines: Int = 0
        private set
    internal var lastTheoryPricingResourceStops: Int = 0
        private set
    internal var lastTheoryPricingWorkOps: Long = 0L
        private set
    internal var lastTheoryPricingEstimatedFtranWorkOps: Long = 0L
        private set
    internal var lastTheoryPricingSelections: Int = 0
        private set
    internal var lastTheorySelectedColumn: Int = -1
        private set

    /**
     * Numerical trouble this solve met, counted rather than only acted on.
     *
     * [singularRefactorizations] counts factorizations that came back singular and
     * [smallPivotBails] the pivots abandoned because the spike's pivot entry was below [TOL]. Both
     * paths end a solve without a [FloatLpResult], so a caller reading the result alone sees none of
     * them — which is why they are read off the engine, as [lastPivots] is. They are the measurement
     * behind two open questions: whether the basis needs scaling, and how often accepting HFactor's
     * rank-deficiency repair would save a cold start.
     */
    private var singularRefactorizations = 0
    private var smallPivotBails = 0
    private val work = LpWork()
    private var maxLuFill = 0.0 // max (nnz of the held factors) / nnz(B) over this solve's factorizations
    private var maxLuDensity = 0.0 // max (nnz of the held factors) / m² — 1.0 means the factors are dense
    private var sourcePrimalResidual = 0.0
    private var sourceBoundViolation = 0.0
    private var sourceBasicDualResidual = 0.0

    override val scalingMetrics: LpScalingMetrics
        get() = numerical.metrics.copy(
            sourcePrimalResidual = sourcePrimalResidual,
            sourceBoundViolation = sourceBoundViolation,
            sourceBasicDualResidual = sourceBasicDualResidual,
        )
    internal val scaleVersion: Long get() = numerical.version

    /**
     * The basis, held across pivots by this engine's factorization owner.
     *
     * Built on first use rather than in the constructor: a native solver owns a handle, and the
     * engines a search discards without ever solving — a component split that declines, a shave that
     * its caller drops — would otherwise each take one.
     */
    private var basisSolver: BasisSolver? = null
    private var retiredBasisWork: BasisOperationWork? = null
    private val basisRepairer = EngineBasisRepairer()
    private val restartSnapshots = mutableListOf<EngineBasisRestartSnapshot>()
    private var refactorPolicy = RefactorPolicy(RefactorPolicyConfig(hardUpdateCap = refactorUpdateLimit))
    private var pendingSolveQuality: BasisSolveQuality? = null

    internal val lastBasisRepairMetrics: BasisRepairMetrics get() = basisRepairer.metrics
    internal val lastRefactorPolicyMetrics: RefactorPolicyMetrics get() = refactorPolicy.metrics
    internal val liveBasisRestartSnapshots: Int get() = restartSnapshots.size

    /** Whether [basisSolver] currently factorizes the seated [basicVar]. False before the first
     *  factorization and after one came back singular. */
    private var basisFactorized = false
    override val exactBasisCache = ExactBasisCache(if (reuseRationalOrder) ::proposedRationalOrder else null)
    private var rejectedExactBasis: IntArray? = null

    private fun proposedRationalOrder(authority: ExactBasisAuthority): RationalBasisOrder? {
        val meter = authority.meter
        val state = model.exactState
        val original = constructionState
        if (state == null || original == null) return null
        meter.charge(2L * state.model.keySize + numVars + 8L * m, 256L + 8L * numVars + 8L * m)
        if (authority.model.exactState !== state || !original.sameMatrix(state) ||
            !authority.headings.contentEquals(basicVar) || !basisFactorized || !trackedHeadingsConsistent()
        ) {
            meter.orderDecline = ExactBasisOrderDecline.STALE
            return null
        }
        val current = basisSolver ?: return null
        if (current.updateCount != 0) {
            meter.orderDecline = ExactBasisOrderDecline.UPDATED
            return null
        }
        // Reserve the owner's snapshot, all four copy getters, translation and validation scratch.
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
        if (!basisStatusConsistent(model, headings, seats)) return null
        return Basis(headings.copyOf(), seats.copyOf(), captureEligible = false)
    }

    override fun rejectSingularBasis(model: LpModel, basis: Basis): Boolean {
        val matches = if (model.exactState == null) this.model === model else this.model.exactState === model.exactState
        if (!matches || !basis.basicVars.contentEquals(basicVar)) return false
        rejectedExactBasis = basis.basicVars.copyOf()
        invalidateUncertainBasisState()
        return true
    }

    // The solve carriers, one per role and reused for this engine's whole life. Reuse is not only
    // about allocation: a solver may recognise the vector its own solve filled and reuse the form it
    // kept when the same one comes back to [BasisSolver.update], which is what makes an update cost
    // one FTRAN. So the entering spike and the pivotal row each keep a vector of their own, and the
    // dual/rhs solves keep theirs, rather than sharing one and defeating that.
    private val spikeVec = IndexedVector(m)
    private val pricingSpikeVec = IndexedVector(m)
    private val pivotEtaVec = IndexedVector(m)
    private val rhsVec = IndexedVector(m)
    private val dualVec = IndexedVector(m)

    /**
     * Nonzeros in the basis matrix `B` — `Σ_t nnz(A_{basicVar(t)})`, maintained across pivots.
     *
     * The work meter is charged from this rather than from the solver's own `nnz`. A backend's fill is
     * its own business and two of them differ on the same basis, so metering it would make a work
     * budget mean something different per deployment, and an A/B keyed on one would stop comparing.
     * This is a property of the model and the pivot path, which is what [LpWork] promises.
     */
    private var nnzB = 0

    /** Density estimates for the last FTRAN and BTRAN results, which steer the solver's choice of
     *  sweep. Fed from what the previous iteration actually produced, as the seam asks. */
    private var ftranDensity = 1.0
    private var btranDensity = 1.0

    /** When [solve] returns null because the primal is infeasible (dual unbounded — no entering column
     *  for the most-violated basic row), the basis and that leaving row at termination, for the exact
     *  Farkas infeasibility check ([integerFarkasRay]). Null on any other failure (non-convergence,
     *  singular pivot, budget) — so the caller only prunes on a genuine infeasibility. */
    override var infeasibleBasis: Basis? = null
        private set
    override var infeasibleRow: Int = -1
        private set

    /** The float candidate Farkas ray `ρ = B⁻ᵀeᵣ` at a dual-unbounded termination, for [integerFarkasRay]
     *  to round and certify. Null unless [solve] returned null on infeasibility. */
    override var infeasibleRay: DoubleArray? = null
        private set

    /**
     * Nonbasic columns whose reduced cost is zero at the last termination — dual degeneracy.
     *
     * Many tied columns mean the pivot rule has little to choose between, which is when a solve stalls
     * and spending more on it repays least. A budgeting policy reads this to decide whether a solve that
     * ran out of budget deserves a larger one or a smaller one. Only maintained when [trackDegeneracy],
     * since it costs a pass over the columns that a solve otherwise need not make.
     */
    private var degenerateColumns = 0

    /**
     * Count nonbasic columns with zero reduced cost against duals [y].
     *
     * Deliberately does not charge [work]: this pass exists to inform the budgeting policy, and a meter
     * that grows when the policy is switched on would be measuring itself — budgets derived from it
     * would then depend on whether they are in use.
     */
    private fun recordDegeneracy(y: DoubleArray) {
        if (!trackDegeneracy) return
        var count = 0
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            if (abs(numerical.costD(j) - columnDot(y, j)) <= TOL) count++
        }
        degenerateColumns = count
    }

    override val lastDegenerateColumns: Int get() = degenerateColumns
    override val lastColumns: Int get() = numVars
    override val lastPivots: Int get() = pivots
    override val lastRefactorizations: Int get() = refactorizations
    override val lastWarmStarted: Boolean get() = warmStarted
    override val lastWorkOps: Long get() = work.ops
    override val lastSingularRefactorizations: Int get() = singularRefactorizations
    override val lastSmallPivotBails: Int get() = smallPivotBails
    override val lastMetrics: LpSolveMetrics get() = LpSolveMetrics(
        pivots = pivots,
        workOps = work.ops,
        warmAttempts = warmAttempts,
        warmHits = if (warmStarted) 1 else 0,
        singularRefactorizations = singularRefactorizations,
        smallPivotBails = smallPivotBails,
        initialRefactorizations = initialRefactorizations,
        warmStartRefactorizations = warmStartRefactorizations,
        singularRecoveryRefactorizations = singularRecoveryRefactorizations,
        updateLimitRefactorizations = updateLimitRefactorizations,
        backendRequestedRefactorizations = backendRequestedRefactorizations,
        reconcileRecoveryRefactorizations = reconcileRecoveryRefactorizations,
        numericalRecoveryRefactorizations = numericalRecoveryRefactorizations,
        primalRefactorizations = primalRefactorizations,
        objectiveWarmAttempts = objectiveWarmAttempts,
        objectiveWarmHits = objectiveWarmHits,
        objectiveWarmRepairs = objectiveWarmRepairs,
    )

    init {
        rebuildRowView()
    }

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

    /** Nonzeros in column [j] of `columns`. */
    private fun columnNnz(j: Int): Int = colPtr[j + 1] - colPtr[j]

    /** Column [j] of `columns` scattered into [into], which is emptied first. Costs the column's nonzeros
     *  rather than `m`, since an indexed vector clears only what it stored. */
    private fun scatterColumn(j: Int, into: IndexedVector) {
        work.add(columnNnz(j))
        into.scatterStored(rowIdx, colPtr[j], colVal, colPtr[j], columnNnz(j))
    }

    /** `y · A_j`, uncharged — for the passes that must not move the work meter. */
    private fun columnDot(y: DoubleArray, j: Int): Double = if (j < n) {
        koblas.sparseKernels.dot(rowIdx, colPtr[j], colVal, colPtr[j], columnNnz(j), y)
    } else {
        y[j - n]
    }

    /** `y · A_j` for the dual vector [y], charged to the work meter. */
    private fun dotColumn(y: DoubleArray, j: Int): Double {
        work.add(columnNnz(j))
        return columnDot(y, j)
    }

    /** This engine's basis solver, built on first use. */
    private fun solver(): BasisSolver = basisSolver ?: newSolver()

    private fun createBasisSolver(): BasisSolver = basisSolverFactory?.invoke(columns) ?: KotlinBasisSolver(columns)

    private fun newSolver(): BasisSolver = createBasisSolver().also {
        basisSolver = it
    }

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        continuationAvailable = false
        stoppedContinuationBasis = null
        exactBasisCache.clear()
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        val snapshots = restartSnapshots.toList()
        restartSnapshots.clear()
        var failure: Throwable? = null
        for (snapshot in snapshots) {
            try {
                snapshot.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        val current = basisSolver
        if (current != null) {
            try {
                val work = current.basisOperationWork ?: BasisOperationWork(complete = false)
                retiredBasisWork = retiredBasisWork?.mergedWith(work) ?: work
            } catch (_: Throwable) {
                val incomplete = BasisOperationWork(complete = false)
                retiredBasisWork = retiredBasisWork?.mergedWith(incomplete) ?: incomplete
            }
            try {
                current.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        basisSolver = null
        basisFactorized = false
        basisKept = false
        ownerColumns = IntArray(0)
        ownerUnitRows = IntArray(0)
        cachedBeta = null
        cachedModel = null
        cachedNumerical = null
        cachedStatus = null
        solvedExactState = null
        if (failure != null) throw failure
    }

    /**
     * Refactorize the seated basis — the columns of `columns` that `basicVar` names — and drop any updates
     * folded into it. False when it came back singular, which leaves this engine unable to solve until
     * a later call succeeds.
     */
    private fun refactorize(reason: LpRefactorReason): RefactorResult {
        refactorizations++
        when (reason) {
            LpRefactorReason.INITIAL -> initialRefactorizations++
            LpRefactorReason.WARM_START -> warmStartRefactorizations++
            LpRefactorReason.SINGULAR_RECOVERY -> singularRecoveryRefactorizations++
            LpRefactorReason.UPDATE_LIMIT -> updateLimitRefactorizations++
            LpRefactorReason.BACKEND_REQUESTED -> backendRequestedRefactorizations++
            LpRefactorReason.RECONCILE_RECOVERY -> reconcileRecoveryRefactorizations++
            LpRefactorReason.NUMERICAL_RECOVERY -> numericalRecoveryRefactorizations++
            LpRefactorReason.PRIMAL -> primalRefactorizations++
        }
        nnzB = 0
        for (t in 0 until m) nnzB += columnNnz(basicVar[t])
        // The elimination's deterministic stand-in: the entries it reads. Not what it produces — that
        // is the backend's fill, which [nnzB] deliberately does not follow.
        work.add(nnzB)
        val solver = solver()
        basisFactorized = try {
            solver.refactorize(basicVar)
        } catch (_: BasisArithmeticException) {
            false
        } catch (_: ArithmeticException) {
            false
        }
        if (!basisFactorized) {
            singularRefactorizations++
            return when (
                val recovery = basisRepairer.recover(
                    solver,
                    basicVar,
                    n,
                    model.basisBoundStates(),
                    status,
                    model,
                    cancellation,
                )
            ) {
                is BasisRecoveryResult.Failed -> {
                    invalidateBasisDependentState()
                    RefactorResult.FAILED
                }

                is BasisRecoveryResult.Recovered -> {
                    installRecoveredBasis(recovery.state)
                    recordFactorization(solver, basisChanged = true)
                    RefactorResult.BASIS_CHANGED
                }
            }
        }
        ownerColumns = basicVar.copyOf()
        ownerUnitRows = IntArray(m) { -1 }
        recordFactorization(solver, basisChanged = false)
        return RefactorResult.UNCHANGED
    }

    private fun recordFactorization(solver: BasisSolver, basisChanged: Boolean) {
        // Fill of the factors the solver now holds: how much they grow the basis, and how dense they
        // become. Read off the solver, so unlike the work meter this measures the backend in play — a
        // density approaching 1 on real bases says the sparse factors are dense after all.
        if (m > 0 && nnzB > 0) {
            val held = solver.nnz.toDouble()
            val fill = held / nnzB
            if (fill > maxLuFill) maxLuFill = fill
            val density = held / (m.toDouble() * m.toDouble())
            if (density > maxLuDensity) maxLuDensity = density
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
        ownerColumns = recovered.ownerColumns
        ownerUnitRows = recovered.ownerUnitRows
        basisFactorized = true
        nnzB = 0
        for (slot in 0 until m) nnzB += columnNnz(basicVar[slot])
        invalidateBasisDependentState()
        basisFactorized = true
        basisKept = true
    }

    private fun invalidateBasisDependentState() {
        exactBasisCache.clear()
        cachedBeta = null
        cachedModel = null
        cachedNumerical = null
        cachedStatus = null
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        ftranDensity = 1.0
        btranDensity = 1.0
        resetGamma()
        basisKept = false
    }

    /**
     * Charge one basis solve: the basis's own entries, then `m` per update folded in since the last
     * refactorization.
     *
     * Both terms are the model's shape and the pivot path, never the solver's fill, so the meter reads
     * the same on a deployment that found an accelerated backend and one that did not.
     */
    private fun chargeSolve() {
        work.add(nnzB.toLong() + (basisSolver?.updateCount ?: 0).toLong() * m)
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
        koblas.sparseKernels.scatter(
            rowIdx,
            colPtr[column],
            colVal,
            colPtr[column],
            columnNnz(column),
            dense,
        )
    }

    private fun sparseAxpy(destination: DoubleArray, alpha: Double, column: Int) {
        koblas.sparseKernels.axpy(
            destination,
            alpha,
            rowIdx,
            colPtr[column],
            colVal,
            colPtr[column],
            columnNnz(column),
        )
    }

    /** `B x = b` for a dense right-hand side, into [out] through [carrier]. */
    private fun ftranDense(
        b: DoubleArray,
        out: DoubleArray,
        carrier: IndexedVector,
        boundUpdateFtran: Boolean = false,
    ): DoubleArray {
        chargeSolve()
        if (b.any { !it.isFinite() }) throw BasisArithmeticException("nonfinite basis right-hand side")
        carrier.scatter(b)
        val solver = solver()
        val before = operationWork(solver)
        solver.ftran(carrier, expectedDensity = 1.0)
        refactorPolicy.recordBasisSolve(
            operationDelta(before, operationWork(solver)) { it.ftran },
            boundUpdateFtran,
        )
        sampleSolveQuality(solver, b, carrier, transpose = false)
        return carrier.gather(out)
    }

    /** `Bᵀ x = b` for a dense right-hand side, into [out] through [carrier]. */
    private fun btranDense(b: DoubleArray, out: DoubleArray, carrier: IndexedVector): DoubleArray {
        chargeSolve()
        if (b.any { !it.isFinite() }) throw BasisArithmeticException("nonfinite basis right-hand side")
        carrier.scatter(b)
        val solver = solver()
        val before = operationWork(solver)
        solver.btran(carrier, expectedDensity = 1.0)
        refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.btran })
        sampleSolveQuality(solver, b, carrier, transpose = true)
        return carrier.gather(out)
    }

    /**
     * The entering column's spike `η = B⁻¹A_q` into [spikeVec].
     *
     * Left exactly as the solver filled it, so [foldPivot] can hand it back and the solver reuse the
     * form it kept instead of solving again.
     */
    private fun spike(q: Int) {
        chargeSolve()
        scatterColumn(q, spikeVec)
        val solver = solver()
        val before = operationWork(solver)
        solver.ftran(spikeVec, ftranDensity)
        refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.ftran })
        if (shouldSampleQuality()) {
            sampleSolveQuality(solver, denseColumn(q), spikeVec, transpose = false, cadenceChecked = true)
        }
        ftranDensity = spikeVec.density
    }

    /**
     * The entering column's spike densely in [out], which is returned; [spikeVec] keeps the indexed
     * form so [foldPivot] can still hand it back.
     *
     * The dual loop reads the spike through its nonzeros, but the ratio tests that pick a leaving row
     * by strictly-better step length — and, under Bland's rule, by lowest variable index among equal
     * ones — resolve near-ties in visit order. Reading those densely keeps them ascending by row.
     */
    private fun spikeDense(q: Int, out: DoubleArray): DoubleArray {
        spike(q)
        return spikeVec.gather(out)
    }

    /** The pivotal row `ρ = eᵣᵀB⁻¹ = B⁻ᵀeᵣ` into [pivotEtaVec], likewise left as the solver filled it. */
    private fun pivotalRow(r: Int) {
        chargeSolve()
        pivotEtaVec.unit(r)
        val solver = solver()
        val before = operationWork(solver)
        solver.btran(pivotEtaVec, btranDensity)
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

    /**
     * Fold the pivot that seated column [q] in slot [r], evicting [leaving], into the basis.
     *
     * [basicVar] must already name the new basis, since a rebuild here factorizes it. The spike in
     * [spikeVec] is handed back for the update; [withPivotEta] additionally offers [pivotEtaVec], which
     * a dual pivot has in hand and a primal one does not.
     */
    private fun foldPivot(r: Int, q: Int, leaving: Int, withPivotEta: Boolean): PivotFold {
        work.add(m)
        nnzB += columnNnz(q) - columnNnz(leaving)
        val solver = solver()
        val before = operationWork(solver)
        val outcome = solver.update(r, q, spikeVec, if (withPivotEta) pivotEtaVec else null)
        if (outcome != BasisUpdate.SINGULAR) {
            refactorPolicy.recordAcceptedUpdate(operationDelta(before, operationWork(solver)) { it.update })
        }
        // APPLIED leaves the factors fit to carry on; REFACTORIZE leaves them fit but worn, which is
        // advisory, and SINGULAR parted them from the basis so only a rebuild recovers. Rebuild on
        // anything but an APPLIED still inside the chain limit.
        if (outcome != BasisUpdate.SINGULAR) {
            exactBasisCache.clear()
            ownerColumns[r] = q
            ownerUnitRows[r] = -1
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
            RefactorResult.UNCHANGED -> PivotFold.REBUILT
            RefactorResult.BASIS_CHANGED -> PivotFold.BASIS_CHANGED
            RefactorResult.FAILED -> PivotFold.FAILED
        }
    }

    /** Duals `y` solving `Bᵀ y = c_B` (BTRAN). */
    private fun duals(): DoubleArray {
        // Zero objective (the gated feasibility filter): the duals solve `Bᵀy = 0`, so the whole
        // BTRAN — a full pass over the factors, once per iteration — is a zero vector.
        if (allZeroCost) {
            dualValues.fill(0.0)
            return dualValues
        }
        for (i in 0 until m) dualRhs[i] = numerical.costD(basicVar[i])
        return btranDense(dualRhs, dualValues, dualVec)
    }

    /** Whether every objective coefficient is zero (pure feasibility): [duals] is then identically 0. */
    private val allZeroCost: Boolean get() = (0 until numVars).all { numerical.costD(it) == 0.0 }

    /** Reset the Devex reference weights to 1 (a fresh reference frame). */
    private fun resetGamma() {
        gamma.fill(1.0)
    }

    /**
     * Devex reference-weight update after a pivot on row [r] with spike [alpha] (`= B⁻¹A_q`, pivot
     * element `alpha[r]`). Each row's weight grows toward `(αᵢ/αᵣ)²·γᵣ` (the reference-frame estimate
     * of the new row norm), and the pivot row takes `max(γᵣ/αᵣ², 1)`. Costs the already-computed
     * spike's nonzeros. Indexed by row position, so it is applied before the basis-column reassignment.
     */
    private fun updateGamma(alpha: IndexedVector, r: Int) {
        val pivot = alpha[r]
        val tau = gamma[r]
        val pivotSq = pivot * pivot
        // Only the spike's nonzeros can raise a weight: a zero `αᵢ` gives a candidate of zero and the
        // weights never fall below 1, so the rows the spike misses would keep what they have anyway.
        alpha.forEachStored { i, v ->
            if (i != r) {
                val ratio = v / pivot
                val cand = ratio * ratio * tau
                if (cand > gamma[i]) gamma[i] = cand
            }
        }
        gamma[r] = maxOf(tau / pivotSq, 1.0)
    }

    /** Squared Euclidean norm of the pivotal row, with a stable norm before the final square. */
    private fun pivotalRowSquaredNorm(): Double {
        val norm = pivotEtaVec.nrm2()
        return norm * norm
    }

    /**
     * Solve the relaxation, optionally warm-started from [warm] — a prior **optimal** basis of the same
     * model structure (cross-node basis reuse). Tightening a child's variable bounds leaves the
     * parent basis dual-feasible (reduced costs are bound-independent), so the dual simplex resumes from
     * near the optimum in a few pivots. The warm basis only changes the search path, never the result:
     * a structural mismatch or a singular factorization silently falls back to a cold start, so reuse is
     * sound regardless of how the basis was obtained.
     */
    override fun solve(warm: Basis?): FloatLpResult? {
        val progress = SolveProgress()
        return numericalSolve { reset -> solveCore(warm, reuse = false, reset = reset, progress = progress) }
    }

    /**
     * Re-solve with per-row enforcement, keeping the basis AND its LU factorization from this
     * instance's previous terminated solve — the persistent gated-residual filter. A row with
     * `enforced(i) = false` does not constrain: its slack is driven into the basis (one designated
     * pivot when nonbasic) and then never selected as violated and never re-enters, so the row's
     * equation merely defines the free slack's value. Node-to-node only [enforced] and the rhs
     * change, neither of which touches the basis matrix, so the kept factorization carries over and
     * feasibility is repaired in a few dual pivots instead of a fresh factorization.
     *
     * Only sound for an all-zero objective (the gated filter's shape): with zero costs every basis is
     * dual-feasible, so the designated reconciliation pivots can never break the dual simplex's
     * invariant. When nothing usable is kept (first call, or the previous solve bailed), this is an
     * ordinary cold start — whose all-slack basis has every unenforced slack basic already.
     */
    override fun resolveGated(enforced: BooleanArray): FloatLpResult? {
        val progress = SolveProgress()
        return numericalSolve { reset ->
            solveCore(null, reuse = true, enforced = enforced, reset = reset, progress = progress)
        }
    }

    /**
     * Re-point this engine at [next] and [token], keeping the seated basis and its factorization, then
     * re-solve. Null when [next] is not a bound-only revision of the current model, which is the caller's
     * signal to build a fresh engine.
     *
     * The basis matrix is `csc`'s columns at [basicVar], and dual feasibility is a function of `cost` and
     * the basis — neither reads a bound. So when both arrays are the *same objects*, a child node's
     * tightened bounds leave the parent's factorization valid and its basis dual-feasible, and the dual
     * simplex repairs the primal infeasibility in a few pivots instead of refactorizing. `LpModel.rebind`
     * shares exactly those two and replaces the rest, so identity is the honest test: it cannot pass for
     * a model whose matrix or objective was rebuilt.
     *
     * The bounds and right-hand side are re-read every iteration of the solve loop, so nothing stale
     * survives the swap; only the basis and its factorization do.
     */
    override fun rebind(next: LpModel, token: Cancellation): Boolean {
        if (model.exactState != null || next.exactState != null) return false
        if (next.csc !== model.csc || next.cost !== model.cost) return false
        if (next.n != n || next.m != m) return false
        refreshNumerical(next)
        cancellation = token
        continuationAvailable = false
        stoppedContinuationBasis = null
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        return true
    }

    override fun prepareLogicals(token: Cancellation): Basis? {
        continuationAvailable = false
        stoppedContinuationBasis = null
        exactBasisCache.clear()
        resetSolveState(false)
        basisKept = false
        cachedBeta = null
        cachedModel = null
        cachedNumerical = null
        cachedStatus = null
        if (model.exactState == null || token()) return null
        cancellation = token
        return try {
            coldStart()
            if (refactorize(LpRefactorReason.INITIAL) == RefactorResult.FAILED || token() ||
                (workLimit > 0L && work.ops > workLimit)
            ) {
                null
            } else {
                basisKept = true
                Basis(basicVar.copyOf(), status.copyOf(), captureEligible = false)
            }
        } catch (_: BasisArithmeticException) {
            close()
            null
        }
    }

    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
        continuationAvailable = false
        stoppedContinuationBasis = null
        val current = model.exactState ?: return false
        if (!current.sameMatrix(state) || token()) return false
        val next = state.toWorkingModel() ?: return false
        if (token()) return false
        refreshNumerical(next)
        cancellation = token
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        return true
    }

    /** Refresh under the owner's immutable matrix scale, retiring scaled factors atomically on decline. */
    private fun refreshNumerical(next: LpModel) {
        val refreshed = numerical.refresh(next)
        if (refreshed != null) {
            model = next
            numerical = refreshed
            return
        }
        installUnscaledFallback(next)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun installUnscaledFallback(next: LpModel) {
        val fallback = LpScalingView.identityAfterFallback(next, numerical.metrics)
        val snapshots = restartSnapshots.toList()
        restartSnapshots.clear()
        var failure: Throwable? = null
        for (snapshot in snapshots) {
            try {
                snapshot.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        basisSolver?.let { current ->
            val operationWork = try {
                current.basisOperationWork ?: BasisOperationWork(complete = false)
            } catch (_: Throwable) {
                BasisOperationWork(complete = false)
            }
            retiredBasisWork = retiredBasisWork?.mergedWith(operationWork) ?: operationWork
            try {
                current.close()
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
        }
        basisSolver = null
        basisFactorized = false
        basisKept = false
        ownerColumns = IntArray(0)
        ownerUnitRows = IntArray(0)
        model = next
        numerical = fallback
        columns = lpColumns(numerical)
        colPtr = columns.copyColumnPointers()
        rowIdx = columns.copyRowIndices()
        colVal = columns.values
        rebuildRowView()
        refactorPolicy = RefactorPolicy(RefactorPolicyConfig(hardUpdateCap = refactorUpdateLimit))
        rejectedExactBasis = null
        invalidateBasisDependentState()
        if (failure != null) throw failure
    }

    override fun captureBasisRestart(token: Cancellation): EngineBasisRestartSnapshot? {
        val current = basisSolver ?: return null
        if (!basisFactorized || current.singular || !trackedHeadingsConsistent() || token()) return null
        val snapshot = EngineBasisRestartSnapshot.capture(
            current,
            basisMatrixIdentity() ?: return null,
            EngineBasisState(basicVar, status, ownerColumns, ownerUnitRows),
            token,
            onClose = { restartSnapshots.remove(it) },
        ) ?: return null
        restartSnapshots.add(snapshot)
        return snapshot
    }

    @Suppress("TooGenericExceptionCaught")
    override fun restoreBasisRestart(snapshot: EngineBasisRestartSnapshot, token: Cancellation): Boolean {
        continuationAvailable = false
        stoppedContinuationBasis = null
        val current = basisSolver ?: return false
        val restored = try {
            snapshot.restore(
                current,
                basisMatrixIdentity() ?: return false,
                model.basisBoundStates(),
                token,
            ) ?: return false
        } catch (primary: Throwable) {
            invalidateUncertainBasisState()
            throw primary
        }
        return when (restored) {
            is BasisRestartResult.Cancelled -> {
                if (restored.factorsMayHaveChanged) invalidateUncertainBasisState()
                false
            }

            is BasisRestartResult.Restored -> {
                installRecoveredBasis(restored.state)
                if (restored.factorsRestored) {
                    recordFactorization(current, basisChanged = true)
                    true
                } else {
                    refactorize(LpRefactorReason.NUMERICAL_RECOVERY) != RefactorResult.FAILED
                }
            }
        }
    }

    private fun invalidateUncertainBasisState() {
        continuationAvailable = false
        stoppedContinuationBasis = null
        basisFactorized = false
        basisKept = false
        ownerColumns = IntArray(0)
        ownerUnitRows = IntArray(0)
        invalidateBasisDependentState()
    }

    private fun basisMatrixIdentity(): BasisMatrixIdentity? {
        val exact = model.exactState ?: return null
        return BasisMatrixIdentity(exact.matrixRevision, n, exact.rows.entries().map { it.id })
    }

    override val appendTransferReady: Boolean
        get() = basisKept && basisFactorized && basisSolver?.singular == false && trackedHeadingsConsistent()
    override val basisLifecycleWork: BasisOperationWork?
        get() {
            val current = basisSolver ?: return retiredBasisWork
            val active = current.basisOperationWork ?: BasisOperationWork(complete = false)
            return retiredBasisWork?.mergedWith(active) ?: active
        }
    override var lastAppendReplacementWork: LpAppendBasisWork? = null
        private set

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun appendReplacement(
        next: LpExactState,
        oldRowsInNew: IntArray,
        oldColumnsInNew: IntArray,
        mode: LpAppendReplacementMode,
        token: Cancellation,
    ): LpAppendReplacementAttempt {
        lastAppendReplacementWork = null
        val current = model.exactState
            ?: return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCOMPATIBLE_STATE)
        if (token()) return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.CANCELLED)
        if (!appendCompatible(current, next, oldRowsInNew, oldColumnsInNew)) {
            return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCOMPATIBLE_STATE)
        }
        val oldSolver = basisSolver
        if (!basisKept || !basisFactorized || oldSolver == null || oldSolver.singular) {
            return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.NOT_READY)
        }
        if (!trackedHeadingsConsistent()) {
            return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCONSISTENT_HEADINGS)
        }
        val nextModel = next.toWorkingModel()
            ?: return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCOMPATIBLE_STATE)
        val intended = mappedAppendBasis(nextModel, oldRowsInNew, oldColumnsInNew)
            ?: return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCONSISTENT_HEADINGS)
        val intendedStatus = mappedAppendStatus(nextModel, oldColumnsInNew, intended)
            ?: return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.INCONSISTENT_HEADINGS)
        val candidate = RevisedSimplex(
            nextModel,
            token,
            refactorUpdateLimit,
            iterationLimit,
            workLimit,
            trackDegeneracy,
            basisSolverFactory,
            pricing,
            reuseRationalOrder,
            scalingOptions,
        )
        val logicalColumns = IntArray(nextModel.m) { nextModel.n + it }
        val adapter = BasisExtensionAdapter { candidate.createBasisSolver() }
        val replacement: BasisReplacement
        var basisWork: Long? = null
        var basisWorkComplete = false
        if (mode == LpAppendReplacementMode.TRANSFER) {
            if (numerical.applied || candidate.numerical.applied) {
                candidate.close()
                return LpAppendReplacementAttempt(decline = LpAppendTransferDecline.STRUCTURAL)
            }
            val transfer = observeAdapterAttempt(adapter) {
                adapter.transfer(
                    oldSolver,
                    candidate.columns,
                    intended,
                    logicalColumns,
                    BasisExtension(ownerColumns, ownerUnitRows, oldRowsInNew, oldColumnsInNew),
                )
            }
            basisWork = transfer.workUnits
            basisWorkComplete = transfer.workComplete
            lastAppendReplacementWork = LpAppendBasisWork(basisWork, basisWorkComplete)
            replacement = transfer.replacement ?: return LpAppendReplacementAttempt(
                decline = if (transfer.arithmeticDeclined) {
                    LpAppendTransferDecline.ARITHMETIC
                } else {
                    LpAppendTransferDecline.STRUCTURAL
                },
                basisWork = transfer.workUnits,
                basisWorkComplete = transfer.workComplete,
            )
        } else {
            val fresh = observeAdapterAttempt(adapter) {
                adapter.replacementAttempt(oldSolver, candidate.columns, intended, logicalColumns)
            }
            basisWork = fresh.workUnits
            basisWorkComplete = fresh.workComplete
            lastAppendReplacementWork = LpAppendBasisWork(basisWork, basisWorkComplete)
            replacement = fresh.replacement ?: return LpAppendReplacementAttempt(
                decline = LpAppendTransferDecline.FRESH_FAILED,
                basisWork = fresh.workUnits,
                basisWorkComplete = fresh.workComplete,
            )
        }
        var installed = false
        var failure: Throwable? = null
        try {
            if (token()) {
                return LpAppendReplacementAttempt(
                    decline = LpAppendTransferDecline.CANCELLED,
                    basisWork = basisWork,
                    basisWorkComplete = basisWorkComplete,
                )
            }
            val basis = candidate.installAppendReplacement(
                replacement,
                intendedStatus,
            ) ?: return LpAppendReplacementAttempt(
                decline = LpAppendTransferDecline.INCONSISTENT_HEADINGS,
                basisWork = basisWork,
                basisWorkComplete = basisWorkComplete,
            )
            installed = true
            return LpAppendReplacementAttempt(
                LpAppendReplacement(candidate, basis, replacement.transferred),
                basisWork = basisWork,
                basisWorkComplete = basisWorkComplete,
            )
        } catch (primary: Throwable) {
            failure = primary
            throw primary
        } finally {
            if (!installed) closeRejectedReplacement(replacement.solver, failure)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> observeAdapterAttempt(adapter: BasisExtensionAdapter, operation: () -> T): T = try {
        operation()
    } catch (primary: Throwable) {
        lastAppendReplacementWork = adapter.lastAttemptWork?.let { LpAppendBasisWork(it.units, it.complete) }
        throw primary
    }

    private fun appendCompatible(
        current: LpExactState,
        next: LpExactState,
        oldRowsInNew: IntArray,
        oldColumnsInNew: IntArray,
    ): Boolean {
        if (next.model.n != n || next.model.m <= m || oldRowsInNew.size != m || oldColumnsInNew.size != numVars) {
            return false
        }
        if (oldRowsInNew.toSet().size != m || oldRowsInNew.any { it !in 0 until next.model.m }) return false
        if (oldColumnsInNew.toSet().size != numVars || oldColumnsInNew.any { it !in 0 until next.model.numVars }) {
            return false
        }
        if ((0 until n).any { oldColumnsInNew[it] != it }) return false
        return oldRowsInNew.indices.all { oldRow ->
            current.rows.row(oldRow).id == next.rows.row(oldRowsInNew[oldRow]).id &&
                oldColumnsInNew[n + oldRow] == next.model.n + oldRowsInNew[oldRow]
        }
    }

    private fun trackedHeadingsConsistent(): Boolean {
        if (ownerColumns.size != m || ownerUnitRows.size != m || basicVar.distinct().size != m) return false
        if (!basisStatusConsistent(model, basicVar, status)) return false
        return basicVar.indices.all { slot ->
            val column = ownerColumns[slot]
            val unit = ownerUnitRows[slot]
            (column >= 0) != (unit >= 0) &&
                (if (column >= 0) column else n + unit) == basicVar[slot]
        }
    }

    private fun mappedAppendBasis(next: LpModel, oldRowsInNew: IntArray, oldColumnsInNew: IntArray): IntArray? {
        val mapped = IntArray(next.m)
        for (slot in basicVar.indices) mapped[slot] = oldColumnsInNew[basicVar[slot]]
        val oldAtNew = BooleanArray(next.m)
        for (row in oldRowsInNew) oldAtNew[row] = true
        var slot = m
        for (row in 0 until next.m) {
            if (!oldAtNew[row]) mapped[slot++] = next.n + row
        }
        return mapped.takeIf { slot == next.m && it.distinct().size == next.m }
    }

    private fun mappedAppendStatus(next: LpModel, oldColumnsInNew: IntArray, headings: IntArray): Array<VarStatus>? {
        val mapped = Array(next.numVars) { VarStatus.BASIC }
        for (column in oldColumnsInNew.indices) mapped[oldColumnsInNew[column]] = status[column]
        val oldColumns = oldColumnsInNew.toSet()
        for (column in mapped.indices) if (column !in oldColumns) mapped[column] = VarStatus.BASIC
        for (heading in headings) mapped[heading] = VarStatus.BASIC
        return mapped.takeIf { basisStatusConsistent(next, headings, it) }
    }

    private fun installAppendReplacement(replacement: BasisReplacement, nextStatus: Array<VarStatus>): Basis? {
        if (basisSolver != null || replacement.sourceHeadings.size != m || replacement.ownerBasis.columns.size != m) {
            return null
        }
        if (!basisStatusConsistent(model, replacement.sourceHeadings, nextStatus)) return null
        replacement.sourceHeadings.copyInto(basicVar)
        nextStatus.copyInto(status)
        ownerColumns = replacement.ownerBasis.columns.copyOf()
        ownerUnitRows = replacement.ownerBasis.unitRows.copyOf()
        if (!trackedHeadingsConsistent()) {
            ownerColumns = IntArray(0)
            ownerUnitRows = IntArray(0)
            return null
        }
        basisSolver = replacement.solver
        basisFactorized = true
        basisKept = true
        nnzB = basicVar.sumOf { columnNnz(it) }
        recordFactorization(replacement.solver, basisChanged = true)
        return Basis(basicVar.copyOf(), status.copyOf(), captureEligible = false)
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

    @Suppress("TooGenericExceptionCaught")
    private fun closeRejectedReplacement(owner: BasisSolver, primary: Throwable?) {
        try {
            owner.close()
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            primary.addSuppressed(cleanup)
        }
    }

    /** Re-solve after a [rebind], continuing from the kept basis and factorization. */
    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
        val previous = floatAllowance
        floatAllowance = allowance
        return try {
            val progress = SolveProgress()
            numericalSolve { reset -> solveCore(null, reuse = true, reset = reset, progress = progress) }
        } finally {
            floatAllowance = previous
        }
    }

    /** Whether the previous solve terminated with its basis still factorized, so [resolveGated] and
     *  [resolveBounds] may continue from it; the seated [basicVar]/[status] are still in place. False
     *  after a bailed solve. */
    private var basisKept = false

    // A checked basis failure cannot supply a terminal claim or a factorization safe to keep.
    @Suppress("TooGenericExceptionCaught")
    private fun numericalSolve(block: (reset: Boolean) -> FloatLpResult?): FloatLpResult? = numericalSolve(block, true)

    @Suppress("TooGenericExceptionCaught")
    private fun numericalSolve(
        block: (reset: Boolean) -> FloatLpResult?,
        allowUnscaledFallback: Boolean,
    ): FloatLpResult? = try {
        stoppedContinuationBasis = null
        continuationAvailable = true
        val candidate = block(allowUnscaledFallback)
        if (candidate == null && allowUnscaledFallback && numerical.applied && !cancellation() &&
            infeasibleRay == null && (smallPivotBails > 0 || singularRefactorizations > 0)
        ) {
            installUnscaledFallback(model)
            return numericalSolve(block, false)
        }
        val result = if (model.exactState != null && cancellation()) {
            solvedExactState = null
            optimalBasis = null
            optimalPrimal = null
            null
        } else {
            candidate
        }
        result.also {
            if (model.exactState != null && it == null && solvedExactState == null) {
                cachedBeta = null
                cachedModel = null
                cachedNumerical = null
                cachedStatus = null
                basisKept = false
                infeasibleBasis = null
                infeasibleRow = -1
                infeasibleRay = null
            }
        }
    } catch (primary: LpScalingArithmeticException) {
        if (allowUnscaledFallback && numerical.applied && !cancellation()) {
            installUnscaledFallback(model)
            numericalSolve(block, false)
        } else {
            retireAfterArithmeticFailure(primary)
        }
    } catch (primary: BasisArithmeticException) {
        if (allowUnscaledFallback && numerical.applied && !cancellation()) {
            installUnscaledFallback(model)
            numericalSolve(block, false)
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
    private fun retireAfterArithmeticFailure(primary: ArithmeticException): FloatLpResult? {
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
        cachedBeta = null
        solvedExactState = null
        return null
    }

    private fun resetSolveState(warmAttempted: Boolean) {
        degenerateColumns = 0
        solvedExactState = null
        optimalBasis = null
        optimalPrimal = null
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        pivots = 0
        maxLuFill = 0.0
        maxLuDensity = 0.0
        sourcePrimalResidual = 0.0
        sourceBoundViolation = 0.0
        sourceBasicDualResidual = 0.0
        refactorizations = 0
        initialRefactorizations = 0
        warmStartRefactorizations = 0
        singularRecoveryRefactorizations = 0
        updateLimitRefactorizations = 0
        backendRequestedRefactorizations = 0
        reconcileRecoveryRefactorizations = 0
        numericalRecoveryRefactorizations = 0
        primalRefactorizations = 0
        objectiveWarmAttempts = 0
        objectiveWarmHits = 0
        objectiveWarmRepairs = 0
        warmAttempts = if (warmAttempted) 1 else 0
        singularRefactorizations = 0
        smallPivotBails = 0
        lastDevexWeightCorrections = 0
        lastHarrisMinistepSelections = 0
        lastTheoryPricingAttempts = 0
        lastTheoryPricingSamples = 0
        lastTheoryPricingSuccessfulSamples = 0
        lastTheoryPricingDeclines = 0
        lastTheoryPricingResourceStops = 0
        lastTheoryPricingWorkOps = 0L
        lastTheoryPricingEstimatedFtranWorkOps = 0L
        lastTheoryPricingSelections = 0
        lastTheorySelectedColumn = -1
        work.reset()
        warmStarted = false
    }

    private fun solveCore(
        warm: Basis?,
        reuse: Boolean,
        enforced: BooleanArray? = null,
        reset: Boolean = true,
        progress: SolveProgress = SolveProgress(),
    ): FloatLpResult? {
        // Per-solve state: the infeasibility certificate slots and counters must not leak across a
        // persistent instance's solves.
        if (reset) resetSolveState(reuse || warm != null)
        if (model.exactState != null &&
            (enforced != null || cancellation() || model.exactState?.conflict != null)
        ) {
            return null
        }
        val kept = reuse && basisKept && basisFactorized
        basisKept = false
        // A kept factorization implies the basis it factorizes is still seated, so that is the warmest
        // start there is; a warm basis alone still pays for a factorization.
        warmStarted = if (reset) kept else warmStarted || kept
        // A warm basis can be singular; fall back to the (always non-singular) slack cold start.
        if (!kept) {
            if (warm == null) {
                coldStart()
            } else {
                if (!tryWarmStart(warm)) coldStart() else warmStarted = true
            }
            if (refactorize(
                    if (warmStarted) LpRefactorReason.WARM_START else LpRefactorReason.INITIAL,
                ) == RefactorResult.FAILED
            ) {
                // The warm basis factorized singular, so the solve runs from the slack start after all.
                coldStart()
                warmStarted = false
                if (refactorize(LpRefactorReason.SINGULAR_RECOVERY) == RefactorResult.FAILED) return null
            }
        }
        if (enforced != null) {
            // Every unenforced row's slack must be basic before the main loop. A failed reconciliation
            // resets to the all-slack cold start, where the invariant holds trivially.
            when (reconcileUnenforced(enforced)) {
                IterationResult.CONTINUE -> Unit

                IterationResult.RESTART -> return restartDual(enforced, progress)

                IterationResult.BASIS_CHANGED -> return restartDual(enforced, progress)

                IterationResult.FAILED -> {
                    coldStart()
                    if (refactorize(LpRefactorReason.RECONCILE_RECOVERY) == RefactorResult.FAILED) return null
                }
            }
        }
        if (model.exactState != null) repairNonbasicStatuses()
        val before = cachedModel?.exactState
        val after = model.exactState
        val objectiveOnly = kept && before != null && after != null && before.sameMatrix(after) &&
            before.boundRevision == after.boundRevision && before.popRevision == after.popRevision &&
            before.rowRevision == after.rowRevision && before.model.objective != after.model.objective
        if (objectiveOnly) {
            objectiveWarmAttempts++
            objectiveWarmHits++
            return solvePrimalCore(null, reuse = true, reset = false, progress = progress)
        }
        val sameObjective = before?.model?.objective == after?.model?.objective
        val sameSeats = cachedStatus?.contentEquals(status) == true
        if ((warmStarted || after != null) && (!kept || !sameObjective || !sameSeats) && !dualFeasible()) {
            if (kept && before != null && after != null && before.model.objective != after.model.objective) {
                objectiveWarmAttempts++
                objectiveWarmRepairs++
            }
            return solvePrimalCore(null, reuse = true, reset = false, progress = progress)
        }
        resetGamma() // fresh Devex reference frame for this solve
        val maxIter = if (effectiveIterationLimit > 0) effectiveIterationLimit else 50 * (m + numVars) + 200
        val rhsAdj = basicRhs
        val beta = dualBeta
        var useCached = kept && model.exactState != null && restoreBasicValues(beta)
        val pivotRowEntry = pivotRowEntries // ρ·A_j per nonbasic, reused by the bound-flip ratio test
        val ratioBuf = enteringRatios // |d_j / a_j| per eligible nonbasic
        val elig = eligibleColumns
        val eligOrdered = eligibleOrdered // scratch for the ratio-ordered permutation of [elig]
        val theoryCandidates = theoryColumns
        // The columns this iteration's pivot row reached, and the iteration that reached them. A stamp
        // rather than a clear: the row is formed over ρ's nonzeros, and zeroing [pivotRowEntry] between
        // iterations would reintroduce the pass over every column that forming it this way removes.
        val touched = touchedColumns
        var touchedCount = 0
        val touchEpoch = columnEpochs
        touchEpoch.fill(0)
        var epoch = 0
        // Whether an iterate's basic values are in [beta], so a solve that stops short can still hand
        // back its bound. The buffer is reused, and holds the last iterate the loop completed.
        var haveBeta = false
        while (progress.dualIterations < maxIter) {
            val iteration = progress.dualIterations++
            // Work budget, checked before the iteration that would exceed it. Pivots are not a unit of
            // cost — one costs an order of magnitude more on a dense basis than a sparse one — so a
            // budget stated in work means the same thing on every model, which a pivot count does not.
            if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) {
                return if (haveBeta) truncated(beta) else null
            }
            // Cooperative deadline: a pivot updates the factorization in place (cheap), but an unbounded
            // loop on a large model would still blow the wall-clock limit. Stopping here yields the
            // current iterate rather than nothing: every basis the dual simplex passes through is
            // dual-feasible, so its objective is a valid lower bound even though the primal is not yet
            // feasible. Phased off the first iteration so an already-spent budget never starts a solve.
            if (iteration % CANCEL_POLL == 0 && cancellation()) {
                return if (haveBeta && model.exactState == null) truncated(beta) else null
            }
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            if (!useCached) {
                adjustedRhs(rhsAdj)
                ftranDense(rhsAdj, beta, rhsVec)
            }
            useCached = false
            when (refactorAtQualitySafePoint()) {
                null -> Unit

                RefactorResult.UNCHANGED -> {
                    resetGamma()
                    progress.dualIterations--
                    continue
                }

                RefactorResult.BASIS_CHANGED -> return restartDual(enforced, progress)

                RefactorResult.FAILED -> return null
            }
            haveBeta = true
            // Leaving: the most infeasible basic bound, scored by Devex — violation² / γ_i (approximate
            // dual steepest edge). Verify the chosen weight against the pivotal row which this iteration
            // needs anyway. Correcting an underestimate and reselecting is bounded by the row count:
            // every retry makes at least one selected weight exact in this reference frame.
            var r = -1
            var worst = 0.0
            var belowLower = false
            while (true) {
                r = -1
                var bestScore = 0.0
                for (i in 0 until m) {
                    val v = basicVar[i]
                    // An unenforced row's basic slack is free: its value is never a violation.
                    if (enforced != null && v >= n && !enforced[v - n]) continue
                    val below = if (model.hasFiniteLower(v)) numerical.lowerD(v) - beta[i] else Double.NEGATIVE_INFINITY
                    val above = if (model.hasFiniteUpper(v)) {
                        beta[i] - numerical.upperD(v)
                    } else {
                        Double.NEGATIVE_INFINITY
                    }
                    val isBelow = below >= above
                    val viol = if (isBelow) below else above
                    if (viol <= TOL) continue
                    val score = viol * viol / gamma[i]
                    if (r == -1 || score > bestScore) {
                        bestScore = score
                        r = i
                        worst = viol
                        belowLower = isBelow
                    }
                }
                if (r == -1) {
                    basisKept = true // terminated cleanly: [resolve] may continue from here
                    return optimal(beta) // primal feasible ⇒ optimal
                }
                // Pivot row ρ = e_r^T B⁻¹ = B⁻ᵀ e_r, kept indexed: it is the hypersparse vector of a
                // simplex iteration, and its true norm verifies the chosen Devex weight at no extra solve.
                pivotalRow(r)
                val trueWeight = pivotalRowSquaredNorm()
                if (!(gamma[r] < DEVEX_WEIGHT_THRESHOLD * trueWeight)) break
                gamma[r] = trueWeight
                lastDevexWeightCorrections++
                if (cancellation()) return if (model.exactState == null) truncated(beta) else null
                if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return truncated(beta)
            }

            val y = duals()
            when (refactorAtQualitySafePoint()) {
                null -> Unit

                RefactorResult.UNCHANGED -> {
                    resetGamma()
                    progress.dualIterations--
                    continue
                }

                RefactorResult.BASIS_CHANGED -> return restartDual(enforced, progress)

                RefactorResult.FAILED -> return null
            }
            // ρ·A_j for every column ρ reaches, accumulated over the rows ρ stores. Costs those rows'
            // entries instead of nnz(A), which is the whole point of ρ staying sparse. A column ρ misses
            // has ρ·A_j = 0 exactly, so the eligibility pass below loses no candidate by skipping it.
            epoch++
            touchedCount = 0
            var pivotRowOps = 0L
            pivotEtaVec.forEachStored { i, rhoI ->
                val cols = rowCols[i]
                val vals = rowVals[i]
                pivotRowOps += cols.size
                touchedCount = SparseSlices.scatterAxpy(
                    rhoI, cols, 0, vals, 0, cols.size,
                    pivotRowEntry, touchEpoch, epoch, touched, 0, touchedCount,
                )
            }
            work.add(pivotRowOps)
            // Collect the dual-feasible entering candidates and their ratios; eligibility is the sign
            // rule that keeps reduced costs feasible as the leaving variable moves to its bound.
            elig.clear()
            for (t in 0 until touchedCount) {
                val j = touched[t]
                if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
                // An unenforced row's slack never enters — it is conceptually basic forever (and the
                // reconciliation above seats it, so a nonbasic one cannot appear mid-loop).
                if (enforced != null && j >= n && !enforced[j - n]) continue
                val a = pivotRowEntry[j]
                if (abs(a) < TOL) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val eligible = if (status[j] == VarStatus.FREE) {
                    true
                } else if (belowLower) {
                    (atLower && a < 0) || (!atLower && a > 0)
                } else {
                    (atLower && a > 0) || (!atLower && a < 0)
                }
                if (!eligible) continue
                ratioBuf[j] = abs((numerical.costD(j) - dotColumn(y, j)) / a)
                elig.add(j)
            }
            val entering = if (elig.isEmpty()) {
                EnteringChoice.Selected(null)
            } else {
                chooseEntering(
                    elig,
                    eligOrdered,
                    theoryCandidates,
                    ratioBuf,
                    pivotRowEntry,
                    worst,
                    r,
                    enforced,
                )
            }
            if (entering == EnteringChoice.ResourceStopped) {
                lastTheoryPricingResourceStops++
                return if (model.exactState == null) truncated(beta) else null
            }
            val q = (entering as EnteringChoice.Selected).column
            if (q == null) {
                // An update chain can turn a tiny violation into a false infeasibility candidate even
                // though β is recomputed every iteration. Rebuild the factors—not merely the RHS solve—
                // and retry once per solve. A basis with no folded updates is already fresh.
                if (!progress.dualNumericalRecoveryTried && worst <= FEAS_TOL && solver().updateCount > 0) {
                    progress.dualNumericalRecoveryTried = true
                    if (cancellation()) return if (model.exactState == null) truncated(beta) else null
                    if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return truncated(beta)
                    when (refactorize(LpRefactorReason.NUMERICAL_RECOVERY)) {
                        RefactorResult.UNCHANGED -> Unit
                        RefactorResult.BASIS_CHANGED -> return restartDual(enforced, progress)
                        RefactorResult.FAILED -> return null
                    }
                    resetGamma()
                    progress.dualIterations-- // recovery is not a pivot and must not consume [iterationLimit]
                    continue
                }
                // Dual unbounded ⇒ primal infeasible. Record the basis + leaving row so the caller can
                // certify infeasibility exactly (the float ray alone is not sound to prune on).
                infeasibleBasis = Basis(basicVar.copyOf(), status.copyOf(), captureEligible = basisCaptureEligible)
                infeasibleRow = r
                // float ρ = B⁻ᵀeᵣ densely; integerFarkasRay rounds + certifies it.
                infeasibleRay = DoubleArray(m) { numerical.sourceDual(it, pivotEtaVec[it]) }
                basisKept = true // the seated basis stays dual-feasible for the next [resolve]
                retainBasicValues(beta)
                solvedExactState = model.exactState
                return null
            }

            spike(q) // spike η = B⁻¹ A_q in the pre-pivot factorization
            if (abs(spikeVec[r]) < TOL) {
                // Numerically singular pivot. Counted before giving up: a solve lost here leaves no
                // result to read, so this is the only place the loss is visible.
                smallPivotBails++
                return null
            }
            updateGamma(spikeVec, r)
            val leaving = basicVar[r]
            status[leaving] = sideStatus(leaving, upper = !belowLower)
            basicVar[r] = q
            status[q] = VarStatus.BASIC
            pivots++
            // Hand the solver back the spike and the pivotal row it just produced, so folding the pivot
            // in costs one FTRAN rather than a recomputation. A rebuild — asked for by the solver or by
            // the chain limit — opens a fresh Devex reference frame; a failed one gives up soundly.
            when (foldPivot(r, q, leaving, withPivotEta = true)) {
                PivotFold.UPDATED -> Unit
                PivotFold.REBUILT -> resetGamma()
                PivotFold.BASIS_CHANGED -> return restartDual(enforced, progress)
                PivotFold.FAILED -> return null
            }
        }
        // Iteration budget spent. Same reasoning as the cancellation exit: the iterate bounds, so hand
        // it back rather than discarding the work.
        return if (haveBeta) truncated(beta) else null
    }

    private fun restartDual(enforced: BooleanArray?, progress: SolveProgress): FloatLpResult? {
        if (progress.restarts >= MAX_SOLVE_RESTARTS || cancellation()) return null
        progress.restarts++
        basisKept = true
        return solveCore(
            warm = null,
            reuse = true,
            enforced = enforced,
            reset = false,
            progress = progress,
        )
    }

    private fun refactorAtQualitySafePoint(): RefactorResult? {
        val current = solver()
        val trigger = refactorPolicy.chooseAtSafePoint(
            current.updateCount,
            current.nnz,
            quality = pendingSolveQuality.also { pendingSolveQuality = null },
        ) ?: return null
        return refactorize(trigger.refactorReason())
    }

    /** `b − Σ_{j nonbasic at upper} A_j·u_j` into [out], the right-hand side the basic values solve. */
    private fun adjustedRhs(out: DoubleArray) {
        for (i in 0 until m) out[i] = numerical.rhsD(i)
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val u = seat(numerical, status[j], j)
            if (u == 0.0) continue
            sparseAxpy(out, -u, j)
            work.add(2 * (colPtr[j + 1] - colPtr[j]))
        }
        work.add(m)
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

    private fun boundRange(column: Int): Double = numerical.boundRangeD(column)

    private fun defaultStatus(column: Int): VarStatus = when {
        model.exactState != null && model.fixed(column) -> VarStatus.FIXED

        model.hasFiniteLower(
            column,
        ) && (numerical.costD(column) >= 0.0 || !model.hasFiniteUpper(column)) -> VarStatus.AT_LOWER

        model.hasFiniteUpper(column) -> VarStatus.AT_UPPER

        else -> VarStatus.FREE
    }

    private fun repairNonbasicStatuses() {
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            status[j] = when {
                model.fixed(j) -> VarStatus.FIXED
                status[j] == VarStatus.AT_LOWER && model.hasFiniteLower(j) -> VarStatus.AT_LOWER
                status[j] == VarStatus.AT_UPPER && model.hasFiniteUpper(j) -> VarStatus.AT_UPPER
                else -> defaultStatus(j)
            }
        }
    }

    private fun dualFeasible(): Boolean {
        val y = duals()
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
            val reduced = numerical.costD(j) - dotColumn(y, j)
            if (!reduced.isFinite()) return false
            when (status[j]) {
                VarStatus.AT_LOWER -> if (reduced < -TOL) return false
                VarStatus.AT_UPPER -> if (reduced > TOL) return false
                VarStatus.FREE -> if (abs(reduced) > TOL) return false
                else -> Unit
            }
        }
        return true
    }

    private fun retainBasicValues(beta: DoubleArray) {
        if (model.exactState == null) return
        cachedBeta = (cachedBeta ?: DoubleArray(m)).also { beta.copyInto(it) }
        cachedModel = model
        cachedNumerical = numerical
        cachedStatus = cachedStatus?.also { status.copyInto(it) } ?: status.copyOf()
    }

    private fun restoreBasicValues(out: DoubleArray): Boolean {
        val previous = cachedModel ?: return false
        val previousNumerical = cachedNumerical ?: return false
        val values = cachedBeta ?: return false
        val seats = cachedStatus ?: return false
        val before = previous.exactState ?: return false
        val after = model.exactState ?: return false
        if (before.popRevision != after.popRevision || (0 until n).any {
                previous.exactShift(
                    it,
                ) != model.exactShift(it)
            }
        ) {
            return false
        }
        if ((0 until m).any { previous.exactRhs(it) != model.exactRhs(it) }) return false
        values.copyInto(out)
        val rhs = basicRhs
        rhs.fill(0.0)
        var changed = false
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val delta = seat(numerical, status[j], j) - seat(previousNumerical, seats[j], j)
            if (delta == 0.0) continue
            changed = true
            sparseAxpy(rhs, -delta, j)
            work.add(2 * (colPtr[j + 1] - colPtr[j]))
        }
        work.add(numVars)
        if (changed) {
            val change = boundChange
            ftranDense(rhs, change, rhsVec, boundUpdateFtran = true)
            koblas.vectorKernels.axpy(out, 0, 1.0, change, 0, m)
            work.add(m)
        }
        return out.all { it.isFinite() }
    }

    /**
     * Drive every unenforced row's slack into the basis with one designated pivot each, so the main
     * loop's free-slack invariant holds: an unenforced slack that is basic never leaves (skipped as a
     * violation) and never re-enters. Evicting another unenforced slack re-queues it, bounded by a
     * `2m` guard; a singular spike or an exhausted guard returns false and the caller cold-starts (the
     * all-slack basis seats every slack trivially). Only called with an all-zero objective, where any
     * basis is dual-feasible, so the arbitrary evicted-to-lower statuses never break the dual simplex.
     */
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
            var best = TOL
            var rAny = -1
            var bestAny = TOL
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
                smallPivotBails++
                return IterationResult.FAILED // singular spike: no pivotable row
            }
            val evicted = basicVar[r]
            if (evicted >= n && !enforced[evicted - n]) requeued.add(evicted - n)
            status[evicted] = VarStatus.AT_LOWER
            basicVar[r] = sc
            status[sc] = VarStatus.BASIC
            pivots++
            // No pivotal row here: this is a designated primal-style pivot, so the solver computes the
            // transposed solve itself if its update needs one.
            when (foldPivot(r, sc, evicted, withPivotEta = false)) {
                PivotFold.UPDATED, PivotFold.REBUILT -> Unit
                PivotFold.BASIS_CHANGED -> return IterationResult.BASIS_CHANGED
                PivotFold.FAILED -> return IterationResult.FAILED
            }
        }
    }

    /**
     * Pick the dual entering variable from the eligible set [elig] (ratios in [ratioBuf], pivot-row
     * coefficients in [pivotRowEntry]). The bound-flipping long step walks the eligible breakpoints in
     * ratio order, flipping each bounded nonbasic whose breakpoint is passed — accumulating `|a_j|·u_j`
     * toward the leaving variable's violation [delta] — until that capacity covers the violation or an
     * unbounded column is reached; that column enters. Harris admits finishing columns below the running
     * scaled bound `max(MINIMUM_DELTA / |α|, ratio + HARRIS_TOL / |α|)` and takes the largest pivot.
     * A boxed column whose remaining range cannot finish the step stays flip-only and cannot enter.
     * Mutates [status] for the flips; sound regardless, since the basis is certified downstream.
     */
    private fun chooseEntering(
        elig: IntArrayList,
        ordered: IntArray,
        theoryCandidates: IntArrayList,
        ratioBuf: DoubleArray,
        pivotRowEntry: DoubleArray,
        delta: Double,
        leavingRow: Int,
        enforced: BooleanArray?,
    ): EnteringChoice {
        // Stable ascending order by ratio, matching the tie order a stable sort by the same key gives.
        val order = argsortBy(elig.size, pricingOrder, pricingScratch) { a, b ->
            ratioBuf[elig[a]].compareTo(ratioBuf[elig[b]])
        }
        for (position in 0 until elig.size) ordered[position] = elig[order[position]]
        for (position in 0 until elig.size) elig[position] = ordered[position]
        var acc = 0.0
        var flipCount = 0
        for (idx in 0 until elig.size) {
            val j = elig[idx]
            val range = boundRange(j)
            val cap = abs(pivotRowEntry[j]) * range
            val last = idx == elig.size - 1
            if (!last && range < Double.MAX_VALUE && acc + cap < delta - TOL) {
                acc += cap
                flipCount++
            } else {
                val remaining = maxOf(delta - acc, 0.0)
                var harrisBound = Double.POSITIVE_INFINITY
                var best = -1
                var bestMag = -1.0
                theoryCandidates.clear()
                var k = idx
                while (k < elig.size && ratioBuf[elig[k]] <= harrisBound) {
                    val cand = elig[k]
                    val mag = abs(pivotRowEntry[cand])
                    val candRange = boundRange(cand)
                    val canFinish = candRange == Double.MAX_VALUE || mag * candRange >= remaining - TOL
                    val relaxed = maxOf(MINIMUM_DELTA / mag, ratioBuf[cand] + HARRIS_TOL / mag)
                    harrisBound = minOf(harrisBound, relaxed)
                    if (canFinish) {
                        if (mag > bestMag) {
                            bestMag = mag
                            best = cand
                        }
                        if (mag >= THEORY_PIVOT_MAGNITUDE_FLOOR) theoryCandidates.add(cand)
                    }
                    k++
                }
                if (best != -1) {
                    val minimizeBoundSupport =
                        pricing.zeroObjective == LpZeroObjectivePricing.MIN_BOUND_SUPPORT && allZeroCost
                    val selected = if (minimizeBoundSupport) {
                        when (val theory = chooseTheoryEntering(theoryCandidates, leavingRow, enforced)) {
                            EnteringChoice.ResourceStopped -> return theory
                            is EnteringChoice.Selected -> theory.column ?: best
                        }
                    } else {
                        best
                    }
                    for (f in 0 until flipCount) {
                        val flipped = elig[f]
                        status[flipped] = if (status[flipped] == VarStatus.AT_LOWER) {
                            VarStatus.AT_UPPER
                        } else {
                            VarStatus.AT_LOWER
                        }
                    }
                    if (ratioBuf[selected] > ratioBuf[j] + HARRIS_TOL) lastHarrisMinistepSelections++
                    return EnteringChoice.Selected(selected)
                }
                return EnteringChoice.Selected(null)
            }
        }
        return EnteringChoice.Selected(null) // defensive: the loop handles the last element
    }

    private fun chooseTheoryEntering(
        candidates: IntArrayList,
        leavingRow: Int,
        enforced: BooleanArray?,
    ): EnteringChoice {
        lastTheoryPricingAttempts++
        if (candidates.isEmpty()) {
            lastTheoryPricingDeclines++
            return EnteringChoice.Selected(null)
        }
        addTheoryPricingWork(candidateOrderingWork(candidates.size))
        if (pricingResourceStopped()) return EnteringChoice.ResourceStopped
        val order = argsortBy(candidates.size, pricingOrder, pricingScratch) { a, b ->
            val left = candidates[a]
            val right = candidates[b]
            val sparsity = columnNnz(left).compareTo(columnNnz(right))
            if (sparsity != 0) sparsity else theoryTieRank(left).compareTo(theoryTieRank(right))
        }
        var best = -1
        var bestSupport = Int.MAX_VALUE
        var bestNnz = Int.MAX_VALUE
        var bestTie = Long.MAX_VALUE
        val samples = minOf(THEORY_SAMPLE_LIMIT, candidates.size)
        for (position in 0 until samples) {
            if (pricingResourceStopped()) return EnteringChoice.ResourceStopped
            val candidate = candidates[order[position]]
            val support = probeTheorySupport(candidate, leavingRow, enforced)
            when (support) {
                TheoryProbe.ArithmeticDeclined -> {
                    lastTheoryPricingDeclines++
                    return EnteringChoice.Selected(null)
                }

                TheoryProbe.ResourceStopped -> return EnteringChoice.ResourceStopped

                is TheoryProbe.Success -> {
                    val nnz = columnNnz(candidate)
                    val tie = theoryTieRank(candidate)
                    if (support.nonFreeBasics < bestSupport ||
                        (support.nonFreeBasics == bestSupport && nnz < bestNnz) ||
                        (support.nonFreeBasics == bestSupport && nnz == bestNnz && tie < bestTie)
                    ) {
                        best = candidate
                        bestSupport = support.nonFreeBasics
                        bestNnz = nnz
                        bestTie = tie
                    }
                }
            }
        }
        if (best == -1) {
            lastTheoryPricingDeclines++
            return EnteringChoice.Selected(null)
        }
        lastTheoryPricingSelections++
        lastTheorySelectedColumn = best
        return EnteringChoice.Selected(best)
    }

    private fun probeTheorySupport(candidate: Int, leavingRow: Int, enforced: BooleanArray?): TheoryProbe {
        lastTheoryPricingSamples++
        val solveCharge = nnzB.toLong() + (basisSolver?.updateCount ?: 0).toLong() * m
        addTheoryPricingWork(solveCharge)
        lastTheoryPricingEstimatedFtranWorkOps += solveCharge
        pricingSpikeVec.clear()
        val nnz = columnNnz(candidate)
        addTheoryPricingWork(nnz.toLong())
        for (entry in colPtr[candidate] until colPtr[candidate + 1]) {
            val value = colVal[entry]
            if (value != 0.0) pricingSpikeVec.store(rowIdx[entry], value)
        }
        if (pricingResourceStopped()) return TheoryProbe.ResourceStopped
        val solver = solver()
        val before = operationWork(solver)
        var failed = false
        try {
            solver.ftran(pricingSpikeVec, ftranDensity)
        } catch (_: BasisArithmeticException) {
            failed = true
        } catch (_: ArithmeticException) {
            failed = true
        } finally {
            refactorPolicy.recordBasisSolve(operationDelta(before, operationWork(solver)) { it.ftran })
        }
        if (pricingResourceStopped()) return TheoryProbe.ResourceStopped
        if (failed) return TheoryProbe.ArithmeticDeclined
        lastTheoryPricingSuccessfulSamples++
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
        lastTheoryPricingWorkOps += amount
    }

    private fun pricingResourceStopped(): Boolean =
        cancellation() || (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit)

    private fun candidateOrderingWork(size: Int): Long {
        var levels = 0
        var remaining = size - 1
        while (remaining > 0) {
            remaining = remaining ushr 1
            levels++
        }
        return size.toLong() * maxOf(levels, 1)
    }

    private fun theoryTieRank(column: Int): Long {
        var value = pricing.tieSeed + GOLDEN_GAMMA * (column.toLong() + 1L)
        value = (value xor (value ushr 30)) * MIX_MULTIPLIER_1
        value = (value xor (value ushr 27)) * MIX_MULTIPLIER_2
        return value xor (value ushr 31)
    }

    private fun optimal(beta: DoubleArray): FloatLpResult {
        basisKept = true
        solvedExactState = model.exactState
        retainBasicValues(beta)
        // Re-add the lower-bound shift the model folded out (c·lo), so [FloatLpResult.objective] is the
        // objective in original coordinates — matching the exact certify.
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = numerical.costD(j)
            if (c != 0.0 && status[j] != VarStatus.BASIC) obj += c * seat(numerical, status[j], j)
        }
        for (i in 0 until m) {
            val c = numerical.costD(basicVar[i])
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
        recordSourceResiduals(beta, y)
        return FloatLpResult(
            basis,
            model.objectiveD(obj),
            y.copyOf(),
            primal,
            pivots,
            maxLuFill,
            maxLuDensity,
            warmStarted = warmStarted,
            refactorizations = refactorizations,
            exactState = model.exactState,
        )
    }

    private fun recordSourceResiduals(beta: DoubleArray, sourceDuals: DoubleArray) {
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
        sourcePrimalResidual = residual.maxOfOrNull { abs(it) } ?: 0.0

        var boundViolation = 0.0
        for (j in 0 until numVars) {
            if (model.hasFiniteLower(j)) boundViolation = maxOf(boundViolation, model.lowerD(j) - values[j])
            if (model.hasFiniteUpper(j)) boundViolation = maxOf(boundViolation, values[j] - model.upperD(j))
        }
        sourceBoundViolation = maxOf(0.0, boundViolation)

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
        sourceBasicDualResidual = dualResidual
        if (numerical.applied &&
            (!sourcePrimalResidual.isFinite() || !sourceBoundViolation.isFinite() || !dualResidual.isFinite())
        ) {
            throw LpScalingArithmeticException()
        }
    }

    /**
     * The iterate a solve stopped short on, as a bound-only result.
     *
     * Deliberately does *not* record [optimalBasis] / [optimalPrimal]: those gate tableau cut generation,
     * which needs an optimal tableau, so leaving them alone makes the cut path decline on its own rather
     * than relying on every caller to remember. Same objective arithmetic as [optimal], since the bound
     * is read the same way.
     */
    private fun truncated(beta: DoubleArray): FloatLpResult {
        solvedExactState = model.exactState
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = numerical.costD(j)
            if (c != 0.0 && status[j] != VarStatus.BASIC) obj += c * seat(numerical, status[j], j)
        }
        for (i in 0 until m) {
            val c = numerical.costD(basicVar[i])
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
        return FloatLpResult(
            Basis(basicVar.copyOf(), status.copyOf(), captureEligible = basisCaptureEligible),
            model.objectiveD(obj),
            y.copyOf(),
            primal,
            pivots,
            maxLuFill,
            maxLuDensity,
            warmStarted = warmStarted,
            refactorizations = refactorizations,
            optimal = false,
            exactState = model.exactState,
        )
    }

    /** The basis at the last optimal [solve]; null until an optimal solve. For tableau cut generation. */
    private var optimalBasis: Basis? = null

    /** The structural primal `x*` at the last optimal [solve], for scoring tableau cuts by violation. */
    private var optimalPrimal: DoubleArray? = null

    /** Gomory (Chvátal) integrality cuts from the last optimal basis, up to [maxCuts]; empty if the
     *  last solve was not optimal. Integer-multiplier row aggregation + super-additive rounding in 128
     *  bits ([integerTableauCuts]), so the cuts are rigorously valid. */
    override fun gomoryCuts(maxCuts: Int): List<Cut> {
        if (model.hasContinuous) return emptyList() // integer tableau cuts need an integer matrix
        val basis = optimalBasis ?: return emptyList()
        val primal = optimalPrimal ?: return emptyList()
        return integerTableauCuts(model, basis, primal, maxCuts, mir = false)
    }

    /** Gomory mixed-integer (MIR) cuts from the last optimal basis, up to [maxCuts]. */
    override fun mirCuts(maxCuts: Int): List<Cut> {
        if (model.hasContinuous) return emptyList() // integer tableau cuts need an integer matrix
        val basis = optimalBasis ?: return emptyList()
        val primal = optimalPrimal ?: return emptyList()
        return integerTableauCuts(model, basis, primal, maxCuts, mir = true)
    }

    /** Seed the basis from a prior [warm] basis; false (⇒ cold start) on a structural mismatch or an
     *  out-of-range column. A singular warm factorization is caught by [solve]'s refactor fallback. */
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

    /** All slacks basic, every structural variable at its (shifted) lower bound. Primal-feasible
     *  whenever every row's slack value `rhs_i` is within the slack's bounds — the common `≤`/`rhs ≥ 0`
     *  case — which is the starting point [solvePrimal] needs. */
    private fun lowerStart() {
        exactBasisCache.clear()
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) status[j] = if (model.hasFiniteLower(j)) sideStatus(j, false) else defaultStatus(j)
    }

    /** Current basic values `β = B⁻¹(b − Σ_{j nonbasic at upper} A_j·u_j)` into [out], which is
     *  returned. */
    private fun basicValues(out: DoubleArray): DoubleArray {
        adjustedRhs(basicRhs)
        return ftranDense(basicRhs, out, rhsVec)
    }

    private fun primalFeasible(beta: DoubleArray): Boolean {
        for (i in 0 until m) {
            val v = basicVar[i]
            if (model.hasFiniteLower(v) && beta[i] < numerical.lowerD(v) - FEAS_TOL) return false
            if (model.hasFiniteUpper(v) && beta[i] > numerical.upperD(v) + FEAS_TOL) return false
        }
        return true
    }

    /**
     * Primal **phase-1**: drive an infeasible basis to primal feasibility by minimizing the total bound
     * infeasibility `w = Σ max(0,−β_i) + max(0,β_i−u_i)` over the same primal pivot machinery. The
     * phase-1 gradient `γ` (−1 for a basic below its lower bound, +1 above its upper, 0 feasible) gives
     * the phase-1 duals `π = Bᵀ⁻¹γ`; entering by the column that most reduces `w`, leaving by the first
     * basic to reach a bound (an infeasible basic crossing into feasibility is a valid leave). True once
     * feasible, false when no improving column remains while `w > 0` (genuinely infeasible) or on a
     * singular pivot / cancellation / budget. Mutates [basicVar] / [status].
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    private fun primalPhase1(progress: SolveProgress): IterationResult {
        val beta = basicValues(phaseOneBeta)
        if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return IterationResult.FAILED
        val gamma = phaseOneGradient
        val pi = phaseOneDuals
        val alphaBuf = alphaValues
        val maxIter = if (effectiveIterationLimit > 0) effectiveIterationLimit else 50 * (m + numVars) + 200
        while (progress.primalIterations < maxIter) {
            val iteration = progress.primalIterations++
            if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return IterationResult.FAILED
            if (iteration % CANCEL_POLL == 0 && cancellation()) return IterationResult.FAILED
            var w = 0.0
            for (i in 0 until m) {
                val v = basicVar[i]
                val hi = if (model.hasFiniteUpper(v)) numerical.upperD(v) else Double.MAX_VALUE
                val lo = if (model.hasFiniteLower(v)) numerical.lowerD(v) else -Double.MAX_VALUE
                gamma[i] = when {
                    beta[i] < lo - FEAS_TOL -> {
                        w += lo - beta[i]
                        -1.0
                    }

                    beta[i] > hi + FEAS_TOL -> {
                        w += beta[i] - hi
                        1.0
                    }

                    else -> 0.0
                }
            }
            if (w <= FEAS_TOL) return IterationResult.CONTINUE // feasible

            btranDense(gamma, pi, dualVec)
            when (refactorAtQualitySafePoint()) {
                null -> Unit
                RefactorResult.UNCHANGED -> return IterationResult.RESTART
                RefactorResult.BASIS_CHANGED -> return IterationResult.BASIS_CHANGED
                RefactorResult.FAILED -> return IterationResult.FAILED
            }
            // Entering reduces w: from lower if π·A_j > 0, from upper if π·A_j < 0; pick the steepest.
            var q = -1
            var qAtLower = true
            var best = TOL
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
                val pj = dotColumn(pi, j)
                val atLower = status[j] == VarStatus.AT_LOWER || (status[j] == VarStatus.FREE && pj > 0.0)
                val gain = if (atLower) pj else -pj
                if (gain > best) {
                    best = gain
                    q = j
                    qAtLower = atLower
                }
            }
            if (q == -1) {
                if (model.exactState != null) {
                    infeasibleRay = DoubleArray(m) { numerical.sourceDual(it, pi[it]) }
                    solvedExactState = model.exactState
                    basisKept = true
                    retainBasicValues(beta)
                }
                return IterationResult.FAILED
            }

            val alpha = spikeDense(q, alphaBuf)
            val dir = if (qAtLower) 1.0 else -1.0
            var tMax = boundRange(q)
            var leaving = -1
            var leavingToUpper = false
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                if (abs(rate) < TOL) continue
                val v = basicVar[i]
                val hi = if (model.hasFiniteUpper(v)) numerical.upperD(v) else Double.MAX_VALUE
                val lo = if (model.hasFiniteLower(v)) numerical.lowerD(v) else -Double.MAX_VALUE
                var t = Double.MAX_VALUE
                var toUpper = false
                when {
                    // Below its lower bound: a rising β_i reaches feasibility at 0 and may leave.
                    gamma[i] < 0 -> if (rate > 0) t = (lo - beta[i]) / rate

                    // Above its upper bound: a falling β_i reaches feasibility at u_i and may leave.
                    gamma[i] > 0 -> if (rate < 0) {
                        t = (beta[i] - hi) / -rate
                        toUpper = true
                    }

                    // Feasible: blocks at whichever bound it heads toward.
                    rate < 0 && model.hasFiniteLower(v) -> t = (beta[i] - lo) / -rate

                    rate > 0 && hi < Double.MAX_VALUE -> {
                        t = (hi - beta[i]) / rate
                        toUpper = true
                    }
                }
                if (t < tMax - TOL) {
                    tMax = t
                    leaving = i
                    leavingToUpper = toUpper
                }
            }
            if (tMax >= Double.MAX_VALUE) return IterationResult.FAILED // no blocker
            if (leaving == -1) {
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                basicValues(beta)
                continue
            }
            if (abs(alpha[leaving]) < TOL) {
                smallPivotBails++
                return IterationResult.FAILED
            }
            val evicted = basicVar[leaving]
            status[evicted] = sideStatus(evicted, leavingToUpper)
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            when (foldPivot(leaving, q, evicted, withPivotEta = false)) {
                PivotFold.UPDATED, PivotFold.REBUILT -> Unit
                PivotFold.BASIS_CHANGED -> return IterationResult.BASIS_CHANGED
                PivotFold.FAILED -> return IterationResult.FAILED
            }
            basicValues(beta)
        }
        return IterationResult.FAILED // budget exhausted
    }

    /**
     * Bounded-variable **primal** simplex (the dual [solve] is the workhorse; this is the complementary
     * engine the feasibility pump optimizes a feasible point with). [primalPhase1] drives an infeasible
     * start to feasibility, then phase-2 pivots toward the optimum by the textbook Dantzig rule with a
     * **bound-flipping** ratio test: an entering variable that reaches its own opposite bound before any
     * basic variable blocks simply flips bounds, taking a long step with no basis change. Returns null on
     * a genuinely infeasible model, an unbounded objective, a singular pivot, cancellation, or the
     * iteration budget — so the caller falls back to the dual path. Like [solve], the float basis it
     * returns is certified exactly downstream, so this never affects soundness, only which vertex is
     * reached.
     */
    override fun solvePrimal(warm: Basis?): FloatLpResult? {
        val progress = SolveProgress()
        return numericalSolve { reset -> solvePrimalCore(warm, reset = reset, progress = progress) }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    private fun solvePrimalCore(
        warm: Basis?,
        reuse: Boolean = false,
        reset: Boolean = true,
        progress: SolveProgress = SolveProgress(),
    ): FloatLpResult? {
        if (reset) resetSolveState(warm != null || reuse)
        if (model.exactState != null && (cancellation() || model.exactState?.conflict != null)) return null
        basisKept = false
        if (!reuse || !basisFactorized) {
            if (warm == null || !tryWarmStart(warm)) {
                lowerStart()
            } else {
                warmStarted = true
            }
            if (refactorize(LpRefactorReason.PRIMAL) == RefactorResult.FAILED) {
                lowerStart()
                warmStarted = false
                if (refactorize(LpRefactorReason.SINGULAR_RECOVERY) == RefactorResult.FAILED) return null
            }
        }
        if (model.exactState != null) repairNonbasicStatuses()
        val beta = basicValues(primalBeta)
        if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return null
        when (refactorAtQualitySafePoint()) {
            null -> Unit
            RefactorResult.UNCHANGED -> return restartPrimal(progress)
            RefactorResult.BASIS_CHANGED -> return restartPrimal(progress)
            RefactorResult.FAILED -> return null
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
        val maxIter = if (effectiveIterationLimit > 0) effectiveIterationLimit else 50 * (m + numVars) + 200
        val blandStall = 2 * (m + numVars) + BLAND_STALL_BASE
        val alphaBuf = alphaValues
        while (progress.primalIterations < maxIter) {
            val iteration = progress.primalIterations++
            if (effectiveWorkLimit > 0L && work.ops >= effectiveWorkLimit) return null
            if (iteration % CANCEL_POLL == 0 && cancellation()) return null
            // Bland's rule once degenerate pivots pile up: lowest-index entering, lowest-variable leaving
            // tie-break. Guarantees termination on a degenerate LP that the Dantzig rule could cycle on.
            val bland = progress.primalDegenerate >= blandStall
            val y = duals()
            when (refactorAtQualitySafePoint()) {
                null -> Unit
                RefactorResult.UNCHANGED -> return restartPrimal(progress)
                RefactorResult.BASIS_CHANGED -> return restartPrimal(progress)
                RefactorResult.FAILED -> return null
            }
            var q = -1
            var qAtLower = true
            var best = TOL
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC || model.fixed(j)) continue
                val dj = numerical.costD(j) - dotColumn(y, j)
                val atLower = status[j] == VarStatus.AT_LOWER || (status[j] == VarStatus.FREE && dj < 0.0)
                // From lower, increasing improves iff d_j < 0; from upper, decreasing improves iff d_j > 0.
                val gain = if (atLower) -dj else dj
                if (gain <= TOL) continue
                if (bland) {
                    q = j // first (lowest-index) improving column
                    qAtLower = atLower
                    break
                }
                if (gain > best) {
                    best = gain
                    q = j
                    qAtLower = atLower
                }
            }
            if (q == -1) return optimal(beta) // no improving column ⇒ optimal

            val alpha = spikeDense(q, alphaBuf) // α = B⁻¹ A_q
            val dir = if (qAtLower) 1.0 else -1.0 // x_q moves by dir·t, t ≥ 0
            // Ratio test with the entering variable's own bound flip as a candidate blocker.
            var tMax = boundRange(q)
            var leaving = -1
            var leavingToUpper = false
            var leavingVar = Int.MAX_VALUE
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                var t = Double.MAX_VALUE
                var toUpper = false
                if (rate < -TOL && model.hasFiniteLower(basicVar[i])) {
                    t = (beta[i] - numerical.lowerD(basicVar[i])) / -rate
                } else if (rate > TOL && model.hasFiniteUpper(basicVar[i])) {
                    t = (numerical.upperD(basicVar[i]) - beta[i]) / rate // β_i rises to its upper bound
                    toUpper = true
                }
                if (t == Double.MAX_VALUE) continue
                // Strictly shorter step, or — under Bland's — an equal step leaving a lower-indexed variable.
                val accept = t < tMax - TOL || (bland && leaving != -1 && t <= tMax + TOL && basicVar[i] < leavingVar)
                if (accept) {
                    if (t < tMax) tMax = t
                    leaving = i
                    leavingToUpper = toUpper
                    leavingVar = basicVar[i]
                }
            }
            if (tMax >= Double.MAX_VALUE) return null // unbounded objective
            if (leaving == -1) {
                // The entering variable reaches its opposite bound first: flip it, no basis change.
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                basicValues(beta)
                continue
            }
            if (abs(alpha[leaving]) < TOL) {
                smallPivotBails++
                return null // numerically singular pivot
            }
            progress.primalDegenerate = if (tMax <= TOL) progress.primalDegenerate + 1 else 0
            val evicted = basicVar[leaving]
            status[evicted] = sideStatus(evicted, leavingToUpper)
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            when (foldPivot(leaving, q, evicted, withPivotEta = false)) {
                PivotFold.UPDATED, PivotFold.REBUILT -> Unit
                PivotFold.BASIS_CHANGED -> return restartPrimal(progress)
                PivotFold.FAILED -> return null
            }
            basicValues(beta)
        }
        return null // budget exhausted
    }

    private fun restartPrimal(progress: SolveProgress): FloatLpResult? {
        if (progress.restarts >= MAX_SOLVE_RESTARTS || cancellation()) return null
        progress.restarts++
        basisKept = true
        return solvePrimalCore(
            null,
            reuse = true,
            reset = false,
            progress = progress,
        )
    }

    private companion object {
        const val TOL: Double = 1e-7

        /** Reduced-cost feasibility allowance in the scaled Harris ratio bound. */
        const val HARRIS_TOL: Double = 0.5 * TOL

        /** GLOP's 0.01 ministep factor applied to the dual feasibility tolerance. */
        const val MINIMUM_DELTA: Double = 0.01 * TOL

        /** A Devex weight below this fraction of the computed pivotal-row norm is corrected. */
        const val DEVEX_WEIGHT_THRESHOLD: Double = 0.25

        /** Theory pricing samples only pivots large enough to be meaningful at simplex tolerance. */
        const val THEORY_PIVOT_MAGNITUDE_FLOOR: Double = 1e-6

        const val THEORY_SUPPORT_TOLERANCE: Double = 1e-7

        const val THEORY_SAMPLE_LIMIT: Int = 8

        const val GOLDEN_GAMMA: Long = -7046029254386353131L
        const val MIX_MULTIPLIER_1: Long = -4658895280553007687L
        const val MIX_MULTIPLIER_2: Long = -7723592293110705685L

        /** Slack tolerance on the initial primal-feasibility check ([solvePrimal]). */
        const val FEAS_TOL: Double = 1e-6

        /** Degenerate-pivot count (beyond `2·(m+numVars)`) after which [solvePrimal] switches to Bland's
         *  rule — well before the iteration budget, so a cycling LP terminates rather than bailing. */
        const val BLAND_STALL_BASE: Int = 50

        /** Iterations between cooperative cancellation polls. */
        const val CANCEL_POLL: Int = 32

        const val MAX_SOLVE_RESTARTS: Int = 4
    }
}

private sealed interface EnteringChoice {
    data class Selected(val column: Int?) : EnteringChoice

    data object ResourceStopped : EnteringChoice
}

private sealed interface TheoryProbe {
    data class Success(val nonFreeBasics: Int) : TheoryProbe

    data object ArithmeticDeclined : TheoryProbe

    data object ResourceStopped : TheoryProbe
}

// Recovery may replace factors or headings, but it remains part of one logical solve. Keeping these
// counters in one carrier prevents a restart from buying a fresh termination or anti-cycling budget.
private class SolveProgress(
    var dualIterations: Int = 0,
    var primalIterations: Int = 0,
    var primalDegenerate: Int = 0,
    var restarts: Int = 0,
    var dualNumericalRecoveryTried: Boolean = false,
)

private enum class RefactorResult {
    UNCHANGED,
    BASIS_CHANGED,
    FAILED,
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

    EngineRefactorTrigger.HARD_UPDATE_CAP -> LpRefactorReason.UPDATE_LIMIT

    EngineRefactorTrigger.RESIDUAL,
    EngineRefactorTrigger.FILL_GROWTH,
    EngineRefactorTrigger.SOLVE_WORK_GROWTH,
    EngineRefactorTrigger.SYNTHETIC_WORK,
    -> LpRefactorReason.NUMERICAL_RECOVERY
}

/** What folding a pivot into the basis did to it. */
private enum class PivotFold {
    /** The update went in and the factors are fit to carry on. */
    UPDATED,

    /** The basis was rebuilt — because the solver asked for it, or because the chain reached its limit. */
    REBUILT,

    /** Numerical repair installed a different ordered basis; iteration-local state must be abandoned. */
    BASIS_CHANGED,

    /** Neither the update nor the rebuild behind it left a usable basis. */
    FAILED,
}

/**
 * The LP's columns as one CSC matrix with the logical columns explicit: structural column `j` as the
 * model stores it, then slack column `n + i` as the unit vector `e_i`.
 *
 * The slacks are materialized rather than left implicit because the basis seam names its columns by
 * index into this matrix, so a basis slot holding a slack has to be an ordinary column for the solver
 * to factor it where it lies. Built once per engine, and never rebuilt: a bound-only rebind shares the
 * model's matrix, and anything that replaces it builds a fresh engine.
 */
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
