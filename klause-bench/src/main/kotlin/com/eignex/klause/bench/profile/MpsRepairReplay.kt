package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal object MpsRepairReplay {
    private const val PIPELINE = "com.eignex.klause.solver.pipeline."

    fun inspect(
        request: JsonObject, source: LinearSourceProblem, numerical: Any, context: SourceProofContext,
        columns: IntArray, mapped: Map<SourceCoordinate, BigFraction>, scale: BigFraction, out: File,
    ) {
        val model = NumericalFrontierSnapshot.field(numerical, "model") as Problem
        val originalObjective = call(numerical, "getObjective") as LinearObjective
        val objective = if (source.objective?.sense == ObjectiveSense.MAXIMIZE) originalObjective.negated()
            else originalObjective
        val sourceValues = columns.map { column -> mapped.getValue(coordinate(column)) }
        val checked = checkNotNull(source.checkMpsWitness(sourceValues))
        val centerObjective = checkNotNull(checked.objective)
        val cutoff = objective.evaluateExact(sample(model, mapped, centerObjective * scale))
        val sets = request.getValue("repairSets").jsonArray.also { require(it.size in 1..128) }
        val allowance = request["repairMillis"]?.jsonPrimitive?.content?.toLong() ?: 1000L
        val operations = request["repairOperations"]?.jsonPrimitive?.content?.toInt() ?: 256
        val work = request["repairWork"]?.jsonPrimitive?.content?.toLong() ?: 1_000_000_000L
        require(allowance in 1..30_000 && operations in 1..4096 && work in 1..1_000_000_000L)
        val center = sample(model, mapped, centerObjective * scale)
        val sourceIntegers = columns.indices.filter { source.variables[it].integer }
            .map { Term.intVar(columns[it]) }
        val realBackings = columns.indices.filter { !source.variables[it].integer && Term.isInt(columns[it]) }
        val comparison = request["comparisonWitness"]?.jsonPrimitive?.content?.removePrefix("v ")?.trim()
            ?.split(Regex("\\s+"))?.associate {
                it.substringBefore('=') to MpsResidualObjective.rational(it.substringAfter('='))
            }?.let { values ->
                val point = source.variables.map { values.getValue(it.name) }
                checkNotNull(source.checkMpsWitness(point))
                point
            }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val proposals = proposals(sets, source, model, context, objective, center, cutoff, columns, allowance, out)
        for ((index, names) in sets.withIndex()) {
            val freedNames = if (names is JsonObject) proposals[index] ?: continue else if (names is JsonPrimitive) {
                require(names.content == "comparison-disagreement")
                val point = checkNotNull(comparison)
                source.variables.indices.filter { source.variables[it].integer && point[it] != sourceValues[it] }
                    .map { source.variables[it].name }
            } else names.jsonArray.map { it.jsonPrimitive.content }
            require(freedNames.distinct().size == freedNames.size)
            val freed = freedNames.map { name ->
                val id = source.variables.indexOfFirst { it.name == name }
                require(id >= 0 && source.variables[id].integer)
                val coordinate = coordinate(columns[id])
                require(coordinate.kind == SourceCoordinate.Kind.Integer)
                coordinate.id
            }.toSet()
            val pins = sourceIntegers.filter { it !in freed }.associateWith {
                mapped.getValue(SourceCoordinate(SourceCoordinate.Kind.Integer, it))
            }
            val conditionType = Class.forName(PIPELINE + "ExactNeighborhoodCondition")
            val condition = conditionType.constructors.single { it.parameterCount == 4 }
                .newInstance(emptyMap<Int, Boolean>(), pins, pins, cutoff)
            val stop = Cancellation.after(allowance.milliseconds)
            val params = TheoryParams(timeout = stop, sourceLpOperations = operations,
                sourceLpWorkLimit = work)
            val ownerType = Class.forName(PIPELINE + "JointLinearRepair")
            val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
            val allocationStart = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            val owner = ownerType.constructors.single { it.parameterCount == 8 }.newInstance(
                model, context, objective, params, policy, true, { center }, stop,
            )
            var pauses = 0L
            var attempt: Any
            var result: Any
            val stats: Any
            try {
                do {
                    attempt = checkNotNull(call(owner, "attempt", condition, Cancellation.Never))
                    result = checkNotNull(call(attempt, "getResult"))
                    if (result.javaClass.simpleName == "Paused") pauses++
                } while (result.javaClass.simpleName == "Paused" && !stop())
                stats = checkNotNull(call(owner, "getStats"))
            } finally {
                (owner as AutoCloseable).close()
            }
            val elapsed = System.nanoTime() - start
            val allocated = bean.getThreadAllocatedBytes(thread) - allocationStart
            val candidate = call(attempt, "getFeasiblePoint") as Sample?
            val values = candidate?.let { next -> columns.map { column ->
                if (Term.isInt(column)) BigFraction.of(next.exactIntValue(Term.intVar(column)),
                    BIG_ONE) else checkNotNull(next.exactReals)[Term.realVar(column)]
            } }
            val verified = values?.let { source.checkMpsWitness(it) }
            if (candidate != null) {
                checkNotNull(verified)
                check(pins.all { (id, value) -> BigFraction.of(candidate.exactIntValue(id),
                    BIG_ONE) == value })
                if (result.javaClass.simpleName == "Witness") check(objective.evaluateExact(candidate) < cutoff)
            }
            val reason = call(attempt, "getReason")?.toString() ?: if (stop()) "Timeout" else null
            val status = when (result.javaClass.simpleName) {
                "Witness" -> "improvement"
                "Refuted" -> "conditioned-closure"
                "Paused" -> if (stop()) "cancelled" else "pause"
                else -> when (reason) {
                    "BudgetExhausted" -> "budget-exhaustion"
                    "Timeout", "Cancelled" -> "cancelled"
                    "SearchExhausted" -> if (verified != null) "feasible-nonimprovement" else "conditioned-exhaustion"
                    else -> "unavailable"
                }
            }
            val report = buildJsonObject {
                put("owner", "mixed-residual")
                put("status", status)
                put("reason", reason)
                put("sourceScope", NumericalResidualProfile.json(context.scope))
                put("requestedFreed", JsonArray(freedNames.map(::JsonPrimitive)))
                put("effectiveFreedIntegers", JsonArray(freed.sorted().map(::JsonPrimitive)))
                put("freeSourceRealIntegerBackings", JsonArray(realBackings.map {
                    JsonPrimitive(source.variables[it].name)
                }))
                put("complementPins", buildJsonObject {
                    pins.forEach { (id, value) -> put(id.toString(), value.toString()) }
                })
                put("strictWorkingCutoff", cutoff.toString())
                put("sourceCenterObjective", checked.objective.toString())
                put("sourceCandidateObjective", verified?.objective?.toString())
                put("sourceCandidateExactlyFeasible", verified?.exact)
                put("fullSourceRows", true)
                put("freeContinuousResidual", true)
                put("sourceVerdictClaim", false)
                put("independentWitnessCheck", false)
                put("allowanceMillis", allowance)
                put("allowanceLpOperations", operations)
                put("allowanceLpWork", work)
                put("pauses", pauses)
                put("elapsedNs", elapsed)
                put("threadBytes", allocated)
                put("stats", NumericalResidualProfile.json(stats))
                put("conditionedBound", NumericalResidualProfile.json(call(attempt, "getBound")))
            }
            out.resolve("repair-$index.json").writeText(report.toString())
            values?.let { next -> out.resolve("repair-$index-witness.txt").writeText("v " +
                source.variables.indices.joinToString(" ") { "${source.variables[it].name}=${next[it]}" } + "\n") }
            println("mixed repair $index: $status")
        }
        if (request["ownerReplay"]?.jsonPrimitive?.content?.toBooleanStrict() == true) {
            MpsRepairOwners.inspect(request, source, model, context, objective, center, columns, scale, out)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun proposals(
        sets: JsonArray, source: LinearSourceProblem, model: Problem, context: SourceProofContext,
        objective: LinearObjective, center: Sample, cutoff: BigFraction, columns: IntArray,
        millis: Long, out: File,
    ): Map<Int, List<String>> {
        if (sets.none { it is JsonObject }) return emptyMap()
        val stop = Cancellation.after(millis.milliseconds)
        val params = TheoryParams(timeout = stop, sourceLpOperations = 256)
        val type = Class.forName(PIPELINE + "JointLinearRepair")
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val allocation = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val owner = type.constructors.single { it.parameterCount == 8 }.newInstance(
            model, context, objective, params, policy, true, { center }, stop)
        val result = linkedMapOf<Int, List<String>>()
        val working = linkedMapOf<Int, List<String>>()
        var prepared = false
        try {
            val prepare = type.getDeclaredMethod("prepare", Cancellation::class.java).apply { isAccessible = true }
            while (!stop() && call(owner, "getExhausted") != true) {
                if (prepare.invoke(owner, Cancellation.Never) == true) {
                    prepared = true
                    break
                }
            }
            if (prepared) {
                val selector = checkNotNull(NumericalFrontierSnapshot.field(owner, "selection"))
                val centers = List(model.numIntVars) { BigFraction.of(center.exactIntValue(it), BIG_ONE) } +
                    center.exactReals.orEmpty()
                for ((index, specification) in sets.withIndex()) {
                    if (specification !is JsonObject) continue
                    require(specification.keys == setOf("count", "round"))
                    val count = specification.getValue("count").jsonPrimitive.content.toInt()
                    val round = specification.getValue("round").jsonPrimitive.content.toLong()
                    require(count in 1..4096 && round in 0..1_000_000)
                    val condition = checkNotNull(call(selector, "propose", centers, center.bools, count,
                        round, BigFraction.ONE, cutoff))
                    val pins = call(condition, "getLower") as Map<Int, BigFraction>
                    check(pins == call(condition, "getUpper"))
                    working[index] = columns.indices.filter { column ->
                        Term.isInt(columns[column]) && Term.intVar(columns[column]) !in pins
                    }.map { source.variables[it].name }
                    result[index] = columns.indices.filter { column ->
                        source.variables[column].integer && Term.intVar(columns[column]) !in pins
                    }.map { source.variables[it].name }
                }
            }
            out.resolve("selection.json").writeText(buildJsonObject {
                put("prepared", prepared)
                put("sourceVerdictClaim", false)
                put("elapsedNs", System.nanoTime() - start)
                put("threadBytes", bean.getThreadAllocatedBytes(thread) - allocation)
                put("stats", NumericalResidualProfile.json(call(owner, "getStats")))
                put("freedSets", buildJsonObject { result.forEach { (index, names) ->
                    put(index.toString(), JsonArray(names.map(::JsonPrimitive)))
                } })
                put("workingFreedSets", buildJsonObject { working.forEach { (index, names) ->
                    put(index.toString(), JsonArray(names.map(::JsonPrimitive)))
                } })
                put("costIncludedInRepairAttempts", false)
            }.toString())
            if (!prepared) for ((index, specification) in sets.withIndex()) {
                if (specification is JsonObject) out.resolve("repair-$index.json").writeText(buildJsonObject {
                    put("owner", "mixed-residual")
                    put("status", if (stop()) "cancelled" else "unavailable")
                    put("reason", "proposal preparation unavailable")
                    put("sourceVerdictClaim", false)
                }.toString())
            }
        } finally {
            (owner as AutoCloseable).close()
        }
        return result
    }

    fun sample(model: Problem, mapped: Map<SourceCoordinate, BigFraction>, objective: BigFraction): Sample {
        require(model.numBoolVars == 0)
        val integers = List(model.numIntVars) { mapped.getValue(SourceCoordinate(SourceCoordinate.Kind.Integer, it)) }
        require(integers.all { it.den == BIG_ONE })
        val reals = List(model.numRealVars) { mapped[SourceCoordinate(SourceCoordinate.Kind.Real, it)] ?: objective }
        val finite = if (integers.all { it.num.fitsLong() })
            LongArray(integers.size) { integers[it].num.toLongExact() } else LongArray(0)
        return Sample(BooleanArray(0), finite,
            reals.map { it.toDouble() }.toDoubleArray(), exactReals = reals, exactInts = integers.map { it.num })
    }

    private fun coordinate(column: Int) = SourceCoordinate(
        if (Term.isInt(column)) SourceCoordinate.Kind.Integer else SourceCoordinate.Kind.Real,
        if (Term.isInt(column)) Term.intVar(column) else Term.realVar(column))

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
