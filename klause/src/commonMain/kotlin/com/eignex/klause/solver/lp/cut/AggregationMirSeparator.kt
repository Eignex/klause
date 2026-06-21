package com.eignex.klause.solver.lp.cut

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Aggregation MIR cuts of the {0,½} Chvátal–Gomory family over the integer [Linear] rows. A single
 * integer row `Σ a_j x_j ≤ b` yields no Gomory/MIR cut on its own — its data is integral, so the
 * fractionality those cuts exploit only appears in the simplex tableau. Combining rows with the
 * fractional multiplier ½ reintroduces it: for any subset `S` of `≤`-rows,
 * `Σ_j ⌊(Σ_{i∈S} a_ij)/2⌋·y_j ≤ ⌊(Σ_{i∈S} b_i)/2⌋` is valid for every nonnegative-integer `y` (rounding
 * each coefficient down can only shrink the left side, and the left side is integral so the right may
 * be floored). MIR with `f₀ = ½` is exactly this rounding; aggregating two rows before it is the
 * "multi-row" step. Variables are shifted to `y_j = x_j − lo_j ≥ 0` by their declared lower bounds, so
 * the cut reads only factor structure and declared domains and is globally valid.
 *
 * Separation enumerates single rows and pairs, emitting a combination only when the LP point violates
 * the resulting cut (an even combination reproduces a scaled original row and never separates). Rows
 * and emitted cuts are capped so the root harvest stays bounded. [Linear] rows over integers and
 * [PseudoBoolean] / [Cardinality] rows over 0/1 literals are both mined (a negated literal `¬x` enters
 * as `1 − x`, its constant folded into the rhs), and a pair may mix the two. Flow-cover cuts are
 * intentionally absent: they need variable-upper-bound / continuous structure this relaxation lacks.
 */
internal class AggregationMirSeparator : CutSeparator {
    private val tol = 1e-6

    /** A `≤`-row over shifted nonnegative variables `y_j = x_j − lo_j`: `Σ a[k]·y_{cols[k]} ≤ b`. */
    private class Row(val cols: IntArray, val a: LongArray, val b: Long)

    override fun separate(ctx: CutContext): List<Cut> {
        val loOf = HashMap<Int, Long>()
        val rows = collectRows(ctx, loOf)
        if (rows.isEmpty()) return emptyList()
        val cuts = ArrayList<Cut>()
        for (i in rows.indices) {
            halfCut(ctx, loOf, rows[i], null)?.let { cuts.add(it) }
            if (cuts.size >= MAX_CUTS) return cuts
        }
        for (i in rows.indices) {
            for (j in i + 1 until rows.size) {
                halfCut(ctx, loOf, rows[i], rows[j])?.let { cuts.add(it) }
                if (cuts.size >= MAX_CUTS) return cuts
            }
        }
        return cuts
    }

    /** Normalize each [Linear] / [PseudoBoolean] / [Cardinality] factor to `≤`-rows over shifted
     *  nonnegative variables, recording each column's declared lower bound in [loOf]. GE flips sign;
     *  EQ / a cardinality range contributes both directions. */
    private fun collectRows(ctx: CutContext, loOf: HashMap<Int, Long>): List<Row> {
        val rows = ArrayList<Row>()
        for (factor in ctx.problem.factors) {
            when (factor) {
                is Linear -> when (factor.op) {
                    LinearOp.LE -> addRow(ctx, factor, flip = false, loOf, rows)

                    LinearOp.GE -> addRow(ctx, factor, flip = true, loOf, rows)

                    LinearOp.EQ -> {
                        addRow(ctx, factor, flip = false, loOf, rows)
                        addRow(ctx, factor, flip = true, loOf, rows)
                    }

                    else -> Unit // NE has no valid linear relaxation row
                }

                is PseudoBoolean -> {
                    val lits = factor.literals
                    val w = { k: Int -> factor.weights[k].toLong() }
                    val b = factor.bound.toLong()
                    when (factor.op) {
                        PbOp.LE -> addBoolRow(ctx, lits, w, b, flip = false, loOf, rows)

                        PbOp.GE -> addBoolRow(ctx, lits, w, b, flip = true, loOf, rows)

                        PbOp.EQ -> {
                            addBoolRow(ctx, lits, w, b, flip = false, loOf, rows)
                            addBoolRow(ctx, lits, w, b, flip = true, loOf, rows)
                        }
                    }
                }

                is Cardinality -> {
                    val ones = { _: Int -> 1L }
                    // min ≤ Σ literals ≤ max: the `≤ max` row and (flipped) the `≥ min` row.
                    addBoolRow(ctx, factor.literals, ones, factor.max.toLong(), flip = false, loOf, rows)
                    addBoolRow(ctx, factor.literals, ones, factor.min.toLong(), flip = true, loOf, rows)
                }

                else -> Unit
            }
            if (rows.size >= MAX_ROWS) break
        }
        return rows
    }

