package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.ProblemSets
import com.eignex.klause.bench.catalog.SetEntry
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.movesource.MoveSourceId
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.portfolio.LocalSearchCatalog
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object GlobalScoringComparison {
    private var captureCells = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        captureCells = 0
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all {
            it in setOf("sets", "seeds", "moves", "millis", "captures", "suite", "problem", "sha256", "source64")
        })
        val single = options.keys.any { it in setOf("suite", "problem", "sha256", "source64") }
        val embedded = options["source64"] != null
        require(!single || ("sets" !in options && options.keys.containsAll(setOf("suite", "problem", "sha256"))))
        val seeds = (options["seeds"] ?: "3,7,11").split(',').map(String::toLong)
        require(seeds.size in 1..32)
        val moves = (options["moves"] ?: "2000").toLong().also { require(it in 1..1_000_000) }
        val millis = (options["millis"] ?: "2000").toLong().also { require(it in 1..60_000) }
        val captures = (options["captures"] ?: "3").toInt().also { require(it in 1..16) }
        val recipe = "cbls/fixed"
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        fixtures(out)
        val entries = if (single) listOf(SetEntry(options.getValue("suite"), options.getValue("problem")))
            else ProblemSets.load(options.getValue("sets").split(','))
        require(entries.size <= 32)
        for ((index, entry) in entries.withIndex()) {
            val dir = out.resolve(index.toString()).apply { mkdirs() }
            val bytes = if (single) {
                trajectorySourceBytes(entry.suite, entry.problem, options.getValue("sha256"), options["source64"])
            } else {
                val reference = Catalog.uncapped(entry.suite).problems.single { it.name == entry.problem }
                CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(64 * 1024 * 1024 + 1) }
            }
            if (bytes.size > 64 * 1024 * 1024) {
                dir.resolve("decline.txt").writeText("source-byte-limit: ${entry.suite}/${entry.problem}")
                continue
            }
            dir.resolve("source.xml").writeBytes(bytes)
            dir.resolve("source.json").writeText(buildJsonObject {
                put("suite", entry.suite)
                put("problem", entry.problem)
                put("sha256", sha256(bytes))
                put("sourceMode", if (embedded) "embedded" else "catalog")
                put("sourceVerdictClaim", false)
                put("independentWitnessCheck", false)
            }.toString())
            val parsed = Xcsp3.parse(bytes.decodeToString())
            val source = parsed.problem
            val privateCells = source.factors.sumOf { factor -> when (factor) {
                is Table -> 8L * factor.numTuples + 4L * factor.arity
                is Mdd -> 2L * factor.numStatesPerLayer.sumOf { it.toLong() } +
                    factor.numStatesPerLayer.max().toLong() + 2L * factor.seq.size
                else -> 0L
            } }
            dir.resolve("shape.json").writeText(buildJsonObject {
                put("privateExtensionalCells", privateCells)
                put("privateExtensionalCellLimit", 1_048_576)
                put("factors", buildJsonObject {
                    for ((kind, count) in source.factors.groupingBy { it::class.simpleName ?: "unknown" }.eachCount()) {
                        put(kind, count)
                    }
                })
            }.toString())
            val decline = when {
                parsed.objective != null -> "satisfaction-only"
                source.numIntVars + source.numBoolVars > 1024 -> "coordinate-limit"
                source.numFactors > 4096 -> "factor-limit"
                privateCells > 1_048_576 -> "native-payload-limit"
                else -> null
            }
            if (decline != null) {
                dir.resolve("decline.txt").writeText(decline)
                continue
            }
            dir.resolve("semantics.txt").writeText(parsed.semantics.toString())
            val started = System.nanoTime()
            val allocated = allocatedBytes()
            val native = LocalSearchProblem(source)
            val pairwise = GlobalScoringViews.pairwise(source, native)
            dir.resolve("views.json").writeText(buildJsonObject {
                put("preparationNs", System.nanoTime() - started)
                put("threadAllocatedBytes", allocationSince(allocated))
                put("canonicalFactors", JsonArray(source.factors.map { JsonPrimitive(it::class.simpleName) }))
                put("pairwise", JsonArray(pairwise.admissions.map { admission -> buildJsonObject {
                    put("factor", admission.factor)
                    put("view", admission.view)
                    put("decline", admission.reason)
                } }))
            }.toString())
            val selection = FactorProjectionProviders(scoring = pairwise.provider).prepare(source)
            val left = view("native", source)
            val right = view("pairwise", source, LocalSearchState(LocalSearchModel.open(source, selection), Random(1)))
            dir.resolve("paired.jsonl").bufferedWriter().use { writer ->
                for (seed in seeds) {
                    left.state.assignment.randomize(Random(seed), left.state.rootDomains)
                    val initial = left.state.assignment.snapshot()
                    writer.appendLine(paired(initial, candidates(left.state, 0), left, right).toString())
                    writer.flush()
                    if (pairwise.admissions.none { it.reason == null }) continue
                    for (alternate in listOf(false, true)) {
                        val label = if (alternate) "pairwise" else "native"
                        val report = walk(source, pairwise, alternate, initial, seed, moves, millis, captures, recipe,
                            left, right) { capture ->
                            writer.appendLine(buildJsonObject {
                                put("walk", label)
                                put("seed", seed)
                                put("capture", capture)
                            }.toString())
                            writer.flush()
                        }
                        dir.resolve("walk-$label-$seed.json").writeText(report.toString())
                    }
                }
            }
            compareRelations(source, seeds, dir, left)
        }
    }

    private fun fixtures(out: File) {
        val mandatory = Problem(0, 5, Array(5) { IntDomain(0, 4) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2, 3, 4))))
        val optional = Problem(2, 3, Array(3) { IntDomain(0, 2) }, arrayOf(AllDifferent(intArrayOf(0, 0, 1, 2),
            intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, true), Lit.make(1, true)), longArrayOf(0))))
        val weighted = Problem(0, 3, Array(3) { IntDomain(0, 7) }, arrayOf(Mdd(intArrayOf(0, 1),
            intArrayOf(1, 2, 1), intArrayOf(0, 8, 16),
            longArrayOf(0, 0, 0, 2, 0, 1, 1, 3, 0, 1, 0, 3, 1, 0, 0, 4), 0, intArrayOf(0), 4, 2)))
        out.resolve("fixtures.jsonl").bufferedWriter().use { writer ->
            for ((problem, sample) in listOf(
                mandatory to Sample(booleanArrayOf(), longArrayOf(0, 0, 0, 1, 1)),
                optional to Sample(booleanArrayOf(true, false), longArrayOf(1, 1, 1)),
                weighted to Sample(booleanArrayOf(), longArrayOf(0, 1, 2)),
            )) {
                val native = LocalSearchProblem(problem)
                val pairs = GlobalScoringViews.pairwise(problem, native)
                val left = view("fixture-native", problem)
                val right = if (problem === weighted) {
                    val relation = requireNotNull(GlobalScoringViews.alternate(problem.factors.single()).factor)
                    view("fixture-weighted-table", copy(problem, arrayOf(relation)))
                } else {
                    val selection = FactorProjectionProviders(scoring = pairs.provider).prepare(problem)
                    view("fixture-pairwise", problem,
                        LocalSearchState(LocalSearchModel.open(problem, selection), Random(1)))
                }
                left.load(sample)
                val candidates = when (problem) {
                    mandatory -> listOf(Move.IntSet(0, 2), Move.IntSet(3, 2))
                    weighted -> listOf(Move.IntSet(2, 5)) + candidates(left.state, 0)
                    else -> candidates(left.state, 0)
                }
                writer.appendLine(paired(sample, candidates, left, right).toString())
                writer.flush()
            }
        }
    }

    private fun compareRelations(source: Problem, seeds: List<Long>, dir: File, left: TrajectoryView) {
        val stop = Cancellation.after(5000.milliseconds)
        dir.resolve("relations.jsonl").bufferedWriter().use { writer ->
            var captured = 0
            for ((id, factor) in source.factors.withIndex()) {
                if (factor !is Table && factor !is Mdd) continue
                if (captured == 8 || stop()) {
                    writer.appendLine(buildJsonObject {
                        put("factor", id)
                        put("decline", if (captured == 8) "capture-limit" else "capture-time-limit")
                    }.toString())
                    continue
                }
                val started = System.nanoTime()
                val allocated = allocatedBytes()
                val relation = GlobalScoringViews.alternate(factor, stop)
                writer.appendLine(buildJsonObject {
                    put("factor", id)
                    put("kind", factor::class.simpleName)
                    put("decline", relation.reason)
                    put("constructionWork", relation.work)
                    put("constructionNs", System.nanoTime() - started)
                    put("threadAllocatedBytes", allocationSince(allocated))
                }.toString())
                writer.flush()
                val alternate = relation.factor ?: continue
                captured++
                val changed = copy(source, source.factors.copyOf().also { it[id] = alternate })
                val right = view("alternate-relation-$id", changed)
                for (seed in seeds) {
                    left.state.assignment.randomize(Random(seed), left.state.rootDomains)
                    writer.appendLine(paired(left.state.assignment.snapshot(), candidates(left.state, id),
                        left, right).toString())
                    writer.flush()
                }
            }
        }
    }

    private fun walk(
        source: Problem,
        pairwise: GlobalScoringViews.Selection,
        alternate: Boolean,
        initial: Sample,
        seed: Long,
        moves: Long,
        millis: Long,
        captures: Int,
        recipeLabel: String,
        left: TrajectoryView,
        right: TrajectoryView,
        record: (JsonObject) -> Unit,
    ): JsonObject {
        val stop = Cancellation.after(millis.milliseconds)
        val started = System.nanoTime()
        val allocated = allocatedBytes()
        val baked = try {
            if (alternate) source.bake(FactorProjectionProviders(scoring = pairwise.provider), stop)
            else source.bake(stop)
        } catch (_: CancellationException) {
            return buildJsonObject {
                put("alternate", alternate)
                put("seed", seed)
                put("decline", "root-preparation-cancelled")
                put("totalNs", System.nanoTime() - started)
                put("independentWitnessCheck", false)
            }
        }
        val preparationNs = System.nanoTime() - started
        var picks = 0L
        var observed: LocalSearchState? = null
        val recorder = object : MoveSource {
            override val id = MoveSourceId("global-view-capture")
            override val phase = Phase.Any
            override val pool = Pool.NoiseEligible
            override fun generate(state: LocalSearchState, sink: MoveSink) {
                observed = state
                val pick = picks++
                if (pick >= captures * 256L || pick % 256L != 0L) return
                record(buildJsonObject {
                    put("pick", pick)
                    put("step", state.step)
                    put("walkRandom", trajectoryRandomBoundary(state))
                    if (state.weights.allocated) {
                        put("walkWeights", JsonArray(state.weights.factorWeights.map(::JsonPrimitive)))
                    }
                    put("pairedWeights", "unit")
                    put("pair", paired(state.assignment.snapshot(),
                        candidates(state, pick.toInt()), left, right))
                })
            }
        }
        val recipe = LocalSearchCatalog.byLabel(recipeLabel).withSources { listOf(ConfiguredSource(recorder)) + it }
        val solver = LocalSearchSolver(baked, strategy = recipe.strategy, optimizeStrategy = recipe.optimizeStrategy,
            greedyRepairOnRestart = false, seedImplicitOnRestart = false)
        val params = LocalSearchParams(randomSeed = seed, maxFlips = moves, cancellation = stop,
            initialAssignment = initial, normalizeWeightsByClass = true, reportResiduals = true)
        return solver.resumableSolve(params).use { run ->
            var result = run.runSlice(stop, Long.MAX_VALUE, Long.MAX_VALUE)
            while (result == null && !stop()) result = run.runSlice(stop, Long.MAX_VALUE, Long.MAX_VALUE)
            val interrupted = stop()
            val endpoint = (result as? SolveResult.Sat)?.assignment
                ?: observed?.takeIf { result != null && !interrupted }?.assignment?.snapshot()
            buildJsonObject {
                put("alternate", alternate)
                put("seed", seed)
                put("recipe", recipeLabel)
                put("greedyInitialization", false)
                put("implicitSeeding", false)
                put("normalizeWeightsByClass", true)
                put("preparationNs", preparationNs)
                put("totalNs", System.nanoTime() - started)
                put("threadAllocatedBytes", allocationSince(allocated))
                put("cancelled", interrupted)
                put("picks", picks)
                put("moves", run.stats.ls.moves.sum)
                put("nativeVerdict", result?.let { it::class.simpleName })
                put("independentWitnessCheck", false)
                put("sourceVerdictClaim", false)
                if (endpoint != null) put("endpoint", paired(endpoint, emptyList(), left, right))
                else put("endpointDecline", "unfinished-or-interrupted-walk")
                put("stats", run.stats.toString())
            }
        }
    }

    private fun paired(
        source: Sample,
        candidates: List<Move>,
        left: TrajectoryView,
        right: TrajectoryView,
    ): JsonObject {
        val coordinates = source.numBoolVars + source.numIntVars
        val cells = (left.state.problem.numFactors.toLong() + right.state.problem.numFactors + 4L * coordinates) *
            (candidates.size + 1)
        if (cells > 8_388_608 - captureCells) return buildJsonObject { put("decline", "capture-cell-limit") }
        captureCells += cells
        return trajectoryPair(source, candidates, left, right)
    }

    private fun view(label: String, problem: Problem, state: LocalSearchState =
        LocalSearchState(LocalSearchModel.open(problem), Random(1))): TrajectoryView = TrajectoryView(
        label, state, VarRemap(IntArray(problem.numBoolVars) { it }, IntArray(problem.numIntVars) { it }))

    private fun candidates(state: LocalSearchState, offset: Int): List<Move> = buildList {
        val total = state.problem.numBoolVars + state.problem.numIntVars
        for (position in 0 until total) {
            if (size == 8) break
            val slot = (position + offset % maxOf(1, total)) % total
            if (slot < state.problem.numBoolVars) {
                add(Move.BoolFlip(slot))
            } else {
                val id = slot - state.problem.numBoolVars
                val domain = state.rootDomains[id]
                val value = state.assignment.intValue(id)
                if (value < domain.max) add(Move.IntSet(id, domain.higher(value)))
                else if (value > domain.min) add(Move.IntSet(id, domain.lower(value)))
            }
        }
    }

    private fun copy(source: Problem, factors: Array<Factor>): Problem = Problem(source.numBoolVars, source.numIntVars,
        source.declaredIntDomains, factors, source.impliedFactorMask, source.hasSymmetryBreaking, source.numRealVars,
        source.realLower, source.realUpper, source.settings, source.semantics)

    private fun allocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return -1L
        return if (bean.isThreadAllocatedMemorySupported) {
            bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
        } else -1L
    }

    private fun allocationSince(start: Long): Long? {
        val end = allocatedBytes()
        return if (start < 0 || end < 0) null else end - start
    }
}
