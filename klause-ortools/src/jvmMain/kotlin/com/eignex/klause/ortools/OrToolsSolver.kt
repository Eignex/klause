package com.eignex.klause.ortools

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverSolutionCallback
import com.google.ortools.sat.CpSolverStatus
import com.google.ortools.sat.LinearArgument
import com.google.ortools.sat.LinearExpr

/**
 * OR-Tools CP-SAT adapter — klause's **anytime / local-search reference**, mirroring the
 * side-door shape of `klause-logicng` / `klause-choco`. Maps a klause [Problem] into a
 * CP-SAT model (see [OrToolsModel]) and solves it in-process. CP-SAT reports incumbents over
 * time, which [improvements] surfaces for the bench's anytime comparison against klause-LS.
 *
 * The OR-Tools native libraries are loaded once on first use.
 */
class OrToolsSolver(override val problem: Problem) : Optimizer<OrToolsParams> {

    override fun solve(params: OrToolsParams): SolveResult {
        val m = OrToolsModel.build(problem)
        val solver = newSolver(params)
        return when (solver.solve(m.model)) {
            CpSolverStatus.OPTIMAL, CpSolverStatus.FEASIBLE -> SolveResult.Sat(readSample(m, solver))
            CpSolverStatus.INFEASIBLE -> SolveResult.Unsat()
            else -> SolveResult.Unknown(TerminationReason.Timeout)
        }
    }

    override fun samples(params: OrToolsParams): Sequence<Sample> = enumerate(params)

    override fun enumerate(params: OrToolsParams): Sequence<Sample> {
        // CP-SAT enumerates via a callback; collect eagerly under the model cap (reference use
        // doesn't need laziness, and the native search runs to completion regardless).
        val m = OrToolsModel.build(problem)
        val solver = newSolver(params)
        solver.parameters.enumerateAllSolutions = true
        val out = ArrayList<Sample>()
        val cb = object : CpSolverSolutionCallback() {
            override fun onSolutionCallback() {
                if (out.size >= params.maxModels) {
                    stopSearch()
                    return
                }
                out.add(
                    Sample(
                        BooleanArray(problem.numBoolVars) { value(m.boolVars[it]) == 1L },
                        IntArray(problem.numIntVars) { value(m.intVars[it]).toInt() },
                    ),
                )
            }
        }
        solver.solve(m.model, cb)
        return out.asSequence()
    }

    override fun minimize(objective: LinearObjective, params: OrToolsParams): MinimizeResult =
        improvements(objective, params).last()

    override fun improvements(objective: LinearObjective, params: OrToolsParams): Sequence<MinimizeResult> {
        val m = OrToolsModel.build(problem)
        val solver = newSolver(params)
        val objExpr = buildObjective(m, objective)
        m.model.minimize(objExpr)

        val incumbents = ArrayList<MinimizeResult>()
        val cb = object : CpSolverSolutionCallback() {
            override fun onSolutionCallback() {
                val sample = Sample(
                    BooleanArray(problem.numBoolVars) { value(m.boolVars[it]) == 1L },
                    IntArray(problem.numIntVars) { value(m.intVars[it]).toInt() },
                )
                incumbents.add(
                    MinimizeResult.BestFound(
                        sample,
                        objectiveValue() + objective.constant,
                        TerminationReason.BudgetExhausted,
                    ),
                )
            }
        }
        val status = solver.solve(m.model, cb)
        val terminal: MinimizeResult = when (status) {
            CpSolverStatus.OPTIMAL -> {
                val last = incumbents.lastOrNull()
                if (last is MinimizeResult.BestFound) {
                    MinimizeResult.Optimal(last.sample, last.objective)
                } else {
                    MinimizeResult.Unknown(TerminationReason.SearchExhausted)
                }
            }

            CpSolverStatus.INFEASIBLE -> MinimizeResult.Infeasible()

            CpSolverStatus.FEASIBLE -> incumbents.lastOrNull() ?: MinimizeResult.Unknown(TerminationReason.Timeout)

            else -> MinimizeResult.Unknown(TerminationReason.Timeout)
        }
        return (incumbents + terminal).asSequence()
    }

    private fun newSolver(params: OrToolsParams): CpSolver {
        OrToolsModel.ensureNativeLoaded()
        val solver = CpSolver()
        params.timeoutMillis?.let { solver.parameters.maxTimeInSeconds = it / 1000.0 }
        if (params.workers > 0) solver.parameters.numSearchWorkers = params.workers
        return solver
    }

    private fun buildObjective(m: OrToolsModel, obj: LinearObjective): LinearExpr {
        val args = ArrayList<LinearArgument>()
        val coeffs = ArrayList<Long>()
        for (b in 0 until problem.numBoolVars) {
            val w = obj.boolWeights.getOrElse(b) { 0L }
            if (w != 0L) {
                args.add(m.boolVars[b])
                coeffs.add(w)
            }
        }
        for (i in 0 until problem.numIntVars) {
            val c = obj.intCoefficients.getOrElse(i) { 0L }
            if (c != 0L) {
                args.add(m.intVars[i])
                coeffs.add(c)
            }
        }
        return if (args.isEmpty()) {
            LinearExpr.constant(0)
        } else {
            LinearExpr.weightedSum(args.toTypedArray(), coeffs.toLongArray())
        }
    }

    private fun readSample(m: OrToolsModel, solver: CpSolver): Sample = Sample(
        bools = BooleanArray(problem.numBoolVars) { solver.value(m.boolVars[it]) == 1L },
        ints = IntArray(problem.numIntVars) { solver.value(m.intVars[it]).toInt() },
    )
}
