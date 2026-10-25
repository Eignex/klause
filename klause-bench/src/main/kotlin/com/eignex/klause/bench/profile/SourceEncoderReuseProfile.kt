package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.util.Cancellation
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SourceEncoderReuseProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "repeats", "warmups") })
        val suite = options.getValue("suite")
        val name = options.getValue("problem")
        val reference = Catalog.uncapped(suite).problems.single { it.name == name }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readBytes() }
        val hash = sha256(bytes)
        require(hash == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val parsed = SmtLib.parse(bytes.decodeToString())
        require(parsed.objective == null)
        val repeats = options["repeats"]?.toInt() ?: 3
        val warmups = options["warmups"]?.toInt() ?: 1
        require(repeats > 0 && warmups >= 0)
        out.resolve("runs.jsonl").bufferedWriter().use { writer ->
            repeat(warmups + repeats) { repeat ->
                for (custom in listOf(false, true)) for (deletion in listOf(false, true)) {
                    val report = measure(parsed.source, custom, deletion)
                    val record = buildJsonObject {
                        put("suite", suite)
                        put("problem", name)
                        put("sourceSha256", hash)
                        put("repeat", repeat)
                        put("warmup", repeat < warmups)
                        put("instrumented", true)
                        put("independentWitnessCheck", false)
                        for ((key, value) in report) put(key, value)
                    }
                    writer.appendLine(record.toString())
                    writer.flush()
                    println(record)
                }
            }
        }
    }

    private fun measure(source: SourceAttachment, custom: Boolean, deletion: Boolean): JsonObject {
        var selections = 0L
        var installations = 0L
        val providers = if (!custom) FactorProjectionProviders.Default else FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                selections++
                val selected = BuiltInEncoderProvider.select(factor, target)
                Encoder(selected.support, selected.requiredIntegers, selected.requiredBooleans) { view, encoding ->
                    installations++
                    selected.install(view, encoding)
                }
            },
        )
        val stop = Cancellation.after(3.seconds)
        val started = System.nanoTime()
        val invocation = SourceSetup.prepare(SourceSetupRequest(source, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK, providers = providers, cancellation = stop))
        val setupNs = System.nanoTime() - started
        val setupSelections = selections
        val setupInstallations = installations
        val budget = PresolveBudget(1_000_000L)
        val result = SourceSetup.execute(SourceExecutionRequest(invocation, ExecutionOptions.parse(emptyList()),
            randomSeed = 3L, timeout = stop, presolveBudget = budget,
            presolveConfig = if (deletion) PresolveConfig.parse("off,+dependent-rows") else PresolveConfig.NONE))
        val totalNs = System.nanoTime() - started
        val native = ((result as SourceExecutionResult.Linear).result as OpenTheoryExecution.Satisfy).result
        return buildJsonObject {
            put("customProvider", custom)
            put("deletion", deletion)
            put("setupNs", setupNs)
            put("executeNs", totalNs - setupNs)
            put("totalNs", totalNs)
            put("nativeVerdict", native::class.simpleName)
            put("preparationNs", native.stats.presolve?.effort?.elapsed?.inWholeNanoseconds)
            put("constraintsRemoved", native.stats.presolve?.constraintsRemoved)
            if (custom) {
                put("setupEncoderSelections", setupSelections)
                put("setupEncoderInstallations", setupInstallations)
                put("executeEncoderSelections", selections - setupSelections)
                put("executeEncoderInstallations", installations - setupInstallations)
            }
            put("stages", buildJsonObject {
                for ((stage, entries) in budget.preparationStages.groupBy { it.stage }) {
                    put(stage.name, entries.sumOf { it.elapsed.inWholeNanoseconds })
                }
            })
        }
    }
}
