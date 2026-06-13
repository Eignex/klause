package com.eignex.klause.solver.lp

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

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
 * Passed to [DualSimplex.solve] to warm-start re-optimization from a parent node's basis. Because
 * branch-and-bound only tightens bounds, the parent basis stays dual feasible, so
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
    /**
     * Farkas infeasibility certificate, set only when [status] is [LpStatus.INFEASIBLE]. The
     * structural columns whose currently-seated bound participates in the dual ray that proves the LP
     * infeasible — together they are a sufficient reason. [certBoundIsUpper] is the parallel array of
     * the seated bound's side (true = the column's upper bound, false = its lower bound). Empty when
     * the solve was feasible. See [DualSimplex.runDualSimplex] for how the leaving row is the ray.
     */
    val certCols: IntArray = IntArray(0),
    val certBoundIsUpper: BooleanArray = BooleanArray(0),
    /**
     * The original-model rows the infeasibility ray combines (nonzero `(B⁻¹)_{r,i}` weight), set
     * with [certCols]. The certificate keeps these rows implicit, so a consumer turning it into a
     * learned clause must check each is globally valid — or cite its recorded premises
     * ([LpModel.rowPremises]) — before keeping the rows silent (see `LpExplanation`).
     */
    val certRows: IntArray = IntArray(0),
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
     * because the bound is exact, the rounded comparison is exact too. Only meaningful in
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
 * native LP core, purpose-built for branch-and-bound node bounding.
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
 * guaranteed termination. Deliberately deferred to follow-ups under: revised simplex with
 * LU / Forrest–Tomlin basis updates, steepest-edge / Devex pricing, the float-fast-path with exact
 * dual certification, and determinant-growth control by periodic refactorization (today an overflow
 * throws [LpOverflowException] instead).
 */
