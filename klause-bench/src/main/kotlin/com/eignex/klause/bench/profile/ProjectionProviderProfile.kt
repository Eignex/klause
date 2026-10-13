package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.BuiltInScoringProjectionProvider
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Repeated matched projection preparation costs and exhaustive finite source controls for provider extensions. */
object ProjectionProviderProfile {
    @Volatile
    private var retained: Any? = null

    /** Measure direct/default/alternate construction and check saved source controls in an independent process. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2) { "expected key=value" }
            parts[0] to parts[1]
        }
        require(options.keys.all { it in setOf("factors", "rounds", "openings", "seed", "out") })
        val factors = options["factors"]?.toInt() ?: 128
        val rounds = options["rounds"]?.toInt() ?: 7
        val openings = options["openings"]?.toInt() ?: 100
        val seed = options["seed"]?.toLong() ?: 3L
        require(factors in 2..4096 && rounds in 1..100 && openings in 1..10000)
        val out = File(options["out"] ?: System.getenv("OUT") ?: "build/projection-provider-profile").apply { mkdirs() }
        val source = source(factors)
        val alternatives = alternatives()
        val selections = mapOf(
            "selected-default" to FactorProjectionProviders.Default.prepare(source),
            "selected-alternate" to alternatives.prepare(source),
        )
        val domains = Array(2) { IntDomain(-1, 1) }
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        meter.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val measurements = buildJsonArray {
            for (round in 0..rounds) {
                val modes = listOf("direct-default", "selected-default", "selected-alternate")
                    .let { if (round % 2 == 0) it else it.reversed() }
                for (mode in modes) {
                    val bytes = meter.getThreadAllocatedBytes(thread)
                    val started = System.nanoTime()
                    var consumed = 0
                    repeat(openings) {
                        val selection = selections[mode]
                        val propagation = if (selection == null) PropagationProblem(source)
                        else PropagationProblem(source, selection)
                        val scoring = if (selection == null) LocalSearchProblem(source, domains)
                        else LocalSearchProblem(source, selection, domains)
                        consumed += propagation.propagators.size + scoring.invariants.size
                        retained = propagation
                        retained = scoring
                    }
                    val elapsed = System.nanoTime() - started
                    val allocated = meter.getThreadAllocatedBytes(thread) - bytes
                    check(consumed == openings * factors * 2)
                    add(buildJsonObject {
                        put("phase", "open-projections")
                        put("round", round)
                        put("warmup", round == 0)
                        put("mode", mode)
                        put("openings", openings)
                        put("nanos", elapsed)
                        put("allocatedBytes", allocated)
                    })
                }
                for (mode in modes) {
                    val selection = selections[mode]
                    val projection = if (selection == null) PropagationProblem(source)
                    else PropagationProblem(source, selection)
                    val propagators = projection.propagators
                    val state = PropagationState(projection,
                        Assumptions.None.withBool(0, true).withBool(1, false).withInt(0, 1).withInt(1, 0))
                    val iterations = openings * 32
                    var accepted = 0
                    val started = System.nanoTime()
                    repeat(iterations) {
                        for (id in propagators.indices) if (propagators[id].propagate(state, id)) accepted++
                    }
                    val elapsed = System.nanoTime() - started
                    check(accepted == iterations * factors)
                    add(buildJsonObject {
                        put("phase", "propagation-dispatch")
                        put("round", round)
                        put("warmup", round == 0)
                        put("mode", mode)
                        put("calls", iterations * factors)
                        put("nanos", elapsed)
                    })
                    retained = state
                }
                for ((mode, providers) in listOf("default" to FactorProjectionProviders.Default,
                    "alternate" to alternatives).let { if (round % 2 == 0) it else it.reversed() }) {
                    val bytes = meter.getThreadAllocatedBytes(thread)
                    val started = System.nanoTime()
                    repeat(openings) { retained = providers.prepare(source) }
                    add(buildJsonObject {
                        put("phase", "select-descriptors")
                        put("round", round)
                        put("warmup", round == 0)
                        put("mode", mode)
                        put("openings", openings)
                        put("nanos", System.nanoTime() - started)
                        put("allocatedBytes", meter.getThreadAllocatedBytes(thread) - bytes)
                    })
                }
            }
        }
        out.resolve("measurements.json").writeText(measurements.toString())
        val sourceJson = sourceJson()
        out.resolve("source.json").writeText(sourceJson.toString())
        out.resolve("provenance.json").writeText(provenance(factors, rounds, openings, seed, sourceJson).toString())
        val controls = buildJsonArray {
            for ((mode, providers) in listOf("default" to FactorProjectionProviders.Default, "alternate" to alternatives)) {
                val baked = source.bake(providers)
                val params = BacktrackParams(randomSeed = seed, maxDecisions = 10000)
                val witnesses = BacktrackSolver(baked).samples(params).toList()
                val projection = LocalSearchProblem(source, providers.prepare(source), domains)
                val state = LocalSearchState(LocalSearchModel.open(source, domains), Random(seed), projection = projection)
                val scores = buildJsonArray {
                    for (bits in 0..3) for (x in -1L..1L) for (y in -1L..1L) {
                        state.assignment.setBool(0, bits and 1 != 0)
                        state.assignment.setBool(1, bits and 2 != 0)
                        state.assignment.setInt(0, x)
                        state.assignment.setInt(1, y)
                        state.recompute()
                        add(buildJsonObject {
                            put("assignment", point(state.assignment.snapshot()))
                            put("accepted", state.cost == 0L)
                        })
                    }
                }
                val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
                val optimization = buildJsonArray {
                    for (maximize in listOf(false, true)) {
                        val result = BacktrackSolver(baked).minimize(if (maximize) objective.negated() else objective,
                            params.copy(lpPlan = LpPlan(bounding = true), cancellation = Cancellation.after(30.seconds)))
                        check(result is MinimizeResult.Optimal) { "optimization did not complete: $result" }
                        add(buildJsonObject {
                            put("maximize", maximize)
                            put("assignment", point(result.sample))
                            put("value", if (maximize) -result.objectiveValue else result.objectiveValue)
                        })
                    }
                }
                add(buildJsonObject {
                    put("mode", mode)
                    put("witnesses", JsonArray(witnesses.map(::point)))
                    put("scores", scores)
                    put("optimization", optimization)
                })
            }
        }
        out.resolve("controls.json").writeText(controls.toString())
        checkSource(out)
        println("PROJECTION_PROFILE factors=$factors rounds=$rounds openings=$openings seed=$seed output=$out")
    }

    private fun source(factors: Int): Problem = Problem(2, 2, Array(2) { IntDomain(-1, 1) },
        Array<Factor>(factors) { if (it % 2 == 0) Clause(intArrayOf(0, 3))
        else Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1) })

    private fun alternatives(): FactorProjectionProviders = FactorProjectionProviders(
        propagation = PropagationProjectionProvider { factor ->
            if (factor !is Clause) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.SOUND_FILTERING, AssignmentCheckCapability.EXACT) {
                object : Propagator {
                    override fun propagate(state: PropagationState, factorId: Int): Boolean {
                        var remaining = 0
                        var free = -1
                        for (literal in factor.literals) when (state.litTruth(literal)) {
                            true -> return true
                            false -> Unit
                            null -> { remaining++; free = literal }
                        }
                        return remaining > 1 || (remaining == 1 && state.pinLit(free, antecedents = null))
                    }
                }
            }
        },
        scoring = ScoringProjectionProvider { factor ->
            if (factor !is Clause) BuiltInScoringProjectionProvider.select(factor)
            else ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _ ->
                object : Invariant {
                    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
                        factor.literals.none { Lit.evaluate(it, state.assignment.boolValue(Lit.variable(it))) }
                    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
                        if (isViolated(state, factorId)) 2 else 0
                    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
                        val after = factor.literals.none {
                            val variable = Lit.variable(it)
                            Lit.evaluate(it, state.assignment.boolValue(variable) xor (variable == boolVar))
                        }
                        return (if (after) 2 else 0) - violationDegree(state, factorId)
                    }
                }
            }
        },
        relaxation = RelaxationProjectionProvider { factor ->
            val constants = (factor as? Linear)?.integerConstants
            if (factor !is Linear || constants == null) BuiltInRelaxationProjectionProvider.select(factor)
            else RelaxationProjection(RelaxationCapability.SOUND) { builder ->
                builder.linearRow(factor.op, factor.vars, LongArray(constants.coeffs.size) {
                    Math.multiplyExact(2L, constants.coeffs[it].toLong())
                }, Math.multiplyExact(2L, constants.bound))
            }
        },
    )

    private fun sourceJson(): JsonObject = buildJsonObject {
        put("schema", "finite-projection-controls-v1")
        put("boolCount", 2)
        put("domains", buildJsonArray { repeat(2) { add(JsonArray(listOf(-1, 0, 1).map(::JsonPrimitive))) } })
        put("clauses", buildJsonArray { add(JsonArray(listOf(0, 3).map(::JsonPrimitive))) })
        put("linear", buildJsonArray { add(buildJsonObject {
            put("coefficients", JsonArray(listOf("1", "1").map(::JsonPrimitive)))
            put("op", "GE")
            put("bound", "1")
        }) })
        put("objective", JsonArray(listOf("1", "2").map(::JsonPrimitive)))
    }

    private fun point(sample: Sample): JsonObject = buildJsonObject {
        put("bools", JsonArray((0 until sample.numBoolVars).map { JsonPrimitive(sample.boolValue(it)) }))
        put("ints", JsonArray((0 until sample.numIntVars).map { JsonPrimitive(sample.exactIntValue(it).toString()) }))
    }

    private fun provenance(factors: Int, rounds: Int, openings: Int, seed: Long, source: JsonObject): JsonObject =
        buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", hash(source.toString().toByteArray()))
            put("factors", factors)
            put("rounds", rounds)
            put("openings", openings)
            put("seed", seed)
            put("maxDecisions", 10000)
            put("optimizationTimeoutSeconds", 30)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("javaVersion", System.getProperty("java.version"))
            put("jvmArguments", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("classpath", buildJsonArray {
                for (path in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(path)
                    if (file.isFile) add(buildJsonObject {
                        put("name", file.name)
                        put("bytes", file.length())
                        put("sha256", hash(file.readBytes()))
                    })
                }
            })
        }

    private fun checkSource(out: File) {
        val checker = out.resolve("check_projection_evidence.py")
        ProjectionProviderProfile::class.java.getResourceAsStream("/source-evidence/check_projection_evidence.py")
            .use { checker.writeBytes(requireNotNull(it).readBytes()) }
        val process = ProcessBuilder("python3", checker.absolutePath, out.resolve("source.json").absolutePath,
            out.resolve("controls.json").absolutePath, out.resolve("source-check.json").absolutePath)
            .redirectErrorStream(true).redirectOutput(out.resolve("source-check.log")).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "source checker timed out" }
            check(process.exitValue() == 0) { "source checker rejected projection records" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
