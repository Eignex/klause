package com.eignex.klause.choco

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
import org.chocosolver.solver.Model
import org.chocosolver.solver.variables.BoolVar
import org.chocosolver.solver.variables.IntVar

/**
 * Translates a klause [Problem] into a Choco [Model]. Mirrors the factor coverage of
 * `klause-smt`'s `SmtTranslator`: the core SAT/CP factors that compiled FlatZinc and the
 * bench's in-code suites actually use. Unsupported factors raise [UnsupportedFactorException]
 * rather than silently dropping a constraint — a reference solver that quietly ignores
 * constraints would make parity meaningless.
 */
class UnsupportedFactorException(val factor: Factor) :
    RuntimeException("klause-choco: unsupported factor ${factor::class.simpleName}")

class ChocoModel private constructor(
    val problem: Problem,
    val model: Model,
    val boolVars: Array<BoolVar>,
    val intVars: Array<IntVar>,
) {
    /** Resolve a klause literal to its Choco view (the bool var, or its negation). */
    private fun litVar(lit: Int): BoolVar {
        val v = boolVars[Lit.variable(lit)]
        return if (Lit.isPositive(lit)) v else v.not()
    }

    private fun litVars(lits: IntArray): Array<IntVar> = Array(lits.size) { litVar(lits[it]) }

    private fun postFactor(f: Factor) {
        when (f) {
            is Clause -> model.or(*Array(f.literals.size) { litVar(f.literals[it]) }).post()
            is Cardinality -> postCount(litVars(f.literals), f.min, f.max)
            is Linear -> model.scalar(intVarsOf(f.vars), f.coeffs, opStr(f.op), f.bound).post()
            is PseudoBoolean -> model.scalar(litVars(f.literals), f.weights, pbStr(f.op), f.bound).post()
            is Xor -> postParity(litVars(f.literals), f.targetParity)
            is AllDifferent -> model.allDifferent(*intVarsOf(f.vars)).post()
            is Product -> model.times(intVars[f.a], intVars[f.b], intVars[f.result]).post()
            is ReifiedLinear ->
                model.scalar(intVarsOf(f.vars), f.coeffs, opStr(f.op), f.bound).reifyWith(boolVars[f.auxBoolVar])
            is ReifiedPseudoBoolean ->
                model.scalar(litVars(f.literals), f.weights, pbStr(f.op), f.bound).reifyWith(boolVars[f.auxBoolVar])
            is ReifiedCardinality ->
                countConstraint(litVars(f.literals), f.min, f.max).reifyWith(boolVars[f.auxBoolVar])
            else -> throw UnsupportedFactorException(f)
        }
    }

    private fun intVarsOf(ids: IntArray): Array<IntVar> = Array(ids.size) { intVars[ids[it]] }

    private fun postCount(vars: Array<IntVar>, min: Int, max: Int) {
        if (min > 0) model.sum(vars, ">=", min).post()
        if (max < vars.size) model.sum(vars, "<=", max).post()
    }

    /** A single reifiable constraint capturing `min <= sum(vars) <= max`, via a sum var. */
    private fun countConstraint(vars: Array<IntVar>, min: Int, max: Int) =
        model.intVar(0, vars.size).let { s ->
            model.sum(vars, "=", s).post()
            model.member(s, min, max)
        }

    private fun postParity(vars: Array<IntVar>, targetParity: Int) {
        val s = model.intVar(0, vars.size)
        model.sum(vars, "=", s).post()
        val allowed = (0..vars.size).filter { it % 2 == (targetParity and 1) }.toIntArray()
        model.member(s, allowed).post()
    }

    companion object {
        fun build(problem: Problem): ChocoModel {
            val model = Model("klause-choco")
            val boolVars = Array(problem.numBoolVars) { model.boolVar("b$it") }
            val intVars = Array(problem.numIntVars) { i ->
                val d = problem.intDomains[i]
                // Use explicit value enumeration when the domain has interior holes.
                if (d.size == d.max - d.min + 1) model.intVar("i$i", d.min, d.max)
                else {
                    val values = ArrayList<Int>(d.size)
                    d.forEach { values.add(it) }
                    model.intVar("i$i", values.toIntArray())
                }
            }
            val cm = ChocoModel(problem, model, boolVars, intVars)
            for (f in problem.factors) cm.postFactor(f)
            return cm
        }

        private fun opStr(op: LinearOp): String = when (op) {
            LinearOp.LE -> "<="
            LinearOp.EQ -> "="
            LinearOp.GE -> ">="
            LinearOp.NE -> "!="
        }

        private fun pbStr(op: PbOp): String = when (op) {
            PbOp.LE -> "<="
            PbOp.EQ -> "="
            PbOp.GE -> ">="
        }
    }
}
