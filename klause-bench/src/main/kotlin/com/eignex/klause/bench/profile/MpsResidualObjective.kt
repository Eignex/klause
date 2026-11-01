package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object MpsResidualObjective {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("batch64="))
        val requests = Json.parseToJsonElement(Base64.getDecoder().decode(args[0].substringAfter('='))
            .decodeToString()).jsonArray
        require(requests.size in 1..32)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        requests.forEachIndexed { index, request -> inspect(request.jsonObject,
            out.resolve(index.toString()).apply { mkdirs() }) }
    }

    @Suppress("UNCHECKED_CAST")
    fun inspect(request: JsonObject, out: File) {
        fun option(key: String) = request.getValue(key).jsonPrimitive.content
        val bytes = sourceBytes(request)
        out.resolve("source.mps").writeBytes(bytes)
        out.resolve("request.json").writeText(request.toString())
        val stop = Cancellation.after(20.seconds)
        val source = checkNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        val witness = option("witness").removePrefix("v ").trim().split(Regex("\\s+"))
            .associate { it.substringBefore('=') to rational(it.substringAfter('=')) }
        val values = source.variables.map { witness.getValue(it.name) }
        val original = checkNotNull(source.checkMpsWitness(values, stop))
        val sourceObjective = checkNotNull(source.objective)
        val constant = BigFraction.of(sourceObjective.constant.numerator, sourceObjective.constant.denominator)
        val discreteCost = sourceObjective.terms.filter { source.variables[it.column].integer }
            .fold(BigFraction.ZERO) { cost, term ->
                cost + BigFraction.of(term.coefficient.numerator, term.coefficient.denominator) * values[term.column]
            }
        val plan = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop,
            linearTraversal = LinearTraversal.NUMERICAL))
        val execution = checkNotNull(NumericalFrontierSnapshot.field(plan.route, "execution"))
        val numerical = checkNotNull(NumericalFrontierSnapshot.field(execution, "request"))
        val context = numerical.javaClass.declaredMethods.single {
            it.name == "authorityContext" && it.parameterCount == 2
        }.apply { isAccessible = true }.invoke(numerical, checkNotNull(call(numerical, "getObjective")), stop)
            as SourceProofContext
        val linear = checkNotNull(NumericalFrontierSnapshot.field(plan, "linearSource"))
        val view = checkNotNull(NumericalFrontierSnapshot.field(linear, "view"))
        val columns = call(view, "getColumns") as IntArray
        val scale = call(view, "getObjectiveScale") as BigFraction
        val coordinates = context.coordinates.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val mapped = columns.indices.associate { id ->
            val column = columns[id]
            SourceCoordinate(if (Term.isInt(column)) SourceCoordinate.Kind.Integer else SourceCoordinate.Kind.Real,
                if (Term.isInt(column)) Term.intVar(column) else Term.realVar(column)) to values[id]
        }
        val point = coordinates.map { mapped[it] ?: (checkNotNull(original.objective) * scale) }
        if ("jointWalkMillis" in request) {
            MpsJointWalk.inspect(request, source, numerical, context, columns, mapped, scale, out)
            return
        }
        if ("repairSets" in request) {
            MpsRepairReplay.inspect(request, source, numerical, context, columns, mapped, scale, out)
            return
        }
        val queryType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthorityQuery")
        val companion = queryType.getField("Companion").get(null)
        val query = checkNotNull(call(companion, "prepare", context, null, stop, 100_000,
            { _: Long -> }, null, emptyList<SourceEvidence>(), { _: Any, _: String, _: Long -> }, true, false))
        check(call(query, "getCoordinates") == coordinates)
        val budget = Class.forName("com.eignex.klause.lp.SourceLpBudget").getConstructor().newInstance()
        val policy = Class.forName("com.eignex.klause.lp.SparseSourceLpPolicy").getConstructor().newInstance()
        val completionType = Class.forName("com.eignex.klause.lp.optimization.LinearResidualCompletion")
        val completion = completionType.constructors.single { it.parameterCount == 3 }.newInstance(budget, policy, null)
        val stages = linkedMapOf<String, Long>()
        val observe: (String, Long) -> Unit = { key, count -> stages[key] = (stages[key] ?: 0L) + count }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val allocated = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        val refined = try {
            val complete: (Any, List<BigFraction>, Cancellation) -> Any? = { rows, costs, token ->
                call(completion, "complete", rows, costs.size, token, observe, costs, { null })
            }
            Class.forName("com.eignex.klause.lp.optimization.LinearPrimalRecoveryKt").methods
                .single { it.name == "refineLinearPrimal" }.invoke(null, coordinates, point,
                    call(query, "getRows"), call(query, "getInactive"), call(query, "getCosts"), stop,
                    complete, observe) as List<BigFraction>?
        } finally {
            (completion as AutoCloseable).close()
        }
        val elapsed = System.nanoTime() - started
        val allocation = bean.getThreadAllocatedBytes(thread) - allocated
        val newValues = refined?.let { next -> columns.indices.map { id ->
            next[coordinates.indexOf(mapped.keys.elementAt(id))]
        } }
        val checked = newValues?.let { source.checkMpsWitness(it, stop) }
        val pins = coordinates.indices.filter { coordinates[it].kind != SourceCoordinate.Kind.Real }
            .associate { coordinates[it] to point[it] }
        val fixed = SourceProofContext(context.scope.copy(condition = SourceCondition(lower = pins, upper = pins)),
            context.coordinates, context.premises, context.objective, context.objectiveConstant,
            semantics = context.semantics)
        val authorityType = Class.forName("com.eignex.klause.lp.optimization.LinearAuthority")
        val authority = authorityType.constructors.single { it.parameterCount == 4 }
            .newInstance(budget, { _: Any -> }, true, policy)
        val bound = try {
            val result = call(authority, "advance", fixed, stop, false)
            result?.let { call(it, "getBound") as SourceEvidence? }
        } finally {
            (authority as AutoCloseable).close()
        }
        val result = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(bytes))
            put("originalSourceObjective", original.objective.toString())
            put("originalSourceExactlyFeasible", original.exact)
            put("sourceSemantics", NumericalResidualProfile.json(source.semantics))
            put("sourceObjectiveConstant", constant.toString())
            put("fixedDiscreteCost", discreteCost.toString())
            put("originalContinuousCost", (checkNotNull(original.objective) - constant - discreteCost).toString())
            put("objectiveScale", scale.toString())
            put("refinedSourceObjective", checked?.objective?.toString())
            put("refinedContinuousCost", checked?.objective?.let { (it - constant - discreteCost).toString() })
            put("nativeProposal", refined != null)
            put("originalSourceChecked", checked != null)
            put("refinedSourceExactlyFeasible", checked?.exact)
            put("independentWitnessCheck", false)
            put("fixedPinNominalBound", bound?.let {
                (((it.claim as SourceClaim.Bound).value as SourceValue.Rational).value * scale.reciprocal()).toString()
            })
            put("elapsedNs", elapsed)
            put("threadBytes", allocation)
            put("stages", NumericalResidualProfile.json(stages))
            put("budget", NumericalResidualProfile.json(budget))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
        }
        Reports.writeJson(out.resolve("result.json").path, result)
        newValues?.let { next -> out.resolve("refined-witness.txt").writeText("v " +
            source.variables.indices.joinToString(" ") { "${source.variables[it].name}=${next[it]}" } + "\n") }
        println(result)
    }

    fun sourceBytes(request: JsonObject): ByteArray {
        fun option(key: String) = request.getValue(key).jsonPrimitive.content
        val bytes = request["sourceGzip64"]?.jsonPrimitive?.content?.let { encoded ->
            GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use {
                it.readNBytes(16 * 1024 * 1024 + 1)
            }
        } ?: run {
            val selected = Catalog.uncapped(option("suite")).problems.single { it.name == option("problem") }
            CorpusFiles.open(CorpusFetcher.resolve(selected.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == option("sha256"))
        return bytes
    }

    fun rational(text: String): BigFraction {
        val parts = text.split('/')
        if (parts.size == 2) return BigFraction.of(parts[0].toBigInteger(), parts[1].toBigInteger())
        val value = BigDecimal(text)
        return if (value.scale() < 0) BigFraction.of(value.toBigIntegerExact(), BigInteger.ONE) else
            BigFraction.of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
    }

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
