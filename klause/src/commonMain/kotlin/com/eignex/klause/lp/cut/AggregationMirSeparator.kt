package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.openAddressingCapacity
import com.eignex.klause.util.subExact

/**
 * Aggregation MIR cuts of the {0,½} Chvátal–Gomory family over integer [LinearRow] comparisons. A single
 * integer row `Σ a_j x_j ≤ b` yields no Gomory/MIR cut on its own — its data is integral, so the
 * fractionality those cuts exploit only appears in the simplex tableau. Combining rows with the
 * fractional multiplier ½ reintroduces it: for any subset `S` of `≤`-rows,
 * `Σ_j ⌊(Σ_{i∈S} a_ij)/2⌋·y_j ≤ ⌊(Σ_{i∈S} b_i)/2⌋` is valid for every nonnegative-integer `y` (rounding
 * each coefficient down can only shrink the left side, and the left side is integral so the right may
 * be floored). MIR with `f₀ = ½` is exactly this rounding; aggregating two rows before it is the
 * "multi-row" step. Variables are shifted to `y_j = x_j − lo_j ≥ 0` by their declared lower bounds, so
 * the cut reads only factor structure and declared domains and is globally valid. See Caprara and
 * Fischetti, "{0,½}-Chvátal–Gomory cuts", Math. Program. 1996, https://doi.org/10.1007/BF02592196.
 *
 * Separation enumerates single rows and pairs, emitting a combination only when the LP point violates
 * the resulting cut (an even combination reproduces a scaled original row and never separates). Rows
 * and emitted cuts are capped so the root harvest stays bounded. Unconditional, non-strict [Linear]
 * and [ExactLinear] rows with compact integer constants and integer coordinates, together with
 * [PseudoBoolean] / [Cardinality] rows over 0/1 literals are mined (a negated literal `¬x` enters
 * as `1 − x`, its constant folded into the rhs), and a pair may mix the two. Flow-cover cuts live in
 * [FlowCoverSeparator].
 */
internal class AggregationMirSeparator : CutSeparator {
    private val tol = 1e-6

    /** A `≤`-row over shifted nonnegative variables `y_j = x_j − lo_j`: `Σ a[k]·y_{cols[k]} ≤ b`. */
    private class Row(val cols: IntArray, val a: LongArray, val b: Long)

    override fun separate(ctx: CutContext): List<Cut> {
        if (!ctx.spendSeparationWork((MAX_ROWS + 1L) + MAX_CUTS)) return emptyList()
        val rows = ArrayList<Row>(MAX_ROWS + 1)
        val cuts = ArrayList<Cut>(MAX_CUTS)
        val loOf = newMap(ctx) ?: return cuts
        val firstExact = collectOrdinaryRows(ctx, loOf, rows)
        val ordinary = rows.size
        for (i in 0 until ordinary) {
            if (!ctx.spendSeparationWork()) return cuts
            halfCut(ctx, loOf, rows[i], null)?.let { cuts.add(it) }
            if (ctx.separationStopped || cuts.size >= MAX_CUTS) return cuts
        }
        for (i in 0 until ordinary) {
            for (j in i + 1 until ordinary) {
                if (!ctx.spendSeparationWork()) return cuts
                halfCut(ctx, loOf, rows[i], rows[j])?.let { cuts.add(it) }
                if (ctx.separationStopped || cuts.size >= MAX_CUTS) return cuts
            }
        }
        if (ctx.separationStopped || ordinary >= MAX_ROWS || firstExact < 0) return cuts
        for (index in firstExact until ctx.problem.factors.size) {
            if (!ctx.spendSeparationWork()) return cuts
            val factor = ctx.problem.factors[index]
            if (factor is ExactLinear) addLinearRows(ctx, factor, loOf, rows, MAX_ROWS)
            if (ctx.separationStopped || rows.size >= MAX_ROWS) break
        }
        if (ctx.separationStopped) return cuts
        for (i in ordinary until rows.size) {
            if (!ctx.spendSeparationWork()) return cuts
            halfCut(ctx, loOf, rows[i], null)?.let { cuts.add(it) }
            if (ctx.separationStopped || cuts.size >= MAX_CUTS) return cuts
        }
        for (i in 0 until ordinary) {
            for (j in ordinary until rows.size) {
                if (!ctx.spendSeparationWork()) return cuts
                halfCut(ctx, loOf, rows[i], rows[j])?.let { cuts.add(it) }
                if (ctx.separationStopped || cuts.size >= MAX_CUTS) return cuts
            }
        }
        for (i in ordinary until rows.size) {
            for (j in i + 1 until rows.size) {
                if (!ctx.spendSeparationWork()) return cuts
                halfCut(ctx, loOf, rows[i], rows[j])?.let { cuts.add(it) }
                if (ctx.separationStopped || cuts.size >= MAX_CUTS) return cuts
            }
        }
        return cuts
    }

