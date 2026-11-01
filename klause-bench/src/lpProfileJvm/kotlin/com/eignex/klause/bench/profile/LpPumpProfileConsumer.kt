package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.lp.LpPrimalAssembly
import com.eignex.klause.backtrack.lp.LpPumpPreparation
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
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

internal class LpPumpProfileConsumer(private val mode: String, private val templateStats: LpStatsSink) :
    AutoCloseable {
    private val sink = SolveStatsSink("pump-preparation-profile")
    private var engine: LpEngine? = null
    private var current: LpRelaxation? = null
    private var prepared: LpPumpPreparation? = null
    private var admissionAttempted = false
    private var rebound = false
    var contextsOpened = 0L
        private set
    var contextsRetired = 0L
        private set
    var freshBuilds = 0L
        private set
    var propagationWork = 0L
        private set
    var ownerPreparationWork = 0L
        private set
    var cancelledOwnerPreparationWork = 0L
        private set
    var maxCancelledOwnerPreparationWork = 0L
        private set
    var privateCompletedConsumers = 0L
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
            LpParams(lpPlan = LpPlan(bounding = true)),
            sink,
            projections = projections,
            containingWork = { ownerPreparationWork += it },
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
}