    /** Normalize a 0/1 literal-weighted `≤`-row `Σ wₖ·Lₖ ≤ bound` (after [flip] for a `≥` row) into a
     *  [Row] over Boolean columns: a positive literal contributes `+w·x`, a negative `¬x` contributes
     *  `w·(1−x) = −w·x + w`, folding the constant into the rhs. Skips the row if any literal's variable
     *  has no LP column. Boolean columns shift by lower bound 0. */
    private fun addBoolRow(
        ctx: CutContext,
        lits: IntArray,
        weightOf: (Int) -> Long,
        bound: Long,
        flip: Boolean,
        loOf: HashMap<Int, Long>,
        out: MutableList<Row>,
    ) {
        try {
            val coef = LinkedHashMap<Int, Long>()
            var b = if (flip) -bound else bound
            for (k in lits.indices) {
                val col = ctx.relaxation.boolColOf[Lit.variable(lits[k])]
                if (col < 0) return
                val w = if (flip) -weightOf(k) else weightOf(k)
                if (Lit.isPositive(lits[k])) {
                    coef[col] = addExact(coef[col] ?: 0L, w)
                } else {
                    coef[col] = addExact(coef[col] ?: 0L, -w)
                    b = addExact(b, -w)
                }
                loOf[col] = 0L
            }
            if (coef.isEmpty()) return
            val cols = IntArray(coef.size)
            val a = LongArray(coef.size)
            var i = 0
            for ((c, cw) in coef) {
                cols[i] = c
                a[i] = cw
                i++
            }
            out.add(Row(cols, a, b))
        } catch (_: LpOverflowException) {
            return // an overflowing accumulation is dropped, not approximated
        }
    }

    private fun addRow(
        ctx: CutContext,
        factor: Linear,
        flip: Boolean,
        loOf: HashMap<Int, Long>,
        out: MutableList<Row>,
    ) {
        val k = factor.vars.size
        val cols = IntArray(k)
        val a = LongArray(k)
        try {
            var b = if (flip) -factor.bound.toLong() else factor.bound.toLong()
            for (idx in 0 until k) {
                val col = ctx.relaxation.intColOf[factor.vars[idx]]
                if (col < 0) return // a variable without an LP column ⇒ skip the row
                val coeff = if (flip) -factor.coeffs[idx].toLong() else factor.coeffs[idx].toLong()
                cols[idx] = col
                a[idx] = coeff
                val lo = ctx.problem.intDomains[factor.vars[idx]].min.toLong()
                loOf[col] = lo
                b = addExact(b, -mulExact(coeff, lo)) // shift to y_j = x_j − lo_j
            }
            out.add(Row(cols, a, b))
        } catch (_: LpOverflowException) {
            return // a coefficient × bound that overflows Long is dropped, not approximated
        }
    }

    /** The {0,½} cut from one or two rows, in original `x` coordinates, or null when it has no integer
     *  term or the LP point does not violate it. */
    private fun halfCut(ctx: CutContext, loOf: HashMap<Int, Long>, r1: Row, r2: Row?): Cut? = try {
        val agg = HashMap<Int, Long>()
        var bSum = r1.b
        accumulate(agg, r1)
        if (r2 != null) {
            bSum = addExact(bSum, r2.b)
            accumulate(agg, r2)
        }
        val rhsY = bSum.floorDiv(2)
        // Σ ⌊A_j/2⌋·y_j ≤ ⌊Bsum/2⌋, then unshift y_j = x_j − lo_j into x coordinates.
        val cols = IntArrayList()
        val coeffs = LongArrayList()
        var rhsX = rhsY
        var lhs = 0.0
        for ((col, aSum) in agg) {
            val c = aSum.floorDiv(2)
            if (c == 0L) continue
            cols.add(col)
            coeffs.add(c)
            rhsX = addExact(rhsX, mulExact(c, loOf.getValue(col)))
            lhs += c.toDouble() * ctx.primalOf(col)
        }
        if (cols.size == 0) return null
        if (lhs <= rhsX.toDouble() + tol) return null // not violated by the LP point
        Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.LE, rhsX, global = true)
    } catch (_: LpOverflowException) {
        null
    }

    private fun accumulate(agg: HashMap<Int, Long>, r: Row) {
        for (idx in r.cols.indices) {
            val col = r.cols[idx]
            agg[col] = addExact(agg[col] ?: 0L, r.a[idx])
        }
    }

    private companion object {
        /** Cap on `Linear` rows mined per separation, bounding the `O(rows²)` pair sweep. */
        const val MAX_ROWS = 48

        /** Cap on cuts emitted per separation round. */
        const val MAX_CUTS = 16
    }
}
