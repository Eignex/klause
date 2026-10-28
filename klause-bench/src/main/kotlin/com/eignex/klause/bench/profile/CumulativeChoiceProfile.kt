package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveEmphasis
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CumulativeChoiceProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.isEmpty() || options.keys == setOf("suite", "problem"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val text = if (options.isEmpty()) {
            control
        } else {
            val ref = Catalog.uncapped(options.getValue("suite")).problems
                .single { it.name == options.getValue("problem") }
            val model = CorpusFetcher.resolve(ref.source).readBytes()
            out.resolve("source.mzn").writeBytes(model)
            ref.data?.let { out.resolve("source.dzn").writeBytes(CorpusFetcher.resolve(it).readBytes()) }
            MiniZincRunner().compileFzn(ref).readText()
        }
        out.resolve("source.fzn").writeText(text)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val records = out.resolve("choices.jsonl")
        records.writeText("")
        for (emphasis in listOf<PresolveEmphasis?>(null, PresolveEmphasis.OFF,
            PresolveEmphasis.DEFAULT, PresolveEmphasis.AGGRESSIVE)) {
            val stop = Cancellation.after(20.seconds)
            val before = bean.getThreadAllocatedBytes(thread)
            val started = System.nanoTime()
            val parsed = parseFlatZinc(text)
            val budget = PresolveBudget(500_000_000L)
            val preparation = emphasis?.let {
                FinitePipeline.prepare(FinitePipelineRequest(
                    parsed.problem, FiniteEngine.BACKTRACK, parsed.linearObjective(),
                    presolveConfig = PresolveConfig(it),
                    explicitPresolveConfig = true, cancellation = stop, solveCancellation = stop,
                    presolveBudget = budget, randomSeed = 3L,
                ))
            }
            val problem = preparation?.problem ?: parsed.problem
            val session = PropagationSession(problem, stop)
            val observation = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("problem", options["problem"] ?: "contained-choice-control")
                put("fznSha256", sha256(text.encodeToByteArray()))
                put("preparation", emphasis?.id ?: "ordinary-propagation")
                put("cancelled", stop())
                put("rootUnsat", session.isUnsatAtRoot)
                put("sourceVerdictClaim", false)
                put("sourceWitnessChecked", false)
                put("javaVersion", System.getProperty("java.version"))
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("preparationWork", budget.spent())
                put("preparationWorkAllowance", budget.allowance)
                put("objectiveSupplied", parsed.linearObjective() != null)
                put("elapsedNs", System.nanoTime() - started)
                put("threadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
                put("integers", problem.numIntVars)
                put("booleans", problem.numBoolVars)
                put("factors", problem.factors.size)
                put("cumulatives", buildJsonArray {
                    for (factor in problem.factors.filterIsInstance<Cumulative>()) add(buildJsonObject {
                        put("tasks", factor.n)
                        put("unary", factor.unary)
                        put("constantDurations", factor.durationVars.isEmpty())
                        put("optional", factor.presents.isNotEmpty())
                        put("rangedDemands", factor.resourceVars.count {
                            session.intDomain(it).min != session.intDomain(it).max
                        })
                        put("channelRows", channelRows(problem, factor, session))
                    })
                })
                if (options.isEmpty() && preparation == null) {
                    put("q", session.intDomain(parsed.intVarsByName.getValue("q")).toString())
                    put("machine", session.intDomain(parsed.intVarsByName.getValue("machine")).toString())
                    val probe = PropagationSession(parsed.problem, stop)
                    put("selectMachine", probe.pinInt(parsed.intVarsByName.getValue("machine"), 2)::class.simpleName)
                }
            }
            records.appendText("$observation\n")
            println(observation)
        }
    }

    private fun channelRows(problem: Problem, factor: Cumulative, session: PropagationSession) = buildJsonArray {
        val rows = problem.factors.filterIsInstance<ReifiedLinear>().filter { it.op == LinearOp.EQ }
        for (demand in factor.resourceVars.distinct()) {
            for (channel in rows.filter { demand in it.vars }) add(buildJsonObject {
                put("demand", demand)
                put("demandDomain", session.intDomain(demand).toString())
                put("boolean", channel.auxBoolVar)
                put("equalities", buildJsonArray {
                    for (row in rows.filter { it.auxBoolVar == channel.auxBoolVar }) add(buildJsonObject {
                        put("bound", row.constants.exactBound.toString())
                        put("terms", buildJsonArray {
                            for (index in row.vars.indices) add(buildJsonObject {
                                val variable = row.vars[index]
                                put("variable", variable)
                                put("coefficient", row.constants.exactCoeff(index).toString())
                                put("domain", session.intDomain(variable).toString())
                            })
                        })
                    })
                })
            })
        }
    }

    private val control = """
        var 0..100: spectator;
        var 100..102: a;
        var 100..102: b;
        var 100..102: c;
        var 1..3: machine;
        var bool: selected;
        var 0..1: q;
        constraint int_eq_reif(machine, 2, selected);
        constraint bool2int(selected, q);
        constraint cumulative([spectator,a,b,c], [1,2,2,2], [1,1,1,q], 1);
        solve satisfy;
    """.trimIndent()
}