internal class DualSimplex(
    private val model: LpModel,
    /**
     * Consecutive degenerate pivots tolerated before the anti-cycling Bland fallback latches; a
     * negative value (the default) uses the size-derived heuristic in [runDualSimplex]. Exposed
     * only so the anti-cycling regression test can force the fallback on a small instance.
     */
    private val stallLimitOverride: Int = -1,
) {
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

    /** Per-row max `|entry|` (clamped to `Long.MAX_VALUE`), maintained by [loadOriginalMatrix] and
     *  [pivot]. Drives the pivot's bulk overflow precheck: when the bound proves a whole row's
     *  Bareiss update cannot overflow, the row runs through an unchecked loop the JIT can
     *  vectorize instead of the branch-per-element checked fallback. */
    private val rowMaxAbs = LongArray(m)

    private fun absClamped(x: Long): Long {
        val a = if (x < 0L) -x else x
        return if (a < 0L) Long.MAX_VALUE else a // -Long.MIN_VALUE overflows back to MIN
    }

    /** Reset [nMat] to the original `[A | I | b]` and `det = 1`. */
    private fun loadOriginalMatrix() {
        for (i in 0 until m) {
            val row = nMat[i]
            for (j in 0 until model.n) row[j] = model.a[i][j]
            for (s in 0 until m) row[model.n + s] = if (s == i) 1L else 0L
            row[rhsCol] = model.rhs[i]
            var mx = 0L
            for (j in 0..numVars) {
                val a = absClamped(row[j])
                if (a > mx) mx = a
            }
            rowMaxAbs[i] = mx
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
     *
     * Hot path: a per-row bulk precheck against [rowMaxAbs] proves `|p·x| ≤ Long.MAX/2` and
     * `|niq·z| ≤ Long.MAX/2` for the whole row at once, so the row updates in a tight unchecked
     * loop (the divisions stay exact by the fraction-free invariant); only rows the bound cannot
     * clear take the element-wise overflow-checked fallback. Rows with `niq == 0` reduce to a pure
     * rescale by `p/dPrev` — the identity when `p == dPrev`.
     */
    private fun pivot(r: Int, q: Int) {
        val pivotRow = nMat[r]
        val p = pivotRow[q]
        val dPrev = d
        val half = Long.MAX_VALUE / 2
        val pSafe = half / absClamped(p) // max |x| with |p·x| ≤ half; p != 0 for a pivot
        val pivMax = rowMaxAbs[r]
        for (i in 0 until m) {
            if (i == r) continue
            val row = nMat[i]
            val niq = row[q]
            var mx = 0L
            if (niq == 0L) {
                // The whole tableau rescales by p/dPrev; a zero-coefficient row still rescales,
                // else its scale desynchronizes from the rest of the matrix.
                if (p == dPrev) continue // identity rescale: row (and its max) unchanged
                if (rowMaxAbs[i] <= pSafe) {
                    if (dPrev == 1L) {
                        for (j in 0..numVars) {
                            val v = p * row[j]
                            row[j] = v
                            val a = if (v < 0L) -v else v
                            if (a > mx) mx = a
                        }
                    } else {
                        for (j in 0..numVars) {
                            val v = p * row[j] / dPrev
                            row[j] = v
                            val a = if (v < 0L) -v else v
                            if (a > mx) mx = a
                        }
                    }
                } else {
                    for (j in 0..numVars) {
                        val v = bareissStep(p, row[j], 0L, 0L, dPrev)
                        row[j] = v
                        val a = absClamped(v)
                        if (a > mx) mx = a
                    }
                }
            } else if (rowMaxAbs[i] <= pSafe && pivMax <= half / absClamped(niq)) {
                // The exact division dominates the near-totally-unimodular phases where the
                // determinant sits at 1 — split it out of the kernel there.
                if (dPrev == 1L) {
                    for (j in 0..numVars) {
                        val v = p * row[j] - niq * pivotRow[j]
                        row[j] = v
                        val a = if (v < 0L) -v else v
                        if (a > mx) mx = a
                    }
                } else {
                    for (j in 0..numVars) {
                        val v = (p * row[j] - niq * pivotRow[j]) / dPrev
                        row[j] = v
                        val a = if (v < 0L) -v else v
                        if (a > mx) mx = a
                    }
                }
            } else {
                for (j in 0..numVars) {
                    val v = bareissStep(p, row[j], niq, pivotRow[j], dPrev)
                    row[j] = v
                    val a = absClamped(v)
                    if (a > mx) mx = a
                }
            }
            rowMaxAbs[i] = mx
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

    /**
     * Solve, optionally warm-starting from a sibling tableau ([seedTableau], cheapest) or from
     * [warmBasis]. Both are pure speedups, so any failure — a shape/coefficient mismatch the seed
     * cannot absorb, a singular basis, or determinant overflow during either load — falls through
     * to the next option and finally to the cold start instead of giving up on the solve.
     */
    fun solve(warmBasis: Basis? = null, seedTableau: DualSimplex? = null): LpSolution {
        lastSolveSeeded = seedTableau != null &&
            try {
                seedFrom(seedTableau)
            } catch (_: LpOverflowException) {
                false
            }
        if (!lastSolveSeeded) {
            val warmed = warmBasis != null &&
                try {
                    loadBasis(warmBasis)
                } catch (_: LpOverflowException) {
                    false
                }
            if (!warmed) coldStart()
        }
        return runDualSimplex()
    }

    /** True when the last [solve] started from a seeded tableau (observability for tests). */
    var lastSolveSeeded: Boolean = false
        private set

    /**
     * True when the last [solve] engaged the Bland anti-cycling fallback. Observability for the
     * regression test that guards termination on degenerate inputs; see [runDualSimplex].
     */
    var lastUsedBland: Boolean = false
        private set

    /**
     * Seed this solve from [prev]'s solved tableau instead of re-pivoting the basis from scratch.
     * Between a branch-and-bound parent and child the row *structure* is identical and only live
     * information moves: variable bounds (which never enter the tableau), the lower-bound shift
     * folded into [LpModel.rhs], and the live big-M coefficients of reified rows — confined to
     * their aux Boolean columns. As long as every changed coefficient lies in a column that is
     * **nonbasic** in [prev]'s basis, the basis matrix `B` is bit-identical, so the carried
     * determinant and the tableau's slack block `det·B⁻¹` stay exact, and the differences patch in
     * through that block with pure integer arithmetic:
     *
     *  - a changed nonbasic column `q`: `N[·][q] += Σ_{i ∈ changed rows} Δa[i][q] · N[·][slack(i)]`
     *  - the rhs column: `N[·][rhs] += Σ_{k: Δb_k ≠ 0} Δb_k · N[·][slack(k)]`
     *
     * — O(m · |changes|) plus an O(m·numVars) array copy, against the O(m²·numVars) pivot reload
     * of [loadBasis]. A patched column's reduced cost moves, so its seat is re-derived from the
     * reduced-cost sign to keep the start dual feasible (changed columns are bounded — they are
     * structural — so both seats exist). Returns false when the shapes, costs, row relations or a
     * basic column differ: the caller then falls back to the basis reload.
     *
     * A child with **more** rows than [prev] and a bit-identical prefix — the cut loop's shape —
     * takes the append path instead: see [seedAppended].
     */
    private fun seedFrom(prev: DualSimplex): Boolean {
        val pm = prev.model
        if (pm.n != model.n) return false
        if (pm.m < m) return seedAppended(prev)
        if (pm.m != m) return false
        if (!pm.cost.contentEquals(model.cost) || !pm.hasUpper.contentEquals(model.hasUpper)) return false

        // Diff the constraint coefficients; reject a change in any column basic in prev (it would
        // change B itself). Collect per-change (row, col, delta) for the patch pass.
        val changeRow = IntArrayList()
        val changeCol = IntArrayList()
        val changeDelta = LongArrayList()
        for (i in 0 until m) {
            val a = model.a[i]
            val pa = pm.a[i]
            for (j in 0 until model.n) {
                if (a[j] == pa[j]) continue
                if (prev.status[j] == VarStatus.BASIC) return false
                changeRow.add(i)
                changeCol.add(j)
                changeDelta.add(subExact(a[j], pa[j]))
            }
        }

        // Carry the solved state over.
        for (i in 0 until m) {
            prev.nMat[i].copyInto(nMat[i])
            rowMaxAbs[i] = prev.rowMaxAbs[i]
        }
        prev.basicVar.copyInto(basicVar)
        prev.status.copyInto(status)
        d = prev.d

        // Patch the changed nonbasic columns through the slack block (unchanged by these writes:
        // every patched column is structural).
        for (c in 0 until changeRow.size) {
            val sCol = model.slackCol(changeRow[c])
            val q = changeCol[c]
            val delta = changeDelta[c]
            for (i in 0 until m) {
                val add = mulExact(delta, nMat[i][sCol])
                if (add != 0L) {
                    val v = addExact(nMat[i][q], add)
                    nMat[i][q] = v
                    val abs = absClamped(v)
                    if (abs > rowMaxAbs[i]) rowMaxAbs[i] = abs
                }
            }
        }
        // Patch the rhs column the same way.
        for (k in 0 until m) {
            val db = subExact(model.rhs[k], pm.rhs[k])
            if (db == 0L) continue
            val sCol = model.slackCol(k)
            for (i in 0 until m) {
                val add = mulExact(db, nMat[i][sCol])
                if (add != 0L) {
                    val v = addExact(nMat[i][rhsCol], add)
                    nMat[i][rhsCol] = v
                    val abs = absClamped(v)
                    if (abs > rowMaxAbs[i]) rowMaxAbs[i] = abs
                }
            }
        }

        // Re-derive the seat of each patched column from its (new) reduced-cost sign: the dual
        // simplex requires a dual-feasible start, and only the patched columns can have drifted.
        for (c in 0 until changeRow.size) {
            val q = changeCol[c]
            if (status[q] == VarStatus.BASIC) continue // unreachable (checked above); defensive
            var red = mulExact(d, model.cost[q])
            for (i in 0 until m) {
                val cb = model.cost[basicVar[i]]
                if (cb != 0L) red = subExact(red, mulExact(cb, nMat[i][q]))
            }
            val sign = fracSign(red)
            if (sign < 0) status[q] = VarStatus.AT_UPPER
            if (sign > 0) status[q] = VarStatus.AT_LOWER
        }
        return true
    }

    /**
     * Seed from a [prev] tableau with **fewer** rows whose row prefix is bit-identical — the cut
     * loop's shape, where rows are only ever appended under unchanged variable bounds. With each
     * appended row's slack basic, the grown basis is block-triangular
     * (`B' = [[B, 0], [C, I]]`, `C` = the new rows' entries at the old basic columns), so the
     * carried determinant is unchanged and:
     *
     *  - old tableau rows carry over verbatim, gaining zero entries under the new slack columns
     *    (`B'⁻¹`'s upper-right block is zero);
     *  - appended row `t` materializes as `det·M'_t − Σ_j a_t[j]·N[rowOf(j)][·]` over the basic
     *    *structural* columns `j` in its support (old-slack basics contribute nothing — a cut row
     *    has no slack coefficients) — O(numVars · |basic support|) instead of a pivot reload.
     *
     * No reseat is needed: the appended basic slacks carry zero cost, so every old column's
     * reduced cost — and with it dual feasibility — is untouched.
     */
    private fun seedAppended(prev: DualSimplex): Boolean {
        val pm = prev.model
        val m0 = pm.m
        // The structural costs and the old rows (coefficients, rhs, relation) must be identical;
        // bounds are free to differ (they never enter the tableau).
        for (j in 0 until model.n) {
            if (pm.cost[j] != model.cost[j]) return false
        }
        for (i in 0 until m0) {
            if (!pm.a[i].contentEquals(model.a[i])) return false
            if (pm.rhs[i] != model.rhs[i]) return false
            if (pm.hasUpper[pm.n + i] != model.hasUpper[model.n + i]) return false
        }

        // Old rows carry over into the wider column layout; new slack columns are zero there.
        val oldVars = model.n + m0
        for (i in 0 until m0) {
            val src = prev.nMat[i]
            val dst = nMat[i]
            src.copyInto(dst, destinationOffset = 0, startIndex = 0, endIndex = oldVars)
            for (j in oldVars until numVars) dst[j] = 0L
            dst[rhsCol] = src[oldVars] // the parent's rhs column index
            rowMaxAbs[i] = prev.rowMaxAbs[i]
            basicVar[i] = prev.basicVar[i]
        }
        for (j in 0 until oldVars) status[j] = prev.status[j]
        d = prev.d

        // Row index of each basic structural column, for the C·B⁻¹ subtraction.
        val varRow = IntArray(model.n) { -1 }
        for (k in 0 until m0) {
            val v = prev.basicVar[k]
            if (v < model.n) varRow[v] = k
        }
        for (t in m0 until m) {
            val dst = nMat[t]
            for (j in 0 until model.n) dst[j] = mulExact(d, model.a[t][j])
            for (j in model.n until numVars) dst[j] = 0L
            dst[model.n + t] = d
            dst[rhsCol] = mulExact(d, model.rhs[t])
            for (j in 0 until model.n) {
                val c = model.a[t][j]
                if (c == 0L) continue
                val k = varRow[j]
                if (k < 0) continue
                val par = nMat[k] // already in the new layout; its new-slack entries are zero
                for (col in 0..numVars) {
                    val sub = mulExact(c, par[col])
                    if (sub != 0L) dst[col] = subExact(dst[col], sub)
                }
            }
            var mx = 0L
            for (col in 0..numVars) {
                val a = absClamped(dst[col])
                if (a > mx) mx = a
            }
            rowMaxAbs[t] = mx
            basicVar[t] = model.n + t
            status[model.n + t] = VarStatus.BASIC
        }
        return true
    }

    /**
     * Exact Gomory fractional cuts from the current optimal tableau, expressed over the
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
    fun gomoryCuts(maxCuts: Int): List<Cut> = tableauCuts(maxCuts) { _, rj -> rj }

    /**
     * Gomory mixed-integer (MIR) cuts from the optimal tableau — the same single-row derivation as
     * [gomoryCuts] but with the stronger mixed-integer rounding multiplier on each nonbasic term.
     *
     * For a basic row `x_v + Σ_j a_j·t_j = b̄` (fractional `b̄`, all `t_j ≥ 0` integer), the MIR
     * inequality is `Σ_j φ(a_j)·t_j ≥ f0` with `f_j = frac(a_j)`, `f0 = frac(b̄)`, and
     * `φ(a_j) = f_j` when `f_j ≤ f0`, else `f0·(1 − f_j)/(1 − f0)`. This dominates the pure-integer
     * Gomory cut `Σ f_j·t_j ≥ f0` (the second branch is smaller whenever `f_j > f0`). Every nonbasic
     * `t_j` here is integer-valued — structural variables are integer and a `≤`-row slack of an
     * integer row at an integer point is integer — so the all-integer MIR function applies to every
     * column. Clearing denominators by `D·(D − r0)` (with `r_j = D·f_j`, `r0 = D·f0`, `D = |d|`)
     * keeps the multiplier `r_j·(D − r0)` / `r0·(D − r_j)` and right-hand side `r0·(D − r0)` exact in
     * Long arithmetic; an overflow on the scale-up drops that one cut (sound — a missed cut never
     * removes a feasible point). The `t_j` are then back-substituted to the structural columns and
     * the result Chvátal-rounded exactly as in [gomoryCuts].
     */
    fun mirCuts(maxCuts: Int): List<Cut> = tableauCuts(maxCuts) { f0, rj ->
        // bigD is captured below via the row builder; r0 == f0, both already D-scaled in [0, D).
        val bigD = if (d < 0L) -d else d
        if (rj <= f0) mulExact(rj, bigD - f0) else mulExact(f0, bigD - rj)
    }

    /**
     * Shared single-row cut generator over fractional basic structural variables. [coefOf] maps the
     * row's `f0` and a nonbasic term's `r_j = D·frac(a_j)` to that term's integer multiplier (plain
     * `r_j` for Gomory, the MIR rounding for [mirCuts]); the base right-hand side scales `f0` by the
     * same factor `coefOf` applies to `r_j == r0`.
     */
    private inline fun tableauCuts(maxCuts: Int, coefOf: (f0: Long, rj: Long) -> Long): List<Cut> {
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
                val baseRhs = coefOf(f0, f0) // scale f0 by the same factor used on r_j == r0
                val cut = tableauRow(i, bigD, sign, f0, baseRhs, coefOf) ?: continue
                cuts.add(cut)
            } catch (_: LpOverflowException) {
                continue // scale-up overflowed: skip this cut, stay sound
            }
        }
        return cuts
    }

    /** Build the structural-space single-row cut for basic row [i]; null if it has no nonzero term. */
    private inline fun tableauRow(
        i: Int,
        bigD: Long,
        sign: Long,
        f0: Long,
        baseRhs: Long,
        coefOf: (f0: Long, rj: Long) -> Long,
    ): Cut? {
        val coef = LongArray(model.n) // accumulated coefficient on each structural x'_k
        var c = 0L // accumulated constant on the left side
        for (j in 0 until numVars) {
            if (status[j] == VarStatus.BASIC) continue
            val atLower = status[j] == VarStatus.AT_LOWER
            // a_j in the classic row form x_v + Σ a_j t_j = b̄: +coef at lower, −coef at upper.
            val aNum = if (atLower) mulExact(sign, nMat[i][j]) else -mulExact(sign, nMat[i][j])
            val rj = floorMod(aNum, bigD) // D·frac(a_j), in [0, D)
            if (rj == 0L) continue
            val mj = coefOf(f0, rj) // term multiplier (r_j for Gomory, MIR rounding otherwise)
            if (mj == 0L) continue
            if (j < model.n) {
                if (atLower) {
                    coef[j] = addExact(coef[j], mj) // t_j = x'_j
                } else {
                    coef[j] = subExact(coef[j], mj) // t_j = ub_j − x'_j
                    c = addExact(c, mulExact(mj, model.upper[j]))
                }
            } else {
                val r = j - model.n
                if (model.hasUpper[r + model.n]) continue // equality slack is fixed at 0 → t_j = 0
                // ≤-row slack: t_j = rhs_r − Σ_k a_rk·x'_k.
                for (k in 0 until model.n) {
                    val ark = model.a[r][k]
                    if (ark != 0L) coef[k] = subExact(coef[k], mulExact(mj, ark))
                }
                c = addExact(c, mulExact(mj, model.rhs[r]))
            }
        }
        // Cut Σ coef_k·x'_k ≥ baseRhs − c, then unshift x'_k = x_k − loShift_k for the builder's space.
        var rhs = subExact(baseRhs, c)
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
        // The Bland fallback below provably terminates, so the cap is only a backstop. Reaching it
        // means the exact solve was abandoned; like an overflow it surfaces as [LpOverflowException]
        // so the branch-and-bound caller keeps the node with no LP bound (sound — a missing bound
        // only loses pruning) instead of aborting the whole solve.
        val maxIter = 1000L + 100L * (numVars + m)
        var iter = 0L
        var pivots = 0
        // Pricing: Dantzig (largest primal infeasibility) chooses the leaving variable by default,
        // which takes far fewer pivots than smallest-index Bland; but Dantzig can cycle under
        // degeneracy. A cycle is necessarily an unbroken run of *degenerate* pivots — ones that take
        // a zero-length dual step and so leave the (monotone) dual objective unchanged; any pivot
        // that does move the objective strictly raises it, so a basis it leaves can never recur. A
        // dual pivot is degenerate exactly when the entering column's reduced cost is zero, so we
        // count consecutive degenerate pivots and latch Bland — which provably terminates — once
        // they pass [stallLimit]. Counting *consecutive degenerate* pivots (rather than a global-best
        // infeasibility count that resets on every new low) is what makes the fallback latch on a long
        // degenerate run instead of being reset out from under itself (issue #379).
        val stallLimit = if (stallLimitOverride >= 0) stallLimitOverride else 2 * (m + numVars) + 32
        var degeneratePivots = 0
        var useBland = false
        lastUsedBland = false
        // Basic values and reduced costs are computed once and then maintained incrementally:
        // both are linear functionals of the tableau rows (beta over the rhs/at-upper columns,
        // reduced costs over the virtual cost row), so each transforms under exactly the same
        // Bareiss step as the tableau itself — one O(m)+O(numVars) update per pivot instead of
        // an O(m·numVars) recompute per iteration.
        val beta = computeBeta()
        val reduced = computeReducedCostsScaled()
        val colScratch = LongArray(m) // pre-pivot entering column, for the beta update
        while (true) {
            if (iter++ > maxIter) {
                // Unreachable once Bland latches; degrade gracefully instead of aborting the solve.
                throw LpOverflowException("dual simplex exceeded $maxIter iterations")
            }

            // --- Leaving variable: largest infeasibility (Dantzig), or smallest index under Bland. ---
            var r = -1
            var leavingVar = Int.MAX_VALUE
            var belowLower = false
            var bestViol = 0L // largest |violation| numerator over |d| seen so far (Dantzig)
            for (i in 0 until m) {
                val v = basicVar[i]
                val low = compareFracToValue(beta[i], d, 0L) < 0 // x_v < 0 (its shifted lower bound)
                val high = model.hasUpper[v] && compareFracToValue(beta[i], d, model.upper[v]) > 0
                if (!low && !high) continue
                if (useBland) {
                    if (v < leavingVar) {
                        leavingVar = v
                        r = i
                        belowLower = low
                    }
                } else {
                    // |violation|·|d|: distance past the violated bound (lower 0, or upper). Ties
                    // break on the smallest basic-variable index — NOT scan order — so the pivot
                    // trajectory is canonical in the basis, independent of how the tableau's rows
                    // happen to be permuted (a seeded reload preserves the parent's row order, a
                    // basis reload assigns its own; without a canonical tie-break the two walk
                    // different pivot paths from identical starts).
                    val raw = if (low) beta[i] else subExact(beta[i], mulExact(model.upper[v], d))
                    val viol = if (raw < 0L) -raw else raw
                    if (viol > bestViol || (viol == bestViol && v < leavingVar) || r == -1) {
                        bestViol = viol
                        r = i
                        leavingVar = v
                        belowLower = low
                    }
                }
            }
            if (r == -1) return buildSolution(beta, reduced, LpStatus.OPTIMAL, pivots)

            // --- Entering variable: dual ratio test, min |d_j / α_j|, Bland tie-break. ---
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
            // No entering variable: the dual is unbounded, so the primal is infeasible. The leaving
            // row [r] (basic variable past bound [belowLower], no column able to repair it) is the
            // Farkas dual ray — record its support as the infeasibility certificate.
            if (q == -1) return buildSolution(beta, reduced, LpStatus.INFEASIBLE, pivots, r, belowLower)

            // Anti-cycling: a degenerate pivot — zero reduced cost on the entering column, hence a
            // zero-length dual step — makes no objective progress, and an unbroken run of them is
            // the only way to cycle. Latch the provably-terminating Bland rule once they pass the
            // limit; a non-degenerate pivot (real objective progress) resets the run.
            if (bestRatioNum == 0L) {
                if (++degeneratePivots > stallLimit) {
                    useBland = true
                    lastUsedBland = true
                }
            } else {
                degeneratePivots = 0
            }

            // Capture what the incremental updates need before the tableau mutates: the entering
            // column (pivot() rewrites it), the pivot element/determinant, and the two scalars the
            // Bareiss updates overwrite. The pivot row itself survives pivot() unchanged.
            val p = pivotRow[q]
            val dPrev = d
            for (i in 0 until m) colScratch[i] = nMat[i][q]
            val betaR = beta[r]
            val redQ = reduced[q]
            val qWasUpper = status[q] == VarStatus.AT_UPPER

            // The leaving variable settles at the bound it was driven to.
            status[leavingVar] = if (belowLower) VarStatus.AT_LOWER else VarStatus.AT_UPPER
            pivots++
            pivot(r, q)
            basicVar[r] = q
            status[q] = VarStatus.BASIC

            // Reduced costs: the virtual cost row pivots like any other row (and lands exactly on
            // 0 for the now-basic column q).
            for (j in 0 until numVars) {
                reduced[j] = bareissStep(p, reduced[j], redQ, pivotRow[j], dPrev)
            }
            // Basic values over the *old* seat set: the same Bareiss step, with the captured
            // entering column standing in for N[i][q] (row r is unchanged by the pivot).
            for (i in 0 until m) {
                if (i != r) beta[i] = bareissStep(p, beta[i], colScratch[i], betaR, dPrev)
            }
            // Seat-set delta. q left the nonbasic set: its post-pivot column is det·e_r, so the
            // at-upper contribution it used to carry survives only in row r. The leaving variable
            // entered it: subtract its column when it settled at its upper bound (at lower it
            // contributes nothing in the shifted space).
            if (qWasUpper) beta[r] = addExact(beta[r], mulExact(p, model.upper[q]))
            if (!belowLower) {
                val ub = model.upper[leavingVar]
                if (ub != 0L) {
                    for (i in 0 until m) beta[i] = subExact(beta[i], mulExact(nMat[i][leavingVar], ub))
                }
            }
        }
    }

    private fun buildSolution(
        beta: LongArray,
        reduced: LongArray,
        st: LpStatus,
        pivots: Int,
        infeasibleRow: Int = -1,
        infeasibleBelowLower: Boolean = false,
    ): LpSolution {
        // Map each basic variable to its row for primal extraction.
        val varRow = IntArray(numVars) { -1 }
        for (i in 0 until m) varRow[basicVar[i]] = i

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

        val cert = infeasibilityCertificate(st, infeasibleRow, infeasibleBelowLower)

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
            certCols = cert.cols,
            certBoundIsUpper = cert.boundIsUpper,
            certRows = cert.rows,
        )
    }

    /**
     * The structural columns in the support of the infeasibility dual ray. The leaving row
     * `x_lv = β/d − Σ_j (N[r][j]/d)·t_j` is an equality implied by the model's constraints; with the
     * leaving basic variable forced past its violated bound and every nonbasic seated at the bound the
     * row references, the bounds are jointly inconsistent. The reason is therefore the leaving
     * variable's violated bound plus the seated bound of each nonbasic *structural* column with a
     * nonzero row coefficient. Slack columns map to model rows (always present, not branch bounds), so
     * they are not part of the bound reason. Sign of the determinant does not affect which side a
     * column is seated at, so this needs no normalization.
     *
     * Keeping the rows implicit is only sound when every combined row is globally valid (or its
     * recorded premises are cited alongside). Row `i`'s weight in the combination is `(B⁻¹)_{r,i}`,
     * which sits in the tableau as the leaving row's slack-column entry `N[r][slackCol(i)]/d`; the
     * rows with nonzero weight are reported as [LpSolution.certRows] and the expressibility
     * decision lives with the consumer (`LpExplanation`), which has the premise data.
     */
    private fun infeasibilityCertificate(st: LpStatus, infeasibleRow: Int, belowLower: Boolean): Certificate {
        if (st != LpStatus.INFEASIBLE || infeasibleRow < 0) {
            return Certificate(IntArray(0), BooleanArray(0), IntArray(0))
        }
        val rows = IntArrayList()
        for (i in 0 until m) {
            if (nMat[infeasibleRow][model.slackCol(i)] != 0L) rows.add(i)
        }
        val cols = IntArrayList()
        val upper = ArrayList<Boolean>()
        val lv = basicVar[infeasibleRow]
        if (lv < model.n) {
            // The leaving variable is driven past its lower bound (belowLower) or upper bound.
            cols.add(lv)
            upper.add(!belowLower)
        }
        val row = nMat[infeasibleRow]
        for (j in 0 until numVars) {
            if (j >= model.n || status[j] == VarStatus.BASIC || row[j] == 0L) continue
            cols.add(j)
            upper.add(status[j] == VarStatus.AT_UPPER)
        }
        return Certificate(cols.toIntArray(), BooleanArray(upper.size) { upper[it] }, rows.toIntArray())
    }

    /** The Farkas certificate triple; see [infeasibilityCertificate]. */
    private class Certificate(val cols: IntArray, val boundIsUpper: BooleanArray, val rows: IntArray)
}
