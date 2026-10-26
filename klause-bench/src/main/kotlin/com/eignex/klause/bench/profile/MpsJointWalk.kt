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
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

internal object MpsJointWalk {
    fun inspect(request: JsonObject, source: LinearSourceProblem, numerical: Any, context: SourceProofContext,
        columns: IntArray, mapped: Map<SourceCoordinate, BigFraction>, scale: BigFraction, out: File,
    ) {
        val millis = request.getValue("jointWalkMillis").jsonPrimitive.content.toLong()
        require(millis in 1..60_000)
        val inputPass = request["sourceInputPass"]?.jsonPrimitive?.content?.toBooleanStrict() ?: false
        val seed = request["seed"]?.jsonPrimitive?.content?.toLong() ?: 1L
        val stop = Cancellation.after(millis.milliseconds)
        val model = NumericalFrontierSnapshot.field(numerical, "model") as Problem
        require(model.numBoolVars == 0)
        val originalObjective = call(numerical, "getObjective") as LinearObjective
        val objective = if (source.objective?.sense == ObjectiveSense.MAXIMIZE) originalObjective.negated()
            else originalObjective
        fun values(sample: Sample) = columns.map { column ->
            if (Term.isInt(column)) BigFraction.of(sample.exactIntValue(Term.intVar(column)), BIG_ONE)
            else checkNotNull(sample.exactReals)[Term.realVar(column)]
        }
        val initial = columns.map { column -> mapped.getValue(coordinate(column)) }
        val checked = checkNotNull(source.checkMpsWitness(initial, stop))
        var best = checkNotNull(checked.objective)
        val center = MpsRepairReplay.sample(model, mapped, best * scale)
        val inputs = columns.indices.filter { source.variables[it].integer }
            .map { Term.intVar(columns[it]) }.toIntArray()
        val params = TheoryParams(timeout = stop, randomSeed = seed, sourceLpOperations = 256)
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val allocation = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val owner = Class.forName("com.eignex.klause.solver.pipeline.JointLinearRepair").constructors
            .single { it.parameterCount == 9 }
            .newInstance(model, context, objective, params, policy, true, { center }, stop, inputs)
        val search = checkNotNull(call(owner, "search", false, seed, inputPass))
        var improvements = 0L
        out.resolve("incumbents.jsonl").bufferedWriter().use { writer ->
            val offer: (Sample, BigFraction?) -> Unit = { sample, _ ->
                val point = values(sample)
                val accepted = source.checkMpsWitness(point, stop)
                check(accepted != null || stop())
                accepted?.objective?.let { value ->
                    val improving = if (source.objective?.sense == ObjectiveSense.MAXIMIZE) value > best
                        else value < best
                    if (improving) {
                        best = value
                        improvements++
                        writer.appendLine(buildJsonObject {
                            put("objective", value.toString())
                            put("sourcePoint", JsonArray(point.map { JsonPrimitive(it.toString()) }))
                            put("elapsedNs", System.nanoTime() - start)
                            put("nativeSourceChecked", true)
                            put("sourceExactlyFeasible", accepted.exact)
                            put("stats", NumericalResidualProfile.json(call(owner, "getStats")))
                            put("sourceVerdictClaim", false)
                        }.toString())
                        writer.flush()
                        out.resolve("best-witness.txt").writeText("v " + source.variables.indices.joinToString(" ") {
                            "${source.variables[it].name}=${point[it]}"
                        } + "\n")
                    }
                }
            }
            try {
                while (!stop() && call(owner, "getExhausted") != true) {
                    if (call(search, "advance", stop, 1000L, offer) == true) break
                }
            } finally {
                (search as AutoCloseable).close()
                (owner as AutoCloseable).close()
            }
        }
        val stats = call(search, "getStats") as SolveStats
        val declined = NumericalFrontierSnapshot.field(owner, "declined") as Boolean
        out.resolve("joint-walk.json").writeText(buildJsonObject {
            put("sourceInputPass", inputPass)
            put("sourceSha256", request.getValue("sha256").jsonPrimitive.content)
            put("seed", seed)
            put("allowanceMillis", millis)
            put("allowanceSourceLpOperations", 256)
            put("allowanceSourceLpWork", params.sourceLpWorkLimit)
            put("allowanceSourceLpAllocation", params.sourceLpAllocationLimit)
            put("initialSourceObjective", checked.objective.toString())
            put("bestSourceObjective", best.toString())
            put("sourceCheckedImprovements", improvements)
            put("nativePreparationDeclined", declined)
            put("reason", when {
                stop() -> TerminationReason.Timeout.toString()
                declined -> TerminationReason.Unsupported.toString()
                else -> TerminationReason.BudgetExhausted.toString()
            })
            put("elapsedNs", System.nanoTime() - start)
            put("threadBytes", bean.getThreadAllocatedBytes(thread) - allocation)
            put("stats", NumericalResidualProfile.json(stats))
            put("globalProofClaim", false)
            put("independentWitnessCheck", false)
        }.toString())
        println("mixed walk inputPass=$inputPass: $improvements source-checked improvements")
    }

    private fun coordinate(column: Int) = SourceCoordinate(
        if (Term.isInt(column)) SourceCoordinate.Kind.Integer else SourceCoordinate.Kind.Real,
        if (Term.isInt(column)) Term.intVar(column) else Term.realVar(column))

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
