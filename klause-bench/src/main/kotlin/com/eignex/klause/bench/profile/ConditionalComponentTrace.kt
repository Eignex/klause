package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Traces the residual structure complete finite search leaves after current preparation, as a lab profile job.
 *
 * Arguments are `source=<suite>:<problem>:<sha256>` (repeatable), `searchMillis=<n>` (default 10,000),
 * `traceMillis=<n>` (default 15,000), `prepareMillis=<n>` (default 60,000) and `seed=<n>` (default 3). The
 * two-five-cycle controls run first, built from WCNF text: two disjoint five-cycles of hard edge clauses,
 * the bridge `s or x(0) or y(0)` and unit soft costs on every variable, once as stated (optimum 6) and once
 * with `s` hard-true (optimum 7).
 *
 * Each source is hash-checked, prepared through [FinitePipeline] with the finite presolve allowance the
 * CLI uses, then minimized by [BacktrackPresets.satOptimized]. A wrapping [VariableSelector] samples the
 * node at each branch point, after propagation, before the delegate picks. Every non-clause factor and
 * every clause with an atom literal connects all its free coordinates; a clause with a true literal is
 * discharged. Components count only live factors, and the objective is treated as additive, so the
 * reported independence is an upper bound for any decomposition that must also respect the incumbent
 * cutoff. A component's key hashes the residual of each of its live factors in factor order; a key seen
 * again after an intervening conflict or restart is a re-encountered child obligation. Learned clauses
 * are added on every eighth trace to count components they would merge. Sampling time and the trace
 * allowance are excluded from the search deadline. Each source writes `<n>-<label>.json` under `$OUT` as
 * soon as it finishes. Witnesses are checked natively against the original clauses only.
 */
object ConditionalComponentTrace {
    private const val DENSE_PICKS = 4_096L
    private const val SPARSE_STRIDE = 16L
    private const val LEARNED_STRIDE = 8L
    private const val MIN_CHILD_LIVE = 2
    private const val MAX_KEYS = 2_000_000
    private const val PRESOLVE_ALLOWANCE = 687_500_000L
    private const val HASH_A = -0x61c8864680b583ebL
    private const val HASH_B = -0x3d4d51c2d82b14b1L
    private val sizeBuckets = longArrayOf(1, 2, 4, 16, 256)

    // Learned literals are internal to the solver module; the trace reads them without widening its API.
    private val learnedClauseLiterals = PropagationSession::class.java.declaredMethods.single {
        it.name.startsWith("learnedClauseLiterals") && it.parameterCount == 1
    }.apply { isAccessible = true }
    private val isLearnedClause = PropagationSession::class.java.declaredMethods.single {
        it.name.startsWith("isLearnedClause") && it.parameterCount == 1
    }.apply { isAccessible = true }

    private class Input(
        val label: String,
        val problem: Problem,
        val objective: LinearObjective,
        val identity: JsonObject,
    )

