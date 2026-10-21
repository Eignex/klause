package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearSourceShape {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (args.size == 1 && args[0].startsWith("batch64=")) {
            val bytes = Base64.getDecoder().decode(args[0].substringAfter('='))
            require(bytes.size <= 32 * 1024)
            val requests = Json.decodeFromString<List<List<String>>>(bytes.decodeToString())
            require(requests.size in 1..8)
            requests.forEachIndexed { index, request -> inspect(request.toTypedArray(), out.resolve("$index")) }
        } else inspect(args, out)
    }

    private fun inspect(args: Array<String>, out: File) {
        require(args.size == 3) { "Use suite=ID problem=NAME sha256=HEX" }
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val expected = options.getValue("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == expected)
        out.mkdirs()
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val started = System.nanoTime()
        val admitted = SmtLib.parse(bytes.decodeToString(), cancellation = Cancellation.after(10.seconds))
        val parsed = admitted
        val parsingNs = System.nanoTime() - started
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            parsed.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))
        val model = requireNotNull(invocation.preparedPlan?.model) { "source has no factor inspection view" }
        val exactRows = model.factors.filterIsInstance<ExactLinear>()
        val activatedRows = exactRows.filter { it.activator != LinearRow.ALWAYS }
        val realSelections = model.factors.filterIsInstance<RealElement>()
        val binarySelectionIndexes = realSelections.filter { it.indexOffset == 0 && it.size == 2 }
            .map { it.idx }.toSet()
        val selectorDefinitions = model.factors.filterIsInstance<ReifiedLinear>().mapNotNull { row ->
            val constants = row.integerConstants ?: return@mapNotNull null
            if (row.vars.size != 1 || constants.coeff(0) != 1L || constants.bound != 1L ||
                row.op !in setOf(LinearOp.EQ, LinearOp.NE)
            ) return@mapNotNull null
            val column = row.vars[0]
            if (column !in binarySelectionIndexes || !model.intBounds.hasLower(column) ||
                !model.intBounds.hasUpper(column) || model.intBounds.lower(column) != 0L ||
                model.intBounds.upper(column) != 1L
            ) return@mapNotNull null
            val guard = Lit.make(row.auxBoolVar, positive = row.op == LinearOp.EQ)
            column to guard
        }.groupBy({ it.first }, { it.second })
        val guardSelectors = selectorDefinitions.filterValues { it.size == 1 }.entries
            .groupBy({ it.value.single() }, { it.key })
        val predicates = HashSet<StructuralKey>()
        for (row in exactRows) {
            val constants = row.constants
            predicates += ExactLinear.of(
                IntArray(row.size, row::ref),
                List(row.size) { BigFraction.of(constants.exactCoeff(it), BIG_ONE) },
                row.relation, BigFraction.of(constants.exactBound, BIG_ONE), row.strict,
            ).structuralKey()
        }
        val shape = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", expected)
            put("sourceBytes", bytes.size)
            put("suite", options.getValue("suite"))
            put("problem", options.getValue("problem"))
            put("scope", "linear-source-shape-only")
            put("solverExecuted", false)
            put("sourceVerdictClaim", false)
            put("parsingNs", parsingNs)
            put("sourceBooleanNames", parsed.boolVarNames.size)
            put("sourceIntegerNames", parsed.intVarNames.size)
            put("sourceRealNames", parsed.realVarNames.size)
            put("compiledBooleans", model.numBoolVars)
            put("compiledIntegers", model.numIntVars)
            put("compiledReals", model.numRealVars)
            put("compiledFactors", model.factors.size)
            put("realSelections", realSelections.size)
            put("binaryRealSelectionIndexes", binarySelectionIndexes.size)
            put("singleGuardBinarySelectionIndexes", guardSelectors.values.sumOf { it.size })
            put("distinctBinarySelectionGuards", guardSelectors.size)
            put("duplicateBinarySelectionIndexes", guardSelectors.values.sumOf { it.size - 1 })
            put("maxBinarySelectionIndexesPerGuard", guardSelectors.values.maxOfOrNull { it.size } ?: 0)
            put("binarySelectionGuardFanoutHistogram", buildJsonObject {
                guardSelectors.values.groupingBy { it.size }.eachCount().toSortedMap()
                    .forEach { (fanout, count) -> put(fanout.toString(), count) }
            })
            put("selectionEquivalence",
                "Prepared binary RealElement indexes in 0..1 with one unit reified EQ/NE definition at 1; same signed guard only")
            put("factorKinds", buildJsonObject {
                model.factors.groupingBy { it::class.simpleName ?: "Factor" }.eachCount().toSortedMap()
                    .forEach { (kind, count) -> put(kind, count) }
            })
            put("exactRows", exactRows.size)
            put("exactRowTerms", exactRows.sumOf { it.size.toLong() })
            put("activatedExactRows", activatedRows.size)
            put("activatedExactRowTerms", activatedRows.sumOf { it.size.toLong() })
            put("rawExactPredicateEntries", model.numBoolVars.toLong() + model.numIntVars + model.numRealVars +
                activatedRows.sumOf { it.size.toLong() + 1L })
            put("entryScope", "Raw source coordinates and activated exact rows; not an internal admission trace")
            put("constantExactRows", exactRows.count { it.size == 0 })
            put("unconditionalExactRows", exactRows.count { it.activator == LinearRow.ALWAYS })
            put("distinctExactPredicatesIgnoringActivators", predicates.size)
            put("predicateEquivalence",
                "ExactLinear structural identity after removing activators; no sign or implication reasoning")
            put("javaVersion", System.getProperty("java.version"))
            put("jvmOptions", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
        }
        out.resolve("shape.json").writeText(shape.toString() + "\n")
        println(shape)
    }
}
