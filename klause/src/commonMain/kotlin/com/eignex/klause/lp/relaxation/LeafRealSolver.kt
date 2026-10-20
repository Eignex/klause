package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveSession
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.floatingOptimumAcceptance
import com.eignex.klause.theory.finiteLinearModel
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray

internal class LeafRealSolver(
    private val problem: Problem,
    private val objective: LinearObjective?,
    componentSplit: Boolean = true,
    private val sink: LpStatsSink? = null,
    context: LpSolveContext = LpSolveContext.Production,
    pricing: LpPricingOptions = LpPricingOptions(),
) : AutoCloseable {
    private val encoders = problem.selectedFactorProjections()
    private val assembly = LpLeafRelaxation(problem, CpToLpRelaxation(problem, objective, projections = null))
    private val solvers = LpSolveSession(context, pricing, componentSplit)
    private var integers = arrayOfNulls<IntDomain>(problem.numIntVars)
    private var closed = false

    fun solve(
        sample: Sample,
        cancellation: Cancellation = Cancellation.Never,
        toleranceCheck: ((Sample) -> Boolean)? = null,
        workLimit: Long = 0L,
    ): LeafRealResult {
        check(!closed) { "real leaf solver is closed" }
        require(sample.numIntVars == problem.numIntVars && sample.numBoolVars == problem.numBoolVars)
        if (cancellation()) return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val encoded = encoders.finiteLinearModel(sample, cancellation)
            ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val relaxation = try {
            relaxation(sample, cancellation)
        } catch (_: LpAssemblyCancelled) {
            null
        } ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val certified = solvers.solve(
            relaxation.model,
            cancellation = cancellation,
            workLimit = workLimit,
            observer = sink?.certificationObserver(LpRoute.STANDALONE),
            floatAccept = toleranceCheck.floatingOptimumAcceptance()?.let { check ->
                { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
            },
            floatOffset = relaxation.objectiveConstant.toDouble(),
        )
        return relaxation.leafResult(certified, problem, objective, sample, sink)
            .checkedEncoding(encoded, sample, cancellation)
    }

    private fun relaxation(sample: Sample, cancellation: Cancellation): LpRelaxation? {
        val pins = Array(problem.numIntVars) { variable ->
            val value = sample.intValue(variable)
            integers[variable]?.takeIf { it.min == value } ?: IntDomain(value, value)
        }
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = pins[varId]
            override fun boolValue(varId: Int): Boolean = sample.boolValue(varId)
        }
        val relaxation = assembly.build(domains, cancellation) ?: return null
        integers = Array(pins.size) { pins[it] }
        return relaxation
    }

    fun releaseSolvers() = solvers.releaseSolvers()

    override fun close() {
        if (closed) return
        closed = true
        solvers.close()
    }
}
