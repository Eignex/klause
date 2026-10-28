package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal data class ComponentResidual(
    val identity: String,
    val clauses: List<List<Int>>,
    val weights: List<Long>,
    val constant: BigInteger,
    val fixed: Map<Int, Boolean>,
    val restrictionCutoff: BigInteger? = null,
) {
    fun capture(allowance: ComponentAllowance): ComponentPartition? {
        val size = clauses.sumOf { it.size.toLong() } + weights.size * 8L + clauses.size * 4L
        if (!allowance.retain(size * 8L) || !allowance.visit(size)) return null
        val partition = BooleanSeparators.partition(clauses, weights.size, fixed, { allowance.visit() },
            allowance::retain) ?: return null
        val owner = IntArray(weights.size) { -1 }
        val local = IntArray(weights.size)
        for ((i, variables) in partition.components.withIndex()) for ((j, v) in variables.withIndex()) {
            if (!allowance.visit()) return null
            owner[v] = i
            local[v] = j
        }
        val grouped = List(partition.components.size) { ArrayList<List<Int>>() }
        for (clause in partition.clauses) {
            if (!allowance.visit(clause.size.toLong() + 1L)) return null
            if (clause.isEmpty()) continue
            val component = owner[Lit.variable(clause.first())]
            check(clause.all { owner[Lit.variable(it)] == component })
            grouped[component] += clause.map { Lit.make(local[Lit.variable(it)], Lit.isPositive(it)) }
        }
        val scopes = partition.components.mapIndexed { i, variables ->
            ComponentCostScope(identity, fixed.toMap(), variables.toList(), grouped[i],
                variables.map { weights[it] }, restrictionCutoff)
        }.toMutableList()
        if (partition.conflict) {
            scopes += ComponentCostScope(identity, fixed.toMap(), emptyList(), listOf(emptyList()), emptyList(),
                restrictionCutoff)
        }
        val witness = MutableList(weights.size) { false }
        val active = partition.components.flatten().toSet()
        var offset = constant
        for (v in weights.indices) {
            if (!allowance.visit()) return null
            val value = fixed[v] ?: if (v !in active) weights[v] < 0L else false
            witness[v] = value
            if (v !in active && value) offset += BigInteger.valueOf(weights[v])
        }
        if (!allowance.visit(size)) return null
        return ComponentPartition(scopes.toList(), offset, witness.toList())
    }

    fun accepts(values: List<Boolean>): Boolean = values.size == weights.size &&
        fixed.all { (v, value) -> values[v] == value } && clauses.all { clause ->
            clause.any { Lit.evaluate(it, values[Lit.variable(it)]) }
        }

    fun cost(values: List<Boolean>): BigInteger = weights.indices.fold(constant) { sum, v ->
        if (values[v]) sum + BigInteger.valueOf(weights[v]) else sum
    }
}

internal data class ComponentPartition(
    val scopes: List<ComponentCostScope>,
    val offset: BigInteger,
    val baseline: List<Boolean>,
) {
    fun reconstruct(children: List<ComponentCostBounds>): List<Boolean>? {
        if (children.any { it.witness == null }) return null
        val witness = baseline.toMutableList()
        for (child in children) for ((i, v) in child.scope.variables.withIndex()) {
            witness[v] = checkNotNull(child.witness)[i]
        }
        return witness.toList()
    }
}

