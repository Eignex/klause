package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpNodeOverhead
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpEmissionTemplates
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.checkLpAssembly
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import kotlin.time.TimeSource

// The pump's root source and selected built-in mechanisms stay fixed; pinning uses separate sessions.
internal class LpPumpPreparation private constructor(
    private val engine: LpEngine,
    private var current: LpRelaxation,
) {
    fun rebind(objective: LinearObjective, cancellation: Cancellation): LpRelaxation? {
        engine.requireOpen()
        val accounting = LpPrimalAssembly(engine, cancellation)
        val token = accounting.token
        val started = TimeSource.Monotonic.markNow()
        var outcome = "FAILED"
        return try {
            token.charge(1L)
            token.checkLpAssembly()
            val rebound = bind(objective, token)
            token.checkLpAssembly()
            outcome = if (rebound == null) "DECLINED" else "REBOUND"
            if (rebound != null) current = rebound
            rebound
        } catch (cancelled: LpAssemblyCancelled) {
            outcome = "CANCELLED"
            throw cancelled
        } finally {
            engine.sink.lp.observePrimalPhase("PRIMAL_LP_PUMP_PREPARATION", outcome,
                started.elapsedNow().inWholeNanoseconds, accounting.work)
        }
    }

    private fun bind(objective: LinearObjective, token: Cancellation): LpRelaxation? {
        val problem = engine.problem
        if (objective.realCoefficientValues.isNotEmpty() ||
            objective.intCoefficientValues.size > problem.numIntVars ||
            objective.boolWeightValues.size > problem.numBoolVars
        ) return null
        for (variable in objective.intCoefficientValues.indices) {
            token.charge(1L)
            token.checkLpAssembly()
            if (objective.intCoefficientValues[variable] != 0L && current.intColOf[variable] < 0) return null
        }
        for (variable in objective.boolWeightValues.indices) {
            token.charge(1L)
            token.checkLpAssembly()
            if (objective.boolWeightValues[variable] != 0L && current.boolColOf[variable] < 0) return null
        }
        val model = current.model
        token.charge(model.n * 3L + model.numVars)
        token.checkLpAssembly()
        val columns = IntArray(model.n) { it }
        val costs = LongArray(model.n) { column ->
            token.checkLpAssembly()
            val variable = current.colVarId[column]
            when {
                variable < 0 -> 0L
                current.colIsBool[column] -> if (current.boolColOf[variable] == column) {
                    objective.boolWeightValues.getOrElse(variable) { 0L }
                } else 0L
                current.intColOf[variable] == column -> objective.intCoefficientValues.getOrElse(variable) { 0L }
                else -> 0L
            }
        }
        val rebound = try {
            model.withRowObjective(columns, costs)
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        token.checkLpAssembly()
        return current.withModel(rebound, sources = null, reboundObjectiveConstant = objective.constant)
    }

    companion object {
        fun create(engine: LpEngine, root: LpRelaxation, cancellation: Cancellation): LpPumpPreparation? {
            engine.requireOpen()
            val accounting = LpPrimalAssembly(engine, cancellation)
            val token = accounting.token
            val started = TimeSource.Monotonic.markNow()
            var outcome = "FAILED"
            return try {
                token.charge(1L)
                token.checkLpAssembly()
                val model = root.model
                val bindingWork = 1L + engine.problem.numIntVars + engine.problem.numBoolVars +
                    model.n * 3L + model.numVars
                val construction = (model.n.toLong() + model.m + model.csc.rowIdx.size) * LpNodeOverhead.BUILD
                val eligible = engine.projections?.builtInRelaxation == true &&
                    engine.projections.nativePropagation && engine.problem.numRealVars == 0 &&
                    model.exactState == null && model.doubleView == null && model.n > 0 &&
                    bindingWork <= LpEmissionTemplates.MAX_UNITS && bindingWork < construction
                outcome = if (eligible) "ADMITTED" else "DECLINED"
                val prepared = if (eligible) LpPumpPreparation(engine, root) else null
                token.checkLpAssembly()
                prepared
            } catch (cancelled: LpAssemblyCancelled) {
                outcome = "CANCELLED"
                throw cancelled
            } finally {
                engine.sink.lp.observePrimalPhase("PRIMAL_LP_PUMP_PREPARATION", outcome,
                    started.elapsedNow().inWholeNanoseconds, accounting.work)
            }
        }
    }
}
