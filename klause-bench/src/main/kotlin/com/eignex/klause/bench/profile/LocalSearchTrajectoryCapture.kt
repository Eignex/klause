package com.eignex.klause.bench.profile

import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.Objective
import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class TrajectoryView(
    val label: String,
    val state: LocalSearchState,
    val coordinates: VarRemap,
    val objective: Objective? = null,
    val reconstruct: (Sample) -> Sample = { it },
    val synchronize: () -> Unit = {},
    owners: IntArray? = state.seeding.ownerInt,
) {
    private val capturedOwners = owners?.copyOf()
    init {
        require(state.problem.numRealVars == 0)
        require(state.problem.numBoolVars + state.problem.numIntVars <= 1024)
        require(state.problem.numFactors <= 4096)
        require((0 until coordinates.boolCount).all { coordinates.bool(it) in -1 until state.problem.numBoolVars })
        require((0 until coordinates.intCount).all { coordinates.int(it) in -1 until state.problem.numIntVars })
    }

    fun load(source: Sample): String? {
        if (source.numBoolVars != coordinates.boolCount || source.numIntVars != coordinates.intCount) {
            return "source-dimensions"
        }
        for (id in 0 until state.problem.numBoolVars) state.assignment.setBool(id, false)
        for (id in state.rootDomains.indices) state.assignment.setInt(id, state.rootDomains[id].min)
        for (id in 0 until coordinates.boolCount) {
            val target = coordinates.bool(id)
            if (target >= 0) state.assignment.setBool(target, source.boolValue(id))
        }
        for (id in 0 until coordinates.intCount) {
            val target = coordinates.int(id)
            if (target < 0) continue
            val value = source.intValue(id)
            if (value !in state.rootDomains[target]) return "target-domain"
            state.assignment.setInt(target, value)
        }
        synchronize()
        state.recompute()
        var pinMismatch = false
        state.assumptions.forEachBool { id, value -> if (state.assignment.boolValue(id) != value) pinMismatch = true }
        state.assumptions.forEachInt { id, value -> if (state.assignment.intValue(id) != value) pinMismatch = true }
        if (pinMismatch) return "target-pin"
        return if (sameSource(source, reconstruct(state.assignment.snapshot()))) null else "source-round-trip"
    }

    fun probe(sourceMove: Move): JsonObject {
        val move = when (sourceMove) {
            is Move.BoolFlip -> coordinates.bool(sourceMove.varId).takeIf { it >= 0 }?.let { Move.BoolFlip(it) }
            is Move.IntSet -> coordinates.int(sourceMove.varId).takeIf { it >= 0 }
                ?.let { Move.IntSet(it, sourceMove.newValue) }
            else -> null
        } ?: return buildJsonObject { put("decline", "unmapped-primitive") }
        val sink = MoveSink(state.assumptions).apply {
            setInvariants(state.invariants)
            setOwners(capturedOwners)
        }
        when (move) {
            is Move.BoolFlip -> sink.addBoolFlip(move.varId)
            is Move.IntSet -> {
                if (move.newValue !in state.rootDomains[move.varId]) {
                    return buildJsonObject { put("decline", "candidate-domain") }
                }
                sink.addIntSet(move.varId, move.newValue)
            }
            else -> error("primitive expected")
        }
        if (sink.list.isEmpty()) return buildJsonObject { put("decline", "candidate-filter") }
        val boundary = trajectoryRandomBoundary(state)
        val inspection = state.inspectMove(move, objective)
        check(boundary == trajectoryRandomBoundary(state))
        val destination = reconstruct(inspection.assignment)
        val sourceEffect = when (sourceMove) {
            is Move.BoolFlip -> destination.boolValue(sourceMove.varId) !=
                reconstruct(state.assignment.snapshot()).boolValue(sourceMove.varId)
            is Move.IntSet -> destination.intValue(sourceMove.varId) == sourceMove.newValue
            else -> false
        }
        return buildJsonObject {
            if (!sourceEffect) put("decline", "candidate-source-effect")
            put("netDelta", inspection.netDelta)
            put("weightedNetDelta", inspection.weightedNetDelta)
            put("objectiveDelta", inspection.objectiveDelta.toString())
            put("degrees", JsonArray(inspection.factorDegrees.map(::JsonPrimitive)))
            put("source", trajectorySource(destination))
            put("probeCount", 1)
        }
    }

    fun snapshot(): JsonObject = buildJsonObject {
        put("source", trajectorySource(reconstruct(state.assignment.snapshot())))
        put("cost", state.cost)
        put("degrees", JsonArray(state.factorDegree.map(::JsonPrimitive)))
        put("violatedOrder", JsonArray(state.violated.toIntArray().map(::JsonPrimitive)))
        put("random", trajectoryRandomBoundary(state))
        put("weightsAllocated", state.weights.allocated)
        if (state.weights.allocated) put("weights", JsonArray(state.weights.factorWeights.map(::JsonPrimitive)))
    }
}

