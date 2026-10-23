package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpCertifierStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsLpQuery {
    fun measure(problem: Problem, objective: LinearObjective, out: File) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        out.resolve("queries.jsonl").bufferedWriter().use { output ->
            for (mode in listOf("full-cold", "full-retained", "cone-cold")) {
                queryMode(problem, objective, mode, bean) { report ->
                    output.appendLine(report.toString())
                    output.flush()
                    println(report)
                }
            }
        }
    }

    private fun queryMode(
        problem: Problem,
        objective: LinearObjective,
        mode: String,
        bean: ThreadMXBean,
        emit: (JsonObject) -> Unit,
    ) {
        val plan = LpPlan(bounding = true, boundMaxDepth = 0, probe = false, cuts = false,
            objectiveCone = mode == "cone-cold", rootBudgetFraction = 0.0)
        val containing = Cancellation.after(30.seconds)
        var callStop: Cancellation = containing
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = callStop()
            override fun deadline() = callStop.deadline()
            override fun workMeter() = callStop.workMeter()
        }
        var owner: Pair<AutoCloseable, Any>? = null
        val thread = Thread.currentThread().threadId()
        try {
            repeat(3) { repeat ->
                callStop = containing or Cancellation.after(10.seconds)
                val startNs = System.nanoTime()
                val startBytes = bean.getThreadAllocatedBytes(thread)
                val reused = owner != null
                val active = owner ?: newOwner(problem, objective, plan, token).also { owner = it }
                val setupNs = System.nanoTime() - startNs
                val setupBytes = bean.getThreadAllocatedBytes(thread) - startBytes
                val before = stats(active.second)
                val boundNs = System.nanoTime()
                val boundBytes = bean.getThreadAllocatedBytes(thread)
                val lower = checkedRootBound(active.first, token)
                val boundElapsed = System.nanoTime() - boundNs
                val boundAllocated = bean.getThreadAllocatedBytes(thread) - boundBytes
                val afterBound = stats(active.second)
                val pruneNs = System.nanoTime()
                val pruneBytes = bean.getThreadAllocatedBytes(thread)
                val session = PropagationSession(problem, token)
                val prune = active.first.javaClass.methods.single { it.name == "pruneNode" }.invoke(
                    active.first, session, Double.POSITIVE_INFINITY, -1, true,
                ) as Boolean
                val pruneElapsed = System.nanoTime() - pruneNs
                val pruneAllocated = bean.getThreadAllocatedBytes(thread) - pruneBytes
                val afterPrune = stats(active.second)
                val closeNs = System.nanoTime()
                val closeBytes = bean.getThreadAllocatedBytes(thread)
                if (mode != "full-retained" || repeat == 2) {
                    active.first.close()
                    owner = null
                }
                val closeElapsed = System.nanoTime() - closeNs
                val closeAllocated = bean.getThreadAllocatedBytes(thread) - closeBytes
                emit(buildJsonObject {
                    put("scope", "native-prepared-root-queries")
                    put("sourceVerdictClaim", false)
                    put("threadAllocationOnly", true)
                    put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory())
                    put("mode", mode)
                    put("repeat", repeat)
                    put("ownerReused", reused)
                    put("freshRootSession", true)
                    put("plan", plan.toString())
                    put("containingSeconds", 30)
                    put("querySeconds", 10)
                    put("cutoff", "positive-infinity")
                    if (lower.isFinite()) put("checkedNativeLowerBound", lower) else {
                        put("checkedNativeLowerBound", JsonNull)
                    }
                    put("certifiedNodePrune", prune)
                    put("cancelled", token())
                    put("setupNs", setupNs)
                    put("setupThreadBytes", setupBytes)
                    put("checkedRootBoundNs", boundElapsed)
                    put("checkedRootBoundThreadBytes", boundAllocated)
                    put("nodeSessionAndPruneNs", pruneElapsed)
                    put("nodeSessionAndPruneThreadBytes", pruneAllocated)
                    put("closeNs", closeElapsed)
                    put("closeThreadBytes", closeAllocated)
                    put("before", before)
                    put("afterCheckedRootBound", afterBound)
                    put("afterNodePrune", afterPrune)
                })
            }
        } finally {
            owner?.first?.close()
        }
    }

    @Suppress("SpreadOperator") // Java reflection requires varargs at the constructor boundary.
    private fun newOwner(
        problem: Problem,
        objective: LinearObjective,
        plan: LpPlan,
        token: Cancellation,
    ): Pair<AutoCloseable, Any> {
        val routeType = Class.forName("com.eignex.klause.solver.result.LpRoute")
        val route = routeType.enumConstants.single { it.toString() == "NODE" }
        val sinkType = Class.forName("com.eignex.klause.solver.result.SolveStatsSink")
        val sink = sinkType.getConstructor(String::class.java, routeType).newInstance("mps-lp-query", route)
        val type = Class.forName("com.eignex.klause.lp.bounding.LpEngine")
        val constructor = type.constructors.single { it.parameterTypes.last().simpleName == "DefaultConstructorMarker" }
        check(constructor.parameterCount == 11)
        val values = arrayOfNulls<Any>(11)
        values[0] = problem
        values[1] = objective
        values[2] = LpParams(plan, cancellation = token, solveBudgetMillis = 30_000, randomSeed = 3)
        values[3] = sink
        values[5] = false
        values[9] = 15.inv()
        return (constructor.newInstance(*values) as AutoCloseable) to sink
    }

    private fun checkedRootBound(owner: AutoCloseable, token: Cancellation): Double {
        val relaxer = owner.javaClass.methods.single { it.name.startsWith("getLpRelaxer") }
            .invoke(owner)
        val type = Class.forName("com.eignex.klause.lp.bounding.LpBoundingKt")
        val method = type.methods.single { it.name == "rootLpRelaxationBound" && it.parameterCount == 4 }
        return method.invoke(null, owner, requireNotNull(relaxer), emptyList<Any>(), token) as Double
    }

    private fun stats(sink: Any): JsonObject {
        val lp = (sink.javaClass.getMethod("snapshot").invoke(sink) as SolveStats).lp
        return buildJsonObject {
            put("nodePasses", lp.nodePasses.sum)
            put("nodeSolves", lp.solves.sum)
            put("nodePruned", lp.pruned.sum)
            put("nodeInfeasible", lp.infeasible.sum)
            put("nodeFixed", lp.fixed.sum)
            put("nodePivots", lp.pivots.sum)
            put("nodeWork", lp.workOps.sum)
            put("nodeOverheadWork", lp.overheadOps.sum)
            put("rootPasses", lp.rootPasses.sum)
            put("rootPivots", lp.rootPivots.sum)
            put("rootWork", lp.rootWorkOps.sum)
            put("warmAttempts", lp.warmStartAttempts.sum)
            put("warmHits", lp.warmStartHits.sum)
            put("refactorizations", lp.refactorizations.sum)
            put("workAllowanceSpent", lp.workAllowanceSpent)
            put("demoted", lp.demoted)
            put("certifiers", buildJsonObject {
                for ((name, value) in listOf(
                    "integer" to lp.integerCertify,
                    "safeBound" to lp.safeObjectiveLowerBound,
                    "farkas" to lp.exactFarkasRay,
                    "rational" to lp.rationalOutcome,
                )) put(name, certifier(value))
            })
        }
    }

    private fun certifier(stats: LpCertifierStats): JsonObject = buildJsonObject {
        put("attempts", stats.attempts.sum)
        put("successes", stats.successes.sum)
        put("declines", stats.declines.sum)
        put("nodeAttempts", stats.node.attempts.sum)
        put("nodeSuccesses", stats.node.successes.sum)
        put("rootAttempts", stats.root.attempts.sum)
        put("rootSuccesses", stats.root.successes.sum)
    }
}
