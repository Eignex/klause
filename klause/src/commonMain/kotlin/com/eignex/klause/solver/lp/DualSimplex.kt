package com.eignex.klause.solver.lp

/** Outcome of an LP solve. */
internal enum class LpStatus {
    /** An optimal vertex was found; [LpSolution] carries the bound, primal, duals and basis. */
    OPTIMAL,

    /** The LP has no feasible point. For branch-and-bound this means the subtree is infeasible. */
    INFEASIBLE,

    /** The objective is unbounded below (minimization). Cannot happen when every variable is bounded. */
    UNBOUNDED,
}

/** Where a variable sits. Nonbasic variables are pinned to a finite bound; basic ones float. */
internal object VarStatus {
    const val BASIC = 0
    const val AT_LOWER = 1
    const val AT_UPPER = 2
}

/**
 * A basis: the set of basic variable columns plus the bound each nonbasic variable is pinned to.
 * Passed to [DualSimplex.solve] to warm-start re-optimization from a parent node's basis (#18,
 * #20). Because branch-and-bound only tightens bounds, the parent basis stays dual feasible, so
 * the child re-optimizes with a few dual pivots instead of a cold solve.
 */
internal class Basis(
    /** The `m` variable columns that are basic. Order is irrelevant; the loader assigns rows. */
    val basicVars: IntArray,
    /** Per-variable status (length `numVars`): [VarStatus.BASIC], [VarStatus.AT_LOWER] or `AT_UPPER`. */
    val status: IntArray,
)

/**
 * An optimal (or infeasible/unbounded) LP solution. All exact quantities are exposed as an
 * integer numerator over the shared determinant [denominator] (> 0): the value is
 * `numerator / denominator`. This is the fraction-free representation — there is one common
 * denominator for the whole solution, not one per number.
 */
internal class LpSolution(
    val status: LpStatus,
    /** Shared positive denominator for every numerator below; the determinant of the optimal basis. */
    val denominator: Long,
    /** Minimized objective numerator (original sense re-applied via [objectiveValue]). */
    val objectiveNumerator: Long,
    /** Per original-variable solution numerator: `x_j = primalNumerator[j] / denominator`. */
    val primalNumerator: LongArray,
    /** Per-row dual value numerator: `y_i = dualNumerator[i] / denominator`. */
    val dualNumerator: LongArray,
    /** Per-variable reduced-cost numerator: `d_j = reducedCostNumerator[j] / denominator`. */
    val reducedCostNumerator: LongArray,
    /** The optimal basis, for warm-starting a child node. */
    val basis: Basis,
    private val sense: Sense,
    /** Dual-simplex pivots taken to reach this solution; lower with a good warm start. */
    val pivots: Int = 0,
) {
    /** The objective in the model's original sense as a floating value (convenience only). */
    val objectiveValue: Double
        get() {
            val signed = if (sense == Sense.MAXIMIZE) -objectiveNumerator else objectiveNumerator
            return signed.toDouble() / denominator.toDouble()
        }

    /**
     * The largest integer that is a valid lower bound on the (minimization) objective: the exact
     * LP bound rounded **up**. Branch-and-bound prunes a node when this is at least the incumbent;
     * because the bound is exact, the rounded comparison is exact too (#20). Only meaningful in
     * minimization sense.
     */
    fun objectiveLowerBoundCeil(): Long = ceilDiv(objectiveNumerator, denominator)

    /** Exact value `primalNumerator[j] / denominator` as a Double, for inspection/tests. */
    fun primal(j: Int): Double = primalNumerator[j].toDouble() / denominator.toDouble()
}

/** Ceiling of `a / b` for `b > 0`, exact over Long. */
private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r > 0L) q + 1 else q
}

