package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object IntegerAggregateBranches {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("problem", "sha256", "aggregate", "cuts"))
        val aggregate = options.getValue("aggregate").toBooleanStrict()
        val cuts = options.getValue("cuts").toBooleanStrict()
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val selected = Catalog.uncapped("integer-aggregate-v1").problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(selected.source)).use { it.readNBytes(65537) }
        require(bytes.size <= 65536 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val preparationStop = Cancellation.after(10.seconds)
        val parsed = SmtLib.parse(bytes.decodeToString(), cancellation = preparationStop)
        val original = parsed.source.originalSource as Problem
        val prepared = SourceSetup.prepare(SourceSetupRequest(parsed.source, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK, cancellation = preparationStop))
        val scope = SourceScope(SourceRevision(sha256(bytes), "klause-ir-exact-v1"),
            objective = SourceObjective("constant-zero", ObjectiveSense.MINIMIZE))
        val context = checkNotNull(original.sourceProofContext(scope, LinearObjective(
            LongArray(original.numBoolVars), LongArray(original.numIntVars)), preparationStop))
        Reports.writeJson(out.resolve("native-system.json").path, NumericalFrontierSnapshot.system(context))
        val solveStop = Cancellation.after(5.seconds)
        val params = TheoryParams(timeout = solveStop, randomSeed = 1L, sourceCuts = cuts,
            sourceLpOperations = 256)
        val invocation = NumericalFrontierSnapshot.open(context, params, frontier = true,
            aggregateBranching = aggregate)
        val started = System.nanoTime()
        try {
            val result = NumericalFrontierSnapshot.run(invocation) { _, _ -> }
            val elapsed = System.nanoTime() - started
            val terminal = NumericalFrontierSnapshot.field(result, "terminal") as SourceEvidence?
            val checked = terminal?.let {
                SourceEvidenceVerifier(context.withLinearAuthority()).verify(it, Cancellation.after(5.seconds))
            }
            val stats = NumericalFrontierSnapshot.stats(invocation)
            Reports.writeJson(out.resolve("report.json").path, buildJsonObject {
                put("revision", Reports.readGitSha())
                put("originalSha256", sha256(bytes))
                put("problem", selected.name)
                put("aggregate", aggregate)
                put("cuts", cuts)
                put("scope", "instrumented-original-ir-authority")
                put("independentProofCheck", false)
                put("terminal", terminal?.claim?.javaClass?.simpleName)
                put("nativeOriginalCheck", checked?.toString())
                put("solveNs", elapsed)
                put("ordinaryRoute", prepared.route.javaClass.simpleName)
                put("preparedExecution", prepared.preparedPlan?.kind?.name)
                put("originalFiniteIntegers", (0 until original.numIntVars).count { original.intDomainOrNull(it) != null })
                put("originalOpenIntegers", (0 until original.numIntVars).count { original.intDomainOrNull(it) == null })
                put("operations", stats.smt.sourceLp.operations)
                put("stages", buildJsonObject {
                    stats.smt.completeSearchStages.orEmpty().toSortedMap().forEach { (key, value) -> put(key, value) }
                })
                put("sourceLp", stats.smt.sourceLp.toString())
                put("javaVersion", System.getProperty("java.version"))
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("jvmOptions", buildJsonArray {
                    ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
                })
            })
        } finally {
            (invocation as AutoCloseable).close()
        }
    }
}
