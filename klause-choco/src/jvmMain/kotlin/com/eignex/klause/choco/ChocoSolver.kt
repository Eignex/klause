package com.eignex.klause.choco

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import org.chocosolver.solver.Model
import org.chocosolver.solver.Solution
import org.chocosolver.solver.variables.IntVar

/**
 * Choco Solver adapter — a complete-search **reference** for klause, mirroring the side-door
 * shape of `klause-logicng` / `klause-smt`: it maps a klause [Problem] into a Choco model
 * (see [ChocoModel]) and solves it in-process. Used for differential parity (klause vs Choco)
 * and as a trusted oracle for SAT/UNSAT/optimum.
 *
 * Each call rebuilds the model — Choco models are single-use once searched. Unsupported
 * factors surface as [UnsupportedFactorException] so a missing translation is loud.
 */
class ChocoSolver(override val problem: Problem) : Optimizer<ChocoParams> {

    override fun solve(params: ChocoParams): SolveResult {
        val cm = ChocoModel.build(problem)
        applyLimits(cm.model, params)
        return if (cm.model.solver.solve()) {
            SolveResult.Sat(readSample(cm))
        } else if (cm.model.solver.isStopCriterionMet()) {
            SolveResult.Unknown(TerminationReason.Timeout)
        } else {
            SolveResult.Unsat()
        }
    }

    override fun samples(params: ChocoParams): Sequence<Sample> = enumerate(params)

    override fun enumerate(params: ChocoParams): Sequence<Sample> = sequence {
        val cm = ChocoModel.build(problem)
        applyLimits(cm.model, params)
        var yielded = 0L
        while (yielded < params.maxModels && cm.model.solver.solve()) {
            yield(readSample(cm))
            yielded++
        }
    }

    override fun minimize(objective: Objective, params: ChocoParams): MinimizeResult {
        require(objective is LinearObjective) {
            "klause-choco only optimizes LinearObjective (got ${objective::class.simpleName})"
        }
        val cm = ChocoModel.build(problem)
        applyLimits(cm.model, params)
        val objVar = buildObjectiveVar(cm, objective)
        cm.model.setObjective(Model.MINIMIZE, objVar)
        val best = Solution(cm.model)
        var found = false
        while (cm.model.solver.solve()) {
            best.record()
            found = true
        }
        if (!found) {
            return if (cm.model.solver.isStopCriterionMet()) {
                MinimizeResult.Unknown(TerminationReason.Timeout)
            } else {
                MinimizeResult.Infeasible()
            }
        }
        val sample = readSample(cm, best)
        val value = best.getIntVal(objVar).toDouble() + objective.constant
        return if (cm.model.solver.isStopCriterionMet()) {
            MinimizeResult.BestFound(sample, value, TerminationReason.Timeout)
        } else {
            MinimizeResult.Optimal(sample, value)
        }
    }

    private fun applyLimits(model: Model, params: ChocoParams) {
        params.timeoutMillis?.let { model.solver.limitTime(it) }
    }

    /** Build an IntVar equal to the linear objective (excluding the constant offset, which is
     *  added back when reporting; it doesn't affect the argmin). */
    private fun buildObjectiveVar(cm: ChocoModel, obj: LinearObjective): IntVar {
        val vars = ArrayList<IntVar>()
        val coeffs = ArrayList<Int>()
        for (b in 0 until problem.numBoolVars) {
            val w = obj.boolWeights.getOrElse(b) { 0.0 }
            if (w != 0.0) {
                vars.add(cm.boolVars[b])
                coeffs.add(w.toInt())
            }
        }
        for (i in 0 until problem.numIntVars) {
            val c = obj.intCoefficients.getOrElse(i) { 0.0 }
            if (c != 0.0) {
                vars.add(cm.intVars[i])
                coeffs.add(c.toInt())
            }
        }
        if (vars.isEmpty()) return cm.model.intVar(0)
        var lo = 0L
        var hi = 0L
        for (k in vars.indices) {
            val v = vars[k]
            val c = coeffs[k]
            val a = c.toLong() * v.lb
            val b = c.toLong() * v.ub
            lo += minOf(a, b)
            hi += maxOf(a, b)
        }
        val objVar = cm.model.intVar("obj", lo.toInt(), hi.toInt())
        cm.model.scalar(vars.toTypedArray(), coeffs.toIntArray(), "=", objVar).post()
        return objVar
    }

    private fun readSample(cm: ChocoModel): Sample = Sample(
        bools = BooleanArray(problem.numBoolVars) { cm.boolVars[it].value == 1 },
        ints = IntArray(problem.numIntVars) { cm.intVars[it].value },
    )

    private fun readSample(cm: ChocoModel, solution: Solution): Sample = Sample(
        bools = BooleanArray(problem.numBoolVars) { solution.getIntVal(cm.boolVars[it]) == 1 },
        ints = IntArray(problem.numIntVars) { solution.getIntVal(cm.intVars[it]) },
    )
}
