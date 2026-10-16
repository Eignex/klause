package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.solver.pipeline.ExecutionOption
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.PortfolioPlan
import com.eignex.klause.solver.pipeline.PortfolioPlanRequest
import com.eignex.klause.solver.pipeline.VarSelectorKind
import com.eignex.klause.solver.pipeline.planPortfolio
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Repeated configuration parsing and recipe planning costs for frontend and direct library callers. */
object ExecutionOptionsProfile {
    @Volatile
    private var retained: Any? = null

    /** Measure planning from typed, parsed and raw `key=value` options that resolve to identical settings. */
    @JvmStatic
    fun main(args: Array<String>) {
        val settings = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(settings.keys.all { it in setOf("rounds", "plans", "out") })
        val rounds = settings["rounds"]?.toInt() ?: 7
        val plans = settings["plans"]?.toInt() ?: 10_000
        require(rounds in 1..100 && plans in 1..100_000)
        val out = File(settings["out"] ?: requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val pairs = listOf("seed=17", "arms=8", "var-selector=vsids", "luby=43", "slice-work=1000")
        val typed = ExecutionOptions(
            ExecutionOption.seed(17L), ExecutionOption.arms(8),
            ExecutionOption.variableSelector(VarSelectorKind.VSIDS), ExecutionOption.luby(43L),
            ExecutionOption.sliceWork(1_000L),
        )
        val parsed = ExecutionOptions.parse(pairs)
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        meter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val expected = describe(plan("typed", pairs, typed, parsed))
        val measurements = buildJsonArray {
            for (round in 0..rounds) {
                val modes = listOf("legacy", "parsed", "typed").let { if (round % 2 == 0) it else it.reversed() }
                for (mode in modes) {
                    val bytes = meter.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    repeat(plans) { retained = plan(mode, pairs, typed, parsed) }
                    val elapsed = System.nanoTime() - start
                    val allocated = meter.getThreadAllocatedBytes(thread) - bytes
                    check(describe(retained as PortfolioPlan.Execute) == expected)
                    if (round > 0) add(buildJsonObject {
                        put("round", round)
                        put("mode", mode)
                        put("plans", plans)
                        put("nanos", elapsed)
                        put("allocatedBytes", allocated)
                    })
                }
            }
        }
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("vmName", System.getProperty("java.vm.name"))
            put("vmArgs", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("sourceOptions", pairs.joinToString("\n"))
            put("resolvedSettings", expected)
            put("classpath", buildJsonObject {
                for (path in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(path)
                    if (file.isFile) put(file.name, MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        .joinToString("") { "%02x".format(Locale.ROOT, it) })
                }
            })
            put("measurements", measurements)
        }
        out.resolve("execution-options.json").writeText(report.toString() + "\n")
        println("EXECUTION_OPTIONS revision=${Reports.readGitSha()} rounds=$rounds plans=$plans")
    }

    private fun plan(
        mode: String,
        pairs: List<String>,
        typed: ExecutionOptions,
        parsed: ExecutionOptions,
    ): PortfolioPlan.Execute {
        val request = if (mode == "legacy") PortfolioPlanRequest(
            FiniteEngine.BACKTRACK, false, 4, pairs, null, 6, LpConfig.OFF,
            LpZeroObjectivePricing.MIN_BOUND_SUPPORT, null, null,
        ) else PortfolioPlanRequest(
            FiniteEngine.BACKTRACK, false, 4, if (mode == "typed") typed else parsed, null, 6, LpConfig.OFF,
            LpZeroObjectivePricing.MIN_BOUND_SUPPORT, null, null,
        )
        return FinitePipeline.planPortfolio(request) as PortfolioPlan.Execute
    }

    private fun describe(plan: PortfolioPlan.Execute): String {
        val scenario = plan.scenario
        val base = BacktrackParams(randomSeed = scenario.seed)
        val edited = scenario.btEdit?.invoke(base) ?: base
        return listOf(scenario.seed, scenario.cores, scenario.arms, scenario.engine, scenario.kind, scenario.sliceWork,
            scenario.maxSliceWork, scenario.sliceGrowth, scenario.lpCeiling.emphasis, scenario.zeroObjectivePricing,
            edited.randomSeed, edited.lubyRestartBase, edited.variableSelector::class.java.name,
            edited.valueSelector::class.java.name)
            .joinToString("|")
    }
}