/** Bounded lab-only controls for exact cutoff composition and one Boolean separator decision. */
object ScopedComponentProfile {
    /** Run controls with `millis=2000 seed=3`, writing records under `$OUT`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("millis", "seed") })
        val millis = options["millis"]?.toLong() ?: 2_000L
        val seed = options["seed"]?.toLong() ?: 3L
        require(millis in 1L..30_000L)
        val out = File(System.getenv("OUT") ?: "build/profile-out").apply { mkdirs() }
        out.resolve("request.json").writeText(buildJsonObject {
            put("millis", millis)
            put("seed", seed)
            put("javaVersion", System.getProperty("java.version"))
            put("osArch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("maxMemory", Runtime.getRuntime().maxMemory())
            put("provenance", InstalledBuild.current.fingerprint)
        }.toString())
        val cycles = (0 until 2).flatMap { cycle -> (0 until 5).map { i ->
            listOf(Lit.make(cycle * 5 + i, true), Lit.make(cycle * 5 + (i + 1) % 5, true))
        } }
        val independent = ComponentResidual("two-five-cycles", cycles, List(10) { 1L }, BigInteger.ZERO, emptyMap())
        write(out, "independent-cutoff-6", composition(independent, BigInteger.valueOf(6), millis, seed))
        write(out, "independent-cutoff-7", composition(independent, BigInteger.valueOf(7), millis, seed))
        val bridge = independent.copy(identity = "two-five-cycles-bridge", clauses = cycles + listOf(listOf(20, 0, 10)),
            weights = List(11) { 1L })
        write(out, "bridge-cutoff-7", separator(bridge, BigInteger.valueOf(7), millis, seed))
        write(out, "natural-cutoff-7", composition(bridge, BigInteger.valueOf(7), millis, seed))
        write(out, "ordinary", ordinary(bridge, millis, seed, lp = false))
        write(out, "ordinary-lp", ordinary(bridge, millis, seed, lp = true))
        write(out, "ordinary-separator-order", ordinary(bridge, millis, seed, lp = false, biased = 10))
    }

    private fun write(out: File, label: String, record: JsonObject) {
        out.resolve("$label.json").writeText(record.toString())
        println("$label $record")
    }

    internal fun composition(
        residual: ComponentResidual,
        cutoff: BigInteger,
        millis: Long,
        seed: Long,
        allowance: ComponentAllowance = ComponentAllowance(Cancellation.after(millis.milliseconds)),
        lp: Boolean = false,
    ): JsonObject {
        val start = System.nanoTime()
        val partition = residual.capture(allowance)
            ?: return buildJsonObject { put("decline", "capture-allowance") }
        val captured = System.nanoTime()
        val result = ScopedComponentSearch.run(partition.scopes, partition.offset, cutoff, allowance, seed, lp)
        val composition = ScopedComponentBounds(partition.offset, partition.scopes)
        val witness = if (allowance.visit(residual.weights.size.toLong() + residual.clauses.sumOf { it.size.toLong() })) {
            partition.reconstruct(result.children)?.also { check(residual.accepts(it)) }
        } else null
        return buildJsonObject {
            put("cutoff", cutoff.toString())
            put("closed", result.closed)
            put("lower", composition.floor(result.children).toString())
            put("exact", composition.exact(result.children)?.toString())
            put("witnessObjective", witness?.let(residual::cost)?.toString())
            put("nativeClauseCheck", witness != null)
            put("sourceInfeasible", result.children.any { it.sourceInfeasible })
            put("components", JsonArray(partition.scopes.map { JsonPrimitive(it.variables.size) }))
            put("childLower", JsonArray(result.children.map { JsonPrimitive(it.lower.toString()) }))
            put("childUpper", JsonArray(result.children.map { JsonPrimitive(it.upper?.toString()) }))
            put("queries", result.queries)
            put("decisions", allowance.nodes.spent)
            put("visits", allowance.visits)
            put("cumulativeModeledBytes", allowance.bytes)
            put("stopped", allowance.stop())
            put("captureMs", (captured - start) / 1_000_000.0)
            put("buildMs", result.buildNanos / 1_000_000.0)
            put("searchMs", result.searchNanos / 1_000_000.0)
            put("checkMs", result.checkNanos / 1_000_000.0)
            put("totalMs", (System.nanoTime() - start) / 1_000_000.0)
        }
    }

    internal fun separator(residual: ComponentResidual, cutoff: BigInteger, millis: Long, seed: Long): JsonObject {
        val allowance = ComponentAllowance(Cancellation.after(millis.milliseconds))
        val start = System.nanoTime()
        val separator = BooleanSeparators.choose(residual.clauses, residual.weights.size, residual.fixed,
            { allowance.visit() }, retain = allowance::retain)
            ?: return buildJsonObject { put("decline", "no-bounded-separator") }
        val analysis = System.nanoTime() - start
        val branches = separator.branches.map { branch ->
            composition(residual.copy(fixed = residual.fixed + (separator.variable to branch.value)),
                cutoff, millis, seed, allowance)
        }
        return buildJsonObject {
            put("variable", separator.variable)
            put("analysisMs", analysis / 1_000_000.0)
            put("branches", JsonArray(branches))
            put("decisionValues", JsonArray(separator.branches.map { JsonPrimitive(it.value) }))
            put("totalMs", (System.nanoTime() - start) / 1_000_000.0)
            put("visits", allowance.visits)
            put("cumulativeModeledBytes", allowance.bytes)
        }
    }

    internal fun ordinary(
        residual: ComponentResidual,
        millis: Long,
        seed: Long,
        lp: Boolean,
        biased: Int? = null,
        cutoff: BigInteger? = null,
        pbCutoff: Boolean = false,
    ): JsonObject {
        val start = System.nanoTime()
        val allowance = ComponentAllowance(Cancellation.after(millis.milliseconds))
        val clauses = residual.clauses + residual.fixed.map { (v, value) -> listOf(Lit.make(v, value)) }
        val factors = clauses.map { Clause(it.toIntArray()) }.toMutableList<Factor>()
        if (cutoff != null) factors += PseudoBoolean(
            residual.weights.map { parseBigInt(it.toString()) }.toTypedArray(),
            IntArray(residual.weights.size) { Lit.make(it, true) }, PbOp.LE,
            parseBigInt((cutoff - BigInteger.ONE - residual.constant).toString()),
        )
        val model = Problem(residual.weights.size, 0, emptyArray(), factors.toTypedArray())
            .bake(cancellation = allowance.stop)
        val base = BacktrackPresets.satOptimized(randomSeed = seed, cancellation = allowance.stop)
        val decisions = ArrayList<JsonObject>()
        val result = BacktrackSolver(model).minimize(LinearObjective(residual.weights.toLongArray(),
            constant = residual.constant.longValueExact()), base.copy(nodeBudget = allowance.nodes,
            variableSelector = DecisionTrace(base.variableSelector, biased, decisions),
            pbObjectiveCutoff = pbCutoff,
            lpConfig = if (lp) LpConfig(LpEmphasis.DEFAULT) else null))
        val witness = result.assignment?.let { sample -> List(residual.weights.size) { sample.boolValue(it) } }
        witness?.let { check(residual.accepts(it)) }
        return buildJsonObject {
            put("authority", "captured-conditions-and-cutoff")
            put("result", result::class.simpleName)
            put("cutoff", cutoff?.toString())
            put("cutoffClosed", cutoff != null && result is MinimizeResult.Infeasible)
            put("pbObjectiveCutoff", pbCutoff)
            put("objective", witness?.let(residual::cost)?.toString())
            put("nativeClauseCheck", witness != null)
            put("decisions", allowance.nodes.spent)
            put("decisionTrace", JsonArray(decisions))
            put("totalMs", (System.nanoTime() - start) / 1_000_000.0)
        }
    }

    private class DecisionTrace(
        private val delegate: VariableSelector,
        private val biased: Int?,
        private val trace: MutableList<JsonObject>,
    ) : VariableSelector by delegate {
        override fun fresh(): VariableSelector = DecisionTrace(delegate.fresh(), biased, trace)

        override fun pick(session: PropagationSession, rng: Random): VarRef? {
            val choice = if (biased != null && session.needsBoolDecision(biased)) VarRef.Bool(biased)
                else delegate.pick(session, rng)
            if (trace.size < 32) trace += buildJsonObject {
                put("level", session.decisionLevel)
                put("variable", (choice as? VarRef.Bool)?.varId)
                put("biased", choice == biased?.let { VarRef.Bool(it) })
            }
            return choice
        }
    }
}
