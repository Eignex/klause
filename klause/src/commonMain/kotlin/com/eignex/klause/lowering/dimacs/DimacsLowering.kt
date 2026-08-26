package com.eignex.klause.lowering.dimacs

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective

/** A WCNF document lowered to hard clauses and a weighted soft-clause objective. */
data class WcnfProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Soft-clause objective. */
    val objective: LinearObjective,
    /** Number of original, non-relaxation variables. */
    val numOriginalBoolVars: Int,
)

/** Lower this parsed CNF document to a solver problem. */
fun CnfDocument.toProblem(): Problem {
    val totalVars = numBoolVars + if (triviallyUnsat) 1 else 0
    val factors = ArrayList<Factor>(clauses.size + if (triviallyUnsat) 2 else 0)
    factors.addAll(clauses.map(::Clause))
    if (triviallyUnsat) {
        val marker = numBoolVars
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
    }
    return Problem(totalVars, 0, emptyArray(), factors.toTypedArray())
}

/** Lower this parsed WCNF document to hard clauses and a weighted soft-clause objective. */
fun WcnfDocument.toProblem(): WcnfProblem {
    val totalVars = numOriginalBoolVars + softClauses.size + if (triviallyUnsat) 1 else 0
    val factors = ArrayList<Factor>(hardClauses.size + softClauses.size + if (triviallyUnsat) 2 else 0)
    factors.addAll(hardClauses.map(::Clause))
    val weights = LongArray(totalVars)
    for ((index, soft) in softClauses.withIndex()) {
        val relax = numOriginalBoolVars + index
        factors.add(Clause(intArrayOf(Lit.make(relax, positive = true)) + soft.literals))
        weights[relax] = soft.weight
    }
    if (triviallyUnsat) {
        val marker = numOriginalBoolVars + softClauses.size
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
    }
    return WcnfProblem(
        Problem(totalVars, 0, emptyArray(), factors.toTypedArray()),
        LinearObjective(boolWeights = weights, constant = fixedCost),
        numOriginalBoolVars,
    )
}