    /** Normalize each eligible linear / [PseudoBoolean] / [Cardinality] factor to `≤`-rows over shifted
     *  nonnegative variables, recording each column's declared lower bound in [loOf]. GE flips sign;
     *  EQ / a cardinality range contributes both directions. */
    private fun collectOrdinaryRows(ctx: CutContext, loOf: MutableIntLongMap, rows: MutableList<Row>): Int {
        var firstExact = -1
        for (index in ctx.problem.factors.indices) {
            if (!ctx.spendSeparationWork()) break
            val factor = ctx.problem.factors[index]
            when (factor) {
                is Linear -> addLinearRows(ctx, factor, loOf, rows)

                is ExactLinear -> if (firstExact < 0 && factor.constants is IntegerConstants &&
                    factor.activator == LinearRow.ALWAYS && !factor.strict && factor.variables.reals.isEmpty() &&
                    factor.relation != LinearOp.NE
                ) firstExact = index

                is PseudoBoolean -> {
                    if (!booleanRowPermitted(ctx, factor)) continue
                    val lits = factor.literals
                    val w = { k: Int -> factor.weights[k] }
                    val b = factor.bound
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
            if (ctx.separationStopped || rows.size >= MAX_ROWS) break
        }
        return firstExact
    }

    // The shared signed-word guard has no work carrier; its arithmetic must match this metered scan.
    private fun booleanRowPermitted(ctx: CutContext, factor: PseudoBoolean): Boolean {
        val c = factor.constants as? IntegerConstants ?: return false
        if (c.coefficients is UnitConsts && c.bound in 0L..c.coefficients.size.toLong()) return true
        return try {
            var lo = 0L
            var hi = 0L
            for (index in 0 until c.coefficients.size) {
                if (!ctx.spendSeparationWork()) return false
                val weight = c.coeff(index)
                if (weight < 0L) lo = addExact(lo, weight) else hi = addExact(hi, weight)
            }
            if (subExact(hi, lo) < 0L) return false
            subExact(lo, c.bound) != Long.MIN_VALUE && subExact(hi, c.bound) != Long.MIN_VALUE
        } catch (_: CheckedLongOverflowException) {
            false
        }
    }

    private fun addLinearRows(
        ctx: CutContext,
        row: LinearRow,
        loOf: MutableIntLongMap,
        out: MutableList<Row>,
        limit: Int = Int.MAX_VALUE,
    ) {
        if (row.constants !is IntegerConstants || row.activator != LinearRow.ALWAYS || row.strict ||
            row.relation == LinearOp.NE || out.size >= limit
        ) return
        for (index in 0 until row.size) {
            if (!ctx.spendSeparationWork() || !Term.isInt(row.ref(index))) return
        }
        when (row.relation) {
            LinearOp.LE -> addRow(ctx, row, flip = false, loOf, out)

            LinearOp.GE -> addRow(ctx, row, flip = true, loOf, out)

            LinearOp.EQ -> {
                addRow(ctx, row, flip = false, loOf, out)
                if (!ctx.separationStopped && out.size < limit) addRow(ctx, row, flip = true, loOf, out)
            }

            else -> Unit
        }
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
        loOf: MutableIntLongMap,
        out: MutableList<Row>,
    ) {
        try {
            val coef = newMap(ctx) ?: return
            // Columns in first-seen order, so the emitted row keeps the source row's column order.
            if (!ctx.spendSeparationWork(8L)) return
            val order = IntArrayList()
            var b = if (flip) subExact(0L, bound) else bound
            for (k in lits.indices) {
                if (!ctx.spendSeparationWork()) return
                val col = ctx.relaxation.boolColOf[Lit.variable(lits[k])]
                if (col < 0) return
                val w = if (flip) subExact(0L, weightOf(k)) else weightOf(k)
                if (!coef.containsKey(col)) {
                    if (!listGrowth(ctx, order.size)) return
                    order.add(col)
                }
                if (Lit.isPositive(lits[k])) {
                    if (!put(ctx, coef, col, addExact(coef.getOrDefault(col, 0L), w))) return
                } else {
                    if (!put(ctx, coef, col, subExact(coef.getOrDefault(col, 0L), w))) return
                    b = subExact(b, w)
                }
                if (!put(ctx, loOf, col, 0L)) return
            }
            if (coef.isEmpty()) return
            if (!ctx.spendSeparationWork(2L * order.size + 1L)) return
            val cols = IntArray(order.size)
            val a = LongArray(order.size)
            for (i in 0 until order.size) {
                if (!ctx.spendSeparationWork()) return
                cols[i] = order[i]
                a[i] = coef.getOrDefault(order[i], 0L)
            }
            out.add(Row(cols, a, b))
        } catch (_: CheckedLongOverflowException) {
            return // an overflowing accumulation is dropped, not approximated
        }
    }

    private fun addRow(
        ctx: CutContext,
        factor: LinearRow,
        flip: Boolean,
        loOf: MutableIntLongMap,
        out: MutableList<Row>,
    ) {
        val row = factor.constants as? IntegerConstants ?: return
        val k = factor.size
        if (!ctx.spendSeparationWork(2L * k + 1L)) return
        val cols = IntArray(k)
        val a = LongArray(k)
        try {
            var b = if (flip) subExact(0L, row.bound) else row.bound
            for (idx in 0 until k) {
                if (!ctx.spendSeparationWork()) return
                val variable = Term.intVar(factor.ref(idx))
                val col = ctx.relaxation.intColOf[variable]
                if (col < 0) return // a variable without an LP column ⇒ skip the row
                val coeff = if (flip) subExact(0L, row.coeff(idx)) else row.coeff(idx)
                cols[idx] = col
                a[idx] = coeff
                // The rounding needs `y_j = x_j − lo_j ≥ 0`, and the cut is emitted globally. Over a
                // column the model leaves open below, the root box's endpoint is not a lower bound of the
                // model, so the shift would not hold there — drop the row instead of shifting by it.
                if (!ctx.statesLowerBound(variable)) return
                val lo = ctx.rootDomain(variable).min
                if (!put(ctx, loOf, col, lo)) return
                b = subExact(b, mulExact(coeff, lo)) // shift to y_j = x_j − lo_j
            }
            out.add(Row(cols, a, b))
        } catch (_: CheckedLongOverflowException) {
            return // a coefficient × bound that overflows Long is dropped, not approximated
        }
    }

    /** The {0,½} cut from one or two rows, in original `x` coordinates, or null when it has no integer
     *  term or the LP point does not violate it. */
    private fun halfCut(ctx: CutContext, loOf: MutableIntLongMap, r1: Row, r2: Row?): Cut? = try {
        val agg = newMap(ctx) ?: return null
        var bSum = r1.b
        if (!accumulate(ctx, agg, r1)) return null
        if (r2 != null) {
            bSum = addExact(bSum, r2.b)
            if (!accumulate(ctx, agg, r2)) return null
        }
        val rhsY = bSum.floorDiv(2)
        // Σ ⌊A_j/2⌋·y_j ≤ ⌊Bsum/2⌋, then unshift y_j = x_j − lo_j into x coordinates.
        if (!ctx.spendSeparationWork(16L)) return null
        val cols = IntArrayList()
        val coeffs = LongArrayList()
        var rhsX = rhsY
        var lhs = 0.0
        var positiveCoefficient = false
        for (index in agg.usedInternal.indices) {
            if (!ctx.spendSeparationWork()) return null
            if (!agg.usedInternal[index]) continue
            val col = agg.keysInternal[index]
            val aSum = agg.valuesInternal[index]
            val c = aSum.floorDiv(2)
            if (c > 0L) positiveCoefficient = true
            if (c != 0L) {
                if (!listGrowth(ctx, cols.size, copies = 2L)) return null
                cols.add(col)
                coeffs.add(c)
                rhsX = addExact(rhsX, mulExact(c, loOf.getOrDefault(col, 0L)))
                lhs += c.toDouble() * ctx.primalOf(col)
            }
        }
        if (cols.size == 0) return null
        if (rhsY >= 0L && !positiveCoefficient) return null
        if (lhs <= rhsX.toDouble() + tol) return null // not violated by the LP point
        if (!ctx.spendSeparationWork(2L * cols.size + 1L)) return null
        Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.LE, rhsX, global = true)
    } catch (_: CheckedLongOverflowException) {
        null
    }

    private fun accumulate(ctx: CutContext, agg: MutableIntLongMap, r: Row): Boolean {
        for (idx in r.cols.indices) {
            if (!ctx.spendSeparationWork()) return false
            val col = r.cols[idx]
            if (!put(ctx, agg, col, addExact(agg.getOrDefault(col, 0L), r.a[idx]))) return false
        }
        return true
    }

    private fun newMap(ctx: CutContext): MutableIntLongMap? {
        if (!ctx.spendSeparationWork(3L * openAddressingCapacity(8))) return null
        return MutableIntLongMap(8)
    }

    private fun put(ctx: CutContext, map: MutableIntLongMap, key: Int, value: Long): Boolean {
        if (!ctx.spendSeparationWork()) return false
        val capacity = map.keysInternal.size
        if ((map.size.toLong() + 1L) * 2L > capacity && !map.containsKey(key) &&
            !ctx.spendSeparationWork(7L * capacity)
        ) return false
        map.put(key, value)
        return true
    }

    private fun listGrowth(ctx: CutContext, size: Int, copies: Long = 1L): Boolean =
        size < 8 || (size and (size - 1)) != 0 || ctx.spendSeparationWork(3L * size * copies)

    private companion object {
        // Ordinary factors can finish at 49; exact fill checks this threshold per direction.
        const val MAX_ROWS = 48

        /** Cap on cuts emitted per separation round. */
        const val MAX_CUTS = 16
    }
}