/**
 * Bounded-variable **dual** simplex with **fraction-free (Bareiss-style) integer** pivoting — the
 * native LP core of issue #18, purpose-built for branch-and-bound node bounding.
 *
 * ## Why dual simplex
 * Branch-and-bound branches tighten variable bounds, which leaves the parent's basis dual feasible
 * (the reduced-cost signs are unchanged) but primal infeasible (a basic variable now violates a
 * tightened bound). The dual simplex re-optimizes from exactly that state, so a warm start costs a
 * few pivots. A primal simplex would need a Phase I per node, which is why it is out of scope here.
 *
 * ## Why this is sound with no tolerance
 * The tableau `N` is maintained as the integer matrix `det(B) · B⁻¹ · [A | I | b]`. Every entry is
 * an integer (it is the adjugate of `B` times integer data) and the whole tableau shares one
 * integer scale, the basis determinant `d`. A pivot is the one-step Bareiss update
 * `N'[i][j] = (p·N[i][j] − N[i][q]·N[r][j]) / d_prev` with `p = N[r][q]`, which divides exactly by
 * the previous determinant and sets the new determinant to `p`. There is no floating point and no
 * rounding, so the LP bound and the reduced costs are exact — LP-based pruning and reduced-cost
 * fixing are provably sound with no tolerance slack at all.
 *
 * ## Cold start
 * The all-slack basis makes `B = I`, so `N` starts as `[A | I | b]` and `det = 1` with no
 * factorization. Since every klause variable is bounded, the basis is made dual feasible simply by
 * seating each nonbasic structural variable at the bound matching its objective-coefficient sign.
 *
 * ## First-implementation scope
 * Dense tableau, recomputed reduced costs and basic values each iteration, and **Bland's rule** for
 * guaranteed termination. Deliberately deferred to follow-ups under #18: revised simplex with
 * LU / Forrest–Tomlin basis updates, steepest-edge / Devex pricing, the float-fast-path with exact
 * dual certification, and determinant-growth control by periodic refactorization (today an overflow
 * throws [LpOverflowException] instead).
 */
internal class DualSimplex(private val model: LpModel) {
    private val m = model.m
    private val numVars = model.numVars
    private val rhsCol = numVars // last column of N holds det(B)·B⁻¹·b

    /** `N[i][j]`, the fraction-free tableau, `m × (numVars + 1)`. */
    private val nMat = Array(m) { LongArray(numVars + 1) }

    /** Signed basis determinant; the shared scale for the whole tableau. */
    private var d = 1L

    /** Variable column basic in each row. */
    private val basicVar = IntArray(m)

    /** Per-variable status; see [VarStatus]. */
    private val status = IntArray(numVars)

    /** Reset [nMat] to the original `[A | I | b]` and `det = 1`. */
    private fun loadOriginalMatrix() {
        for (i in 0 until m) {
            val row = nMat[i]
            for (j in 0 until model.n) row[j] = model.a[i][j]
            for (s in 0 until m) row[model.n + s] = if (s == i) 1L else 0L
            row[rhsCol] = model.rhs[i]
        }
        d = 1L
    }

    /** Cold start: slack basis, structural variables seated dual-feasibly by cost sign. */
    private fun coldStart() {
        loadOriginalMatrix()
        for (i in 0 until m) {
            basicVar[i] = model.slackCol(i)
            status[model.slackCol(i)] = VarStatus.BASIC
        }
        for (j in 0 until model.n) {
            // Minimization: a nonbasic var with cost ≥ 0 wants to be as small as possible (lower
            // bound), one with cost < 0 as large as possible (upper bound). That assignment makes
            // every reduced cost the right sign, i.e. dual feasible.
            status[j] = if (model.cost[j] >= 0L) VarStatus.AT_LOWER else VarStatus.AT_UPPER
        }
    }