    /** Run the trace [args] describe, writing into `$OUT` (`build/profile-out` when unset). */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.getenv("OUT")?.let(::File) ?: File("build/profile-out")
        out.mkdirs()
        val sources = args.filter { it.startsWith("source=") }.map { it.removePrefix("source=") }
        val options = args.filterNot { it.startsWith("source=") }.associate { arg ->
            require('=' in arg) { "expected key=value, got '$arg'" }
            arg.substringBefore('=') to arg.substringAfter('=')
        }
        val unknown = options.keys - setOf("searchMillis", "traceMillis", "prepareMillis", "seed")
        require(unknown.isEmpty()) { "unknown argument(s) $unknown" }
        val searchMillis = options["searchMillis"]?.toLong() ?: 10_000L
        val traceMillis = options["traceMillis"]?.toLong() ?: 15_000L
        val prepareMillis = options["prepareMillis"]?.toLong() ?: 60_000L
        val seed = options["seed"]?.toLong() ?: 3L
        out.resolve("request.json").writeText(buildJsonObject {
            put("sources", sources.joinToString(","))
            put("searchMillis", searchMillis)
            put("traceMillis", traceMillis)
            put("prepareMillis", prepareMillis)
            put("seed", seed)
            put("javaVersion", System.getProperty("java.version"))
            put("osArch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("maxMemory", Runtime.getRuntime().maxMemory())
            put("provenance", runCatching { InstalledBuild.current.fingerprint }.getOrElse { "unavailable: $it" })
        }.toString())
        val inputs = listOf<() -> Input>({ control(conditioned = false) }, { control(conditioned = true) }) +
            sources.map { spec -> { catalogSource(spec) } }
        inputs.forEachIndexed { index, load ->
            val record = runCatching { trace(load(), searchMillis, traceMillis, prepareMillis, seed) }.getOrElse {
                buildJsonObject { put("failure", it.toString()) }
            }
            val label = record["label"]?.toString()?.trim('"') ?: "input"
            out.resolve("${index.toString().padStart(2, '0')}-${label.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
                .writeText(record.toString())
            println(record)
        }
    }

    private fun control(conditioned: Boolean): Input {
        val hard = buildList {
            for (i in 0 until 5) add(intArrayOf(1 + i, 1 + (i + 1) % 5))
            for (i in 0 until 5) add(intArrayOf(6 + i, 6 + (i + 1) % 5))
            add(intArrayOf(11, 1, 6))
            if (conditioned) add(intArrayOf(11))
        }
        val soft = (1..11).map { intArrayOf(-it) }
        val text = buildString {
            append("p wcnf 11 ${hard.size + soft.size} 12\n")
            for (c in hard) append("12 ${c.joinToString(" ")} 0\n")
            for (c in soft) append("1 ${c.joinToString(" ")} 0\n")
        }
        val wcnf = Dimacs.parseWcnf(text).toProblem()
        val label = if (conditioned) "control-two-five-cycle-s-true" else "control-two-five-cycle"
        return Input(label, wcnf.problem, wcnf.objective.toLinearObjective(), buildJsonObject {
            put("kind", "self-authored")
            put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())))
            put("expectedOptimum", if (conditioned) 7 else 6)
        })
    }

    private fun catalogSource(spec: String): Input {
        val parts = spec.split(':')
        require(parts.size == 3) { "expected suite:problem:sha256, got '$spec'" }
        val (suite, name, expected) = parts
        val ref = Catalog.uncapped(suite).problems.single { it.name == name }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytes += read
            }
        }
        val actual = hex(digest.digest())
        require(actual == expected) { "$suite/$name: sha256 $actual, expected $expected" }
        val entry = Runners.resolve(ref)
        return Input("$suite-$name", entry.problem, requireNotNull(entry.objective), buildJsonObject {
            put("kind", "catalog")
            put("suite", suite)
            put("problem", name)
            put("sha256", actual)
            put("bytes", bytes)
        })
    }

    private fun trace(
        input: Input,
        searchMillis: Long,
        traceMillis: Long,
        prepareMillis: Long,
        seed: Long,
    ): JsonObject {
        val prepareStart = System.nanoTime()
        val stop = Cancellation.after(prepareMillis.milliseconds)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            input.problem, FiniteEngine.BACKTRACK, input.objective,
            cancellation = stop, solveCancellation = stop, presolveBudget = PresolveBudget(PRESOLVE_ALLOWANCE),
            randomSeed = seed,
        ))
        val prepareNanos = System.nanoTime() - prepareStart
        val baked = prepared.problem as? BakedProblem
        val objective = prepared.objective
        val header = buildJsonObject {
            put("label", input.label)
            put("source", input.identity)
            put("sourceBoolVars", input.problem.numBoolVars)
            put("sourceIntVars", input.problem.numIntVars)
            put("sourceFactors", input.problem.factors.size)
            putJsonObject("preparation") {
                put("elapsedMs", prepareNanos / 1_000_000)
                put("stopped", stop())
                put("infeasible", prepared.presolve?.infeasible == true)
                put("passes", prepared.presolve?.passes?.joinToString(",").orEmpty())
                put("constraintsRemoved", prepared.presolve?.constraintsRemoved ?: 0)
            }
        }
        if (baked == null || objective == null || stop() || prepared.presolve?.infeasible == true) {
            return JsonObject(header + ("decline" to JsonPrimitive("preparation-terminal")))
        }
        val recorder = Recorder(baked, traceMillis * 1_000_000)
        val start = System.nanoTime()
        val deadline = searchMillis * 1_000_000
        val base = BacktrackPresets.satOptimized(
            randomSeed = seed,
            cancellation = Cancellation { System.nanoTime() - start - recorder.traceNanos > deadline },
        )
        val params = base.copy(variableSelector = TracingSelector(base.variableSelector, recorder))
        val result = BacktrackSolver(baked).minimize(objective, params)
        val wallNanos = System.nanoTime() - start
        val sourceSample = result.assignment?.let(prepared.reconstruct)
        return JsonObject(header + buildJsonObject {
            put("preparedBoolVars", baked.numBoolVars)
            put("preparedIntVars", baked.numIntVars)
            put("preparedFactors", baked.factors.size)
            put("preparedFactorKinds", baked.factors.groupingBy { it::class.simpleName ?: "?" }.eachCount()
                .entries.joinToString(",") { "${it.key}=${it.value}" })
            putJsonObject("solve") {
                put("preset", "satOptimized")
                put("result", result::class.simpleName)
                put("proven", result is MinimizeResult.Optimal || result is MinimizeResult.Infeasible)
                put("preparedObjective", result.objectiveValue)
                sourceSample?.let { put("sourceObjective", sourceObjective(input.objective, it)) }
                sourceSample?.let { put("sourceClauseViolations", clauseViolations(input.problem, it)) }
                put("sourceNonClauseFactorsUnchecked", input.problem.factors.count { it !is Clause })
                put("nodes", result.stats.search.nodes.sum)
                put("fails", result.stats.search.fails.sum)
                put("restarts", result.stats.search.restarts.sum)
                put("wallMs", wallNanos / 1_000_000)
                put("searchMs", (wallNanos - recorder.traceNanos) / 1_000_000)
            }
            put("trace", recorder.summary())
        })
    }

    private fun sourceObjective(objective: LinearObjective, sample: Sample): Long {
        var total = objective.constant
        val weights = objective.boolWeights
        for (v in weights.indices) {
            if (sample.boolValue(v)) total += weights[v]
        }
        val coefficients = objective.intCoefficients
        for (v in coefficients.indices) total += coefficients[v] * sample.intValue(v)
        return total
    }

    private fun clauseViolations(problem: Problem, sample: Sample): Int = problem.factors.count { f ->
        f is Clause && f.allLiteralsBool(problem.numBoolVars) &&
            f.literals.none { sample.boolValue(Lit.variable(it)) == Lit.isPositive(it) }
    }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    /** Forwards every hook to [delegate] and lets [recorder] observe each branch point first. */
    private class TracingSelector(private val delegate: VariableSelector, private val recorder: Recorder) :
        VariableSelector {
        override fun pick(session: PropagationSession, rng: Random): VarRef? {
            recorder.observe(session)
            return delegate.pick(session, rng)
        }

        override fun fresh(): VariableSelector = TracingSelector(delegate.fresh(), recorder)
        override fun onSolution(snapshot: Sample) = delegate.onSolution(snapshot)
        override fun onConflict(varRef: VarRef) {
            recorder.epoch++
            delegate.onConflict(varRef)
        }

        override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
            recorder.epoch++
            delegate.onConflict(varRef, conflict)
        }

        override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
            recorder.epoch++
            delegate.onConflict(varRef, unsat)
        }

        override fun onCommit(varRef: VarRef) = delegate.onCommit(varRef)
        override fun onPropagation(implied: PropagationResult.Implied) = delegate.onPropagation(implied)
        override fun onRestart() {
            recorder.epoch++
            delegate.onRestart()
        }

        override val tracksUnassign: Boolean get() = delegate.tracksUnassign
        override fun onUnassign(varRef: VarRef) = delegate.onUnassign(varRef)
    }

    private class Seen(var epoch: Long, var sightings: Int)

    private class Component(var live: Int = 0, var free: Int = 0, var h1: Long = 0L, var h2: Long = 0L)

    /** Accumulates residual component statistics over sampled branch points. */
    private class Recorder(private val prepared: Problem, private val traceCapNanos: Long) {
        var traceNanos = 0L
        var epoch = 0L

        // The search session can rebuild its root model; arrays follow the model each sample reads.
        private var problem: Problem = prepared
        private var numBool = problem.numBoolVars
        private var parent = IntArray(numBool + problem.numIntVars)
        private var liveRep = IntArray(problem.factors.size)
        private var liveH1 = LongArray(problem.factors.size)
        private var liveH2 = LongArray(problem.factors.size)
        private var modelSwitches = 0L
        private var foreignSamples = 0L
        private val seen = HashMap<Pair<Long, Long>, Seen>()
        private var picks = 0L
        private var samples = 0L
        private var capSkipped = 0L
        private var keyCapHits = 0L
        private var atomClauses = 0L
        private var unitAtFixpoint = 0L
        private var root: JsonObject? = null
        private var splitSamples = 0L
        private var maxComponents = 0
        private var maxSplitLevel = -1
        private var splitLevelSum = 0L
        private var splitFree = 0L
        private var splitChildFree = 0L
        private var maxSightings = 0
        private val secondBuckets = LongArray(sizeBuckets.size + 1)
        private var largestLiveShareSum = 0.0
        private var liveSamples = 0L
        private var childSightings = 0L
        private var reencounters = 0L
        private var reencounteredLive = 0L
        private var maxReencounteredLive = 0
        private val reencounterBuckets = LongArray(sizeBuckets.size + 1)
        private var samePath = 0L
        private var learnedSamples = 0L
        private var learnedSplitSamples = 0L
        private var learnedMergedSplits = 0L
        private var globalLearnedMergedSplits = 0L

        fun observe(session: PropagationSession) {
            picks++
            if (traceNanos >= traceCapNanos) {
                capSkipped++
                return
            }
            if (picks > DENSE_PICKS && picks % SPARSE_STRIDE != 0L) return
            val started = System.nanoTime()
            analyze(session)
            traceNanos += System.nanoTime() - started
        }

        private fun find(v: Int): Int {
            var r = v
            while (parent[r] != r) r = parent[r]
            var x = v
            while (parent[x] != r) {
                val next = parent[x]
                parent[x] = r
                x = next
            }
            return r
        }

        private fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }

        private fun free(session: PropagationSession, v: Int): Boolean =
            if (v < numBool) session.boolValue(v) == null else !session.intDomain(v - numBool).isFixed

        @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
        private fun analyze(session: PropagationSession) {
            samples++
            if (session.problem !== problem) {
                problem = session.problem
                numBool = problem.numBoolVars
                parent = IntArray(numBool + problem.numIntVars)
                liveRep = IntArray(problem.factors.size)
                liveH1 = LongArray(problem.factors.size)
                liveH2 = LongArray(problem.factors.size)
                modelSwitches++
            }
            if (problem !== prepared) foreignSamples++
            for (i in parent.indices) parent[i] = i
            var live = 0
            for ((fi, f) in problem.factors.withIndex()) {
                var h1 = fi.toLong() * HASH_A
                var h2 = fi.toLong() * HASH_B
                var rep = -1
                if (f is Clause && f.allLiteralsBool(numBool)) {
                    var satisfied = false
                    var freeLits = 0
                    for (lit in f.literals) {
                        when (session.litTruth(lit)) {
                            true -> {
                                satisfied = true
                                break
                            }

                            null -> {
                                val v = Lit.variable(lit)
                                if (rep < 0) rep = v else union(rep, v)
                                freeLits++
                                h1 = (h1 xor lit.toLong()) * HASH_A
                                h2 = (h2 + lit.toLong()) * HASH_B
                            }

                            false -> Unit
                        }
                    }
                    if (satisfied || rep < 0) continue
                    if (freeLits == 1) unitAtFixpoint++
                } else {
                    if (f is Clause) atomClauses++
                    val vars = f.variables
                    val scope = ArrayList<Int>()
                    for (v in vars.boolVars) {
                        if (v >= numBool) continue
                        val value = session.boolValue(v)
                        val code = when (value) {
                            null -> 2L
                            true -> 1L
                            false -> 0L
                        }
                        h1 = (h1 xor code) * HASH_A
                        if (value == null) scope.add(v)
                    }
                    for (v in vars.ints) {
                        val domain = session.intDomain(v)
                        h1 = (h1 xor domain.min) * HASH_A
                        h2 = (h2 + domain.max) * HASH_B
                        h2 = (h2 xor domain.valueCount) * HASH_A
                        if (!domain.isFixed) scope.add(numBool + v)
                    }
                    if (scope.isEmpty()) continue
                    rep = scope.first()
                    for (v in scope) union(rep, v)
                }
                liveRep[live] = rep
                liveH1[live] = h1
                liveH2[live] = h2
                live++
            }
            val components = HashMap<Int, Component>()
            for (k in 0 until live) {
                val c = components.getOrPut(find(liveRep[k])) { Component() }
                c.live++
                c.h1 = (c.h1 xor liveH1[k]) * HASH_A + 1
                c.h2 = (c.h2 + liveH2[k]) * HASH_B + 1
            }
            var freeVars = 0
            for (v in parent.indices) {
                if (!free(session, v)) continue
                freeVars++
                components[find(v)]?.let { it.free++ }
            }
            val level = session.decisionLevel
            val sizes = components.values.map { it.live }.sortedDescending()
            if (root == null && level == 0) root = buildJsonObject {
                put("freeVars", freeVars)
                put("liveFactors", live)
                put("components", components.size)
                put("largestLive", sizes.firstOrNull() ?: 0)
                put("secondLive", sizes.getOrNull(1) ?: 0)
            }
            if (live > 0) {
                liveSamples++
                largestLiveShareSum += sizes.first().toDouble() / live
            }
            maxComponents = maxOf(maxComponents, components.size)
            val second = sizes.getOrNull(1) ?: 0
            secondBuckets[bucket(second)]++
            if (second >= MIN_CHILD_LIVE) {
                splitSamples++
                splitLevelSum += level
                maxSplitLevel = maxOf(maxSplitLevel, level)
                val largest = components.values.maxBy { it.live }
                for (c in components.values) {
                    if (c !== largest && c.live >= MIN_CHILD_LIVE) splitChildFree += c.free
                }
                splitFree += freeVars
            }
            for (c in components.values) {
                if (c.live < MIN_CHILD_LIVE) continue
                childSightings++
                val key = c.h1 to c.h2
                val prior = seen[key]
                if (prior == null) {
                    if (seen.size < MAX_KEYS) seen[key] = Seen(epoch, 1) else keyCapHits++
                    continue
                }
                if (prior.epoch != epoch) {
                    reencounters++
                    reencounteredLive += c.live
                    maxReencounteredLive = maxOf(maxReencounteredLive, c.live)
                    reencounterBuckets[bucket(c.live)]++
                } else {
                    samePath++
                }
                prior.epoch = epoch
                prior.sightings++
                maxSightings = maxOf(maxSightings, prior.sightings)
            }
            if (samples % LEARNED_STRIDE == 0L && second >= MIN_CHILD_LIVE) {
                learnedSamples++
                learnedSplitSamples++
                val roots = components.keys.toList()
                val saved = parent.copyOf()
                if (mergesWithLearned(session, roots, skipPermanent = true)) globalLearnedMergedSplits++
                saved.copyInto(parent)
                if (mergesWithLearned(session, roots, skipPermanent = false)) learnedMergedSplits++
                saved.copyInto(parent)
            } else if (samples % LEARNED_STRIDE == 0L) {
                learnedSamples++
            }
        }

        // Permanent entries are incumbent-scoped assertions, so they count only toward the cutoff-inclusive merge.
        private fun mergesWithLearned(session: PropagationSession, roots: List<Int>, skipPermanent: Boolean): Boolean {
            for (i in 0 until session.learnedClauseCount) {
                if (isLearnedClause.invoke(session, i) != true) continue
                if (skipPermanent && session.learnedClausePermanent(i)) continue
                val lits = learnedClauseLiterals.invoke(session, i) as IntArray
                if (lits.any { Lit.variable(it) >= numBool } || lits.any { session.litTruth(it) == true }) continue
                var rep = -1
                for (lit in lits) {
                    if (session.litTruth(lit) != null) continue
                    val v = Lit.variable(lit)
                    if (rep < 0) {
                        rep = v
                    } else {
                        union(rep, v)
                    }
                }
            }
            return roots.map(::find).toSet().size < roots.size
        }

        private fun bucket(size: Int): Int {
            var b = 0
            while (b < sizeBuckets.size && size > sizeBuckets[b]) b++
            return b
        }

        fun summary(): JsonObject = buildJsonObject {
            put("picks", picks)
            put("samples", samples)
            put("traceMs", traceNanos / 1_000_000)
            put("capSkippedPicks", capSkipped)
            put("conflictEpochs", epoch)
            put("modelSwitches", modelSwitches)
            put("samplesOutsidePreparedModel", foreignSamples)
            root?.let { put("root", it) }
            put("atomClauses", atomClauses)
            put("unitClausesAtFixpoint", unitAtFixpoint)
            put("maxComponents", maxComponents)
            put("splitSamples", splitSamples)
            put("meanSplitLevel", if (splitSamples == 0L) 0.0 else splitLevelSum.toDouble() / splitSamples)
            put("maxSplitLevel", maxSplitLevel)
            put("splitFreeVars", splitFree)
            put("splitChildFreeVars", splitChildFree)
            put("meanLargestLiveShare", if (liveSamples == 0L) 0.0 else largestLiveShareSum / liveSamples)
            put("secondLiveBuckets", bucketLabels(secondBuckets))
            put("childSightings", childSightings)
            put("distinctChildKeys", seen.size)
            put("keyCapHits", keyCapHits)
            put("samePathRepeats", samePath)
            put("reencounters", reencounters)
            put("reencounteredLive", reencounteredLive)
            put("maxReencounteredLive", maxReencounteredLive)
            put("maxKeySightings", maxSightings)
            put("reencounterLiveBuckets", bucketLabels(reencounterBuckets))
            put("learnedSamples", learnedSamples)
            put("learnedSplitSamples", learnedSplitSamples)
            put("globalLearnedMergedSplits", globalLearnedMergedSplits)
            put("allLearnedMergedSplits", learnedMergedSplits)
        }

        private fun bucketLabels(counts: LongArray): String = counts.indices.joinToString(",") { b ->
            val label = when (b) {
                0 -> "<=1"
                sizeBuckets.size -> ">${sizeBuckets.last()}"
                else -> "${sizeBuckets[b - 1] + 1}-${sizeBuckets[b]}"
            }
            "$label:${counts[b]}"
        }
    }
}
