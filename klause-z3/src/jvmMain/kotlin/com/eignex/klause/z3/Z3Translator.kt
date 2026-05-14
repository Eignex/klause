package com.eignex.klause.z3

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.microsoft.z3.ArithExpr
import com.microsoft.z3.BoolExpr
import com.microsoft.z3.BoolSort
import com.microsoft.z3.Context
import com.microsoft.z3.IntExpr
import com.microsoft.z3.IntSort

/**
 * Result of translating a klause [Problem] into Z3 expressions. Holds the boolean and
 * integer Z3 variables corresponding to the original problem's vars; [decode] uses them
 * to lift a Z3 model back to klause types.
 */
internal class Z3Encoding(
    val ctx: Context,
    val boolExprs: Array<BoolExpr>,
    val intExprs: Array<IntExpr>,
)

/**
 * Direct (non-bit-blasted) SMT translation of a klause [Problem] to Z3. Each factor type
 * maps to a native Z3 expression — Z3 reasons over integers natively, so this catches
 * bit-blaster bugs the LogicNG path inherits.
 */
internal object Z3Translator {

    fun translate(problem: Problem, ctx: Context): Pair<Z3Encoding, List<BoolExpr>> {
        val boolExprs: Array<BoolExpr> = Array(problem.numBoolVars) { i ->
            ctx.mkBoolConst("b$i") as BoolExpr
        }
        val intExprs: Array<IntExpr> = Array(problem.numIntVars) { i ->
            ctx.mkIntConst("i$i") as IntExpr
        }
        val encoding = Z3Encoding(ctx, boolExprs, intExprs)

        val constraints = ArrayList<BoolExpr>()
        // Domain constraints for every int var.
        for (i in 0 until problem.numIntVars) {
            val d = problem.intDomains[i]
            constraints.add(ctx.mkAnd(
                ctx.mkGe(intExprs[i], ctx.mkInt(d.min)),
                ctx.mkLe(intExprs[i], ctx.mkInt(d.max)),
            ))
        }
        for (factor in problem.factors) {
            constraints.add(translateFactor(factor, encoding, ctx))
        }
        return encoding to constraints
    }

    private fun translateFactor(factor: Factor, e: Z3Encoding, ctx: Context): BoolExpr = when (factor) {
        is Clause -> orOfLits(factor.literals, e, ctx)
        is Cardinality -> {
            val sum = sumOfLitInts(factor.literals, e, ctx)
            val n = ctx.mkInt(factor.literals.size).let { _ -> sum } // keep ref
            ctx.mkAnd(
                ctx.mkGe(n, ctx.mkInt(factor.min)),
                ctx.mkLe(n, ctx.mkInt(factor.max)),
            )
        }
        is Linear -> {
            val sum = weightedIntSum(factor.coeffs, factor.vars, e, ctx)
            opLinear(sum, factor.op, factor.bound, ctx)
        }
        is PseudoBoolean -> {
            val sum = weightedLitSum(factor.weights, factor.literals, e, ctx)
            opPb(sum, factor.op, factor.bound, ctx)
        }
        is Xor -> {
            // XOR of all literals == targetParity
            // Z3's mkXor is binary, so fold it.
            var acc: BoolExpr = ctx.mkFalse()
            for (lit in factor.literals) acc = ctx.mkXor(acc, litExpr(lit, e, ctx))
            if (factor.targetParity == 1) acc else ctx.mkNot(acc)
        }
        is AllDifferent -> {
            val operands = factor.vars.map { e.intExprs[it] as ArithExpr<IntSort> }
            ctx.mkDistinct(*operands.toTypedArray())
        }
        is Product -> ctx.mkEq(
            e.intExprs[factor.result],
            ctx.mkMul(e.intExprs[factor.a], e.intExprs[factor.b]),
        )
        is ReifiedLinear -> {
            val sum = weightedIntSum(factor.coeffs, factor.vars, e, ctx)
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], opLinear(sum, factor.op, factor.bound, ctx))
        }
        is ReifiedPseudoBoolean -> {
            val sum = weightedLitSum(factor.weights, factor.literals, e, ctx)
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], opPb(sum, factor.op, factor.bound, ctx))
        }
        is ReifiedCardinality -> {
            val sum = sumOfLitInts(factor.literals, e, ctx)
            val pred = ctx.mkAnd(
                ctx.mkGe(sum, ctx.mkInt(factor.min)),
                ctx.mkLe(sum, ctx.mkInt(factor.max)),
            )
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], pred)
        }
        else -> error("Z3Translator: unsupported factor type ${factor::class.simpleName}")
    }

    /** Boolean expression for a klause literal — `boolExprs[v]` or its negation. */
    private fun litExpr(lit: Int, e: Z3Encoding, ctx: Context): BoolExpr {
        val v = e.boolExprs[Lit.variable(lit)]
        return if (Lit.isPositive(lit)) v else ctx.mkNot(v)
    }

    /** OR of klause literals as Z3 expression. */
    private fun orOfLits(literals: IntArray, e: Z3Encoding, ctx: Context): BoolExpr {
        val operands = Array(literals.size) { litExpr(literals[it], e, ctx) }
        return ctx.mkOr(*operands)
    }

    /** Σ of (literal as 0/1) over the given literals. */
    private fun sumOfLitInts(literals: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        if (literals.isEmpty()) return ctx.mkInt(0) as IntExpr
        val terms = Array(literals.size) { litToInt(literals[it], e, ctx) }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** Σ coeffs[i] * intExprs[vars[i]] */
    private fun weightedIntSum(coeffs: IntArray, vars: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        val terms = Array<ArithExpr<IntSort>>(coeffs.size) { i ->
            ctx.mkMul(ctx.mkInt(coeffs[i]), e.intExprs[vars[i]])
        }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** Σ weights[i] * (literal[i] as 0/1) */
    private fun weightedLitSum(weights: IntArray, literals: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        val terms = Array<ArithExpr<IntSort>>(weights.size) { i ->
            ctx.mkMul(ctx.mkInt(weights[i]), litToInt(literals[i], e, ctx))
        }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** A klause literal as a Z3 0/1 IntExpr (1 when the literal is true). */
    private fun litToInt(lit: Int, e: Z3Encoding, ctx: Context): IntExpr {
        val v = e.boolExprs[Lit.variable(lit)]
        val bool: BoolExpr = if (Lit.isPositive(lit)) v else ctx.mkNot(v)
        @Suppress("UNCHECKED_CAST")
        return ctx.mkITE(bool, ctx.mkInt(1), ctx.mkInt(0)) as IntExpr
    }

    private fun opLinear(sum: IntExpr, op: LinearOp, bound: Int, ctx: Context): BoolExpr {
        val b = ctx.mkInt(bound)
        return when (op) {
            LinearOp.LE -> ctx.mkLe(sum, b)
            LinearOp.EQ -> ctx.mkEq(sum, b)
            LinearOp.GE -> ctx.mkGe(sum, b)
            LinearOp.NE -> ctx.mkNot(ctx.mkEq(sum, b))
        }
    }

    private fun opPb(sum: IntExpr, op: PbOp, bound: Int, ctx: Context): BoolExpr {
        val b = ctx.mkInt(bound)
        return when (op) {
            PbOp.LE -> ctx.mkLe(sum, b)
            PbOp.GE -> ctx.mkGe(sum, b)
            PbOp.EQ -> ctx.mkEq(sum, b)
        }
    }
}
