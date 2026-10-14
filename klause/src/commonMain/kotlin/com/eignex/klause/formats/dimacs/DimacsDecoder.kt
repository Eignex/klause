@file:Suppress("MatchingDeclarationName")

package com.eignex.klause.formats.dimacs

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings

/** A WCNF document lowered to hard clauses and a weighted soft-clause objective. */
data class WcnfProblem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Soft-clause objective. */
    val objective: LinearObjectiveSpec,
    /** Number of original, non-relaxation variables. */
    val numOriginalBoolVars: Int,
    /** Exact soft-clause violation definitions over original literals. */
    val boolFolds: List<BoolFoldDefinition> = emptyList(),
) {
    /** Construct a problem without soft-clause fold metadata. */
    constructor(problem: Problem, objective: LinearObjectiveSpec, numOriginalBoolVars: Int) :
        this(problem, objective, numOriginalBoolVars, emptyList())

    /** Copy legacy fields while retaining [boolFolds]. */
    fun copy(
        problem: Problem = this.problem,
        objective: LinearObjectiveSpec = this.objective,
        numOriginalBoolVars: Int = this.numOriginalBoolVars,
    ): WcnfProblem = WcnfProblem(problem, objective, numOriginalBoolVars, boolFolds)
}

/** Lower this parsed CNF document to a solver problem. */
fun CnfDocument.toProblem(settings: ProblemSettings = KlauseConfig.current.problemSettings()): Problem {
    val totalVars = numBoolVars + if (triviallyUnsat) 1 else 0
    val factors = ArrayList<Factor>(clauses.size + if (triviallyUnsat) 2 else 0)
    factors.addAll(clauses.map(::Clause))
    if (triviallyUnsat) {
        val marker = numBoolVars
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
    }
    return Problem(totalVars, 0, emptyArray(), factors.toTypedArray(), settings = settings)
}

/** Lower this parsed WCNF document to hard clauses and a weighted soft-clause objective. */
fun WcnfDocument.toProblem(settings: ProblemSettings = KlauseConfig.current.problemSettings()): WcnfProblem {
    val unitCosts = LongArray(numOriginalBoolVars)
    val direct = BooleanArray(softClauses.size)
    var constant = fixedCost
    val unitWeight = softClauses.firstOrNull()?.weight
    val uniformCosts = softClauses.all { it.weight == unitWeight }
    for ((index, soft) in softClauses.withIndex()) {
        if (!uniformCosts || soft.literals.size != 1) continue
        val literal = soft.literals[0]
        val variable = Lit.variable(literal)
        val weight = soft.weight
        if (Lit.isPositive(literal)) {
            if (constant > Long.MAX_VALUE - weight || unitCosts[variable] < Long.MIN_VALUE + weight) continue
            constant += weight
            unitCosts[variable] -= weight
        } else {
            if (unitCosts[variable] > Long.MAX_VALUE - weight) continue
            unitCosts[variable] += weight
        }
        direct[index] = true
    }
    val relaxedClauses = direct.count { !it }
    val totalVars = numOriginalBoolVars + relaxedClauses + if (triviallyUnsat) 1 else 0
    val factors = ArrayList<Factor>(hardClauses.size + relaxedClauses + if (triviallyUnsat) 2 else 0)
    factors.addAll(hardClauses.map(::Clause))
    val weights = unitCosts.copyOf(totalVars)
    val boolFolds = ArrayList<BoolFoldDefinition>(relaxedClauses)
    var relax = numOriginalBoolVars
    for ((index, soft) in softClauses.withIndex()) {
        if (direct[index]) continue
        factors.add(Clause(intArrayOf(Lit.make(relax, positive = true)) + soft.literals))
        for (literal in soft.literals) {
            factors.add(Clause(intArrayOf(Lit.make(relax, positive = false), Lit.negate(literal))))
        }
        boolFolds.add(BoolFoldDefinition(
            relax, IntArray(soft.literals.size) { Lit.negate(soft.literals[it]) }, isAnd = true,
        ))
        weights[relax++] = soft.weight
    }
    if (triviallyUnsat) {
        val marker = numOriginalBoolVars + relaxedClauses
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = true))))
        factors.add(Clause(intArrayOf(Lit.make(marker, positive = false))))
    }
    return WcnfProblem(
        Problem(totalVars, 0, emptyArray(), factors.toTypedArray(), settings = settings),
        LinearObjectiveSpec(boolWeights = weights, constant = constant),
        numOriginalBoolVars,
        boolFolds,
    )
}