    /**
     * Warm start from [basis]: rebuild the tableau by fraction-free Gauss–Jordan over the basic
     * columns. Returns false if the basis is singular (then the caller should cold-start).
     */
    private fun loadBasis(basis: Basis): Boolean {
        loadOriginalMatrix()
        for (j in 0 until numVars) status[j] = basis.status[j]
        val rowUsed = BooleanArray(m)
        for (v in basis.basicVars) {
            // Pick any unused row with a nonzero entry in column v as that variable's pivot row.
            var pr = -1
            for (i in 0 until m) {
                if (!rowUsed[i] && nMat[i][v] != 0L) {
                    pr = i
                    break
                }
            }
            if (pr == -1) return false // singular basis
            rowUsed[pr] = true
            pivot(pr, v)
            basicVar[pr] = v
            status[v] = VarStatus.BASIC
        }
        return m == 0 || rowUsed.all { it }
    }

    /**
     * One fraction-free pivot turning column [q] basic in row [r]. Updates every non-pivot row by
     * the Bareiss formula (which keeps all entries integer), leaves the pivot row unchanged, and
     * advances the shared determinant to the pivot element. Caller updates [basicVar]/[status].
     */
    private fun pivot(r: Int, q: Int) {
        val pivotRow = nMat[r]
        val p = pivotRow[q]
        val dPrev = d
        for (i in 0 until m) {
            if (i == r) continue
            val row = nMat[i]
            val niq = row[q]
            // Apply to ALL columns, including when niq == 0: the whole tableau rescales by p/dPrev,
            // so skipping a row would desynchronize its scale from the rest of the matrix.
            for (j in 0..numVars) {
                row[j] = bareissStep(p, row[j], niq, pivotRow[j], dPrev)
            }
        }
        d = p
    }

