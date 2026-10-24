package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.MpsCompiled
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.unaryMinus
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object ContinuousCompletionReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val bytes = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(bytes.size <= 48 * 1024)
        val requests = Reports.json.parseToJsonElement(bytes.decodeToString()).jsonArray
        require(requests.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.json").writeBytes(bytes)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        requests.forEachIndexed { index, request ->
            replay(request.jsonObject, out.resolve("$index").apply { mkdirs() }, bean)
        }
    }

    private fun replay(input: JsonObject, out: File, bean: ThreadMXBean) {
        require(input.keys == setOf("job", "source", "sha256", "candidatesSha256", "summarySha256"))
        val job = input.getValue("job").jsonPrimitive.content.toLong().also { require(it > 0L) }
        val source = input.getValue("source").jsonPrimitive.content.toInt().also { require(it in 0..7) }
        val jobs = File(requireNotNull(System.getenv("JOB_DIR"))).canonicalFile.parentFile
        val folder = jobs.resolve("$job/cases/0/out/$source").canonicalFile
        require(folder.toPath().startsWith(jobs.toPath()))
        val bytes = read(folder.resolve("source.mps"), input.getValue("sha256"), 16 * 1024 * 1024)
        val trace = read(folder.resolve("candidates.jsonl"), input.getValue("candidatesSha256"), 8 * 1024 * 1024)
        val summaryBytes = read(folder.resolve("summary.json"), input.getValue("summarySha256"), 64 * 1024)
        val summary = Reports.json.parseToJsonElement(summaryBytes.decodeToString()).jsonObject
        val original = summary.getValue("input").jsonObject
        require(original.getValue("sha256") == input.getValue("sha256"))
        val exact = original.getValue("exact").jsonPrimitive.content.toBooleanStrict()
        val rows = trace.decodeToString().lineSequence().filter { it.isNotBlank() }
            .map { Reports.json.parseToJsonElement(it).jsonObject }.toList()
        require(rows.size in 1..16)
        val candidates = rows.mapIndexed { index, row ->
            require(row.getValue("index").jsonPrimitive.content.toInt() == index)
            decodeSample(row.getValue("pins").jsonObject)
        }
        val prepared = prepare(bytes, exact)
        require(candidates.all {
            it.numBoolVars == prepared.problem.numBoolVars && it.numIntVars == prepared.problem.numIntVars &&
                it.numRealVars == prepared.problem.numRealVars
        })
        out.resolve("source.mps").writeBytes(bytes)
        out.resolve("candidates.jsonl").writeBytes(trace)
        for (round in -1..2) {
            val arms = if (round % 2 == 0) listOf(true, false) else listOf(false, true)
            for (retained in arms) {
                val measured = measure(prepared, candidates, retained, bean)
                out.resolve("round-$round-retained-$retained.json").writeText(buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("captureRevision", summary["revision"] ?: JsonNull)
                    put("input", input)
                    put("sourcePolicy", original)
                    put("round", round)
                    put("warmup", round == -1)
                    put("retained", retained)
                    put("scope", "identical-candidate-prefix-owner-comparison")
                    put("sourceVerdictClaim", false)
                    put("inputProjectionExcluded", true)
                    put("captureInstrumentation", false)
                    put("instrumentation", "lab JFR, thread allocation counters, solve statistics and source checks")
                    put("sequenceDeadlineSeconds", 10)
                    put("sequenceLength", candidates.size)
                    put("floatingTieSeed", 0)
                    put("processors", Runtime.getRuntime().availableProcessors())
                    put("result", measured)
                }.toString())
            }
        }
    }

    private data class Prepared(
        val source: LinearSourceProblem,
        val compiled: MpsCompiled,
        val problem: BakedProblem,
        val objective: LinearObjective,
        val exact: Boolean,
    )

    private fun prepare(bytes: ByteArray, exact: Boolean): Prepared {
        val overrides = if (exact) SemanticOverrides(feasibilityTolerance = SourceTolerance()) else SemanticOverrides()
        val parsed = Mps.parse(bytes.decodeToString())
        val source = requireNotNull(parsed.toLinearSourceProblem(overrides))
        val compiled = parsed.toProblem(semanticOverrides = overrides)
        val objective = requireNotNull(compiled.objective).toLinearObjective().let {
            if (compiled.maximize) it.negated() else it
        }
        return Prepared(source, compiled, compiled.model.bake(), objective, exact)
    }

    private data class Query(
        val index: Int,
        val result: Completion,
        val stats: LpStats?,
        val elapsedNs: Long,
        val allocatedBytes: Long,
        val check: LinearSourceWitness?,
        val checkDeclined: Boolean,
        val checkNs: Long,
        val checkBytes: Long,
        val checkedImprovement: Boolean,
        val cancelled: Boolean,
    )

    private fun measure(
        prepared: Prepared,
        candidates: List<Sample>,
        retained: Boolean,
        bean: ThreadMXBean,
    ): JsonObject {
        val thread = Thread.currentThread().threadId()
        fun allocated() = bean.getThreadAllocatedBytes(thread)
        val queries = ArrayList<Query>()
        var lastStats: LpStats? = null
        var best: BigFraction? = null
        var closeNs = 0L
        var closeBytes = 0L
        val stop = Cancellation.after(10.seconds)
        val sequenceStart = System.nanoTime()
        val sequenceBytes = allocated()
        val accepts: (Sample) -> Boolean = { sample ->
            prepared.compiled.withinTolerance(sample.ints, sample.reals, stop)
        }
        val completion = ContinuousCompletionProfile.newCompletion(
            prepared.problem, prepared.objective, accepts, prepared.exact, retained,
        )
        ContinuousCompletionProfile.observe(completion) { _, stats -> lastStats = stats }
        val setupNs = System.nanoTime() - sequenceStart
        val setupBytes = allocated() - sequenceBytes
        AutoCloseable {
            val started = System.nanoTime()
            val before = allocated()
            try {
                (completion as AutoCloseable).close()
            } finally {
                closeNs = System.nanoTime() - started
                closeBytes = allocated() - before
            }
        }.use {
            for ((index, candidate) in candidates.withIndex()) {
                if (stop()) break
                lastStats = null
                val before = allocated()
                val started = System.nanoTime()
                val result = completion.complete(candidate, stop)
                val elapsed = System.nanoTime() - started
                val bytes = allocated() - before
                val cancelled = stop()
                val checkStop = stop or Cancellation.after(1.seconds)
                val checkStart = System.nanoTime()
                val checkBefore = allocated()
                val checked = (result as? Completion.Witness)?.sample?.let {
                    verifySource(prepared, it, checkStop)
                }
                val checkNs = System.nanoTime() - checkStart
                val checkBytes = allocated() - checkBefore
                val value = checked?.objective?.let { if (prepared.compiled.maximize) -it else it }
                val improves = value != null && (best == null || value < checkNotNull(best))
                if (improves) best = value
                queries += Query(index, result, lastStats, elapsed, bytes, checked,
                    checked == null && checkStop(), checkNs, checkBytes, improves, cancelled)
                if (stop()) break
            }
        }
        val elapsed = System.nanoTime() - sequenceStart
        val bytes = allocated() - sequenceBytes
        return buildJsonObject {
            put("elapsedNs", elapsed)
            put("allocatedBytes", bytes)
            put("completionSetupNs", setupNs)
            put("completionSetupBytes", setupBytes)
            put("closeNs", closeNs)
            put("closeBytes", closeBytes)
            put("queries", queries.size)
            put("completedCandidates", queries.count { it.result !is Completion.Undecided })
            put("checkedWitnesses", queries.count { it.check != null })
            put("checkedImprovements", queries.count { it.checkedImprovement })
            put("bestCheckedObjective", best?.let { if (prepared.compiled.maximize) -it else it }?.toString())
            put("cancelled", stop())
            put("candidateResults", JsonArray(queries.map(::query)))
        }
    }

    private fun verifySource(prepared: Prepared, sample: Sample, cancellation: Cancellation): LinearSourceWitness? {
        val values = prepared.compiled.columns.map { column ->
            if (column.real) {
                sample.exactReals?.get(column.id) ?: BigFraction.ofDouble(sample.approximateRealValue(column.id))
                    ?: return null
            } else BigFraction.ofLong(sample.intValue(column.id))
        }
        return prepared.source.checkMpsWitness(values, cancellation)
    }

    private fun query(value: Query): JsonElement = buildJsonObject {
        put("index", value.index)
        put("completion", value.result::class.simpleName)
        put("work", value.result.work)
        put("elapsedNs", value.elapsedNs)
        put("allocatedBytes", value.allocatedBytes)
        put("cancelled", value.cancelled)
        put("sourceCheckNs", value.checkNs)
        put("sourceCheckBytes", value.checkBytes)
        put("sourceCheck", when {
            value.result !is Completion.Witness -> "not-a-witness"
            value.checkDeclined -> "declined"
            value.check == null -> "rejected"
            else -> "accepted"
        })
        put("sourceExact", value.check?.exact?.let(::JsonPrimitive) ?: JsonNull)
        put("sourceObjective", value.check?.objective?.toString())
        put("checkedImprovement", value.checkedImprovement)
        put("lp", value.stats?.let(::stats) ?: JsonNull)
    }

    private fun stats(value: LpStats): JsonObject = buildJsonObject {
        val route = value.standaloneRoute.mergedWith(value.componentRoute)
        put("passes", value.standalonePasses.sum + value.componentPasses.sum)
        put("pivots", value.standalonePivots.sum + value.componentPivots.sum)
        put("floatingWork", value.standaloneWorkOps.sum + value.componentWorkOps.sum)
        put("warmStartAttempts", route.warmStartAttempts.sum)
        put("warmStartHits", route.warmStartHits.sum)
        put("initialRefactorizations", route.initialRefactorizations.sum)
        put("warmStartRefactorizations", route.warmStartRefactorizations.sum)
        put("exactPointWork", value.phases["EXACT_POINT_RECOVERY_STANDALONE"]?.work ?: 0L)
    }

    private fun read(path: File, expected: JsonElement, limit: Int): ByteArray {
        require(path.length() in 1..limit.toLong())
        return path.readBytes().also { require(sha256(it) == expected.jsonPrimitive.content) }
    }

    private fun decodeSample(input: JsonObject): Sample {
        val bools = input.getValue("bools").jsonArray
            .map { it.jsonPrimitive.content.toBooleanStrict() }.toBooleanArray()
        val ints = input.getValue("ints").jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray()
        val reals = input.getValue("reals").jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
        val exact = input["exactReals"]?.takeUnless { it == JsonNull }?.jsonArray?.map {
            val parts = it.jsonPrimitive.content.split('/')
            require(parts.size in 1..2)
            BigFraction.of(parseBigInt(parts[0]), parseBigInt(parts.getOrElse(1) { "1" }))
        }
        return Sample(bools, ints, reals, exactReals = exact)
    }
}
