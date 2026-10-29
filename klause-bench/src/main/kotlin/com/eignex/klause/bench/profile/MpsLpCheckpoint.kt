package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsLpCheckpoint {
    private const val ENGINE = "com.eignex.klause.lp.engine."
    private const val TOTAL_WORK = 150_000_000L

    fun measure(problem: Problem, objective: LinearObjective, out: File) {
        val stop = Cancellation.after(30.seconds)
        val prepared = MpsLpAdmission.rootRelaxation(problem, objective, false, stop)
        val sourceModel = requireNotNull(call(prepared, "getModel"))
        val authority = Class.forName(ENGINE + "LegacyLpModelKt").methods.single {
            it.name == "authoritativeModel" && it.parameterCount == 1
        }.invoke(null, sourceModel)
        val trail = Class.forName(ENGINE + "LpBoundTrail").constructors.single {
            it.parameterCount == 1 && it.parameterTypes.single().isInstance(authority)
        }.newInstance(authority)
        val state = requireNotNull(call(trail, "getState"))
        val meter = Class.forName(ENGINE + "LpProjectionMeter").getConstructor().newInstance()
        val model = requireNotNull(call(state, "toWorkingModel", meter))
        out.resolve("checkpoint-shape.json").writeText(NumericalResidualProfile.shape(state).toString())
        out.resolve("checkpoints.jsonl").bufferedWriter().use { output ->
            for (round in 0..1) {
                val modes = listOf("uninterrupted", "stopped", "checkpointed")
                for (mode in if (round == 0) modes else modes.reversed()) {
                    run(model, mode, round, out) { report ->
                        output.appendLine(report.toString())
                        output.flush()
                        println(report)
                    }
                }
            }
        }
    }

    private fun run(model: Any, mode: String, round: Int, out: File, emit: (JsonObject) -> Unit) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val stop = Cancellation.after(10.seconds)
        val context = Class.forName(ENGINE + "LpSolveContext").getConstructor().newInstance()
        val pricing = Class.forName(ENGINE + "LpPricingOptions").constructors.single { it.parameterCount == 2 }
            .newInstance(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 3L)
        val type = Class.forName(ENGINE + "LpSolveSession")
        val owner = type.constructors.single { it.parameterCount == 3 }.newInstance(context, pricing, false)
        val solverField = type.getDeclaredField("solver").apply { isAccessible = true }
        val routeType = Class.forName("com.eignex.klause.solver.result.LpRoute")
        val sink = Class.forName("com.eignex.klause.solver.result.LpStatsSink").constructors.single {
            it.parameterCount == 1
        }.newInstance(routeType.enumConstants.single { it.toString() == "STANDALONE" })
        val observer = requireNotNull(call(sink, "certificationObserver",
            routeType.enumConstants.single { it.toString() == "STANDALONE" }))
        val claim = Class.forName(ENGINE + "LpClaim").enumConstants.single {
            it.toString() == "ATTAINED_OPTIMUM"
        }
        val defaultSolve = type.methods.single { it.name == "solve\$default" && it.parameterCount == 12 }
        val cursor = if (mode == "checkpointed") {
            requireNotNull(call(owner, "openSolve", model, stop, TOTAL_WORK, observer, null, 0.0, claim))
        } else null
        var floatingWork = 0L
        var preparationWork = 0L
        var pivots = 0L
        var refactors = 0L
        var previousWork = 0L
        var previousPreparation = 0L
        var previousPivots = 0L
        var previousRefactors = 0L
        var iteration = 0
        try {
            while (!stop() && (floatingWork < TOTAL_WORK || cursor != null)) {
                val quantum = if (mode == "uninterrupted") TOTAL_WORK else {
                    maxOf(1L, minOf(1_000_000L shl minOf(iteration, 6), 50_000_000L, TOTAL_WORK - floatingWork))
                }
                val started = System.nanoTime()
                val allocated = bean.getThreadAllocatedBytes(thread)
                val step = cursor?.let { call(it, "advance", quantum, { false }) }
                val completed = cursor == null || step?.javaClass?.simpleName == "Completed"
                val result = if (cursor == null) {
                    defaultSolve.invoke(null, owner, model, stop, quantum, observer, null, null, 0.0,
                        null, claim, 240, null)
                } else if (completed) call(requireNotNull(step), "getValue") else null
                val elapsed = System.nanoTime() - started
                val bytes = bean.getThreadAllocatedBytes(thread) - allocated
                val solver = requireNotNull(solverField.get(owner))
                val metrics = requireNotNull(call(solver, "getLastMetrics"))
                val work = call(metrics, "getWorkOps") as Long
                val preparation = call(metrics, "getPreparationOps") as Long
                val count = (call(metrics, "getPivots") as Int).toLong()
                val builds = (call(solver, "getLastRefactorizations") as Int).toLong()
                val deltaWork = work - previousWork
                val deltaPreparation = preparation - previousPreparation
                val deltaPivots = count - previousPivots
                val deltaRefactors = builds - previousRefactors
                check(listOf(deltaWork, deltaPreparation, deltaPivots, deltaRefactors).all { it >= 0L })
                floatingWork += deltaWork
                preparationWork += deltaPreparation
                pivots += deltaPivots
                refactors += deltaRefactors
                if (cursor != null) {
                    previousWork = work
                    previousPreparation = preparation
                    previousPivots = count
                    previousRefactors = builds
                }
                val headings = field(solver, "basicVar") as IntArray
                val headingBytes = headings.joinToString(",").toByteArray()
                val filename = "headings-$mode-$round-$iteration.json"
                out.resolve(filename).writeText(JsonArray(headings.map(::JsonPrimitive)).toString())
                emit(buildJsonObject {
                    put("scope", "immutable-full-prepared-root-continuation")
                    put("sourceVerdictClaim", false)
                    put("round", round)
                    put("warmup", round == 0)
                    put("mode", mode)
                    put("iteration", iteration)
                    put("offeredWork", quantum)
                    put("cumulativeWorkLimit", TOTAL_WORK)
                    put("floatingWorkDelta", deltaWork)
                    put("floatingWork", floatingWork)
                    put("preparationWork", preparationWork)
                    put("observerNumericalWork", call(sink, "getStandaloneWork") as Long)
                    put("pivotsDelta", deltaPivots)
                    put("pivots", pivots)
                    put("refactorizations", refactors)
                    put("completed", completed)
                    put("cancelled", stop())
                    put("solveNs", elapsed)
                    put("solveThreadBytes", bytes)
                    put("headings", filename)
                    put("headingsSha256", sha256(headingBytes))
                    put("artificialBounds", field(solver, "artificialLower") != null)
                    put("phaseContinuation", field(solver, "activeSolve")?.let {
                        field(it, "continuation")?.toString()
                    })
                    put("numericalMetrics", NumericalResidualProfile.json(call(solver, "getLastNumericalMetrics")))
                    put("floatTermination", call(solver, "getLastTermination")?.toString())
                    put("verdict", result?.let { call(it, "getVerdict").toString() })
                    put("checkedNativeBound", result?.let { call(it, "getLowerBound")?.toString() })
                    put("exactPoint", result?.let {
                        NumericalResidualProfile.json(call(it, "getExactPrimal"))
                    } ?: JsonNull)
                    put("observerCounters", NumericalResidualProfile.json(call(sink, "snapshot")))
                })
                iteration++
                if (completed && (cursor != null || mode == "uninterrupted" || result?.let {
                        call(it, "getVerdict").toString() in setOf("ATTAINED_OPTIMUM", "INFEASIBLE", "UNBOUNDED")
                    } == true)) break
                check(iteration <= 256) { "root continuation made no bounded progress" }
            }
        } finally {
            try {
                (cursor as? AutoCloseable)?.close()
            } finally {
                (owner as AutoCloseable).close()
            }
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg arguments: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *arguments)
}
