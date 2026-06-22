package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.lp.cut.Cut
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
    /** Max LU fill ratio `(nnz L+U)/nnz B` over this solve's factorizations (#27 sparsity audit). */
    val luMaxFill: Double = 0.0,
    /** Max LU density `(nnz L+U)/m²` — approaching 1.0 means the sparse LU filled in to dense. */
    val luMaxDensity: Double = 0.0,
)

/**
 * Double-precision bounded-variable **dual** simplex in *revised* form: the basis is held as a
 * sparse LU factorization ([SparseLu], `O(nnz)` memory) and the constraint columns in sparse CSC,
 * instead of a full `m × (n+m)` dense tableau or an explicit dense `B⁻¹`.
 * The decision logic — slack cold start, most-violated leaving variable, dual ratio-test entering
 * variable — is the textbook bounded-variable dual simplex; only the linear algebra is revised
 * (FTRAN/BTRAN via the LU), so it scales to large sparse models without materializing an `m²` structure.
 *
 * It is a heuristic that can return null (non-convergence / dual-unbounded /
 * singular basis); its [FloatLpResult.basis] is then certified exactly downstream, so float rounding is never
 * safety-critical.
 *
 * Between refactorizations the basis is maintained incrementally as an [EtaBasis] (product-form of the
 * inverse): each pivot appends one `O(m)` eta rather than refactorizing the whole basis, and the chain
 * is rebuilt from scratch once it reaches [refactorEtaLimit] (bounding fill and numerical drift). The
 * limit is a constructor knob only so tests can force a refactorization per pivot and compare.
 */
