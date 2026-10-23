package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object BooleanDispatchProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val parsed = SmtLib.parse(bytes.decodeToString(), cancellation = Cancellation.after(10.seconds))
        val setup = SourceSetup.prepare(SourceSetupRequest(
            parsed.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val model = requireNotNull(setup.preparedPlan?.model)
        val clauses = model.factors.filterIsInstance<Clause>()
        val clauseHash = sha256(clauses.joinToString("\n") { it.literals.joinToString(",") }.encodeToByteArray())
        val constructor = Class.forName("com.eignex.klause.propagation.NativeClauseSearchComponent")
            .getConstructor(Int::class.javaPrimitiveType, List::class.java, Cancellation::class.java)
        val runs = buildJsonArray {
            for (round in 0 until 4) {
                for (native in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    for (peer in listOf(false, true)) {
                        val started = System.nanoTime()
                        val component = if (native) {
                            constructor.newInstance(model.numBoolVars, clauses, Cancellation.Never) as SearchComponent
                        } else ClauseSearchComponent(clauses)
                        val observer = AssertionCounter()
                        val session = SearchSession(if (peer) listOf(component, observer) else listOf(component))
                        val root = session.initialize()
                        val initializationNs = System.nanoTime() - started
                        val observations = MessageDigest.getInstance("SHA-256")
                        observations.update(root::class.simpleName.orEmpty().encodeToByteArray())
                        val traceStarted = System.nanoTime()
                        var propagationNs = 0L
                        if (root is ComponentResult.Consistent) {
                            // Each root probe uses the same source id and polarity in both lanes. It
                            // does not follow a lane's branch selection or invoke arithmetic.
                            val stride = maxOf(1, (model.numBoolVars + 255) / 256)
                            for (variable in 0 until model.numBoolVars step stride) {
                                for (positive in listOf(false, true)) {
                                    val pushStarted = System.nanoTime()
                                    val result = session.push(SearchDecision.Bool((variable shl 1) or if (positive) 0 else 1))
                                    propagationNs += System.nanoTime() - pushStarted
                                    observations.update(result::class.simpleName.orEmpty().encodeToByteArray())
                                    if (result is ComponentResult.Consistent) {
                                        for (v in 0 until model.numBoolVars) observations.update(
                                            when (session.boolValue(v)) { true -> 1; false -> 0; null -> 2 }.toByte(),
                                        )
                                    }
                                    val popStarted = System.nanoTime()
                                    session.popTo(0)
                                    propagationNs += System.nanoTime() - popStarted
                                }
                            }
                        }
                        val traceNs = System.nanoTime() - traceStarted
                        add(buildJsonObject {
                            put("round", round)
                            put("warmup", round == 0)
                            put("native", native)
                            put("peer", peer)
                            put("initializationNs", initializationNs)
                            put("traceNs", traceNs)
                            put("propagationNs", propagationNs)
                            put("callbacks", observer.assertions)
                            put("observationsSha256", observations.digest().joinToString("") { "%02x".format(it) })
                        })
                    }
                }
            }
        }
        out.resolve("boolean-dispatch.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(bytes))
            put("clausesSha256", clauseHash)
            put("booleans", model.numBoolVars)
            put("clauses", clauses.size)
            put("sourceVerdictClaim", false)
            put("scope", "at-most-256-source-id-root-probes-with-optional-inert-peer; propagationNs excludes snapshots")
            put("runs", runs)
        }.toString())
    }

    private class AssertionCounter : SearchComponent {
        var assertions = 0L
        override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
            assertions++
            return ComponentResult.Consistent
        }
    }
}
