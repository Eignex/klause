package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.authoritativeWithTemplates
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.time.Duration.Companion.seconds

internal object LpTemplatePreparationProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4)
        val file = if (args[0].startsWith("selection=")) {
            val line = File(args[0].removePrefix("selection=")).readLines().single { it.isNotBlank() }
            val selected = Json.parseToJsonElement(line).jsonObject
            require(selected.getValue("suite").jsonPrimitive.content == "miplib3")
            require(selected.getValue("format").jsonPrimitive.content == "MPS")
            require(selected.getValue("problem").jsonPrimitive.content in listOf("p0033", "p0201"))
            File(selected.getValue("model").jsonPrimitive.content).also {
                require(it.nameWithoutExtension == selected.getValue("problem").jsonPrimitive.content)
            }
        } else {
            File(args[0])
        }
        val bytes = file.readBytes()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        require(hash == args[1])
        val repeats = args[2].toInt().also { require(it in 1..5) }
        val steps = args[3].toInt().also { require(it in 2..64) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("source.mps").writeBytes(bytes)
        val parseStart = System.nanoTime()
        val compiled = Mps.parse(bytes.decodeToString()).toProblem()
        val parseNs = System.nanoTime() - parseStart
        val source = compiled.model
        require(source.numRealVars == 0 && source.numIntVars >= 2)
        val objective = requireNotNull(compiled.objective).toLinearObjective()
            .let { if (compiled.maximize) it.negated() else it }
        val stop = Cancellation.after(120.seconds)
        val cpu = ManagementFactory.getThreadMXBean().takeIf {
            it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled
        }
        val modes = listOf("disabled", "object", "index", "candidate")
        out.resolve("runs.jsonl").bufferedWriter().use { writer ->
            repeat(repeats + 1) { round ->
                for (change in listOf("cold", "unchanged", "bound", "objective", "row", "cancel")) {
                    val signatures = HashMap<String, List<String>>()
                    for (offset in modes.indices) {
                        val mode = modes[(round + offset) % modes.size]
                        val startBytes = profileAllocatedBytes()
                        val startCpu = cpu?.currentThreadCpuTime?.takeIf { it >= 0L }
                        val start = System.nanoTime()
                        val selected = FactorProjectionProviders.Default.prepare(source)
                        val changedSource = when (change) {
                            "bound" -> Problem(source.numBoolVars, source.numIntVars,
                                Array(source.numIntVars) { index ->
                                    val domain = source.finiteIntDomain(index)
                                    if (index == 0) IntDomain(domain.min, domain.max - 1) else domain
                                }, source.factors, semantics = source.semantics)
                            "row" -> source.withFactors(source.factors.copyOf().also { factors ->
                                val mapping = VarRemap(IntArray(source.numBoolVars) { it },
                                    IntArray(source.numIntVars) { if (it == 0) 1 else if (it == 1) 0 else it })
                                factors[0] = factors[0].remap(mapping)
                            })
                            else -> source
                        }
                        val changedObjective = if (change == "objective") objective.copy(
                            intCoefficients = objective.intCoefficients.also { it[0] += 1L },
                        ) else objective
                        val sink = LpStatsSink()
                        var work = 0L
                        val meter = WorkMeter { work += it }
                        val token = object : Cancellation {
                            override fun isCancelled(): Boolean = stop()
                            override fun workMeter(): WorkMeter = meter
                        }
                        var assemblyNs = 0L
                        var solveNs = 0L
                        var retainedUnits = 0L
                        var coldNs = 0L
                        val results = ArrayList<String>()
                        repeat(steps) consumers@ { step ->
                            check(!stop())
                            val view = (if (step % 2 == 1) changedSource else source)
                                .let { it.withFactors(it.factors.copyOf()) }
                            val cost = if (step % 2 == 1) changedObjective else objective
                            val opening = System.nanoTime()
                            val selectedView = if (change == "cold") {
                                FactorProjectionProviders.Default.prepare(view)
                            } else selected.forEngineView(view)
                            val consumerToken = if (change == "cancel" && step % 2 == 1) {
                                object : Cancellation {
                                    override fun isCancelled(): Boolean = true
                                    override fun workMeter(): WorkMeter = meter
                                }
                            } else token
                            val consumer = CpToLpRelaxation(view, cost,
                                projections = selectedView, templateStats = sink,
                                reuseSourceTemplates = mode != "disabled", reuseTemplateIndexes = mode != "object",
                                reuseTemplateMetadata = mode == "candidate", preparationCancellation = consumerToken)
                            val relaxation = try {
                                consumer.build(RootDomains(view), cancellation = consumerToken)
                            } catch (_: LpAssemblyCancelled) {
                                check(change == "cancel" && step % 2 == 1)
                                assemblyNs += System.nanoTime() - opening
                                results.add("cancelled")
                                return@consumers
                            }
                            assemblyNs += System.nanoTime() - opening
                            retainedUnits = maxOf(retainedUnits, consumer.preparationTemplates?.storageUnits ?: 0L)
                            val solving = System.nanoTime()
                            val exact = requireNotNull(relaxation.authoritativeWithTemplates(token) {
                                consumer.observeBoundTemplates(it)
                            })
                            val result = solveAndCertify(exact, cancellation = token)
                            solveNs += System.nanoTime() - solving
                            results.add("${result.verdict}:${result.lowerBound}:${result.witness?.objective}")
                            if (step == 0) coldNs = System.nanoTime() - start
                        }
                        val phases = sink.snapshot().phases
                        val totalNs = System.nanoTime() - start
                        val endCpu = cpu?.currentThreadCpuTime?.takeIf { it >= 0L }
                        val threadCpuNs = if (startCpu != null && endCpu != null) endCpu - startCpu else null
                        val allocatedBytes = profileAllocatedBytes()?.let { it - requireNotNull(startBytes) }
                        signatures[mode] = results
                        val preparation = phases["TEMPLATE_PREPARATION_NODE"]
                        val assembly = phases["TEMPLATE_ASSEMBLY_NODE"]
                        val record = buildJsonObject {
                            put("source", file.name)
                            put("sourceSha256", hash)
                            put("parseNs", parseNs)
                            put("round", round)
                            put("warmup", round == 0)
                            put("mode", mode)
                            put("change", change)
                            put("steps", steps)
                            put("totalNs", totalNs)
                            put("threadCpuNs", threadCpuNs)
                            put("coldSetupAndConsumerNs", coldNs)
                            put("assemblyIncludingPreparationNs", assemblyNs)
                            put("solveAndCertificationNs", solveNs)
                            put("totalThreadBytes", allocatedBytes)
                            put("totalWork", work)
                            put("preparationNs", preparation?.nanos ?: 0L)
                            put("preparationWork", preparation?.work ?: 0L)
                            put("indexesReused", preparation?.outcomes?.get("INDEXES_REUSED") ?: 0L)
                            put("componentsDirty", preparation?.outcomes?.get("COMPONENTS_DIRTY") ?: 0L)
                            put("rowsAssembled", assembly?.outcomes?.get("ROWS") ?: 0L)
                            put("boundsAssembled", assembly?.outcomes?.get("BOUNDS") ?: 0L)
                            put("exactBoundsAdopted", assembly?.outcomes?.get("EXACT_BOUND_OBJECTS") ?: 0L)
                            put("peakRetainedTemplateUnits", retainedUnits)
                            put("nativeResults", results.joinToString(","))
                            put("independentProofCheck", false)
                        }
                        writer.appendLine(record.toString())
                        writer.flush()
                        println(record)
                    }
                    check(signatures.values.distinct().size == 1) { "consumer results differ across reuse modes" }
                }
            }
        }
    }
}
