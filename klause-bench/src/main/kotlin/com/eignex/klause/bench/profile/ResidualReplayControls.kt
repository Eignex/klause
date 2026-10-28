package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal class ResidualRun(
    val vars: IntArray,
    val result: MinimizeResult?,
    val buildNanos: Long,
    val solveNanos: Long,
)

/** Fresh child, joint, objective-ordered and ordinary LP controls over the same retained residual. */
internal object ResidualReplayControls {
    @Suppress("LongParameterList")
    fun solve(
        vars: IntArray,
        factors: List<Factor>,
        weights: LongArray,
        stop: Cancellation,
        seed: Long,
        control: (BacktrackParams) -> BacktrackParams = { it },
    ): ResidualRun {
        if (stop()) return ResidualRun(vars, null, 0L, 0L)
        val built = System.nanoTime()
        val inverse = IntArray(maxOf(weights.size, (vars.maxOrNull() ?: -1) + 1)) { -1 }
        vars.forEachIndexed { index, v -> inverse[v] = index }
        val remapping = VarRemap(inverse, IntArray(0))
        return try {
            val local = factors.map {
                if (stop()) throw CancellationException()
                it.remap(remapping)
            }
            val baked = Problem(vars.size, 0, emptyArray(), local.toTypedArray()).bake(stop)
            val objective = LinearObjective(LongArray(vars.size) { weights.getOrElse(vars[it]) { 0L } })
            val solving = System.nanoTime()
            val base = BacktrackPresets.satOptimized(randomSeed = seed, cancellation = stop)
            val result = BacktrackSolver(baked).minimize(objective, control(base))
            ResidualRun(vars, result, solving - built, System.nanoTime() - solving)
        } catch (_: CancellationException) {
            ResidualRun(vars, null, System.nanoTime() - built, 0L)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun replay(snapshot: ResidualSnapshot, enforcing: Boolean, millis: Long, seed: Long): JsonObject {
        require(millis in 0..60_000)
        val started = System.nanoTime()
        ResidualSnapshots.verify(snapshot)
        val factors = if (enforcing) {
            ResidualSnapshots.enforcingProblem(snapshot).factors.map {
                it.remap(VarRemap(snapshot.residualToPrepared.toIntArray(), IntArray(0)))
            }
        } else snapshot.residualClauses.map { Clause(it.toIntArray()) }
        val weights = snapshot.boolWeights.toLongArray()
        val components = (if (enforcing) snapshot.enforcingComponents else snapshot.sourceComponents).toMutableList()
        if (factors.any { it.boolVars.isEmpty() }) components += emptyList<Int>()
        val grouped = components.map { vars ->
            val members = vars.toSet()
            factors.filter { factor ->
                if (factor.boolVars.isEmpty()) vars.isEmpty() else factor.boolVars.first() in members
            }
        }
        val used = components.flatten().toSet()
        val isolated = snapshot.values.indices.filter { snapshot.values[it] == null && it !in used }
        val isolatedCost = isolated.fold(BigInteger.ZERO) { total, v ->
            if (weights[v] < 0) total + BigInteger.valueOf(weights[v]) else total
        }
        val fixed = BigInteger(snapshot.fixedObjective) + isolatedCost
        val witness = BooleanArray(weights.size) { snapshot.values[it] == true }
        for (v in isolated) witness[v] = weights[v] < 0L
        val preparedNanos = System.nanoTime() - started
        val childStop = Cancellation.after(millis.milliseconds)
        val children = components.indices.sortedBy { grouped[it].size }.map { i ->
            solve(components[i].toIntArray(), grouped[i], weights, childStop, seed)
        }
        val vars = used.sorted().toIntArray()
        val joint = solve(vars, factors, weights, Cancellation.after(millis.milliseconds), seed)
        val ordered = solve(vars, factors, weights, Cancellation.after(millis.milliseconds), seed) {
            it.copy(objectiveGuidedValues = true)
        }
        val lp = solve(vars, factors, weights, Cancellation.after(millis.milliseconds), seed) {
            it.copy(lpConfig = LpConfig(LpEmphasis.DEFAULT))
        }
        val optima = children.map { exactOptimum(it, weights) }
        val sum = if (optima.all { it != null }) {
            optima.fold(BigInteger.ZERO) { a, b -> a + requireNotNull(b) }
        } else null
        if (sum != null) {
            for (child in children) {
                val sample = requireNotNull(child.result?.assignment)
                child.vars.forEachIndexed { index, v -> witness[v] = sample.boolValue(index) }
            }
        }
        val witnessValues = witness.toList()
        val checked = sum != null && factors.all { satisfied(it, witnessValues) }
        check(sum == null || checked) { "composed residual witness violates retained factors" }
        val jointOptimum = exactOptimum(joint, weights)
        check(sum == null || jointOptimum == null || sum == jointOptimum) { "residual optima disagree" }
        return buildJsonObject {
            put("scope", if (enforcing) "all-enforcing-with-cutoff" else "source-relaxation-at-captured-condition")
            put("conditionIdentity", snapshot.conditionIdentity)
            put("originalSourceReplay", snapshot.originalSourceReplay)
            put("components", components.size)
            put("structuralPreparationNs", preparedNanos)
            put("children", JsonArray(children.map { record(it, weights) }))
            put("joint", record(joint, weights))
            put("jointOrdered", record(ordered, weights))
            put("jointLp", record(lp, weights))
            put("fixedAndIsolated", fixed.toString())
            put("composedOptimum", sum?.let { (it + fixed).toString() })
            put("jointOptimum", jointOptimum?.let { (it + fixed).toString() })
            put("compositionComplete", children.all { it.result is MinimizeResult.Optimal } ||
                children.any { it.result is MinimizeResult.Infeasible })
            put("childRefuted", children.any { it.result is MinimizeResult.Infeasible })
            put("jointRefuted", joint.result is MinimizeResult.Infeasible)
            put("nativeResidualWitnessChecked", checked)
            if (checked) put("preparedWitness", JsonArray(witness.map(::JsonPrimitive)))
            put("sourceVerdictClaim", false)
        }
    }

    fun satisfied(factor: Factor, values: List<Boolean>): Boolean = when (factor) {
        is Clause -> factor.literals.any { values[Lit.variable(it)] == Lit.isPositive(it) }
        is PseudoBoolean -> {
            require(factor.op == PbOp.GE)
            val sum = factor.literals.indices.fold(BigInteger.ZERO) { total, i ->
                if (values[Lit.variable(factor.literals[i])] == Lit.isPositive(factor.literals[i]))
                    total + BigInteger(factor.constants.exactCoeff(i).toString()) else total
            }
            sum >= BigInteger(factor.constants.exactBound.toString())
        }
        else -> error("unsupported residual witness factor")
    }

    private fun exactOptimum(run: ResidualRun, weights: LongArray): BigInteger? =
        (run.result as? MinimizeResult.Optimal)?.assignment?.let { sample ->
            run.vars.indices.fold(BigInteger.ZERO) { total, i ->
                if (sample.boolValue(i)) total + BigInteger.valueOf(weights[run.vars[i]]) else total
            }
        }

    private fun record(run: ResidualRun, weights: LongArray): JsonObject = buildJsonObject {
        put("verdict", run.result?.let { it::class.simpleName } ?: "NotRun")
        put("buildNs", run.buildNanos)
        put("solveNs", run.solveNanos)
        put("nodes", run.result?.stats?.search?.nodes?.sum ?: 0.0)
        put("exactOptimum", exactOptimum(run, weights)?.toString())
    }
}
