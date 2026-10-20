package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks DIMACS, WCNF and polynomial OPB/WBO records against original source bytes with exact
 * arithmetic. The parser and evaluator are independent of the solver's own format readers, so a
 * shared parsing defect cannot vouch for itself. Proof claims go to a [Prover].
 */
internal object BooleanSourceChecker {
    private const val USAGE =
        "usage: verify-boolean cases=<json[.gz]> source-root=<dir> output=<file> [records-root=<dir>]"
    private val OPTIONS = setOf("cases", "source-root", "records-root", "output")
    private val FORMATS = setOf("DIMACS", "WCNF", "OPB")
    private const val FEASIBLE = "source feasible"

    private val WHITESPACE = Regex("\\s+")
    private val SOURCE_HASH = Regex("[0-9a-f]{64}")
    private val BITSTRING = Regex("[01]+")
    private val OPB_COORDINATE = Regex("-?x[1-9]\\d*")
    private val CNF_COORDINATE = Regex("-?[1-9]\\d*")
    private val INTEGER = Regex("[+-]?\\d+")
    private val EXACT_BOUND = Regex("-?\\d+")
    private val OPB_LITERAL = Regex("~?x[1-9]\\d*")
    private val OPB_HEADER = Regex("#variable=\\s*(\\d+)")
    private val SOFT_HEADER = Regex("soft:\\s*([+-]?\\d+)?\\s*")
    private val SOFT_WEIGHT = Regex("\\[([+]?\\d+)\\]\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
    private val RELATION_ROW = Regex("(.*?)\\s*(>=|<=|=)\\s*([+-]?\\d+)\\s*", RegexOption.DOT_MATCHES_ALL)

    enum class Relation(val symbol: String) {
        AT_LEAST(">="),
        AT_MOST("<="),
        EQUAL("="),
        ;

        fun holds(value: BigInteger, bound: BigInteger): Boolean = when (this) {
            AT_LEAST -> value >= bound
            AT_MOST -> value <= bound
            EQUAL -> value == bound
        }
    }

    data class Literal(val index: Int, val negated: Boolean)

    /** A coefficient times the conjunction of its [product] literals. */
    data class Term(val coefficient: BigInteger, val product: List<Literal>)

    data class Row(val terms: List<Term>, val relation: Relation, val rhs: BigInteger, val softWeight: BigInteger?)

    /** A [weight] of null marks a hard clause, including weights at or above the WCNF top. */
    class Clause(val weight: BigInteger?, val literals: IntArray)

    sealed interface Source {
        val size: Int
    }

    class Cnf(override val size: Int, val clauses: List<Clause>, val weighted: Boolean) : Source

    class Opb(
        override val size: Int,
        val objective: List<Term>?,
        val rows: List<Row>,
        val softRows: List<Row>,
        val top: BigInteger?,
    ) : Source {
        val optimizing: Boolean get() = objective != null || softRows.isNotEmpty()
    }

    /** Whether [source] admits a point satisfying every hard relation with cost strictly below [costBelow]. */
    data class Query(val source: Source, val costBelow: BigInteger?)

    sealed interface Answer {
        data object Sat : Answer

        data object Unsat : Answer

        data class Unknown(val reason: String) : Answer
    }

    interface Prover {
        /** Identity of the decision procedure, recorded with every answer it settles. */
        val identity: Map<String, String>

        fun decide(query: Query): Answer
    }

    /** [violation] names the first hard relation the point breaks; [cost] is null on satisfaction sources. */
    data class Outcome(val violation: String?, val cost: BigInteger?)

    data class Check(
        val reportedProven: JsonElement,
        val witness: String,
        val claim: String? = null,
        val objective: BigInteger? = null,
        val proof: String? = null,
        val checkedLowerBound: BigInteger? = null,
        val boundCheck: String? = null,
        val reason: String? = null,
        val proofChecker: Map<String, String>? = null,
    ) {
        fun toJson(): JsonObject = JsonObject(
            buildMap<String, JsonElement> {
                put("sourceHashVerified", JsonPrimitive(true))
                put("reportedProven", reportedProven)
                put("witness", JsonPrimitive(witness))
                claim?.let { put("claim", JsonPrimitive(it)) }
                if (witness == FEASIBLE) put("objective", JsonPrimitive(objective))
                proof?.let { put("proof", JsonPrimitive(it)) }
                checkedLowerBound?.let { put("checkedLowerBound", JsonPrimitive(it)) }
                boundCheck?.let { put("boundCheck", JsonPrimitive(it)) }
                reason?.let { put("reason", JsonPrimitive(it)) }
                proofChecker?.let { identity ->
                    put("proofChecker", JsonObject(identity.mapValues { JsonPrimitive(it.value) }.toSortedMap()))
                }
            }.toSortedMap(),
        )
    }

    data class CaseCheck(val index: JsonElement, val check: Check? = null, val error: String? = null) {
        fun toJson(): JsonObject = JsonObject(
            buildMap<String, JsonElement> {
                put("index", index)
                check?.let { put("check", it.toJson()) }
                error?.let { put("error", JsonPrimitive(it)) }
            }.toSortedMap(),
        )
    }

    /** Runs `cases=… source-root=… output=… [records-root=…]` and returns the process exit code. */
    fun run(args: List<String>): Int {
        val fields = args.associate { argument ->
            require('=' in argument) { USAGE }
            argument.substringBefore('=') to argument.substringAfter('=')
        }
        require(fields.keys.all { it in OPTIONS }) { USAGE }
        val sourceRoot = File(requireNotNull(fields["source-root"]) { USAGE })
        val output = File(requireNotNull(fields["output"]) { USAGE })
        val recordsRoot = fields["records-root"]?.let(::File)
        val cases = readCases(File(requireNotNull(fields["cases"]) { USAGE })).map { case ->
            if (recordsRoot == null) case else {
                val record = File(recordsRoot, "cases/${case.string("index")}/record.json")
                JsonObject(case + ("record" to Json.parseToJsonElement(record.readText()).jsonObject))
            }
        }
        val checks = Z3BooleanProver().use { prover -> checkCases(cases, prover) { source(it, sourceRoot) } }
        output.parentFile?.mkdirs()
        val report = JsonArray(checks.map { it.toJson() })
        output.writeText(Reports.json.encodeToString(JsonElement.serializer(), report) + "\n")
        return if (checks.any { it.error != null }) 1 else 0
    }

    /** Reads a JSON case array, gzip-compressed when the name ends in `.gz`. */
    fun readCases(file: File): List<JsonObject> {
        require(!file.name.endsWith(".xz")) { "xz case files are unsupported; decompress them first" }
        val bytes = file.inputStream().use { input ->
            if (file.name.endsWith(".gz")) GZIPInputStream(input).use { it.readBytes() } else input.readBytes()
        }
        return Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonArray
            .map { it.jsonObject }
    }

    /** The original source named by the record's model hash, after verifying that hash. */
    fun source(record: JsonObject, sourceRoot: File): String {
        val digest = record.obj("sourceHashes").string("model")
        require(SOURCE_HASH.matches(digest)) { "invalid source hash" }
        val raw = File(sourceRoot, "$digest.txt").readBytes()
        require(sha256(raw) == digest) { "source hash mismatch" }
        return raw.decodeToString(throwOnInvalidSequence = true)
    }

    /** Checks each Boolean-format case, reporting failures per case. Identical claims are checked once. */
    fun checkCases(cases: List<JsonObject>, prover: Prover, sourceOf: (JsonObject) -> String): List<CaseCheck> {
        val checks = ArrayList<CaseCheck>()
        val cache = HashMap<List<String?>, Check>()
        for (case in cases) {
            val format = case.obj("problem").string("format")
            if (format !in FORMATS) continue
            val index = case["index"] ?: JsonNull
            checks += runCatching {
                require(case["status"].present()?.jsonPrimitive?.content == "DONE") { "unfinished case" }
                val record = case.obj("record")
                val key = listOf(
                    format,
                    record.obj("sourceHashes").string("model"),
                    record["feasible"].present()?.toString(),
                    record["proven"].present()?.toString(),
                    record["finalWitness"].present()?.toString(),
                    reportedObjective(record)?.toString(),
                    record["stats"].present()?.jsonObject?.get("coreGuidedLowerBound").present()?.toString(),
                )
                CaseCheck(index, cache.getOrPut(key) { checkCase(case, sourceOf(record), prover) })
            }.getOrElse { failure ->
                if (failure is Error) throw failure
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                CaseCheck(index, error = "${failure::class.simpleName}: ${failure.message}")
            }
        }
        return checks
    }

    /** Checks one case's witness, objective, core floor and proof claim against the original [text]. */
    fun checkCase(case: JsonObject, text: String, prover: Prover): Check {
        val record = case.obj("record")
        val format = case.obj("problem").string("format")
        val lower = record["stats"].present()?.jsonObject?.get("coreGuidedLowerBound").present()
            ?.jsonPrimitive?.content?.let {
                require(EXACT_BOUND.matches(it)) { "invalid exact core bound" }
                BigInteger(it)
            }
        val feasible = record["feasible"].present()?.jsonPrimitive?.boolean
        val proven = record["proven"].present()?.jsonPrimitive?.boolean == true
        var result = Check(reportedProven = record["proven"] ?: JsonNull, witness = "absent")
        if (feasible == null) {
            require(!proven) { "proof claimed without a verdict" }
            result = result.copy(claim = "none")
            if (lower == null) return result
        }
        val weighted = format == "WCNF"
        val opb = format == "OPB"
        if (weighted || opb) {
            require(record["maximize"].present()?.jsonPrimitive?.boolean != true) {
                "source objective orientation differs"
            }
        }
        val source = if (opb) parseOpb(text) else parseCnf(text, weighted)
        if (lower != null) {
            require(weighted || (source is Opb && source.optimizing)) { "bound on a satisfaction source" }
        }
        if (feasible == true) {
            val witness = requireNotNull(record["finalWitness"].present()) { "missing source witness" }
            val outcome = evaluate(source, assignment(witness.jsonPrimitive.content, source.size, opb, weighted))
            require(outcome.violation == null) { outcome.violation.orEmpty() }
            val cost = outcome.cost
            if (cost != null) {
                val reported = integerObjective(record)
                require(cost == reported) { "source objective mismatch: source=$cost, reported=$reported" }
                require(lower == null || lower <= cost) { "source witness refutes core bound" }
            } else {
                require(reportedObjective(record) == null) { "source has no objective" }
            }
            result = result.copy(witness = FEASIBLE, objective = cost)
            if (!proven && lower == null) return result
            if (cost == null) return result.copy(proof = "source satisfaction")
        } else if (feasible == false) {
            require(proven) { "refutation claimed without proof" }
            if (source is Cnf && !source.weighted && pigeonhole(source.clauses)) {
                return result.copy(proof = "source pigeonhole refutation")
            }
        }
        val costBelow = when {
            feasible == true && proven -> integerObjective(record)
            lower != null && feasible != false -> lower
            else -> null
        }
        return when (val answer = prover.decide(Query(source, costBelow))) {
            Answer.Unsat -> result.copy(
                checkedLowerBound = lower,
                proof = when {
                    !proven -> null
                    feasible == true -> "strictly better source objective excluded"
                    else -> "source refutation"
                },
                proofChecker = prover.identity,
            )
            is Answer.Unknown -> if (lower != null && !proven) {
                result.copy(boundCheck = "unknown", reason = answer.reason, proofChecker = prover.identity)
            } else {
                result.copy(proof = "unknown", reason = answer.reason, proofChecker = prover.identity)
            }
            Answer.Sat -> error("source counterexample to proof claim")
        }
    }

    /** A complete source assignment indexed `1..size` from signed literals, OPB names or a WCNF bitstring. */
    fun assignment(witness: String, size: Int, opb: Boolean, bitstring: Boolean): BooleanArray {
        require(witness.startsWith("v ")) { "witness lacks a value line" }
        val body = witness.substring(2)
        val values = BooleanArray(size + 1)
        if (bitstring && BITSTRING.matches(body)) {
            require(body.length == size) { "incomplete source bitstring" }
            body.forEachIndexed { i, bit -> values[i + 1] = bit == '1' }
            return values
        }
        val seen = BooleanArray(size + 1)
        var assigned = 0
        for (word in words(body)) {
            if (word == "0") continue
            require((if (opb) OPB_COORDINATE else CNF_COORDINATE).matches(word)) { "unsupported source coordinate" }
            val index = word.trimStart('-', 'x').toIntOrNull()
            require(index != null && index in 1..size && !seen[index]) {
                "duplicate or out-of-range source coordinate $word"
            }
            seen[index] = true
            values[index] = !word.startsWith('-')
            assigned++
        }
        require(assigned == size) { "incomplete source assignment" }
        return values
    }

    fun evaluate(source: Source, values: BooleanArray): Outcome {
        return when (source) {
            is Cnf -> {
                var cost = BigInteger.ZERO
                for (clause in source.clauses) {
                    val satisfied = clause.literals.any { values[abs(it)] == (it > 0) }
                    val weight = clause.weight
                    if (weight == null) {
                        if (!satisfied) return Outcome("violated source clause", null)
                    } else if (!satisfied) {
                        cost += weight
                    }
                }
                Outcome(null, cost.takeIf { source.weighted })
            }
            is Opb -> {
                for (row in source.rows) {
                    if (!row.relation.holds(value(row.terms, values), row.rhs)) {
                        return Outcome("violated source relation", null)
                    }
                }
                val soft = source.softRows.fold(BigInteger.ZERO) { sum, row ->
                    val violated = !row.relation.holds(value(row.terms, values), row.rhs)
                    if (violated) sum + checkNotNull(row.softWeight) else sum
                }
                val top = source.top
                if (top != null && source.softRows.isNotEmpty() && soft >= top) {
                    return Outcome("WBO soft top exceeded", null)
                }
                Outcome(null, if (source.optimizing) value(source.objective.orEmpty(), values) + soft else null)
            }
        }
    }

    private fun value(terms: List<Term>, values: BooleanArray): BigInteger = terms.fold(BigInteger.ZERO) { sum, term ->
        if (term.product.all { values[it.index] != it.negated }) sum + term.coefficient else sum
    }

    fun parseCnf(text: String, weighted: Boolean): Cnf {
        val lines = ArrayList<String>()
        for (line in text.lines()) {
            val start = line.trimStart()
            if (start.startsWith('%')) break
            if (start.isNotEmpty() && !start.startsWith('c')) lines.add(line)
        }
        require(lines.isNotEmpty()) { "empty source" }
        val head = words(lines[0])
        val declared: Int?
        val count: Int?
        val top: BigInteger?
        val body = if (head[0] == "p") {
            require(head.take(2) == listOf("p", if (weighted) "wcnf" else "cnf")) { "invalid source header" }
            require(head.size == 4 || (weighted && head.size == 5)) { "invalid source header" }
            declared = head[2].toInt()
            count = head[3].toInt()
            require(declared >= 0 && count >= 0) { "invalid source header" }
            top = head.getOrNull(4)?.let(::BigInteger)
            lines.subList(1, lines.size)
        } else {
            require(weighted) { "missing CNF header" }
            declared = null
            count = null
            top = null
            lines
        }
        val tokens = body.asSequence().flatMap { words(it) }.iterator()
        val clauses = ArrayList<Clause>()
        while (tokens.hasNext()) {
            val weight = if (!weighted) null else tokens.next().let { if (it == "h") null else BigInteger(it) }
            val literals = ArrayList<Int>()
            var terminated = false
            while (tokens.hasNext()) {
                val word = tokens.next()
                if (word == "0") {
                    terminated = true
                    break
                }
                val literal = word.toInt()
                require(literal != 0 && literal != Int.MIN_VALUE && (declared == null || abs(literal) <= declared)) {
                    "source literal $word outside the declared variable range"
                }
                literals.add(literal)
            }
            require(terminated) { "unterminated source clause" }
            require(weight == null || weight.signum() >= 0) { "negative source weight" }
            val soft = weight?.takeIf { top == null || it < top }
            clauses.add(Clause(soft, literals.toIntArray()))
        }
        require(count == null || clauses.size == count) { "source clause count differs from its header" }
        val size = declared ?: clauses.maxOfOrNull { clause -> clause.literals.maxOfOrNull { abs(it) } ?: 0 } ?: 0
        return Cnf(size, clauses, weighted)
    }

    fun parseOpb(text: String): Opb {
        val declared = OPB_HEADER.find(text)?.groupValues?.get(1)?.toInt()
        val body = text.lines().filterNot { it.trimStart().startsWith('*') }.joinToString(" ")
        var objective: List<Term>? = null
        val rows = ArrayList<Row>()
        val softRows = ArrayList<Row>()
        var top: BigInteger? = null
        fun inRange(terms: List<Term>): List<Term> = terms.also {
            require(declared == null || it.all { term -> term.product.all { literal -> literal.index <= declared } }) {
                "OPB literal outside the declared variable range"
            }
        }
        for (statement in body.split(';')) {
            var part = statement.trim()
            if (part.isEmpty()) continue
            if (part.startsWith("soft:")) {
                val match = requireNotNull(SOFT_HEADER.matchEntire(part)) { "unsupported WBO soft header" }
                top = match.groups[1]?.value?.let(::BigInteger)
                continue
            }
            var softWeight: BigInteger? = null
            if (part.startsWith('[')) {
                val match = requireNotNull(SOFT_WEIGHT.matchEntire(part)) { "unsupported WBO weight" }
                softWeight = BigInteger(match.groupValues[1])
                part = match.groupValues[2]
            }
            if (part.startsWith("min:")) {
                require(softWeight == null) { "weighted objective declaration" }
                require(objective == null) { "repeated OPB objective" }
                objective = inRange(terms(part.substring(4)))
            } else {
                val match = requireNotNull(RELATION_ROW.matchEntire(part)) { "unsupported OPB syntax" }
                val (expression, symbol, rhs) = match.destructured
                val row = Row(inRange(terms(expression)), Relation.entries.first { it.symbol == symbol },
                    BigInteger(rhs), softWeight)
                if (softWeight == null) rows.add(row) else softRows.add(row)
            }
        }
        val size = declared ?: (objective.orEmpty() + (rows + softRows).flatMap { it.terms })
            .maxOfOrNull { term -> term.product.maxOf { it.index } } ?: 0
        return Opb(size, objective, rows, softRows, top)
    }

    private fun terms(expression: String): List<Term> {
        val tokens = words(expression)
        val terms = ArrayList<Term>()
        var offset = 0
        while (offset < tokens.size) {
            require(INTEGER.matches(tokens[offset])) { "invalid OPB coefficient" }
            val coefficient = BigInteger(tokens[offset++])
            val product = ArrayList<Literal>()
            while (offset < tokens.size && !INTEGER.matches(tokens[offset])) {
                val name = tokens[offset++]
                require(OPB_LITERAL.matches(name)) { "unsupported OPB literal" }
                product.add(Literal(name.trimStart('~', 'x').toInt(), name.startsWith('~')))
            }
            require(product.isNotEmpty()) { "OPB term has no literals" }
            terms.add(Term(coefficient, product))
        }
        return terms
    }

    /** Whether the positive clauses are disjoint pigeons over holes that negative pairs make exclusive cliques. */
    fun pigeonhole(clauses: List<Clause>): Boolean {
        val pigeons = clauses.filter { it.literals.isNotEmpty() && it.literals.all { literal -> literal > 0 } }
            .map { it.literals.toSet() }
        if (pigeons.isEmpty()) return false
        val vertices = pigeons.flatMapTo(HashSet()) { it }
        if (pigeons.sumOf { it.size } != vertices.size) return false
        val adjacent = vertices.associateWith { HashSet<Int>() }
        for (clause in clauses) {
            if (clause.literals.size != 2 || clause.literals.any { it >= 0 }) continue
            val a = -clause.literals[0]
            val b = -clause.literals[1]
            if (a !in vertices || b !in vertices) return false
            adjacent.getValue(a).add(b)
            adjacent.getValue(b).add(a)
        }
        val holes = ArrayList<Set<Int>>()
        val remaining = LinkedHashSet(vertices)
        while (remaining.isNotEmpty()) {
            val hole = HashSet<Int>()
            val stack = ArrayDeque<Int>().apply { add(remaining.first()) }
            while (stack.isNotEmpty()) {
                val vertex = stack.removeLast()
                if (hole.add(vertex)) adjacent.getValue(vertex).filterTo(stack) { it !in hole }
            }
            remaining.removeAll(hole)
            holes.add(hole)
        }
        return pigeons.size > holes.size && holes.all { hole ->
            hole.all { vertex -> adjacent.getValue(vertex).containsAll(hole - vertex) } &&
                pigeons.all { pigeon -> pigeon.count { it in hole } == 1 }
        }
    }

    private fun integerObjective(record: JsonObject): BigInteger {
        val text = requireNotNull(reportedObjective(record)) { "missing source objective" }.jsonPrimitive.content.trim()
        val value = if ('/' in text) {
            val (numerator, denominator) = text.split('/', limit = 2).map { BigInteger(it.trim()) }
            val (quotient, remainder) = numerator.divideAndRemainder(denominator)
            quotient.takeIf { remainder.signum() == 0 }
        } else {
            runCatching { BigDecimal(text).toBigIntegerExact() }.getOrNull()
        }
        return requireNotNull(value) { "nonintegral source objective" }
    }

    private fun reportedObjective(record: JsonObject): JsonElement? =
        record["exactObjective"].present() ?: record["objective"].present()

    private fun words(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }

    private fun JsonElement?.present(): JsonElement? = this?.takeUnless { it is JsonNull }

    private fun JsonObject.obj(key: String): JsonObject =
        requireNotNull(this[key].present()) { "missing $key" }.jsonObject

    private fun JsonObject.string(key: String): String =
        requireNotNull(this[key].present()) { "missing $key" }.jsonPrimitive.content
}