internal class RevisedSimplex(
    private val model: LpModel,
    private val cancellation: Cancellation = Cancellation.Never,
    private val refactorEtaLimit: Int = DEFAULT_REFACTOR_ETA_LIMIT,
) {
    private val m = model.m
    private val n = model.n
    private val numVars = model.numVars

    /** Devex reference weights γ_i per basic row position (approximate ‖B⁻ᵀeᵢ‖²); all 1 at a fresh
     *  reference frame, reset on every refactorization. */
    private val gamma = DoubleArray(m) { 1.0 }

    // Sparse CSC of the structural columns; slack column n+i is the unit vector e_i (implicit).
    private val colRows: Array<IntArray>
    private val colVals: Array<DoubleArray>

    private val basicVar = IntArray(m)
    private val status = Array(numVars) { VarStatus.BASIC }
    private var pivots = 0
    private var maxLuFill = 0.0 // max (nnz(L)+nnz(U)) / nnz(B) over this solve's factorizations (#27)
    private var maxLuDensity = 0.0 // max (nnz(L)+nnz(U)) / m² — 1.0 means the LU is effectively dense

    /** When [solve] returns null because the primal is infeasible (dual unbounded — no entering column
     *  for the most-violated basic row), the basis and that leaving row at termination, for the exact
     *  Farkas infeasibility check ([integerFarkasRay]). Null on any other failure (non-convergence,
     *  singular pivot, budget) — so the caller only prunes on a genuine infeasibility. */
    var infeasibleBasis: Basis? = null
        private set
    var infeasibleRow: Int = -1
        private set

    /** The float candidate Farkas ray `ρ = B⁻ᵀeᵣ` at a dual-unbounded termination, for [integerFarkasRay]
     *  to round and certify. Null unless [solve] returned null on infeasibility. */
    var infeasibleRay: DoubleArray? = null
        private set

    init {
        colRows = Array(n) { IntArray(0) }
        colVals = Array(n) { DoubleArray(0) }
        // Read columns through the model's CSC accessor. Two passes (the accessor is inline,
        // so each is a tight loop) — count nnz, then fill.
        for (j in 0 until n) {
            var nnz = 0
            model.forEachInColumn(j) { _, _ -> nnz++ }
            val rows = IntArray(nnz)
            val vals = DoubleArray(nnz)
            var k = 0
            model.forEachInColumn(j) { i, v ->
                rows[k] = i
                vals[k] = v.toDouble()
                k++
            }
            colRows[j] = rows
            colVals[j] = vals
        }
    }

    /** Dense original-row column `A_full[*][j]` into [out] (structural via CSC, slack as unit). */
    private fun denseColumn(j: Int, out: DoubleArray) {
        for (i in 0 until m) out[i] = 0.0
        if (j >= n) {
            out[j - n] = 1.0
        } else {
            val rows = colRows[j]
            val vals = colVals[j]
            for (k in rows.indices) out[rows[k]] = vals[k]
        }
    }

    /** `y · A_j` for the dual vector [y]; column j structural (CSC) or slack (single entry). */
    private fun dotColumn(y: DoubleArray, j: Int): Double {
        if (j >= n) return y[j - n]
        var acc = 0.0
        val rows = colRows[j]
        val vals = colVals[j]
        for (k in rows.indices) acc += y[rows[k]] * vals[k]
        return acc
    }

    /** Refactorize the current basis `B` (`B[i][t] = A_full[i][basicVar[t]]`) into a fresh, empty
     *  [EtaBasis]; null if singular. */
    private fun refactor(): EtaBasis? {
        val rows = Array(m) { HashMap<Int, Double>() }
        var nnzB = 0
        for (t in 0 until m) {
            val col = basicVar[t]
            if (col >= n) {
                rows[col - n][t] = 1.0
                nnzB++
            } else {
                val rs = colRows[col]
                val vs = colVals[col]
                for (k in rs.indices) rows[rs[k]][t] = vs[k]
                nnzB += rs.size
            }
        }
        val lu = SparseLu.factorize(rows, m, equilibrate = true) ?: return null
        // Track LU fill (#27): how much the factorization grows the basis (fill ratio) and how dense
        // it becomes (nnz / m²). If density approaches 1 on real bases, the "sparse" LU is dense.
        if (m > 0 && nnzB > 0) {
            val fill = lu.nnz.toDouble() / nnzB
            if (fill > maxLuFill) maxLuFill = fill
            val density = lu.nnz.toDouble() / (m.toDouble() * m.toDouble())
            if (density > maxLuDensity) maxLuDensity = density
        }
        return EtaBasis.of(lu, m)
    }

    /** Duals `y` solving `Bᵀ y = c_B` (BTRAN). */
    private fun duals(factor: EtaBasis): DoubleArray =
        factor.btran(DoubleArray(m) { model.cost[basicVar[it]].toDouble() })

    /** Reset the Devex reference weights to 1 (a fresh reference frame). */
    private fun resetGamma() {
        for (i in 0 until m) gamma[i] = 1.0
    }

    /**
     * Devex reference-weight update after a pivot on row [r] with spike [alpha] (`= B⁻¹A_q`, pivot
     * element `alpha[r]`). Each row's weight grows toward `(αᵢ/αᵣ)²·γᵣ` (the reference-frame estimate
     * of the new row norm), and the pivot row takes `max(γᵣ/αᵣ², 1)`. `O(m)` over the already-computed
     * spike. Indexed by row position, so it is applied before the basis-column reassignment.
     */
    private fun updateGamma(alpha: DoubleArray, r: Int) {
        val pivot = alpha[r]
        val tau = gamma[r]
        val pivotSq = pivot * pivot
        for (i in 0 until m) {
            if (i == r) continue
            val ratio = alpha[i] / pivot
            val cand = ratio * ratio * tau
            if (cand > gamma[i]) gamma[i] = cand
        }
        gamma[r] = maxOf(tau / pivotSq, 1.0)
    }

    /**
     * Solve the relaxation, optionally warm-started from [warm] — a prior **optimal** basis of the same
     * model structure (cross-node basis reuse, #705). Tightening a child's variable bounds leaves the
     * parent basis dual-feasible (reduced costs are bound-independent), so the dual simplex resumes from
     * near the optimum in a few pivots. The warm basis only changes the search path, never the result:
     * a structural mismatch or a singular factorization silently falls back to a cold start, so reuse is
     * sound regardless of how the basis was obtained.
     */
    fun solve(warm: Basis? = null): FloatLpResult? {
        if (warm == null || !tryWarmStart(warm)) coldStart()
        // A warm basis can be singular; fall back to the (always non-singular) slack cold start.
        var factor: EtaBasis = refactor() ?: run {
            coldStart()
            refactor() ?: return null
        }
        resetGamma() // fresh Devex reference frame for this solve
        val maxIter = 50 * (m + numVars) + 200
        val rhsAdj = DoubleArray(m)
        val unit = DoubleArray(m)
        val aq = DoubleArray(m)
        val pivotRowEntry = DoubleArray(numVars) // ρ·A_j per nonbasic, reused by the bound-flip ratio test
        val ratioBuf = DoubleArray(numVars) // |d_j / a_j| per eligible nonbasic
        val elig = ArrayList<Int>()
        var iter = 0
        while (iter++ < maxIter) {
            // Cooperative deadline: a pivot updates the factorization in place (cheap), but an unbounded
            // loop on a large model would still blow the wall-clock limit (#574). On cancellation give
            // up (null) — the basis is only a heuristic, so this is sound.
            if (iter % CANCEL_POLL == 0 && cancellation()) return null
            // β = B⁻¹ (b − Σ_{j nonbasic at upper} A_j·u_j)
            for (i in 0 until m) rhsAdj[i] = model.rhs[i].toDouble()
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.AT_UPPER) {
                    val u = model.upper[j].toDouble()
                    if (j >= n) {
                        rhsAdj[j - n] -= u
                    } else {
                        val rs = colRows[j]
                        val vs = colVals[j]
                        for (k in rs.indices) rhsAdj[rs[k]] -= vs[k] * u
                    }
                }
            }
            val beta = factor.ftran(rhsAdj)
            // Leaving: the most infeasible basic bound, scored by Devex — violation² / γ_i (approximate
            // dual steepest edge). `worst` keeps the *raw* violation of the chosen row for the
            // bound-flipping ratio test.
            var r = -1
            var bestScore = 0.0
            var worst = 0.0
            var belowLower = false
            for (i in 0 until m) {
                val v = basicVar[i]
                val below = -beta[i]
                val above = if (model.hasUpper[v]) beta[i] - model.upper[v].toDouble() else Double.NEGATIVE_INFINITY
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
            if (r == -1) return optimal(beta, factor) // primal feasible ⇒ optimal

            val y = duals(factor)
            // Pivot row ρ = e_r^T B⁻¹ = B⁻ᵀ e_r; entering column by dual ratio test.
            for (i in 0 until m) unit[i] = if (i == r) 1.0 else 0.0
            val rho = factor.btran(unit)
            // Collect the dual-feasible entering candidates and their ratios; eligibility is the sign
            // rule that keeps reduced costs feasible as the leaving variable moves to its bound.
            elig.clear()
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val a = dotColumn(rho, j)
                if (abs(a) < TOL) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val eligible = if (belowLower) {
                    (atLower && a < 0) || (!atLower && a > 0)
                } else {
                    (atLower && a > 0) || (!atLower && a < 0)
                }
                if (!eligible) continue
                pivotRowEntry[j] = a
                ratioBuf[j] = abs((model.cost[j].toDouble() - dotColumn(y, j)) / a)
                elig.add(j)
            }
            if (elig.isEmpty()) {
                // Dual unbounded ⇒ primal infeasible. Record the basis + leaving row so the caller can
                // certify infeasibility exactly (the float ray alone is not sound to prune on).
                infeasibleBasis = Basis(basicVar.copyOf(), status.copyOf())
                infeasibleRow = r
                infeasibleRay = rho.copyOf() // float ρ = B⁻ᵀeᵣ; integerFarkasRay rounds + certifies it
                return null
            }
            val q = chooseEntering(elig, ratioBuf, pivotRowEntry, worst)

            denseColumn(q, aq)
            val alpha = factor.ftran(aq) // spike η = B⁻¹ A_q in the pre-pivot factorization
            if (abs(alpha[r]) < TOL) return null // numerically singular pivot
            updateGamma(alpha, r)
            status[basicVar[r]] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            basicVar[r] = q
            status[q] = VarStatus.BASIC
            pivots++
            // Fold the pivot into the factorization as one eta; refactorize once the chain is full so
            // fill and rounding drift stay bounded. A refactorize failure (singular) gives up soundly.
            if (factor.etaCount + 1 >= refactorEtaLimit) {
                factor = refactor() ?: return null
                resetGamma() // refactorization opens a fresh Devex reference frame
            } else {
                factor.update(r, alpha)
            }
        }
        return null // budget exhausted
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
        elig: ArrayList<Int>,
        ratioBuf: DoubleArray,
        pivotRowEntry: DoubleArray,
        delta: Double,
    ): Int {
        elig.sortBy { ratioBuf[it] }
        var acc = 0.0
        for (idx in elig.indices) {
            val j = elig[idx]
            val range = if (model.hasUpper[j]) model.upper[j].toDouble() else Double.MAX_VALUE
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

    private fun optimal(beta: DoubleArray, factor: EtaBasis): FloatLpResult {
        // Re-add the lower-bound shift the model folded out (c·lo), so [FloatLpResult.objective] is the
        // objective in original coordinates — matching the exact certify.
        var obj = model.objConstant.toDouble()
        for (j in 0 until numVars) {
            val c = model.cost[j]
            if (c != 0L && status[j] == VarStatus.AT_UPPER) obj += c.toDouble() * model.upper[j].toDouble()
        }
        for (i in 0 until m) {
            val c = model.cost[basicVar[i]]
            if (c != 0L) obj += c.toDouble() * beta[i]
        }
        val primal = DoubleArray(n)
        for (j in 0 until n) {
            primal[j] = model.loShift[j].toDouble() +
                if (status[j] == VarStatus.AT_UPPER) model.upper[j].toDouble() else 0.0
        }
        for (i in 0 until m) {
            val v = basicVar[i]
            if (v < n) primal[v] = model.loShift[v].toDouble() + beta[i]
        }
        val basis = Basis(basicVar.copyOf(), status.copyOf())
        optimalBasis = basis
        optimalPrimal = primal
        return FloatLpResult(basis, obj, duals(factor), primal, pivots, maxLuFill, maxLuDensity)
    }

    /** The basis at the last optimal [solve]; null until an optimal solve. For tableau cut generation. */
    private var optimalBasis: Basis? = null

    /** The structural primal `x*` at the last optimal [solve], for scoring tableau cuts by violation. */
    private var optimalPrimal: DoubleArray? = null

    /** Gomory (Chvátal) integrality cuts from the last optimal basis (#22), up to [maxCuts]; empty if the
     *  last solve was not optimal. Integer-multiplier row aggregation + super-additive rounding in 128
     *  bits ([integerTableauCuts]), so the cuts are rigorously valid. */
    fun gomoryCuts(maxCuts: Int): List<Cut> {
        val basis = optimalBasis ?: return emptyList()
        val primal = optimalPrimal ?: return emptyList()
        return integerTableauCuts(model, basis, primal, maxCuts, mir = false)
    }

    /** Gomory mixed-integer (MIR) cuts from the last optimal basis (#22), up to [maxCuts]. */
    fun mirCuts(maxCuts: Int): List<Cut> {
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
            status[j] = if (model.cost[j] >= 0L) VarStatus.AT_LOWER else VarStatus.AT_UPPER
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

    /** Current basic values `β = B⁻¹(b − Σ_{j nonbasic at upper} A_j·u_j)`. */
    private fun basicValues(factor: EtaBasis): DoubleArray {
        val rhsAdj = DoubleArray(m) { model.rhs[it].toDouble() }
        for (j in 0 until numVars) {
            if (status[j] != VarStatus.AT_UPPER) continue
            val u = model.upper[j].toDouble()
            if (j >= n) {
                rhsAdj[j - n] -= u
            } else {
                val rs = colRows[j]
                val vs = colVals[j]
                for (k in rs.indices) rhsAdj[rs[k]] -= vs[k] * u
            }
        }
        return factor.ftran(rhsAdj)
    }

    private fun primalFeasible(beta: DoubleArray): Boolean {
        for (i in 0 until m) {
            if (beta[i] < -FEAS_TOL) return false
            val v = basicVar[i]
            if (model.hasUpper[v] && beta[i] > model.upper[v].toDouble() + FEAS_TOL) return false
        }
        return true
    }

    /**
     * Primal **phase-1**: drive an infeasible basis to primal feasibility by minimizing the total bound
     * infeasibility `w = Σ max(0,−β_i) + max(0,β_i−u_i)` over the same primal pivot machinery. The
     * phase-1 gradient `γ` (−1 for a basic below its lower bound, +1 above its upper, 0 feasible) gives
     * the phase-1 duals `π = Bᵀ⁻¹γ`; entering by the column that most reduces `w`, leaving by the first
     * basic to reach a bound (an infeasible basic crossing into feasibility is a valid leave). Returns
     * the feasible factorization, or null when no improving column remains while `w > 0` (genuinely
     * infeasible) or on a singular pivot / cancellation / budget. Mutates [basicVar] / [status].
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LongMethod")
    private fun primalPhase1(start: EtaBasis): EtaBasis? {
        var factor = start
        var beta = basicValues(factor)
        val gamma = DoubleArray(m)
        val aq = DoubleArray(m)
        val maxIter = 50 * (m + numVars) + 200
        var iter = 0
        while (iter++ < maxIter) {
            if (iter % CANCEL_POLL == 0 && cancellation()) return null
            var w = 0.0
            for (i in 0 until m) {
                val v = basicVar[i]
                val hi = if (model.hasUpper[v]) model.upper[v].toDouble() else Double.MAX_VALUE
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
            if (w <= FEAS_TOL) return factor // feasible

            val pi = factor.btran(gamma)
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
            if (q == -1) return null // w > 0 with no improving column ⇒ primal infeasible

            denseColumn(q, aq)
            val alpha = factor.ftran(aq)
            val dir = if (qAtLower) 1.0 else -1.0
            var tMax = if (model.hasUpper[q]) model.upper[q].toDouble() else Double.MAX_VALUE
            var leaving = -1
            var leavingToUpper = false
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                if (abs(rate) < TOL) continue
                val v = basicVar[i]
                val hi = if (model.hasUpper[v]) model.upper[v].toDouble() else Double.MAX_VALUE
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
            if (tMax >= Double.MAX_VALUE) return null // no blocker (degenerate/unbounded direction)
            if (leaving == -1) {
                status[q] = if (qAtLower) VarStatus.AT_UPPER else VarStatus.AT_LOWER
                beta = basicValues(factor)
                continue
            }
            if (abs(alpha[leaving]) < TOL) return null
            status[basicVar[leaving]] = if (leavingToUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            factor = if (factor.etaCount + 1 >= refactorEtaLimit) {
                refactor() ?: return null
            } else {
                factor.also {
                    it.update(leaving, alpha)
                }
            }
            beta = basicValues(factor)
        }
        return null // budget exhausted
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
    fun solvePrimal(warm: Basis? = null): FloatLpResult? {
        if (warm == null || !tryWarmStart(warm)) lowerStart()
        var factor: EtaBasis = refactor() ?: run {
            lowerStart()
            refactor() ?: return null
        }
        var beta = basicValues(factor)
        if (!primalFeasible(beta)) {
            factor = primalPhase1(factor) ?: return null
            beta = basicValues(factor)
            if (!primalFeasible(beta)) return null // phase-1 could not reach feasibility
        }
        val maxIter = 50 * (m + numVars) + 200
        val blandStall = 2 * (m + numVars) + BLAND_STALL_BASE
        val aq = DoubleArray(m)
        var iter = 0
        var degenerate = 0 // consecutive zero-length pivots; past [blandStall] switch to Bland's rule
        while (iter++ < maxIter) {
            if (iter % CANCEL_POLL == 0 && cancellation()) return null
            // Bland's rule once degenerate pivots pile up: lowest-index entering, lowest-variable leaving
            // tie-break. Guarantees termination on a degenerate LP that the Dantzig rule could cycle on.
            val bland = degenerate >= blandStall
            val y = duals(factor)
            var q = -1
            var qAtLower = true
            var best = TOL
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val dj = model.cost[j].toDouble() - dotColumn(y, j)
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
            if (q == -1) return optimal(beta, factor) // no improving column ⇒ optimal

            denseColumn(q, aq)
            val alpha = factor.ftran(aq) // α = B⁻¹ A_q
            val dir = if (qAtLower) 1.0 else -1.0 // x_q moves by dir·t, t ≥ 0
            // Ratio test with the entering variable's own bound flip as a candidate blocker.
            var tMax = if (model.hasUpper[q]) model.upper[q].toDouble() else Double.MAX_VALUE
            var leaving = -1
            var leavingToUpper = false
            var leavingVar = Int.MAX_VALUE
            for (i in 0 until m) {
                val rate = -alpha[i] * dir // dβ_i/dt
                var t = Double.MAX_VALUE
                var toUpper = false
                if (rate < -TOL) {
                    t = beta[i] / -rate // β_i falls to its lower bound 0
                } else if (rate > TOL && model.hasUpper[basicVar[i]]) {
                    t = (model.upper[basicVar[i]].toDouble() - beta[i]) / rate // β_i rises to its upper bound
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
                beta = basicValues(factor)
                continue
            }
            if (abs(alpha[leaving]) < TOL) return null // numerically singular pivot
            degenerate = if (tMax <= TOL) degenerate + 1 else 0
            status[basicVar[leaving]] = if (leavingToUpper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            basicVar[leaving] = q
            status[q] = VarStatus.BASIC
            pivots++
            if (factor.etaCount + 1 >= refactorEtaLimit) {
                factor = refactor() ?: return null
            } else {
                factor.update(leaving, alpha)
            }
            beta = basicValues(factor)
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

        /** Pivots folded into the eta chain before a refactorization; bounds fill and rounding drift. */
        const val DEFAULT_REFACTOR_ETA_LIMIT: Int = 50
    }
}
