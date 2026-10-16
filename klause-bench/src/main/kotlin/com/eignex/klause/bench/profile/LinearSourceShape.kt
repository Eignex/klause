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
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength
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

    private data class PredicateKey(
        val terms: List<Pair<Int, BigFraction>>,
        val bound: BigFraction,
        val strict: Boolean,
    )

    private fun normalizedPredicates(rows: List<ExactLinear>, stop: Cancellation) = buildJsonObject {
        val classes = HashMap<PredicateKey, Int>()
        var entries = 0L
        var scanned = 0
        var eligible = 0
        var completed = 0
        var oriented = 0L
        var bothPolarities = 0
        var openClasses = 0
        var closedClasses = 0
        var scalarDeclined = 0
        var emptyDeclined = 0
        var interrupted = false
        var inputLimit = false
        for (row in rows) {
            stop.charge(row.size.toLong() + 1L)
            if (stop()) {
                interrupted = true
                break
            }
            if (row.size.toLong() + 1L > 65_536L - entries) {
                inputLimit = true
                break
            }
            entries += row.size + 1L
            scanned++
            if ((row.relation != LinearOp.LE && row.relation != LinearOp.GE) ||
                (0 until row.size).none { Term.isReal(row.ref(it)) }
            ) continue
            eligible++
            val constants = row.constants
            stop.charge(2L * row.size + 1L)
            if (stop()) {
                interrupted = true
                break
            }
            if (constants.exactBound.magnitudeBitLength() > 4096 ||
                (0 until row.size).any { constants.exactCoeff(it).magnitudeBitLength() > 4096 }
            ) {
                scalarDeclined++
                continue
            }
            stop.charge(row.size.toLong() * row.size + 1L)
            if (stop()) {
                interrupted = true
                break
            }
            val order = (0 until row.size).sortedBy(row::ref)
            val first = order.firstOrNull { constants.exactCoeff(it).signum() != 0 }
            if (first == null) {
                emptyDeclined++
                continue
            }
            val divisor = constants.exactCoeff(first)
            val divisorBits = divisor.magnitudeBitLength()
            val terms = ArrayList<Pair<Int, BigFraction>>(row.size)
            for (index in order) {
                val coefficient = constants.exactCoeff(index)
                val words = (coefficient.magnitudeBitLength().toLong() + divisorBits + 127L) / 64L
                stop.charge(4L * words + 1L)
                if (stop()) {
                    interrupted = true
                    break
                }
                if (coefficient.signum() != 0) terms += row.ref(index) to BigFraction.of(coefficient, divisor)
            }
            if (interrupted) break
            val words = (constants.exactBound.magnitudeBitLength().toLong() + divisorBits + 127L) / 64L
            stop.charge(4L * words + 1L)
            if (stop()) {
                interrupted = true
                break
            }
            val bound = BigFraction.of(constants.exactBound, divisor)
            val negative = (row.relation == LinearOp.GE) != (divisor.signum() < 0)
            val key = PredicateKey(terms, bound, if (negative) !row.strict else row.strict)
            stop.charge(terms.size.toLong() + 1L)
            if (stop()) {
                interrupted = true
                break
            }
            val previous = classes[key] ?: 0
            val next = previous or if (negative) 2 else 1
            classes[key] = next
            if (previous == 0) {
                if (key.strict) openClasses++ else closedClasses++
                oriented++
            } else if (next != previous) {
                bothPolarities++
                oriented++
            }
            completed++
            if (stop()) {
                interrupted = true
                break
            }
        }
        if (stop()) interrupted = true
        val complete = scanned == rows.size && completed == eligible &&
            !interrupted && !inputLimit && scalarDeclined == 0 && emptyDeclined == 0
        put("inputRows", rows.size)
        put("scannedRows", scanned)
        put("eligibleRows", eligible)
        put("completedRows", completed)
        put("complete", complete)
        put("prefixCountsOnly", !complete)
        put("interrupted", interrupted)
        put("inputLimit", inputLimit)
        put("scalarDeclinedRows", scalarDeclined)
        put("emptyDeclinedRows", emptyDeclined)
        put("inputEntries", entries)
        put("distinctScaledOrientedPredicates", oriented)
        put("distinctComplementPredicateClasses", classes.size)
        put("classesWithBothLiteralPolarities", bothPolarities)
        put("openCanonicalClasses", openClasses)
        put("closedCanonicalClasses", closedClasses)
        put("scope", "Activated compiled real inequalities; exact ratio/sign/strict complement classes")
        put("coordinateOrder", "Ascending resolved tagged Term references")
        put("limits", "65536 cumulative row/term entries;4096-bit integer operands;shared parse/analysis token")
        put("authority", "Diagnostic classes; not allocation savings, provider admission, implications or verdicts")
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
        val cancellation = Cancellation.after(10.seconds)
        val admitted = SmtLibAdmission.parse(bytes.decodeToString(), cancellation = cancellation)
        val parsed = requireNotNull((admitted as? SmtLibInput.Linear)?.problem) { "Linear admission did not complete" }
        val parsingNs = System.nanoTime() - started
        val model = parsed.model
        val exactRows = model.factors.filterIsInstance<ExactLinear>()
        val activatedRows = exactRows.filter { it.activator != LinearRow.ALWAYS }
        val normalized = normalizedPredicates(activatedRows, cancellation)
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
            put("exactRowTerms", exactRows.sumOf { it.size.toLong() })
            put("activatedExactRows", activatedRows.size)
            put("activatedExactRowTerms", activatedRows.sumOf { it.size.toLong() })
            put("rawExactPredicateEntries", model.numBoolVars.toLong() + model.numIntVars + model.numRealVars +
                activatedRows.sumOf { it.size.toLong() + 1L })
            put("entryScope", "Raw source coordinates and activated exact rows; not an internal admission trace")
            put("constantExactRows", exactRows.count { it.size == 0 })
            put("unconditionalExactRows", exactRows.count { it.activator == LinearRow.ALWAYS })
            put("distinctExactPredicatesIgnoringActivators", predicates.size)
            put("normalizedActivatedRealPredicates", normalized)
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