    /** Basic-variable values scaled by [d]: `beta[i] = (det·B⁻¹·b)[i] − Σ nonbasic-at-upper`. */
    private fun computeBeta(): LongArray {
        val beta = LongArray(m)
        for (i in 0 until m) {
            var acc = nMat[i][rhsCol]
            // Nonbasic variables at their lower bound are 0 (bounds were shifted); only those pinned
            // at a finite upper bound move the basic values.
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.AT_UPPER) {
                    acc = subExact(acc, mulExact(nMat[i][j], model.upper[j]))
                }
            }
            beta[i] = acc
        }
        return beta
    }

    /** Reduced costs scaled by [d]: `reduced[j] = det·c_j − Σ_i c_{basic_i}·N[i][j]`. */
    private fun computeReducedCostsScaled(): LongArray {
        val reduced = LongArray(numVars)
        for (j in 0 until numVars) {
            var acc = mulExact(d, model.cost[j])
            for (i in 0 until m) {
                val cb = model.cost[basicVar[i]]
                if (cb != 0L) acc = subExact(acc, mulExact(cb, nMat[i][j]))
            }
            reduced[j] = acc
        }
        return reduced
    }

    /** Sign of `num/den − value`: -1, 0 or +1. Exact. */
    private fun compareFracToValue(num: Long, den: Long, value: Long): Int {
        val diff = subExact(num, mulExact(value, den))
        val s = if (diff == 0L) {
            0
        } else if (diff < 0L) {
            -1
        } else {
            1
        }
        return if (den < 0L) -s else s
    }

    /** Sign of an integer with the determinant sign folded in: sign of `x / d`. */
    private fun fracSign(x: Long): Int {
        val s = if (x == 0L) {
            0
        } else if (x < 0L) {
            -1
        } else {
            1
        }
        return if (d < 0L) -s else s
    }

    /** Solve, optionally warm-starting from [warmBasis]. */
    fun solve(warmBasis: Basis? = null): LpSolution {
        if (warmBasis == null || !loadBasis(warmBasis)) coldStart()
        return runDualSimplex()
    }

    /**
     * Exact Gomory fractional cuts (#22) from the current optimal tableau, expressed over the
     * structural columns so they can be re-added by rebuilding the relaxation. Call only after a
     * [solve] that returned [LpStatus.OPTIMAL]; produces at most [maxCuts] cuts, one per fractional
     * basic structural variable.
     *
     * Derivation, in the engine's shifted space (every variable lower bound 0). A basic variable's
     * row is `x_v = b̄ + Σ_j ā_j·t_j` where each nonbasic `j` contributes a distance-from-bound
     * `t_j ≥ 0` (integer, since all data is integer). Writing it as `x_v + Σ a_j·t_j = b̄` with
     * `a_j = ∓ā_j`, and using that `x_v` and the `t_j` are integers, the pure-integer Gomory cut is
     * `Σ_j frac(a_j)·t_j ≥ frac(b̄)`. It cuts off the current vertex (all `t_j = 0`, so the left side
     * is 0 < frac(b̄)) but holds at every integer point. Each `t_j` is then substituted back to the
     * structural variables: a nonbasic structural variable is `x'_k` (at lower) or `ub_k − x'_k`
     * (at upper); a `≤`-row slack is `rhs_r − Σ_k a_rk·x'_k`; an equality slack is fixed at 0. The
     * result is a linear cut over the structural columns. All arithmetic is exact over the
     * determinant `D = |d|`; an overflow on the scale-up drops that one cut (sound — a missed cut
     * never removes a feasible point).
     */
    fun gomoryCuts(maxCuts: Int): List<Cut> {
        val beta = computeBeta()
        val bigD = if (d < 0L) -d else d
        val sign = if (d < 0L) -1L else 1L
        val cuts = ArrayList<Cut>()
        for (i in 0 until m) {
            if (cuts.size >= maxCuts) break
            val v = basicVar[i]
            if (v >= model.n) continue // cut on fractional structural variables only
            try {
                val bNum = mulExact(sign, beta[i])
                val f0 = floorMod(bNum, bigD)
                if (f0 == 0L) continue // integral value: no cut from this row
                val cut = gomoryRow(i, bigD, sign, f0) ?: continue
                cuts.add(cut)
            } catch (_: LpOverflowException) {
                continue // scale-up overflowed: skip this cut, stay sound
            }
        }
        return cuts
    }

    /** Build the structural-space Gomory cut for basic row [i]; null if it has no nonzero term. */
    private fun gomoryRow(i: Int, bigD: Long, sign: Long, f0: Long): Cut? {
        val coef = LongArray(model.n) // accumulated coefficient on each structural x'_k
        var c = 0L // accumulated constant on the left side
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val atLower = status[j] == VarStatus.AT_LOWER
            // a_j in the classic row form x_v + Σ a_j t_j = b̄: +coef at lower, −coef at upper.
            val aNum = if (atLower) mulExact(sign, nMat[i][j]) else -mulExact(sign, nMat[i][j])
            val rj = floorMod(aNum, bigD) // D·frac(a_j), in [0, D)
            if (rj == 0L) continue
            if (j < model.n) {
                if (atLower) {
                    coef[j] = addExact(coef[j], rj) // t_j = x'_j
                } else {
                    coef[j] = subExact(coef[j], rj) // t_j = ub_j − x'_j
                    c = addExact(c, mulExact(rj, model.upper[j]))
                }
            } else {
                val r = j - model.n
                if (model.hasUpper[r + model.n]) continue // equality slack is fixed at 0 → t_j = 0
                // ≤-row slack: t_j = rhs_r − Σ_k a_rk·x'_k.
                for (k in 0 until model.n) {
                    val ark = model.a[r][k]
                    if (ark != 0L) coef[k] = subExact(coef[k], mulExact(rj, ark))
                }
                c = addExact(c, mulExact(rj, model.rhs[r]))
            }
        }
        // Cut Σ coef_k·x'_k ≥ f0 − c, then unshift x'_k = x_k − loShift_k for the builder's space.
        var rhs = subExact(f0, c)
        for (k in 0 until model.n) {
            if (coef[k] != 0L) rhs = addExact(rhs, mulExact(coef[k], model.loShift[k]))
        }
        var count = 0
        for (k in 0 until model.n) if (coef[k] != 0L) count++
        if (count == 0) return null
        // Divide by the gcd of the coefficients (keeping them small bounds determinant growth on
        // re-solve) and round the GE right-hand side up — a valid Chvátal strengthening, since the
        // reduced coefficients and integer variables make the left side integral.
        var g = 0L
        for (k in 0 until model.n) if (coef[k] != 0L) g = gcdLong(g, coef[k])
        if (g < 1L) g = 1L
        val cols = IntArray(count)
        val vals = LongArray(count)
        var idx = 0
        for (k in 0 until model.n) {
            if (coef[k] != 0L) {
                cols[idx] = k
                vals[idx] = coef[k] / g
                idx++
            }
        }
        val q = rhs / g
        val newRhs = if (rhs % g != 0L && rhs > 0L) q + 1 else q // ceil(rhs / g) for g > 0
        return Cut(cols, vals, Relation.GE, newRhs)
    }

    /** Nonnegative remainder of [a] mod [m] (`m > 0`), in `[0, m)`. */
    private fun floorMod(a: Long, m: Long): Long {
        val r = a % m
        return if (r < 0L) r + m else r
    }

    private fun runDualSimplex(): LpSolution {
        // Bland's rule guarantees termination; the cap only catches an implementation bug.
        val maxIter = 1000L + 100L * (numVars + m)
        var iter = 0L
        var pivots = 0
        // Pricing: Dantzig (largest primal infeasibility) chooses the leaving variable by default,
        // which takes far fewer pivots than smallest-index Bland; but Dantzig can cycle under
        // degeneracy, so a stall detector switches to Bland — which provably terminates — once the
        // infeasibility count stops improving for [stallLimit] iterations.
        val stallLimit = 2 * (m + numVars) + 32
        var bestInfeas = Int.MAX_VALUE
        var sinceImprove = 0
        var useBland = false
        while (true) {
            check(iter++ <= maxIter) { "dual simplex exceeded $maxIter iterations (cycling bug?)" }
            val beta = computeBeta()

            // --- Leaving variable: largest infeasibility (Dantzig), or smallest index under Bland. ---
            var r = -1
            var leavingVar = Int.MAX_VALUE
            var belowLower = false
            var bestViol = 0L // largest |violation| numerator over |d| seen so far (Dantzig)
            var infeas = 0
            for (i in 0 until m) {
                val v = basicVar[i]
                val low = compareFracToValue(beta[i], d, 0L) < 0 // x_v < 0 (its shifted lower bound)
                val high = model.hasUpper[v] && compareFracToValue(beta[i], d, model.upper[v]) > 0
                if (!low && !high) continue
                infeas++
                if (useBland) {
                    if (v < leavingVar) {
                        leavingVar = v
                        r = i
                        belowLower = low
                    }
                } else {
                    // |violation|·|d|: distance past the violated bound (lower 0, or upper).
                    val raw = if (low) beta[i] else subExact(beta[i], mulExact(model.upper[v], d))
                    val viol = if (raw < 0L) -raw else raw
                    if (viol > bestViol || r == -1) {
                        bestViol = viol
                        r = i
                        leavingVar = v
                        belowLower = low
                    }
                }
            }
            if (r == -1) return buildSolution(beta, LpStatus.OPTIMAL, pivots)
            // Stall detection: if the infeasibility count is not shrinking, fall back to Bland.
            if (infeas < bestInfeas) {
                bestInfeas = infeas
                sinceImprove = 0
            } else if (++sinceImprove > stallLimit) {
                useBland = true
            }

            // --- Entering variable: dual ratio test, min |d_j / α_j|, Bland tie-break. ---
            val reduced = computeReducedCostsScaled()
            val pivotRow = nMat[r]
            var q = -1
            var bestRatioNum = 0L // |reduced[q]|
            var bestRatioDen = 0L // |pivotRow[q]|
            for (j in 0 until numVars) {
                if (status[j] == VarStatus.BASIC) continue
                val arj = pivotRow[j]
                if (arj == 0L) continue
                val atLower = status[j] == VarStatus.AT_LOWER
                val alphaSign = fracSign(arj)
                // Eligible iff moving j in its only feasible direction drives the leaving basic toward
                // the bound it violated.
                val eligible = if (belowLower) {
                    (atLower && alphaSign < 0) || (!atLower && alphaSign > 0)
                } else {
                    (atLower && alphaSign > 0) || (!atLower && alphaSign < 0)
                }
                if (!eligible) continue
                val rn = if (reduced[j] < 0L) -reduced[j] else reduced[j]
                val rd = if (arj < 0L) -arj else arj
                // ratio_j = rn/rd; choose the minimum, Bland tie-break on smallest column index.
                if (q == -1 || mulExact(rn, bestRatioDen) < mulExact(bestRatioNum, rd)) {
                    q = j
                    bestRatioNum = rn
                    bestRatioDen = rd
                }
            }
            // No entering variable: the dual is unbounded, so the primal is infeasible.
            if (q == -1) return buildSolution(beta, LpStatus.INFEASIBLE, pivots)

            // The leaving variable settles at the bound it was driven to.
            status[leavingVar] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            pivots++
            pivot(r, q)
            basicVar[r] = q
            status[q] = VarStatus.BASIC
        }
    }

    private fun buildSolution(beta: LongArray, st: LpStatus, pivots: Int): LpSolution {
        // Map each basic variable to its row for primal extraction.
        val varRow = IntArray(numVars) { -1 }
        for (i in 0 until m) varRow[basicVar[i]] = i

        val reduced = computeReducedCostsScaled()

        // Objective (shifted, scaled by d): Σ_basic c·beta_i + d·Σ_{nonbasic at upper} c_j·ub_j.
        var objShifted = 0L
        for (i in 0 until m) {
            val cb = model.cost[basicVar[i]]
            if (cb != 0L) objShifted = addExact(objShifted, mulExact(cb, beta[i]))
        }
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.AT_UPPER && model.cost[j] != 0L) {
                objShifted = addExact(objShifted, mulExact(d, mulExact(model.cost[j], model.upper[j])))
            }
        }
        // Re-add the constant the lower-shift folded out: (objShifted + objConstant·d) / d.
        var objNum = addExact(objShifted, mulExact(model.objConstant, d))

        // Primal: x_j = x'_j + loShift_j, scaled by d.
        val primalNum = LongArray(model.n)
        for (j in 0 until model.n) {
            val shiftedScaled = when (status[j]) {
                VarStatus.AT_LOWER -> 0L
                VarStatus.AT_UPPER -> mulExact(d, model.upper[j])
                else -> beta[varRow[j]] // basic
            }
            primalNum[j] = addExact(shiftedScaled, mulExact(d, model.loShift[j]))
        }

        // Duals: y_i = -d_{slack_i}; reduced costs straight through.
        val dualNum = LongArray(m)
        for (i in 0 until m) dualNum[i] = -reduced[model.slackCol(i)]
        val redNum = reduced.copyOf()

        // Normalize to a positive denominator so consumers (ceil bound, sign tests) need not track
        // the determinant's sign.
        var den = d
        if (den < 0L) {
            den = -den
            objNum = -objNum
            for (j in primalNum.indices) primalNum[j] = -primalNum[j]
            for (i in dualNum.indices) dualNum[i] = -dualNum[i]
            for (j in redNum.indices) redNum[j] = -redNum[j]
        }

        return LpSolution(
            status = st,
            denominator = den,
            objectiveNumerator = objNum,
            primalNumerator = primalNum,
            dualNumerator = dualNum,
            reducedCostNumerator = redNum,
            basis = Basis(basicVars = basicVar.copyOf(), status = status.copyOf()),
            sense = model.sense,
            pivots = pivots,
        )
    }
}
