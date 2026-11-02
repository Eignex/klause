package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.lp.LpPrimalAssembly
import com.eignex.klause.backtrack.lp.LpPumpPreparation
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.SessionDomains
import com.eignex.klause.lp.relaxation.buildPrimalSource
import com.eignex.klause.lp.relaxation.checkLpAssembly
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation

internal class LpPumpProfileConsumer(
    private val mode: String,
    private val templateStats: LpStatsSink,
    private val floatingPrimal: Boolean = false,
) : AutoCloseable {
    private val sink = SolveStatsSink("pump-preparation-profile")
    private var engine: LpEngine? = null
    private var current: LpRelaxation? = null
    private var prepared: LpPumpPreparation? = null
    private var admissionAttempted = false
    private var rebound = false
    private val floatingOutcomes = LinkedHashMap<String, Long>()
    var contextsOpened = 0L
        private set
    var contextsRetired = 0L
        private set
    var freshBuilds = 0L
        private set
    var propagationWork = 0L
        private set
    var ownerTotalWork = 0L
        private set
    var numericalWork = 0L
        private set
    var numericalPreparationOps = 0L
        private set
    val ownerPreparationWork: Long get() = ownerTotalWork - numericalWork
    var cancelledOwnerPreparationWork = 0L
        private set
    var maxCancelledOwnerPreparationWork = 0L
        private set
    var privateCompletedConsumers = 0L
        private set
    var numericalOwnersOpened = 0L
        private set
    var numericalOwnersRetired = 0L
        private set
    var floatingNumericalCalls = 0L
        private set
    var floatingPrimalCalls = 0L
        private set
    var floatingDualFallbackCalls = 0L
        private set
    var floatingOptimalIterates = 0L
        private set

    fun build(
        problem: Problem,
        objective: LinearObjective,
        projections: PreparedFactorProjections,
        cancellation: Cancellation,
        cold: Boolean,
        cancelWorkLimit: Long?,
    ): LpRelaxation {
        val before = ownerPreparationWork
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = cancellation() ||
                (cancelWorkLimit != null && ownerPreparationWork - before >= cancelWorkLimit)
            override fun deadline() = cancellation.deadline()
            override fun workMeter() = cancellation.workMeter()
        }
        return try {
            assemble(problem, objective, projections, token, cold)
        } catch (cancelled: LpAssemblyCancelled) {
            val work = ownerPreparationWork - before
            cancelledOwnerPreparationWork += work
            maxCancelledOwnerPreparationWork = maxOf(maxCancelledOwnerPreparationWork, work)
            throw cancelled
        }
    }

    private fun assemble(
        problem: Problem,
        objective: LinearObjective,
        projections: PreparedFactorProjections,
        cancellation: Cancellation,
        cold: Boolean,
    ): LpRelaxation {
        rebound = false
        if (cold || engine?.problem !== problem || engine?.projections !== projections) close()
        val owner = engine ?: LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true),
                solveBudgetMillis = if (floatingPrimal) FLOATING_SOLVE_BUDGET_MILLIS else null),
            sink,
            projections = projections,
            containingWork = { ownerTotalWork += it },
            reusePrimalTemplates = false,
            reusePumpPreparation = false,
        ).also {
            engine = it
            contextsOpened++
        }
        if (mode == "candidate") {
            val previous = current
            if (previous != null && !admissionAttempted) {
                admissionAttempted = true
                prepared = LpPumpPreparation.create(owner, previous, cancellation)
            }
            prepared?.rebind(objective, cancellation)?.let {
                current = it
                rebound = true
                return it
            }
        }
        val root = PropagationSession(problem, cancellation)
        try {
            if (root.fixpointCancelled || root.probeCancelled || cancellation()) throw LpAssemblyCancelled()
            check(!root.isUnsatAtRoot) { "profile source propagation is inconsistent" }
            val accounting = LpPrimalAssembly(owner, cancellation)
            accounting.token.checkLpAssembly()
            return buildPrimalSource(
                problem, objective, SessionDomains(root), accounting.token,
                onWork = accounting::chargeConstruction, selection = projections,
                reuseCompleteTemplates = false,
                reuseSourceTemplates = mode == "object" || mode == "index",
                reuseTemplateIndexes = mode == "index", templateStats = templateStats,
            ).also {
                current = it
                freshBuilds++
            }
        } finally {
            propagationWork += root.work
        }
    }

    fun completedConsumer() {
        if (rebound) privateCompletedConsumers++
    }

    fun consumePrimal(cancellation: Cancellation): LpFloatingProfileResult {
        check(floatingPrimal)
        val primal = consumeFloating(cancellation, primal = true)
        observeFloating("PRIMAL", primal)
        val result = if (primal.outcome == LpFloatingProfileResult.OPTIMAL) primal else {
            consumeFloating(cancellation, primal = false).also { observeFloating("DUAL_FALLBACK", it) }
        }
        if (result.outcome == LpFloatingProfileResult.OPTIMAL) completedConsumer()
        return result
    }

    private fun consumeFloating(cancellation: Cancellation, primal: Boolean): LpFloatingProfileResult {
        val owner = checkNotNull(engine)
        val relaxation = checkNotNull(current)
        owner.requireOpen()
        if (cancellation()) return LpFloatingProfileResult("CANCELLED", null)
        if (owner.lpWorkAllowanceSpent() || owner.workSpentExceeds(owner.params.lpPlan.rootMaxWork)) {
            return LpFloatingProfileResult("CURRENT_ALLOWANCE_SPENT", null)
        }
        val simplex = newTableauCutSolver(relaxation.model, cancellation,
            workLimit = owner.rootPrimalWorkBudget(),
            factory = owner.solveContext.engineFactory, pricing = owner.pricingOptions)
        numericalOwnersOpened++
        try {
            floatingNumericalCalls++
            val result = if (primal) {
                floatingPrimalCalls++
                simplex.solvePrimal()
            } else {
                floatingDualFallbackCalls++
                simplex.solve()
            }
            check(result == null || result.objective.isFinite() && result.primal.all { it.isFinite() })
            if (result?.optimal == true) floatingOptimalIterates++
            val outcome = if (result?.optimal == true) LpFloatingProfileResult.OPTIMAL else {
                "NO_OPTIMAL_FLOAT_ITERATE_" + (simplex.lastTermination?.name ?: "OTHER")
            }
            return LpFloatingProfileResult(outcome,
                result?.objective?.plus(relaxation.objectiveConstant.toDouble()))
        } finally {
            numericalPreparationOps += simplex.lastMetrics.preparationOps
            val before = owner.totalSolveWork()
            try {
                owner.observeRootSolve(simplex)
            } finally {
                numericalWork += owner.totalSolveWork() - before
                try {
                    simplex.close()
                } finally {
                    numericalOwnersRetired++
                }
            }
        }
    }

    private fun observeFloating(method: String, point: LpFloatingProfileResult) {
        val key = method + "_" + point.outcome
        floatingOutcomes[key] = (floatingOutcomes[key] ?: 0L) + 1L
    }

    fun floatingOutcomes(): Map<String, Long> = floatingOutcomes

    fun phases() = sink.snapshot().lp.phases

    override fun close() {
        val owner = engine
        engine = null
        current = null
        prepared = null
        admissionAttempted = false
        if (owner != null) {
            try {
                owner.close()
            } finally {
                contextsRetired++
            }
        }
    }

    companion object {
        const val FLOATING_SOLVE_BUDGET_MILLIS = 10_000L
    }
}
