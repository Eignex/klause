package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearSourceShape {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Use suite=ID problem=NAME sha256=HEX" }
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val expected = options.getValue("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(4 * 1024 * 1024 + 1) }
        require(bytes.size <= 4 * 1024 * 1024 && sha256(bytes) == expected)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val started = System.nanoTime()
        val admitted = SmtLibAdmission.parse(bytes.decodeToString(), cancellation = Cancellation.after(10.seconds))
        val parsed = requireNotNull((admitted as? SmtLibInput.Linear)?.problem) { "Linear admission did not complete" }
        val parsingNs = System.nanoTime() - started
        val model = parsed.model
        val exactRows = model.factors.filterIsInstance<ExactLinear>()
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
            put("factorKinds", buildJsonObject {
                model.factors.groupingBy { it::class.simpleName ?: "Factor" }.eachCount().toSortedMap()
                    .forEach { (kind, count) -> put(kind, count) }
            })
            put("exactRows", exactRows.size)
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