internal fun trajectoryPair(
    source: Sample,
    candidates: List<Move>,
    left: TrajectoryView,
    right: TrajectoryView,
): JsonObject {
    require(candidates.size <= 32)
    val started = System.nanoTime()
    val leftDecline = left.load(source)
    val rightDecline = right.load(source)
    return buildJsonObject {
        put("source", trajectorySource(source))
        put("left", left.label)
        put("right", right.label)
        put("sourceVerdictClaim", false)
        put("leftLoad", leftDecline ?: "paired")
        put("rightLoad", rightDecline ?: "paired")
        put("loadCount", 2)
        if (leftDecline == null && rightDecline == null) {
            val leftBefore = left.snapshot()
            val rightBefore = right.snapshot()
            put("leftState", leftBefore)
            put("rightState", rightBefore)
            put("candidates", JsonArray(candidates.map { move ->
                val a = left.probe(move)
                val b = right.probe(move)
                buildJsonObject {
                    put("move", move.toString())
                    put("left", a)
                    put("right", b)
                    put("pairedDestination", a["decline"] == null && b["decline"] == null &&
                        a["source"] != null && a["source"] == b["source"])
                }
            }))
            check(leftBefore == left.snapshot() && rightBefore == right.snapshot())
        }
        put("diagnosticNanos", System.nanoTime() - started)
    }
}

internal fun trajectorySource(sample: Sample): JsonObject = buildJsonObject {
    put("bools", JsonArray(sample.bools.map(::JsonPrimitive)))
    put("ints", JsonArray(sample.ints.map(::JsonPrimitive)))
}

internal fun sameSource(left: Sample, right: Sample): Boolean =
    left.numBoolVars == right.numBoolVars && left.numIntVars == right.numIntVars &&
        (0 until left.numBoolVars).all { left.boolValue(it) == right.boolValue(it) } &&
        (0 until left.numIntVars).all { left.intValue(it) == right.intValue(it) }

internal fun trajectoryRandomBoundary(state: LocalSearchState): JsonObject = buildJsonObject {
    put("implementation", state.rng.javaClass.name)
    val fields = state.rng.javaClass.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
    val values = fields.associate { field ->
        field.isAccessible = true
        field.name to field.getInt(state.rng)
    }
    put("fields", buildJsonObject { for ((name, value) in values) put(name, value) })
    val xorWow = values.keys == setOf("x", "y", "z", "w", "v", "addend")
    put("drawCounterSupported", xorWow)
    if (xorWow) {
        val modulus = BigInteger.ONE.shiftLeft(32)
        val inverse = BigInteger.valueOf(362437).modInverse(modulus)
        val counter = BigInteger.valueOf(values.getValue("addend").toLong() and 0xffffffffL)
            .multiply(inverse).mod(modulus).toLong()
        put("nextIntDrawBoundaryModulo2To32", counter)
    }
}
