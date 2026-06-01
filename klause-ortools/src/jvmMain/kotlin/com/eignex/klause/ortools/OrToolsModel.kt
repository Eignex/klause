package com.eignex.klause.ortools

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
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.google.ortools.Loader
import com.google.ortools.sat.BoolVar
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearArgument
import com.google.ortools.sat.LinearExpr
import com.google.ortools.sat.Literal
import com.google.ortools.util.Domain

/**
 * Translates a klause [Problem] into an OR-Tools CP-SAT [CpModel]. Covers the same core
 * factor set as the Choco adapter; unsupported factors raise [UnsupportedFactorException] so
 * a missing translation is loud rather than silently dropping a constraint.
 *
 * Reified linear / pseudo-Boolean factors are full-reified by enforcing the constraint under
 * `aux` and its operator-complement under `not(aux)` (CP-SAT half-reification via
 * `onlyEnforceIf`).
 */
class UnsupportedFactorException(val factor: Factor) :
    RuntimeException("klause-ortools: unsupported factor ${factor::class.simpleName}")

class OrToolsModel private constructor(
    val problem: Problem,
    val model: CpModel,
    val boolVars: Array<BoolVar>,
    val intVars: Array<IntVar>,
) {
    private fun lit(lit: Int): Literal =
        boolVars[Lit.variable(lit)].let { if (Lit.isPositive(lit)) it else it.not() }

    private fun litArgs(lits: IntArray): Array<LinearArgument> = Array(lits.size) { lit(lits[it]) as LinearArgument }

    private fun intArgs(ids: IntArray): Array<LinearArgument> = Array(ids.size) { intVars[ids[it]] as LinearArgument }

    private fun IntArray.longs(): LongArray = LongArray(size) { this[it].toLong() }

    private fun postFactor(f: Factor) {
        when (f) {
            is Clause -> model.addBoolOr(Array(f.literals.size) { lit(f.literals[it]) })
            is Cardinality -> {
                val sum = LinearExpr.sum(litArgs(f.literals))
                model.addLinearConstraint(sum, f.min.toLong(), f.max.toLong())
            }
            is Linear -> postLinearDomain(LinearExpr.weightedSum(intArgs(f.vars), f.coeffs.longs()), domainFor(f.op, f.bound))
            is PseudoBoolean -> postLinearDomain(LinearExpr.weightedSum(litArgs(f.literals), f.weights.longs()), domainFor(f.op, f.bound))
            is Xor -> {
                // Parity over (possibly negated) literals: count of true literals must have
                // parity == targetParity. Encode as sum-in-allowed-values.
                val sum = LinearExpr.sum(litArgs(f.literals))
                val allowed = (0..f.literals.size).filter { it % 2 == (f.targetParity and 1) }.map { it.toLong() }.toLongArray()
                model.addLinearExpressionInDomain(sum, Domain.fromValues(allowed))
            }
            is AllDifferent -> model.addAllDifferent(Array(f.vars.size) { intVars[f.vars[it]] })
            is Product -> model.addMultiplicationEquality(intVars[f.result], intVars[f.a], intVars[f.b])
            is ReifiedLinear -> reifyLinear(
                LinearExpr.weightedSum(intArgs(f.vars), f.coeffs.longs()), f.op, f.bound, boolVars[f.auxBoolVar])
            is ReifiedPseudoBoolean -> reifyPb(
                LinearExpr.weightedSum(litArgs(f.literals), f.weights.longs()), f.op, f.bound, boolVars[f.auxBoolVar])
            else -> throw UnsupportedFactorException(f)
        }
    }

    private fun postLinearDomain(expr: LinearExpr, domain: Domain) {
        model.addLinearExpressionInDomain(expr, domain)
    }

    private fun reifyLinear(expr: LinearExpr, op: LinearOp, bound: Int, aux: BoolVar) {
        val d = domainFor(op, bound)
        model.addLinearExpressionInDomain(expr, d).onlyEnforceIf(aux)
        model.addLinearExpressionInDomain(expr, d.complement()).onlyEnforceIf(aux.not())
    }

    private fun reifyPb(expr: LinearExpr, op: PbOp, bound: Int, aux: BoolVar) {
        val d = domainFor(op, bound)
        model.addLinearExpressionInDomain(expr, d).onlyEnforceIf(aux)
        model.addLinearExpressionInDomain(expr, d.complement()).onlyEnforceIf(aux.not())
    }

    companion object {
        private const val NEG = -1_000_000_000L
        private const val POS = 1_000_000_000L

        @Volatile private var loaded = false
        /** Load the OR-Tools JNI libraries once, before any native object is constructed. */
        fun ensureNativeLoaded() {
            if (!loaded) synchronized(this) { if (!loaded) { Loader.loadNativeLibraries(); loaded = true } }
        }

        fun build(problem: Problem): OrToolsModel {
            ensureNativeLoaded()
            val model = CpModel()
            val boolVars = Array(problem.numBoolVars) { model.newBoolVar("b$it") }
            val intVars = Array(problem.numIntVars) { i ->
                val d = problem.intDomains[i]
                if (d.size == d.max - d.min + 1) model.newIntVar(d.min.toLong(), d.max.toLong(), "i$i")
                else {
                    val values = ArrayList<Long>(d.size)
                    d.forEach { values.add(it.toLong()) }
                    model.newIntVarFromDomain(Domain.fromValues(values.toLongArray()), "i$i")
                }
            }
            val m = OrToolsModel(problem, model, boolVars, intVars)
            for (f in problem.factors) m.postFactor(f)
            return m
        }

        private fun domainFor(op: LinearOp, bound: Int): Domain = when (op) {
            LinearOp.LE -> Domain(NEG, bound.toLong())
            LinearOp.GE -> Domain(bound.toLong(), POS)
            LinearOp.EQ -> Domain(bound.toLong(), bound.toLong())
            LinearOp.NE -> Domain(bound.toLong(), bound.toLong()).complement()
        }

        private fun domainFor(op: PbOp, bound: Int): Domain = when (op) {
            PbOp.LE -> Domain(NEG, bound.toLong())
            PbOp.GE -> Domain(bound.toLong(), POS)
            PbOp.EQ -> Domain(bound.toLong(), bound.toLong())
        }
    }
}
