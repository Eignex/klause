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
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object BooleanObjectiveDescentProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2)
            parts[0] to parts[1]
        }
        require(options.keys - setOf(
            "prepareMs", "timeout", "conditional", "conditionalClosure", "conditionalRecipe",
        ) ==
            setOf("suite", "problem", "sha256", "witness64", "work"))
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val work = requireNotNull(options["work"]).toLong().also { require(it in 1..1_000_000) }
        val timeout = options["timeout"]?.toLong() ?: 5000L
        val prepareMs = options["prepareMs"]?.toLong() ?: 500L
        val conditional = options["conditional"]?.toInt() ?: 0
        val closureMode = options["conditionalClosure"] ?: "false"
        require(closureMode in listOf("true", "false", "both"))
        require(conditional in 0..32)
        require(closureMode == "false" || conditional > 0)
        val recipeLabel = options["conditionalRecipe"] ?: "cbls/fixed"
        require(recipeLabel in listOf("cbls/fixed", "sa/adaptive-perturb"))
        require(options["conditionalRecipe"] == null || conditional > 0)
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
        if (conditional > 0) {
            val roots = parsed.boolFolds.map { Lit.variable(it.lits.single()) }.distinct()
            val variants = if (closureMode == "both") listOf(false, true) else listOf(closureMode == "true")
            val rows = buildJsonArray {
                var index = 0
                var identity: String? = null
                for (closedRepairs in variants) {
                    val results = conditionalRepairs(
                        problem, objective, sweep, initial, roots, conditional, work, timeout,
                        suite, name, digest, parsed.numOriginalBoolVars, sourceObjective, prepared.reconstruct,
                        closedRepairs, recipeLabel,
                    )
                    for (result in results) {
                        val row = result.jsonObject
                        val signature = row.getValue("record").jsonObject
                            .getValue("preparedModelSha256").jsonPrimitive.content
                        check(identity == null || identity == signature)
                        identity = signature
                        add(JsonObject(row + ("index" to JsonPrimitive(index++))))
                    }
                }
            }
            out.resolve("cases.json").writeText(rows.toString() + "\n")
            return
        }
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
                    val worker = PortfolioBuilder.build(
                        problem.bake(),
                        PortfolioScenario(1, 1, Kind.COP, EngineMix.LOCAL_SEARCH,
                            seed = 3L, lsPool = listOf({ recipe })),
                        objective, if (functional) guide else null, sweep,
                    ).single()
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

    private fun conditionalRepairs(
        problem: Problem,
        objective: LinearObjective,
        sweep: DefinitionalSweep,
        initial: Sample,
        roots: List<Int>,
        count: Int,
        work: Long,
        timeout: Long,
        suite: String,
        name: String,
        digest: String,
        originalBools: Int,
        sourceObjective: LinearObjective,
        reconstruct: (Sample) -> Sample,
        closedRepairs: Boolean,
        recipeLabel: String,
    ) = buildJsonArray {
        val setupStarted = System.nanoTime()
        val pins = requireNotNull(LocalSearchModel.of(problem.bake()).pinsUnder(Assumptions.None))
        pins.forEachBool { id, value -> require(initial.boolValue(id) == value) }
        val preparedIdentity = preparedIdentity(problem, objective, pins)
        val model = LocalSearchModel.open(problem)
        val base = LocalSearchState(model, Random(3), pins)
        for (v in 0 until problem.numBoolVars) base.assignment.setBool(v, initial.boolValue(v))
        base.invariants = sweep.network(0, problem.numBoolVars)
        base.recompute()
        require(base.cost == 0L)
        val graph = if (closedRepairs) BinaryRepairGraph.prepare(problem, pins,
            Cancellation.after(timeout.milliseconds)) else null
        val targets = roots.filter { !pins.isFrozenBool(it) }.mapNotNull { v ->
            val delta = requireNotNull(base.objectiveDelta(objective, Move.BoolFlip(v)))
            if (delta < 0.0) v to delta else null
        }.sortedWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first }).take(count)
        require(targets.isNotEmpty()) { "No unpinned improving unit source flips" }
        val sharedSetupNanos = System.nanoTime() - setupStarted
        for ((index, target) in targets.withIndex()) {
            val materializeStarted = System.nanoTime()
            val (root, delta) = target
            val assumptions = pins.withBool(root, !initial.boolValue(root))
            val state = LocalSearchState(model, Random(3), assumptions, base.projection)
            for (v in 0 until problem.numBoolVars) state.assignment.setBool(v, initial.boolValue(v))
            assumptions.forEachBool { id, value -> state.assignment.setBool(id, value) }
            val closure = graph?.let { BinaryRepairClosure(it, assumptions) }
            val seedCancellation = Cancellation.after(timeout.milliseconds)
            val seedRejected = closure?.seed(state, root, !initial.boolValue(root), seedCancellation) == false
            val seedCancelled = seedRejected && seedCancellation.isCancelled()
            sweep.sweep(state.assignment, state.rootDomains, problem.factors, assumptions::isFrozenBool)
            state.invariants = sweep.network(0, problem.numBoolVars)
            state.recompute()
            if (!seedRejected && graph != null) check(graph.violations(state) == 0L)
            val initialCost = state.cost
            val cancellation = Cancellation.after(timeout.milliseconds)
            val recipe = LocalSearchCatalog.byLabel(recipeLabel).strategy
            val strategy = closure?.wrap(recipe, cancellation) ?: recipe
            val materializeNanos = System.nanoTime() - materializeStarted
            val started = System.nanoTime()
            var moves = 0L
            var stalled = false
            var bestHardCost = state.cost
            while (!seedRejected && state.cost != 0L && moves < work && !cancellation.isCancelled()) {
                val move = strategy.pickMove(state)
                if (move == null) {
                    stalled = true
                    break
                }
                state.apply(move)
                moves++
                bestHardCost = minOf(bestHardCost, state.cost)
            }
            val elapsedNanos = System.nanoTime() - started
            assumptions.forEachBool { id, value -> check(state.assignment.boolValue(id) == value) }
            val binaryCost = graph?.violations(state)
            if (!seedRejected && binaryCost != null) check(binaryCost == 0L)
            val incrementalCost = state.cost
            state.recompute()
            check(state.cost == incrementalCost)
            check(state.step == moves)
            val feasible = !seedRejected && state.cost == 0L
            val sourceSample = if (feasible) reconstruct(state.assignment.snapshot()) else null
            val value = sourceSample?.let(sourceObjective::evaluateExact)
            add(buildJsonObject {
                put("index", index); put("status", "DONE")
                put("arm", "conditional-${if (closedRepairs) "closed" else "raw"}-$recipeLabel-$root")
                put("seed", 3)
                put("problem", buildJsonObject {
                    put("suite", suite); put("problem", name); put("format", "WCNF")
                })
                put("record", buildJsonObject {
                    put("feasible", if (feasible) JsonPrimitive(true) else JsonNull)
                    put("proven", false); put("maximize", false)
                    if (sourceSample != null) {
                        put("exactObjective", value.toString())
                        put("finalWitness", "v " + (0 until originalBools).joinToString("") {
                            if (sourceSample.boolValue(it)) "1" else "0"
                        })
                    }
                    put("sourceHashes", buildJsonObject { put("model", digest) })
                    put("preparedModelSha256", preparedIdentity); put("searchFactorCount", problem.numFactors)
                    put("initialObjective", sourceObjective.evaluateExact(initial).toString())
                    put("strictImprovements",
                        if (value != null && value < sourceObjective.evaluateExact(initial)) 1 else 0)
                    put("targetRoot", root); put("targetDelta", delta)
                    put("rootPins", pins.numBools); put("pinsSatisfied", true)
                    put("initialHardCost", initialCost); put("finalHardCost", state.cost)
                    put("bestHardCost", bestHardCost)
                    put("closedRepairs", closedRepairs); put("closureSeedRejected", seedRejected)
                    put("closureSeedCancelled", seedCancelled)
                    put("binaryHardCost", binaryCost?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("binaryEdges", graph?.edgeCount ?: 0); put("binaryUnits", graph?.units?.size ?: 0)
                    put("closureCalls", closure?.candidateCalls ?: 0)
                    put("closureRejected", closure?.rejected ?: 0); put("closureCancelled", closure?.cancelled ?: 0)
                    put("closureFacts", closure?.factVisits ?: 0); put("closureEdges", closure?.edgeVisits ?: 0)
                    put("closureLargestMove", closure?.largestMove ?: 0)
                    put("nativeWitnesses", if (feasible) 1 else 0)
                    put("recipe", recipeLabel); put("seedOnlyWitness", feasible && moves == 0L)
                    put("sharedSetupNanos", sharedSetupNanos); put("materializeNanos", materializeNanos)
                    put("elapsedNanos", elapsedNanos); put("workLimit", work); put("timeoutMillis", timeout)
                    put("stalled", stalled); put("cancelled", cancellation.isCancelled())
                    put("stats", buildJsonObject {
                        put("lsMoves", moves); put("lsAcceptedStep", state.step)
                        put("lsPrimitiveFlips", state.tabu.touchCount.sumOf { it.toLong() }); put("lsRestarts", 0)
                    })
                })
            })
            println("conditional root=$root closed=$closedRepairs rejectedSeed=$seedRejected " +
                "delta=$delta initialHard=$initialCost bestHard=$bestHardCost finalHard=${state.cost} " +
                "closureRejected=${closure?.rejected} largestMove=${closure?.largestMove} " +
                "sourceObjective=$value moves=$moves elapsedNanos=$elapsedNanos")
        }
        println("conditional targets=${targets.size} requested=$count sharedSetupNanos=$sharedSetupNanos")
    }

    private fun preparedIdentity(problem: Problem, objective: LinearObjective, pins: Assumptions): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: Long) {
            for (shift in 56 downTo 0 step 8) digest.update((value ushr shift).toByte())
        }
        append(problem.numBoolVars.toLong())
        append(problem.numFactors.toLong())
        for (factor in problem.factors) {
            val literals = (factor as Clause).literals
            append(literals.size.toLong())
            for (literal in literals) append(literal.toLong())
        }
        append(objective.constant)
        val weights = objective.boolWeights
        append(weights.size.toLong())
        for (weight in weights) append(weight)
        append(pins.numBools.toLong())
        pins.forEachBool { variable, value -> append(variable.toLong()); append(if (value) 1 else 0) }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
