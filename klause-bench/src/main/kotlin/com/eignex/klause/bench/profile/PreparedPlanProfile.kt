package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PreparedPlanProfile {
    @Volatile
    private var retained: PreparedSourcePlan? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("iterations", "repeats", "warmups") })
        val iterations = options["iterations"]?.toInt() ?: 1000
        val repeats = options["repeats"]?.toInt() ?: 7
        val warmups = options["warmups"]?.toInt() ?: 3
        require(iterations in 1..100000 && repeats in 1..100 && warmups in 0..20)
        val output = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val allocated = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocated.isThreadAllocatedMemorySupported)
        allocated.isThreadAllocatedMemoryEnabled = true
        val objective = LinearObjective(constant = 7)
        val finite = Problem(0, 1, arrayOf(IntDomain(0, 8)), emptyArray())
        val polynomial = Problem(0, 1, arrayOf(IntDomain(0, 8)), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-2.0, 0.0), realUpper = doubleArrayOf(2.0, 4.0))
        val openText = "(declare-const x Real) (assert (> x 0)) (assert (< x 3))"
        val open = (SmtLibAdmission.parse(openText) as SmtLibInput.Linear).problem.model
        val nonlinearText = "(declare-const x Int) (assert (= (* x x) 4))"
        val nonlinear = (SmtLibAdmission.parse(nonlinearText) as SmtLibInput.NonlinearInteger).problem
        val nativeText = "(declare-const x Real) (assert (= (* x x) 2))"
        val native = (SmtLibAdmission.parse(nativeText) as SmtLibInput.Polynomial).problem
        val preparations: Map<String, () -> PreparedSourcePlan> = linkedMapOf(
            "finite" to { finite.prepareExecutionPlan(preferFinite = true) },
            "open" to { open.prepareExecutionPlan() },
            "polynomial" to { polynomial.prepareExecutionPlan() },
            "native-polynomial" to { native.prepareExecutionPlan() },
            "nonlinear-integer" to { nonlinear.prepareExecutionPlan() },
        )
        output.resolve("provenance.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("iterations", iterations)
            put("repeats", repeats)
            put("warmups", warmups)
            put("seed", "not applicable to immutable preparation")
            put("budget", "no solver invocation")
            put("sources", buildJsonObject {
                put("finite", "Problem(bool=0,int=[0..8],factors=[])")
                put("polynomial", "Problem(bool=0,int=[0..8],real=[-2..2,0..4],ContinuousProduct(0,0,1))")
                put("open", openText)
                put("native-polynomial", nativeText)
                put("nonlinear-integer", nonlinearText)
            })
            put("classpath", buildJsonArray {
                System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
                    .filter(File::isFile).forEach { file -> add(buildJsonObject {
                        put("name", file.name)
                        put("sha256", sha256(file.readBytes()))
                    }) }
            })
        }.toString())
        val facade = PreparedSourcePlan::class.java
        output.resolve("facade-descriptors.txt").writeText(
            (facade.declaredConstructors.map { it.toString() } + facade.declaredMethods.map { it.toString() })
                .sorted().joinToString("\n", postfix = "\n"),
        )
        val measurements = output.resolve("measurements.jsonl")
        for (round in -warmups until repeats) {
            for ((name, prepare) in preparations.entries.let { if (round % 2 == 0) it.toList() else it.reversed() }) {
                val prepared = prepare()
                val phases: Map<String, () -> PreparedSourcePlan> = linkedMapOf(
                    "prepare" to prepare,
                    "bind" to { prepared.withObjective(objective, maximize = true) },
                )
                for ((phase, action) in phases) {
                    val beforeBytes = allocated.getThreadAllocatedBytes(Thread.currentThread().threadId())
                    val started = System.nanoTime()
                    repeat(iterations) { retained = action() }
                    val nanos = System.nanoTime() - started
                    val bytes = allocated.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeBytes
                    val result = requireNotNull(retained)
                    measurements.appendText(buildJsonObject {
                        put("source", name)
                        put("phase", phase)
                        put("round", round)
                        put("iterations", iterations)
                        put("nanos", nanos)
                        put("allocatedBytes", bytes)
                        put("kind", result.kind.name)
                        put("decline", result.decline?.name)
                        put("satisfy", result.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
                        put("optimize", result.capabilities.admission(ExecutionStrategy.COMPLETE,
                            ExecutionOperation.OPTIMIZE).runnable)
                        put("coordinates", buildJsonArray {
                            repeat(result.sourceIntCount) { add(JsonPrimitive(result.integerCoordinate(it))) }
                            repeat(result.sourceRealCount) { add(JsonPrimitive(result.realCoordinate(it))) }
                        })
                    }.toString() + "\n")
                }
            }
            if (round >= 0) println("completed preparation round $round")
        }
        println("default finite route: ${finite.pipelineRoute()::class.simpleName}")
        println("exact open route: ${open.pipelineRoute(routePureRealToTheory = true)::class.simpleName}")
    }
}
