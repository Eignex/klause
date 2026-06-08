package com.eignex.klause.solver.lp

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/** Constraint relation for a row added to the builder, before normalization to `<=` form. */
internal enum class Relation { LE, GE, EQ }

/** Optimization sense. Branch-and-bound minimizes; [MAXIMIZE] is negated at build time. */
internal enum class Sense { MINIMIZE, MAXIMIZE }

/**
 * A bounded-variable LP in the normalized form the integer-preserving dual simplex (#18)
 * consumes. All input coefficients, bounds and right-hand sides are integers — this is the
 * "integer based" core: it exploits that every klause datum is integral rather than carrying
 * a general-purpose floating LP.
 *
 * Build instances with [LpBuilder], which performs three normalizations so the engine sees a
 * uniform shape:
 *
 *  1. **Lower bounds shifted to zero.** Each structural variable `x_j` with domain
 *     `[lo_j, hi_j]` becomes `x'_j = x_j - lo_j` with domain `[0, hi_j - lo_j]`. A nonbasic
 *     variable at its lower bound then contributes nothing to the basic values, which keeps
 *     the inner loops simple. The shift folds into the right-hand sides and an objective
 *     constant ([objConstant]); [loShift] records it so solutions map back.
 *  2. **`>=` rows negated to `<=`.** Multiplying a `>=` row by `-1` yields a `<=` row, so
 *     every inequality slack has lower bound `0` and only the (possibly infinite) upper bound
 *     varies. Equality rows keep a slack fixed at `[0, 0]`.
 *  3. **Slack columns appended.** Row `i` becomes `Σ a_ij x'_j + s_i = b_i`. With the
 *     all-slack basis the constraint matrix is `[A | I]`, an exact identity in the slack
 *     columns, so the initial determinant is `1` and no factorization is needed to start.
 *
 * Variables are indexed `0 until n` for structural columns and `n until n + m` for the slack
 * of row `i = column - n`. Lower bound is `0` for every variable; the upper bound is finite
 * for structural variables and equality slacks, and `+∞` for inequality slacks (see
 * [hasUpper]).
 */
internal class LpModel(
    /** Number of structural (original) variables. */
    val n: Int,
    /** Number of constraint rows, equivalently the number of slack variables. */
    val m: Int,
    /** Dense constraint coefficients over structural variables: `a[i][j]`, row-major, `m × n`. */
    val a: Array<LongArray>,
    /** Right-hand side per row, after the lower-bound shift. */
    val rhs: LongArray,
    /** Objective coefficient per variable (length `n + m`); minimization sense, slacks are `0`. */
    val cost: LongArray,
    /** Upper bound per variable (length `n + m`); meaningful only where [hasUpper] is true. */
    val upper: LongArray,
    /** Whether the variable has a finite upper bound; `false` means `+∞` (inequality slacks). */
    val hasUpper: BooleanArray,
    /** Lower bound that was shifted out of each structural variable (length `n`). */
    val loShift: LongArray,
    /** Constant folded into the objective by the shift; the true objective is `c·x' + objConstant`. */
    val objConstant: Long,
    /** Original optimization sense; the stored [cost] is always minimization. */
    val sense: Sense,
    /** Caller tag per structural column, for mapping LP columns back to `(varId, value)` (#21). */
    val tag: IntArray,
) {
    /** Total variable count: structural plus slack. */
    val numVars: Int get() = n + m

    /** Column index of row `i`'s slack variable. */
    fun slackCol(i: Int): Int = n + i
}

/**
 * Builds an [LpModel] from structural variables and constraint rows. Coefficients are sparse
 * during construction and densified at [build]. The builder owns the normalizations documented
 * on [LpModel]; callers add variables and rows in natural `<=`/`>=`/`=` form with integer data.
 *
 * Structural variables must have a finite lower bound (every klause integer variable does);
 * an infinite lower bound is rejected because the lower-shift normalization requires it.
 */
internal class LpBuilder {
    // Primitive-specialized accumulators: one Long/Int per variable, no per-element boxing.
    private val lo = LongArrayList()
    private val hi = LongArrayList()
    private val cost = LongArrayList()
    private val tags = IntArrayList()

