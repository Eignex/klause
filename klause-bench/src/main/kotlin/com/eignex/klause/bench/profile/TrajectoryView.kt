package com.eignex.klause.bench.profile

import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.Objective
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val TRAJECTORY_COORDINATE_LIMIT = 8192
internal const val TRAJECTORY_FACTOR_LIMIT = 8192

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
        require(state.problem.numBoolVars + state.problem.numIntVars <= TRAJECTORY_COORDINATE_LIMIT)
        require(state.problem.numFactors <= TRAJECTORY_FACTOR_LIMIT)
        require(capturedOwners == null || capturedOwners.size == state.problem.numIntVars)
        require((0 until coordinates.boolCount).all { coordinates.bool(it) in -1 until state.problem.numBoolVars })
        require((0 until coordinates.intCount).all { coordinates.int(it) in -1 until state.problem.numIntVars })
        state.moveSink.setOwners(capturedOwners)
    }

    fun load(source: Sample): String? =
        loadSourceCenter(source, state, coordinates, reconstruct, synchronize)

    private fun projectedMove(sourceMove: Move): Move? = when (sourceMove) {
        is Move.BoolFlip -> coordinates.bool(sourceMove.varId).takeIf { it >= 0 }?.let { Move.BoolFlip(it) }
        is Move.IntSet -> coordinates.int(sourceMove.varId).takeIf { it >= 0 }
            ?.let { Move.IntSet(it, sourceMove.newValue) }
        else -> null
    }

    fun admits(sourceMove: Move): Boolean = projectedMove(sourceMove)?.let { candidateDecline(it) == null } == true

    private fun candidateDecline(move: Move): String? {
        val sink = proposalSink()
        when (move) {
            is Move.BoolFlip -> sink.addBoolFlip(move.varId)
            is Move.IntSet -> {
                if (move.newValue !in state.rootDomains[move.varId]) {
                    return "candidate-domain"
                }
                sink.addIntSet(move.varId, move.newValue)
            }
            else -> error("primitive expected")
        }
        return if (sink.list.isEmpty()) "candidate-filter" else null
    }

    fun proposalSink(): MoveSink = MoveSink(state.assumptions).apply {
        setInvariants(state.invariants)
        setOwners(capturedOwners)
    }

    fun probe(sourceMove: Move): JsonObject {
        val move = projectedMove(sourceMove) ?: return buildJsonObject { put("decline", "unmapped-primitive") }
        candidateDecline(move)?.let { decline -> return buildJsonObject { put("decline", decline) } }
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
        put("workingBooleans", JsonArray((0 until state.problem.numBoolVars).map {
            JsonPrimitive(state.assignment.boolValue(it))
        }))
        put("workingIntegers", JsonArray((0 until state.problem.numIntVars).map {
            JsonPrimitive(state.assignment.intValue(it))
        }))
        put("sourceBooleanSlots", JsonArray((0 until coordinates.boolCount).map {
            JsonPrimitive(coordinates.bool(it))
        }))
        put("sourceIntegerSlots", JsonArray((0 until coordinates.intCount).map { JsonPrimitive(coordinates.int(it)) }))
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
    val leftRandom = trajectoryRandomBoundary(left.state)
    val rightRandom = trajectoryRandomBoundary(right.state)
    val leftDecline = left.load(source)
    val rightDecline = right.load(source)
    check(leftRandom == trajectoryRandomBoundary(left.state) && rightRandom == trajectoryRandomBoundary(right.state))
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

internal fun trajectoryCandidates(
    source: Sample,
    views: List<TrajectoryView>,
    focus: Int = -1,
    priorityInts: IntArray = IntArray(0),
): List<Move> {
    val candidates = ArrayList<Move>()
    val integers = (priorityInts.asList() + listOf(focus - source.numBoolVars) +
        (0 until source.numIntVars)).filter { it in 0 until source.numIntVars }.distinct()
    fun integer(variable: Int) {
        val current = source.intValue(variable)
        val values = views.flatMap { view ->
            val target = view.coordinates.int(variable)
            if (target < 0) emptyList() else {
                val domain = view.state.rootDomains[target]
                buildList {
                    add(domain.min)
                    add(domain.max)
                    if (current > domain.min) add(domain.lower(current))
                    if (current < domain.max) add(domain.higher(current))
                }
            }
        }
        for (value in values.distinct()) {
            val move = Move.IntSet(variable, value)
            if (value != current && candidates.size < 32 && views.all { it.admits(move) }) candidates += move
        }
    }
    for (variable in priorityInts.distinct()) {
        if (variable in 0 until source.numIntVars && candidates.size < 32) integer(variable)
    }
    for (variable in 0 until source.numBoolVars) {
        if (candidates.size == 32) break
        val move = Move.BoolFlip(variable)
        if (views.all { it.admits(move) }) candidates += move
    }
    for (variable in integers) {
        if (candidates.size == 32) break
        if (variable !in priorityInts) integer(variable)
    }
    return candidates
}

internal fun trajectorySource(sample: Sample): JsonObject = buildJsonObject {
    put("bools", JsonArray(sample.bools.map(::JsonPrimitive)))
    put("ints", JsonArray(sample.ints.map(::JsonPrimitive)))
}

internal fun sameSource(left: Sample, right: Sample): Boolean =
    left.numRealVars == 0 && right.numRealVars == 0 &&
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
        val counter = ((values.getValue("addend").toLong() and 0xffffffffL) * XORWOW_INCREMENT_INVERSE) and
            0xffffffffL
        put("nextIntDrawBoundaryModulo2To32", counter)
    }
}

private const val XORWOW_INCREMENT_INVERSE = 945708813L
