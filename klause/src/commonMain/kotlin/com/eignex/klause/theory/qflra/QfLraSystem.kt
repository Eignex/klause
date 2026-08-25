package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.Sense
import com.eignex.klause.solver.ProblemSpec

internal class QfLraSystem(private val model: ProblemSpec) {
    fun build(bools: BooleanArray): QfLraLeaf {
        val builder = LpBuilder()
        val positive = IntArray(model.numRealVars)
        val negative = IntArray(model.numRealVars) { -1 }
        val realId = ArrayList<Int>()
        val realSign = ArrayList<Int>()
        for (real in 0 until model.numRealVars) {
            val lo = model.realLower[real].takeIf(Double::isFinite)
            val hi = model.realUpper[real].takeIf(Double::isFinite)
            positive[real] = if (lo != null) {
                add(builder, realId, realSign, real, 1, lo, hi)
            } else {
                val pos = add(builder, realId, realSign, real, 1, 0.0, hi?.takeIf { it >= 0.0 })
                val neg = add(builder, realId, realSign, real, -1, 0.0, null)
                negative[real] = neg
                if (hi != null && hi < 0.0) {
                    builder.addRealRow(intArrayOf(pos, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
                }
                pos
            }
        }
        for (factor in model.factors) {
            when (factor) {
                is Linear -> factor.realConstants?.let { row ->
                    addRow(
                        builder,
                        positive,
                        negative,
                        factor.realVars,
                        row.realCoefficients.toDoubleArray(),
                        factor.op,
                        row.bound,
                        row.strict,
                    )
                }

                is ReifiedRealLinear -> {
                    val truth = bools[factor.aux]
                    val op = if (truth) factor.op else flip(factor.op)
                    addRow(
                        builder,
                        positive,
                        negative,
                        factor.realVars,
                        factor.realCoeffs,
                        op,
                        factor.bound,
                        if (truth) factor.strict else !factor.strict,
                    )
                }

                else -> Unit
            }
        }
        return QfLraLeaf(builder.build(Sense.MINIMIZE), realId.toIntArray(), realSign.toIntArray())
    }

    private fun add(
        builder: LpBuilder,
        ids: MutableList<Int>,
        signs: MutableList<Int>,
        real: Int,
        sign: Int,
        lo: Double,
        hi: Double?,
    ): Int {
        val column = builder.addRealVar(lo, hi)
        ids.add(real)
        signs.add(sign)
        return column
    }

    private fun addRow(
        builder: LpBuilder,
        positive: IntArray,
        negative: IntArray,
        reals: IntArray,
        coeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
    ) {
        require(op != LinearOp.NE) { "QF_LRA disequality must be lowered before exact feasibility" }
        val columns = IntArray(reals.size + reals.count { negative[it] >= 0 })
        val values = DoubleArray(columns.size)
        var out = 0
        for (index in reals.indices) {
            val real = reals[index]
            columns[out] = positive[real]
            values[out++] = coeffs[index]
            if (negative[real] >= 0) {
                columns[out] = negative[real]
                values[out++] = -coeffs[index]
            }
        }
        builder.addRealRow(columns, values, relation(op), bound, strict)
    }

    private fun relation(op: LinearOp): Relation = when (op) {
        LinearOp.LE -> Relation.LE
        LinearOp.GE -> Relation.GE
        LinearOp.EQ -> Relation.EQ
        LinearOp.NE -> error("checked by addRow")
    }

    private fun flip(op: LinearOp): LinearOp = if (op == LinearOp.LE) LinearOp.GE else LinearOp.LE
}

internal class QfLraLeaf(val model: LpModel, val realId: IntArray, val realSign: IntArray)