    // A row's coefficients as parallel primitive arrays (column index, value); no boxed map.
    private class RawRow(val cols: IntArray, val vals: LongArray, val rel: Relation, val rhs: Long)

    private val rows = ArrayList<RawRow>()

    /** Number of structural variables added so far; valid column indices are `0 until varCount`. */
    val varCount: Int get() = lo.size

    /**
     * Add a structural variable with domain `[lower, upper]` and objective coefficient [cost].
     * [tag] is an opaque caller identifier (e.g. an encoded `(varId, value)`) carried through to
     * [LpModel.tag] for reduced-cost fixing. Returns the variable's column index.
     */
    fun addVar(lower: Long, upper: Long, cost: Long = 0L, tag: Int = -1): Int {
        require(lower <= upper) { "empty domain [$lower, $upper]" }
        lo.add(lower)
        hi.add(upper)
        this.cost.add(cost)
        tags.add(tag)
        return lo.size - 1
    }

    /**
     * Add a constraint `Σ vals[k]·x_{cols[k]}  rel  rhs`. [cols] are structural column indices as
     * returned by [addVar]; absent columns are zero, repeated columns are summed. The arrays are
     * copied, so the caller may reuse its buffers.
     */
    fun addRow(cols: IntArray, vals: LongArray, rel: Relation, rhs: Long) {
        require(cols.size == vals.size) { "cols/vals length mismatch: ${cols.size} vs ${vals.size}" }
        rows.add(RawRow(cols.copyOf(), vals.copyOf(), rel, rhs))
    }

    /** Convenience overload for sparse maps (test call sites); unpacks into parallel arrays. */
    fun addRow(coeffs: Map<Int, Long>, rel: Relation, rhs: Long) {
        val cols = IntArray(coeffs.size)
        val vals = LongArray(coeffs.size)
        var k = 0
        for ((j, c) in coeffs) {
            cols[k] = j
            vals[k] = c
            k++
        }
        rows.add(RawRow(cols, vals, rel, rhs))
    }

    /**
     * Materialize the normalized [LpModel] for the given [sense]. Maximization is converted to
     * minimization by negating the objective; the reported objective re-applies the sign.
     */
    fun build(sense: Sense): LpModel {
        val n = lo.size
        val m = rows.size
        val a = Array(m) { LongArray(n) }
        val rhs = LongArray(m)
        val loShift = LongArray(n) { lo[it] }

        for ((i, row) in rows.withIndex()) {
            // Normalize >= to <= by negating both sides; == stays put (its slack is fixed at zero).
            val flip = row.rel == Relation.GE
            var b = if (flip) -row.rhs else row.rhs
            for (k in row.cols.indices) {
                val j = row.cols[k]
                val coeff = if (flip) -row.vals[k] else row.vals[k]
                a[i][j] = addExact(a[i][j], coeff) // sum repeated columns
                // Apply the lower-bound shift: substituting x_j = x'_j + lo_j moves the constant
                // coeff*lo_j across to the right-hand side.
                b = subExact(b, mulExact(coeff, lo[j]))
            }
            rhs[i] = b
        }

        val numVars = n + m
        val cost = LongArray(numVars)
        val upper = LongArray(numVars)
        val hasUpper = BooleanArray(numVars)
        val signedSense = if (sense == Sense.MAXIMIZE) -1L else 1L
        var objConstant = 0L
        for (j in 0 until n) {
            cost[j] = mulExact(signedSense, this.cost[j])
            upper[j] = subExact(hi[j], lo[j])
            hasUpper[j] = true
            // c_j·x_j = c_j·x'_j + c_j·lo_j; the shifted constants accumulate here.
            objConstant = addExact(objConstant, mulExact(cost[j], lo[j]))
        }
        for (i in 0 until m) {
            val sc = n + i
            cost[sc] = 0L
            upper[sc] = 0L
            // An equality slack is fixed at [0, 0] (finite upper); an inequality slack is [0, +inf).
            hasUpper[sc] = rows[i].rel == Relation.EQ
        }

        return LpModel(
            n = n, m = m, a = a, rhs = rhs, cost = cost,
            upper = upper, hasUpper = hasUpper, loShift = loShift,
            objConstant = objConstant, sense = sense, tag = IntArray(n) { tags[it] },
        )
    }
}
