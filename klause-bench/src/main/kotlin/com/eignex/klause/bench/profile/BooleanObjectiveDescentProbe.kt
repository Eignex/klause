package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.LocalSearchWorkerConfig
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BooleanObjectiveDescentProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            parts[0] to parts[1]
        }
        require(options.keys - setOf("prepareMs", "timeout") ==
            setOf("suite", "problem", "sha256", "witness64", "work"))
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val work = requireNotNull(options["work"]).toLong().also { require(it in 1..1_000_000) }
        val timeout = options["timeout"]?.toLong() ?: 5000L
        val prepareMs = options["prepareMs"]?.toLong() ?: 500L
        require(timeout in 1..60_000 && prepareMs in 1..5000)
        val ref = Catalog.uncapped(suite).problems.single { it.name == name }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        val digest = sha256(bytes)
        require(bytes.size <= MAX_SOURCE_BYTES && digest == options["sha256"])
        val encoded = requireNotNull(options["witness64"])
        require(encoded.length <= 96 * 1024)
        val witness = GZIPInputStream(Base64.getDecoder().decode(encoded).inputStream()).use {
            val decoded = it.readNBytes(MAX_SOURCE_BYTES + 1)
            require(decoded.size <= MAX_SOURCE_BYTES)
            decoded.toString(Charsets.UTF_8).trim().removePrefix("v ")
        }
        val parsed = Dimacs.parseWcnf(bytes.toString(Charsets.UTF_8)).toProblem()
        require(parsed.boolFolds.isNotEmpty() && parsed.boolFolds.all { it.lits.size == 1 }) {
            "Probe requires explicit unit penalty folds"
        }
        require(witness.length == parsed.numOriginalBoolVars && witness.all { it in "01" })
        val values = BooleanArray(parsed.problem.numBoolVars) { it < witness.length && witness[it] == '1' }
        for (fold in parsed.boolFolds) {
            val literal = fold.lits.single()
            values[fold.out] = Lit.evaluate(literal, values[Lit.variable(literal)])
        }
        val initial = Sample(values, LongArray(0))
        val sourceObjective = parsed.objective.toLinearObjective()
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            parsed.problem, FiniteEngine.MIXED, objective = sourceObjective,
            cancellation = Cancellation.after(prepareMs.milliseconds), solveCancellation = Cancellation.Never,
            presolveBudget = PresolveBudget(687_500_000L), randomSeed = 3L,
        ))
        val problem = prepared.problem
        require(problem.numBoolVars == parsed.problem.numBoolVars &&
            problem.numIntVars == 0 && problem.numRealVars == 0)
        require(problem.factors.all { factor ->
            factor is Clause && factor.literals.any { Lit.evaluate(it, values[Lit.variable(it)]) }
        }) { "Initial witness is incompatible with the prepared clausal model" }
        val objective = requireNotNull(prepared.objective)
        val sweep = requireNotNull(DefinitionalSweep.infer(parsed.problem, boolFolds = parsed.boolFolds))
        val boolWeights = objective.boolWeights
        val boolTerms = boolWeights.indices.filter { boolWeights[it] != 0L }.toIntArray()
        val guide = requireNotNull(sweep.functionalObjective(
            intArrayOf(), longArrayOf(), objective.constant, true,
            boolTerms, LongArray(boolTerms.size) { boolWeights[boolTerms[it]] },
        ))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("sources").mkdirs()
        out.resolve("sources/$digest.txt").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val rows = buildJsonArray {
            var index = 0
            for (functional in listOf(false, true)) {
                for (mode in FeasibleDescent.entries) {
                    val base = LocalSearchCatalog.byLabel("cbls/fixed")
                    val recipe = LocalSearchRecipe(
                        "cbls-${mode.name}", base.strategy.copy(feasibleDescent = mode),
                        base.optimizeStrategy?.copy(feasibleDescent = mode),
                        base.perMoveInvariants, base.seedImplicitOnRestart,
                    )
                    val materializeStarted = System.nanoTime()
                    val worker = LocalSearchWorkerConfig(recipe).materialize(
                        problem.bake(), 0, 0, 3L, 1.0, objective,
                        if (functional) guide else null, sweep, null, null,
                    )
                    val materializeNanos = System.nanoTime() - materializeStarted
                    var best = initial
                    var bestValue = objective.evaluateExact(initial)
                    var moves = 0.0
                    var restarts = 0.0
                    var improvements = 0
                    var nativeWitnesses = 0
                    val started = System.nanoTime()
                    worker.use {
                        for (result in it.improvements(
                            { Double.POSITIVE_INFINITY }, Cancellation.after(timeout.milliseconds), initial, work,
                        )) {
                            moves = maxOf(moves, result.stats.ls.moves.sum)
                            restarts = maxOf(restarts, result.stats.search.restarts.sum)
                            if (result !is MinimizeResult.WithSample) continue
                            nativeWitnesses++
                            val value = objective.evaluateExact(result.sample)
                            if (value < bestValue) {
                                best = result.sample
                                bestValue = value
                                improvements++
                            }
                        }
                    }
                    val elapsedNanos = System.nanoTime() - started
                    val sourceSample = prepared.reconstruct(best)
                    val label = "${mode.name}/${if (functional) "functional" else "linear"}"
                    add(buildJsonObject {
                        put("index", index++)
                        put("status", "DONE")
                        put("arm", label)
                        put("seed", 3)
                        put("problem", buildJsonObject {
                            put("suite", suite); put("problem", name); put("format", "WCNF")
                        })
                        put("record", buildJsonObject {
                            put("feasible", true); put("proven", false); put("maximize", false)
                            put("exactObjective", sourceObjective.evaluateExact(sourceSample).toString())
                            put("initialObjective", sourceObjective.evaluateExact(initial).toString())
                            put("searchObjective", bestValue.toString())
                            put("finalWitness", "v " + (0 until parsed.numOriginalBoolVars).joinToString("") {
                                if (sourceSample.boolValue(it)) "1" else "0"
                            })
                            put("sourceHashes", buildJsonObject { put("model", digest) })
                            put("sourceFactorCount", parsed.problem.numFactors)
                            put("searchFactorCount", problem.numFactors)
                            put("prepareMillis", prepareMs)
                            put("timeoutMillis", timeout)
                            put("materializeNanos", materializeNanos)
                            put("elapsedNanos", elapsedNanos)
                            put("workLimit", work)
                            put("nativeWitnesses", nativeWitnesses)
                            put("strictImprovements", improvements)
                            put("stats", buildJsonObject {
                                put("lsMoves", moves); put("lsRestarts", restarts)
                            })
                        })
                    })
                    println("$label initial=${objective.evaluateExact(initial)} final=$bestValue " +
                        "improvements=$improvements moves=$moves elapsedNanos=$elapsedNanos")
                }
            }
        }
        out.resolve("cases.json").writeText(rows.toString() + "\n")
    }

    private const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
}
