package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.argsortBy
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import com.eignex.koblas.sparse.basis.BasisUpdate
import com.eignex.koblas.sparse.basis.F64BasisSolver
import com.eignex.koblas.sparse.basis.F64IndexedVector
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
)

/** Updates folded into the basis before it is rebuilt; bounds fill and rounding drift. */
internal const val DEFAULT_REFACTOR_UPDATE_LIMIT: Int = 50

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
 * The basis itself is held by a koblas `F64BasisSolver`, which owns the factors, the pivot order and
 * the updates while this owns pricing, the ratio tests and the refactorization policy. A basis is named
 * by index into `columns`, whose columns are fixed for the solver's lifetime, so a pivot hands the solver the
 * spike it already computed for the ratio test rather than a rebuilt square matrix. Which backend fills
 * the seam is a deployment question: HFactor where its binding loaded, koblas's portable product-form
 * solver otherwise, and the pivot path is the same either way.
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
) : TableauCutSolver,
    PersistentLpSolver {
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars

    /** Devex reference weights γ_i per basic row position (approximate ‖B⁻ᵀeᵢ‖²); all 1 at a fresh
     *  reference frame, reset on every refactorization. */
    private val gamma = DoubleArray(m) { 1.0 }

    /**
     * The LP's columns with the logical ones explicit, fixed for this engine's lifetime.
     *
     * That fixity is what lets a basis be named by index: [basicVar] *is* what the basis solver
     * factorizes, so a refactorization hands over the choice of columns rather than a square matrix
     * assembled for the occasion.
     */
    private val columns: SparseMatrix = lpColumns(model)

    // The CSC of `columns` as flat arrays — the same structure the seam holds, read here for pricing rather
    // than copied into a second representation. Column j occupies colPtr(j) until colPtr(j+1).
    private val colPtr: IntArray = columns.copyColumnPointers()
    private val rowIdx: IntArray = columns.copyRowIndices()
    private val colVal: DoubleArray = columns.values

    // `columns` by row. The pivot row is formed from the nonzeros of ρ = B⁻ᵀeᵣ over these, so a hypersparse ρ
    // costs the rows it touches instead of a pass over every column — which is what makes a hypersparse
    // BTRAN worth having, since dotting ρ against all numVars columns would swamp it.
    private val rowCols: Array<IntArray>
    private val rowVals: Array<DoubleArray>

    private val basicVar = IntArray(m)
    private val status = Array(numVars) { VarStatus.BASIC }
    private var pivots = 0
    private var warmStarted = false
    private var refactorizations = 0

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

    /**
     * The basis, held across pivots by whichever backend fills koblas's basis seam.
     *
     * Built on first use rather than in the constructor: a native solver owns a handle, and the
     * engines a search discards without ever solving — a component split that declines, a shave that
     * its caller drops — would otherwise each take one.
     */
    private var basisSolver: F64BasisSolver? = null

    /** Whether [basisSolver] currently factorizes the seated [basicVar]. False before the first
     *  factorization and after one came back singular. */
    private var basisFactorized = false

    // The solve carriers, one per role and reused for this engine's whole life. Reuse is not only
    // about allocation: a solver may recognise the vector its own solve filled and reuse the form it
    // kept when the same one comes back to [F64BasisSolver.update], which is what makes an update cost
    // one FTRAN. So the entering spike and the pivotal row each keep a vector of their own, and the
    // dual/rhs solves keep theirs, rather than sharing one and defeating that.
    private val spikeVec = F64IndexedVector(m)
    private val pivotEtaVec = F64IndexedVector(m)
    private val rhsVec = F64IndexedVector(m)
    private val dualVec = F64IndexedVector(m)

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
            if (abs(model.costD(j) - columnDot(y, j)) <= TOL) count++
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

    init {
        // Transpose `columns` once. Counting sort by row: tally each row's entries, then fill, so the whole
        // transpose is two passes over nnz rather than a per-row scan of every column.
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
    private fun scatterColumn(j: Int, into: F64IndexedVector) {
        work.add(columnNnz(j))
        into.clear()
        for (k in colPtr[j] until colPtr[j + 1]) {
            val v = colVal[k]
            if (v != 0.0) into.store(rowIdx[k], v)
        }
    }

    /** `y · A_j`, uncharged — for the passes that must not move the work meter. */
    private fun columnDot(y: DoubleArray, j: Int): Double {
        var acc = 0.0
        for (k in colPtr[j] until colPtr[j + 1]) acc += y[rowIdx[k]] * colVal[k]
        return acc
    }

    /** `y · A_j` for the dual vector [y], charged to the work meter. */
    private fun dotColumn(y: DoubleArray, j: Int): Double {
        work.add(columnNnz(j))
        return columnDot(y, j)
    }

    /** This engine's basis solver, built on first use. */
    private fun solver(): F64BasisSolver = basisSolver ?: newSolver()

    private fun newSolver(): F64BasisSolver {
        ensureKoblasBackends()
        return koblas.basisSolver(columns).also { basisSolver = it }
    }

    override fun close() {
        basisSolver?.close()
        basisSolver = null
        basisFactorized = false
        basisKept = false
    }

    /**
     * Refactorize the seated basis — the columns of `columns` that `basicVar` names — and drop any updates
     * folded into it. False when it came back singular, which leaves this engine unable to solve until
     * a later call succeeds.
     */
    private fun refactorize(): Boolean {
        refactorizations++
        nnzB = 0
        for (t in 0 until m) nnzB += columnNnz(basicVar[t])
        // The elimination's deterministic stand-in: the entries it reads. Not what it produces — that
        // is the backend's fill, which [nnzB] deliberately does not follow.
        work.add(nnzB)
        val solver = solver()
        basisFactorized = solver.refactorize(basicVar)
        if (!basisFactorized) {
            singularRefactorizations++
            return false
        }
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
        return true
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

    /** `B x = b` for a dense right-hand side, into [out] through [carrier]. */
    private fun ftranDense(b: DoubleArray, out: DoubleArray, carrier: F64IndexedVector): DoubleArray {
        chargeSolve()
        carrier.scatter(b)
        solver().ftran(carrier, expectedDensity = 1.0)
        return carrier.gather(out)
    }

    /** `Bᵀ x = b` for a dense right-hand side, into [out] through [carrier]. */
    private fun btranDense(b: DoubleArray, out: DoubleArray, carrier: F64IndexedVector): DoubleArray {
        chargeSolve()
        carrier.scatter(b)
        solver().btran(carrier, expectedDensity = 1.0)
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
        solver().ftran(spikeVec, ftranDensity)
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
        solver().btran(pivotEtaVec, btranDensity)
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
        val outcome = solver.update(r, q, spikeVec, if (withPivotEta) pivotEtaVec else null)
        // APPLIED leaves the factors fit to carry on; REFACTORIZE leaves them fit but worn, which is
        // advisory, and SINGULAR parted them from the basis so only a rebuild recovers. Rebuild on
        // anything but an APPLIED still inside the chain limit.
        if (outcome == BasisUpdate.APPLIED && solver.updateCount < refactorUpdateLimit) return PivotFold.UPDATED
        return if (refactorize()) PivotFold.REBUILT else PivotFold.FAILED
    }

    /** Duals `y` solving `Bᵀ y = c_B` (BTRAN). */
    private fun duals(): DoubleArray {
        // Zero objective (the gated feasibility filter): the duals solve `Bᵀy = 0`, so the whole
        // BTRAN — a full pass over the factors, once per iteration — is a zero vector.
        if (allZeroCost) return DoubleArray(m)
        return btranDense(DoubleArray(m) { model.costD(basicVar[it]) }, DoubleArray(m), dualVec)
    }

    /** Whether every objective coefficient is zero (pure feasibility): [duals] is then identically 0. */
    private val allZeroCost: Boolean = run {
        var zero = true
        for (j in 0 until numVars) if (model.costD(j) != 0.0) zero = false
        zero
    }

    /** Reset the Devex reference weights to 1 (a fresh reference frame). */
    private fun resetGamma() {
        for (i in 0 until m) gamma[i] = 1.0
    }

    /**
     * Devex reference-weight update after a pivot on row [r] with spike [alpha] (`= B⁻¹A_q`, pivot
     * element `alpha[r]`). Each row's weight grows toward `(αᵢ/αᵣ)²·γᵣ` (the reference-frame estimate
     * of the new row norm), and the pivot row takes `max(γᵣ/αᵣ², 1)`. Costs the already-computed
     * spike's nonzeros. Indexed by row position, so it is applied before the basis-column reassignment.
     */
    private fun updateGamma(alpha: F64IndexedVector, r: Int) {
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

    /**
     * Solve the relaxation, optionally warm-started from [warm] — a prior **optimal** basis of the same
     * model structure (cross-node basis reuse). Tightening a child's variable bounds leaves the
     * parent basis dual-feasible (reduced costs are bound-independent), so the dual simplex resumes from
     * near the optimum in a few pivots. The warm basis only changes the search path, never the result:
     * a structural mismatch or a singular factorization silently falls back to a cold start, so reuse is
     * sound regardless of how the basis was obtained.
     */
    override fun solve(warm: Basis?): FloatLpResult? = solveCore(warm, reuse = false)

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
    override fun resolveGated(enforced: BooleanArray): FloatLpResult? =
        solveCore(null, reuse = true, enforced = enforced)

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
        if (next.csc !== model.csc || next.cost !== model.cost) return false
        if (next.n != n || next.m != m) return false
        model = next
        cancellation = token
        return true
    }

    /** Re-solve after a [rebind], continuing from the kept basis and factorization. */
    override fun resolveBounds(): FloatLpResult? = solveCore(null, reuse = true)

    /** Whether the previous solve terminated with its basis still factorized, so [resolveGated] and
     *  [resolveBounds] may continue from it; the seated [basicVar]/[status] are still in place. False
     *  after a bailed solve. */
    private var basisKept = false

    private fun solveCore(warm: Basis?, reuse: Boolean, enforced: BooleanArray? = null): FloatLpResult? {
        // Per-solve state: the infeasibility certificate slots and counters must not leak across a
        // persistent instance's solves.
        infeasibleBasis = null
        infeasibleRow = -1
        infeasibleRay = null
        pivots = 0
        maxLuFill = 0.0
        maxLuDensity = 0.0
        refactorizations = 0
        singularRefactorizations = 0
        smallPivotBails = 0
        work.reset()
        val kept = reuse && basisKept && basisFactorized
        basisKept = false
        // A kept factorization implies the basis it factorizes is still seated, so that is the warmest
        // start there is; a warm basis alone still pays for a factorization.
        warmStarted = kept
        // A warm basis can be singular; fall back to the (always non-singular) slack cold start.
        if (!kept) {
            if (warm == null || !tryWarmStart(warm)) coldStart() else warmStarted = true
            if (!refactorize()) {
                // The warm basis factorized singular, so the solve runs from the slack start after all.
                coldStart()
                warmStarted = false
                if (!refactorize()) return null
            }
        }
        if (enforced != null) {
            // Every unenforced row's slack must be basic before the main loop. A failed reconciliation
            // resets to the all-slack cold start, where the invariant holds trivially.
            if (!reconcileUnenforced(enforced)) {
                coldStart()
                if (!refactorize()) return null
            }
        }
        resetGamma() // fresh Devex reference frame for this solve
        val maxIter = if (iterationLimit > 0) iterationLimit else 50 * (m + numVars) + 200
        val rhsAdj = DoubleArray(m)
        val beta = DoubleArray(m)
        val pivotRowEntry = DoubleArray(numVars) // ρ·A_j per nonbasic, reused by the bound-flip ratio test
        val ratioBuf = DoubleArray(numVars) // |d_j / a_j| per eligible nonbasic
        val elig = IntArrayList()
        val eligOrdered = IntArray(numVars) // scratch for the ratio-ordered permutation of [elig]
        // The columns this iteration's pivot row reached, and the iteration that reached them. A stamp
        // rather than a clear: the row is formed over ρ's nonzeros, and zeroing [pivotRowEntry] between
        // iterations would reintroduce the pass over every column that forming it this way removes.
        val touched = IntArrayList()
        val touchEpoch = IntArray(numVars)
        var epoch = 0
        var iter = 0
        // Whether an iterate's basic values are in [beta], so a solve that stops short can still hand
        // back its bound. The buffer is reused, and holds the last iterate the loop completed.
        var haveBeta = false
        while (iter++ < maxIter) {
            // Work budget, checked before the iteration that would exceed it. Pivots are not a unit of
            // cost — one costs an order of magnitude more on a dense basis than a sparse one — so a
            // budget stated in work means the same thing on every model, which a pivot count does not.
            if (workLimit > 0L && work.ops >= workLimit) {
                return if (haveBeta) truncated(beta) else null
            }
            // Cooperative deadline: a pivot updates the factorization in place (cheap), but an unbounded
            // loop on a large model would still blow the wall-clock limit. Stopping here yields the
            // current iterate rather than nothing: every basis the dual simplex passes through is
            // dual-feasible, so its objective is a valid lower bound even though the primal is not yet
            // feasible. Phased off the first iteration so an already-spent budget never starts a solve.
            if ((iter - 1) % CANCEL_POLL == 0 && cancellation()) {
                return if (haveBeta) truncated(beta) else null
            }
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            adjustedRhs(rhsAdj)
            ftranDense(rhsAdj, beta, rhsVec)
            haveBeta = true
            // Leaving: the most infeasible basic bound, scored by Devex — violation² / γ_i (approximate
            // dual steepest edge). `worst` keeps the *raw* violation of the chosen row for the
            // bound-flipping ratio test.
            var r = -1
            var bestScore = 0.0
            var worst = 0.0
            var belowLower = false
            for (i in 0 until m) {
                val v = basicVar[i]
                // An unenforced row's basic slack is free: its value is never a violation.
                if (enforced != null && v >= n && !enforced[v - n]) continue
                val below = -beta[i]
                val above = if (model.hasFiniteUpper(v)) beta[i] - model.upperD(v) else Double.NEGATIVE_INFINITY
                val isBelow = below >= above
                val viol = if (isBelow) below else above
                if (viol <= TOL) continue
                val score = viol * viol / gamma[i]
                if (score > bestScore) {
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

            val y = duals()
            // Pivot row ρ = e_r^T B⁻¹ = B⁻ᵀ e_r, kept indexed: it is the hypersparse vector of a simplex
            // iteration, and the row is formed from its nonzeros below rather than by dotting it against
            // every column.
            pivotalRow(r)
            // ρ·A_j for every column ρ reaches, accumulated over the rows ρ stores. Costs those rows'
            // entries instead of nnz(A), which is the whole point of ρ staying sparse. A column ρ misses
            // has ρ·A_j = 0 exactly, so the eligibility pass below loses no candidate by skipping it.
            epoch++
            touched.clear()
            var pivotRowOps = 0L
            pivotEtaVec.forEachStored { i, rhoI ->
                val cols = rowCols[i]
                val vals = rowVals[i]
                pivotRowOps += cols.size
                for (k in cols.indices) {
                    val j = cols[k]
                    if (touchEpoch[j] != epoch) {
                        touchEpoch[j] = epoch
                        pivotRowEntry[j] = 0.0
                        touched.add(j)
                    }
                    pivotRowEntry[j] += rhoI * vals[k]
                }
            }
            work.add(pivotRowOps)
            // Collect the dual-feasible entering candidates and their ratios; eligibility is the sign
            // rule that keeps reduced costs feasible as the leaving variable moves to its bound.
            elig.clear()
            for (t in 0 until touched.size) {
                val j = touched[t]
                if (status[j] == VarStatus.BASIC) continue
                // An unenforced row's slack never enters — it is conceptually basic forever (and the
                // reconciliation above seats it, so a nonbasic one cannot appear mid-loop).
                if (enforced != null && j >= n && !enforced[j - n]) continue
                val a = pivotRowEntry[j]
                if (abs(a) < TOL) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val eligible = if (belowLower) {
                    (atLower && a < 0) || (!atLower && a > 0)
                } else {
                    (atLower && a > 0) || (!atLower && a < 0)
                }
                if (!eligible) continue
                ratioBuf[j] = abs((model.costD(j) - dotColumn(y, j)) / a)
                elig.add(j)
            }
            if (elig.isEmpty()) {
                // Dual unbounded ⇒ primal infeasible. Record the basis + leaving row so the caller can
                // certify infeasibility exactly (the float ray alone is not sound to prune on).
                infeasibleBasis = Basis(basicVar.copyOf(), status.copyOf())
                infeasibleRow = r
                // float ρ = B⁻ᵀeᵣ densely; integerFarkasRay rounds + certifies it.
                infeasibleRay = pivotEtaVec.toDoubleArray()
                basisKept = true // the seated basis stays dual-feasible for the next [resolve]
                return null
            }
            val q = chooseEntering(elig, eligOrdered, ratioBuf, pivotRowEntry, worst)

            spike(q) // spike η = B⁻¹ A_q in the pre-pivot factorization
            if (abs(spikeVec[r]) < TOL) {
                // Numerically singular pivot. Counted before giving up: a solve lost here leaves no
                // result to read, so this is the only place the loss is visible.
                smallPivotBails++
                return null
            }
            updateGamma(spikeVec, r)
            val leaving = basicVar[r]
            status[leaving] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            basicVar[r] = q
            status[q] = VarStatus.BASIC
            pivots++
            // Hand the solver back the spike and the pivotal row it just produced, so folding the pivot
            // in costs one FTRAN rather than a recomputation. A rebuild — asked for by the solver or by
            // the chain limit — opens a fresh Devex reference frame; a failed one gives up soundly.
            when (foldPivot(r, q, leaving, withPivotEta = true)) {
                PivotFold.UPDATED -> Unit
                PivotFold.REBUILT -> resetGamma()
                PivotFold.FAILED -> return null
            }
        }
        // Iteration budget spent. Same reasoning as the cancellation exit: the iterate bounds, so hand
        // it back rather than discarding the work.
        return if (haveBeta) truncated(beta) else null
    }

    /** `b − Σ_{j nonbasic at upper} A_j·u_j` into [out], the right-hand side the basic values solve. */
    private fun adjustedRhs(out: DoubleArray) {
        for (i in 0 until m) out[i] = model.rhsD(i)
        for (j in 0 until numVars) {
            if (status[j] != VarStatus.AT_UPPER) continue
            val u = model.upperD(j)
            for (k in colPtr[j] until colPtr[j + 1]) out[rowIdx[k]] -= colVal[k] * u
        }
    }

    /**
     * Drive every unenforced row's slack into the basis with one designated pivot each, so the main
     * loop's free-slack invariant holds: an unenforced slack that is basic never leaves (skipped as a
     * violation) and never re-enters. Evicting another unenforced slack re-queues it, bounded by a
     * `2m` guard; a singular spike or an exhausted guard returns false and the caller cold-starts (the
     * all-slack basis seats every slack trivially). Only called with an all-zero objective, where any
     * basis is dual-feasible, so the arbitrary evicted-to-lower statuses never break the dual simplex.
     */
    private fun reconcileUnenforced(enforced: BooleanArray): Boolean {
        val alphaBuf = DoubleArray(m)
        var guard = 0
        var i = 0
        val requeued = ArrayDeque<Int>()
        while (true) {
            val row = when {
                i < m -> i++
                requeued.isNotEmpty() -> requeued.removeFirst()
                else -> return true
            }
            val sc = n + row
            if (enforced[row] || status[sc] == VarStatus.BASIC) continue
            if (guard++ > 2 * m) return false
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
                return false // singular spike: no pivotable row
            }
            val evicted = basicVar[r]
            if (evicted >= n && !enforced[evicted - n]) requeued.add(evicted - n)
            status[evicted] = VarStatus.AT_LOWER
            basicVar[r] = sc
            status[sc] = VarStatus.BASIC
            pivots++
            // No pivotal row here: this is a designated primal-style pivot, so the solver computes the
            // transposed solve itself if its update needs one.
            if (foldPivot(r, sc, evicted, withPivotEta = false) == PivotFold.FAILED) return false
        }
    }

    /**
     * Pick the dual entering variable from the eligible set [elig] (ratios in [ratioBuf], pivot-row
     * coefficients in [pivotRowEntry]). The bound-flipping long step walks the eligible breakpoints in
     * ratio order, flipping each bounded nonbasic whose breakpoint is passed — accumulating `|a_j|·u_j`
     * toward the leaving variable's violation [delta] — until that capacity covers the violation or an
     * unbounded column is reached; that column enters. Among the contiguous ratio-cluster within
     * [HARRIS_TOL] of the stopping ratio (all valid entering choices) it takes the largest pivot
     * magnitude (Harris, numerical stability). Mutates [status] for the flips; sound regardless, since
     * the basis is certified downstream.
     */
    private fun chooseEntering(
        elig: IntArrayList,
        ordered: IntArray,
        ratioBuf: DoubleArray,
        pivotRowEntry: DoubleArray,
        delta: Double,
    ): Int {
        // Stable ascending order by ratio, matching the tie order a stable sort by the same key gives.
        val order = argsortBy(elig.size) { a, b -> ratioBuf[elig[a]].compareTo(ratioBuf[elig[b]]) }
        for (position in order.indices) ordered[position] = elig[order[position]]
        for (position in order.indices) elig[position] = ordered[position]
        var acc = 0.0
        for (idx in 0 until elig.size) {
            val j = elig[idx]
            val range = if (model.hasFiniteUpper(j)) model.upperD(j) else Double.MAX_VALUE
            val cap = abs(pivotRowEntry[j]) * range
            val last = idx == elig.size - 1
            if (!last && range < Double.MAX_VALUE && acc + cap < delta - TOL) {
                status[j] = if (status[j] == VarStatus.AT_LOWER) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                acc += cap
            } else {
                // The long step stops at column j (ratio θ). Harris: among the contiguous ratio-cluster
                // [idx..] within tolerance of θ — all valid entering choices — take the largest pivot.
                val theta = ratioBuf[j]
                var best = j
                var bestMag = abs(pivotRowEntry[j])
                var k = idx + 1
                while (k < elig.size && ratioBuf[elig[k]] <= theta + HARRIS_TOL) {
                    val cand = elig[k]
                    val mag = abs(pivotRowEntry[cand])
                    if (mag > bestMag) {
                        bestMag = mag
                        best = cand
                    }
                    k++
                }
                return best
            }
        }
        return elig[elig.size - 1] // defensive: the loop returns on the last element
    }

    private fun optimal(beta: DoubleArray): FloatLpResult {
        // Re-add the lower-bound shift the model folded out (c·lo), so [FloatLpResult.objective] is the
        // objective in original coordinates — matching the exact certify.
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = model.costD(j)
            if (c != 0.0 && status[j] == VarStatus.AT_UPPER) obj += c * model.upperD(j)
        }
        for (i in 0 until m) {
            val c = model.costD(basicVar[i])
            if (c != 0.0) obj += c * beta[i]
        }
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShiftD(j) +
                if (status[j] == VarStatus.AT_UPPER) model.upperD(j) else 0.0
        }
        for (i in 0 until m) {
            val v = basicVar[i]
            if (v < n) primal[v] = model.loShiftD(v) + beta[i]
        }
        val basis = Basis(basicVar.copyOf(), status.copyOf())
        optimalBasis = basis
        optimalPrimal = primal
        val y = duals()
        recordDegeneracy(y)
        return FloatLpResult(
            basis,
            obj,
            y,
            primal,
            pivots,
            maxLuFill,
            maxLuDensity,
            warmStarted = warmStarted,
            refactorizations = refactorizations,
        )
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
        var obj = model.objConstantD
        for (j in 0 until numVars) {
            val c = model.costD(j)
            if (c != 0.0 && status[j] == VarStatus.AT_UPPER) obj += c * model.upperD(j)
        }
        for (i in 0 until m) {
            val c = model.costD(basicVar[i])
            if (c != 0.0) obj += c * beta[i]
        }
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShiftD(j) + if (status[j] == VarStatus.AT_UPPER) model.upperD(j) else 0.0
        }
        for (i in 0 until m) {
            val v = basicVar[i]
            if (v < n) primal[v] = model.loShiftD(v) + beta[i]
        }
        val y = duals()
        recordDegeneracy(y)
        return FloatLpResult(
            Basis(basicVar.copyOf(), status.copyOf()),
            obj,
            y,
            primal,
            pivots,
            maxLuFill,
            maxLuDensity,
            warmStarted = warmStarted,
            refactorizations = refactorizations,
            optimal = false,
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
        if (warm.basicVars.size != m || warm.status.size != numVars) return false
        for (t in 0 until m) if (warm.basicVars[t] !in 0 until numVars) return false
        warm.basicVars.copyInto(basicVar)
        warm.status.copyInto(status)
        return true
    }

    private fun coldStart() {
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) {
            // A column with no finite upper has no upper seat to take, whatever its cost: seating it
            // there reads an upper the model does not have — for a genuinely open column, the stale
            // probe-derived slot — and starts the solve outside the feasible set.
            status[j] = if (model.costD(j) >= 0.0 || !model.hasFiniteUpper(j)) {
                VarStatus.AT_LOWER
            } else {
                VarStatus.AT_UPPER
            }
        }
    }

    /** All slacks basic, every structural variable at its (shifted) lower bound. Primal-feasible
     *  whenever every row's slack value `rhs_i` is within the slack's bounds — the common `≤`/`rhs ≥ 0`
     *  case — which is the starting point [solvePrimal] needs. */
    private fun lowerStart() {
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until n) status[j] = VarStatus.AT_LOWER
    }

    /** Current basic values `β = B⁻¹(b − Σ_{j nonbasic at upper} A_j·u_j)` into [out], which is
     *  returned. */
    private fun basicValues(out: DoubleArray = DoubleArray(m)): DoubleArray {
        val rhsAdj = DoubleArray(m)
        adjustedRhs(rhsAdj)
        return ftranDense(rhsAdj, out, rhsVec)
    }

    private fun primalFeasible(beta: DoubleArray): Boolean {
        for (i in 0 until m) {
            if (beta[i] < -FEAS_TOL) return false
            val v = basicVar[i]
            if (model.hasFiniteUpper(v) && beta[i] > model.upperD(v) + FEAS_TOL) return false
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
    private fun primalPhase1(): Boolean {
        val beta = basicValues()
        val gamma = DoubleArray(m)
        val pi = DoubleArray(m)
        val alphaBuf = DoubleArray(m)
        val maxIter = 50 * (m + numVars) + 200
        var iter = 0
        while (iter++ < maxIter) {
            if ((iter - 1) % CANCEL_POLL == 0 && cancellation()) return false
            var w = 0.0
            for (i in 0 until m) {
                val v = basicVar[i]
                val hi = if (model.hasFiniteUpper(v)) model.upperD(v) else Double.MAX_VALUE
                gamma[i] = when {
                    beta[i] < -FEAS_TOL -> {
                        w -= beta[i]
                        -1.0
                    }

                    beta[i] > hi + FEAS_TOL -> {
                        w += beta[i] - hi
                        1.0
                    }

                    else -> 0.0
                }
            }
            if (w <= FEAS_TOL) return true // feasible

            btranDense(gamma, pi, dualVec)
            // Entering reduces w: from lower if π·A_j > 0, from upper if π·A_j < 0; pick the steepest.
            var q = -1
            var qAtLower = true
            var best = TOL
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val pj = dotColumn(pi, j)
                val atLower = status[j] == VarStatus.AT_LOWER
                val gain = if (atLower) pj else -pj
                if (gain > best) {
                    best = gain
                    q = j
                    qAtLower = atLower
                }
            }
            if (q == -1) return false // w > 0 with no improving column ⇒ primal infeasible

            val alpha = spikeDense(q, alphaBuf)
            val dir = if (qAtLower) 1.0 else -1.0
            var tMax = if (model.hasFiniteUpper(q)) model.upperD(q) else Double.MAX_VALUE
            var leaving = -1
            var leavingToUpper = false
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                if (abs(rate) < TOL) continue
                val v = basicVar[i]
                val hi = if (model.hasFiniteUpper(v)) model.upperD(v) else Double.MAX_VALUE
                var t = Double.MAX_VALUE
                var toUpper = false
                when {
                    // Below its lower bound: a rising β_i reaches feasibility at 0 and may leave.
                    gamma[i] < 0 -> if (rate > 0) t = -beta[i] / rate

                    // Above its upper bound: a falling β_i reaches feasibility at u_i and may leave.
                    gamma[i] > 0 -> if (rate < 0) {
                        t = (beta[i] - hi) / -rate
                        toUpper = true
                    }

                    // Feasible: blocks at whichever bound it heads toward.
                    rate < 0 -> t = beta[i] / -rate

                    hi < Double.MAX_VALUE -> {
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
            if (tMax >= Double.MAX_VALUE) return false // no blocker (degenerate/unbounded direction)
            if (leaving == -1) {
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                basicValues(beta)
                continue
            }
            if (abs(alpha[leaving]) < TOL) {
                smallPivotBails++
                return false
            }
            val evicted = basicVar[leaving]
            status[evicted] = if (leavingToUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            if (foldPivot(leaving, q, evicted, withPivotEta = false) == PivotFold.FAILED) return false
            basicValues(beta)
        }
        return false // budget exhausted
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
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    override fun solvePrimal(warm: Basis?): FloatLpResult? {
        if (warm == null || !tryWarmStart(warm)) lowerStart()
        if (!refactorize()) {
            lowerStart()
            if (!refactorize()) return null
        }
        val beta = basicValues()
        if (!primalFeasible(beta)) {
            if (!primalPhase1()) return null
            basicValues(beta)
            if (!primalFeasible(beta)) return null // phase-1 could not reach feasibility
        }
        val maxIter = 50 * (m + numVars) + 200
        val blandStall = 2 * (m + numVars) + BLAND_STALL_BASE
        val alphaBuf = DoubleArray(m)
        var iter = 0
        var degenerate = 0 // consecutive zero-length pivots; past [blandStall] switch to Bland's rule
        while (iter++ < maxIter) {
            if ((iter - 1) % CANCEL_POLL == 0 && cancellation()) return null
            // Bland's rule once degenerate pivots pile up: lowest-index entering, lowest-variable leaving
            // tie-break. Guarantees termination on a degenerate LP that the Dantzig rule could cycle on.
            val bland = degenerate >= blandStall
            val y = duals()
            var q = -1
            var qAtLower = true
            var best = TOL
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val dj = model.costD(j) - dotColumn(y, j)
                val atLower = status[j] == VarStatus.AT_LOWER
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
            var tMax = if (model.hasFiniteUpper(q)) model.upperD(q) else Double.MAX_VALUE
            var leaving = -1
            var leavingToUpper = false
            var leavingVar = Int.MAX_VALUE
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                var t = Double.MAX_VALUE
                var toUpper = false
                if (rate < -TOL) {
                    t = beta[i] / -rate // β_i falls to its lower bound 0
                } else if (rate > TOL && model.hasFiniteUpper(basicVar[i])) {
                    t = (model.upperD(basicVar[i]) - beta[i]) / rate // β_i rises to its upper bound
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
            degenerate = if (tMax <= TOL) degenerate + 1 else 0
            val evicted = basicVar[leaving]
            status[evicted] = if (leavingToUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            if (foldPivot(leaving, q, evicted, withPivotEta = false) == PivotFold.FAILED) return null
            basicValues(beta)
        }
        return null // budget exhausted
    }

    private companion object {
        const val TOL: Double = 1e-7

        /** Ratio-tolerance band for the Harris two-pass entering test: columns whose dual ratio is
         *  within this of the minimum are all valid pivots, so the largest-magnitude one is chosen. */
        const val HARRIS_TOL: Double = 1e-9

        /** Slack tolerance on the initial primal-feasibility check ([solvePrimal]). */
        const val FEAS_TOL: Double = 1e-6

        /** Degenerate-pivot count (beyond `2·(m+numVars)`) after which [solvePrimal] switches to Bland's
         *  rule — well before the iteration budget, so a cycling LP terminates rather than bailing. */
        const val BLAND_STALL_BASE: Int = 50

        /** Iterations between cooperative cancellation polls. */
        const val CANCEL_POLL: Int = 32
    }
}

/** What folding a pivot into the basis did to it. */
private enum class PivotFold {
    /** The update went in and the factors are fit to carry on. */
    UPDATED,

    /** The basis was rebuilt — because the solver asked for it, or because the chain reached its limit. */
    REBUILT,

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
private fun lpColumns(model: LpModel): SparseMatrix {
    val m = model.m
    val n = model.n
    var nnz = m // one per slack column
    for (j in 0 until n) model.forEachInColumnD(j) { _, _ -> nnz++ }
    val rows = IntArray(nnz)
    val cols = IntArray(nnz)
    val vals = DoubleArray(nnz)
    var k = 0
    for (j in 0 until n) {
        model.forEachInColumnD(j) { i, v ->
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
