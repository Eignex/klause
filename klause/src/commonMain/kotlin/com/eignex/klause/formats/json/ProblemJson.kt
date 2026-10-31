package com.eignex.klause.formats.json

import com.eignex.klause.factor.codec.FactorCodec
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SourceIntDomains
import com.eignex.klause.ir.defaultIrSemantics
import com.eignex.klause.ir.intdomain.intDomainFromRuns
import com.eignex.klause.ir.intdomain.runBounds
import com.eignex.klause.util.Bits
import com.eignex.klause.util.LongArrayList
import com.eignex.skema.schemaJsonConfig
import com.eignex.skema.serialization.JsonNodeCatalog
import com.eignex.skema.serialization.toJsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Lossless JSON interchange of [Problem] models, including models built directly through the IR.
 *
 * A document states the Boolean count, every integer column's range (open sides, declared value sets and
 * fallback boxes), real bounds, the factors in order, implied-factor flags, symmetry declaration, resource
 * settings and source semantics. Each factor is one `{key: payload}` node written by the [FactorCodec]
 * registered for its exact class, so native factor kinds and payloads survive unchanged, including
 * arbitrary-width integer and exact rational constants. A factor or key without a codec fails with
 * [MissingFactorCodecException] instead of being dropped or decomposed.
 *
 * Decoding is strict and reports malformed input as a [JsonFormatException] naming its document path.
 */
class ProblemJson(
    /** Codecs this instance reads and writes factors with. */
    val codecs: FactorCodecs = FactorCodecs.builtin,
) {
    private val json: Json = Json { schemaJsonConfig() }

    private val catalog: JsonNodeCatalog = JsonNodeCatalog { listOf(codecs.nodeSerializer, FloatSemanticsSerializer) }

    private val documentSerializer: KSerializer<ProblemDocument<Any>> =
        ProblemDocument.serializer(codecs.nodeSerializer)

    /** Encode [problem] as a JSON object. */
    fun encodeToJsonElement(problem: Problem): JsonObject {
        val document = ProblemDocument(
            bools = problem.numBoolVars,
            ints = List(problem.numIntVars) { v -> intColumn(problem.declaredIntDomains, v) },
            reals = List(problem.numRealVars) { v -> realColumn(problem.realLower[v], problem.realUpper[v], v) },
            factors = problem.factors.mapIndexed(::encodeFactor),
            implied = problem.impliedFactorMask?.let { mask -> mask.indices.filter { mask[it] } }?.takeIf {
                it.isNotEmpty()
            },
            symmetryBreaking = problem.hasSymmetryBreaking,
            settings = SettingsDocument.of(problem.settings),
            semantics = problem.semantics.takeUnless {
                !it.hasOverrides && it.declared == defaultIrSemantics.declared
            }?.let { SemanticsDocument.of(it) },
        )
        return try {
            json.encodeToJsonElement(documentSerializer, document) as JsonObject
        } catch (e: SerializationException) {
            throw JsonFormatException(e.message ?: "unrepresentable problem", e)
        }
    }

    /** Encode [problem] as JSON text. */
    fun encodeToString(problem: Problem): String = encodeToJsonElement(problem).toString()

    /** Decode a document produced by [encodeToJsonElement]. */
    fun decodeFromJsonElement(element: JsonElement): Problem {
        requireKnownFactorKeys(element)
        val document = try {
            json.decodeFromJsonElement(documentSerializer, element)
        } catch (e: SerializationException) {
            throw JsonFormatException(e.message ?: "malformed problem document", e)
        }
        return toProblem(document)
    }

    /** Decode JSON [text] produced by [encodeToString]. */
    fun decodeFromString(text: String): Problem {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw JsonFormatException(e.message ?: "malformed JSON", e)
        }
        return decodeFromJsonElement(element)
    }

    /** JSON Schema (draft 2020-12) of the documents this instance reads, derived from the same serializers. */
    fun jsonSchema(): JsonObject = documentSerializer.toJsonSchema(catalog, json)

    private fun encodeFactor(index: Int, factor: Factor): Any {
        val path = "$.factors[$index]"
        val codec = codecs.forFactor(factor)
            ?: throw MissingFactorCodecException(path, "no factor codec for ${factor::class.simpleName ?: factor}")
        return withPath(path) { codec.encode(factor) }
    }

    private fun requireKnownFactorKeys(element: JsonElement) {
        val factors = (element as? JsonObject)?.get("factors") as? JsonArray ?: return
        factors.forEachIndexed { index, factor -> codecs.requireKnownKey(factor, "$.factors[$index]") }
    }

    private fun toProblem(document: ProblemDocument<Any>): Problem {
        val factors = Array(document.factors.size) { index ->
            val payload = document.factors[index]
            withPath("$.factors[$index].${codecs.codecFor(payload).key}") { codecs.factor(payload) }
        }
        val implied = document.implied?.let { indices ->
            BooleanArray(factors.size).also { mask ->
                indices.forEachIndexed { i, factor ->
                    withPath("$.implied[$i]") {
                        require(factor in factors.indices) { "implied factor $factor is not a factor index" }
                    }
                    mask[factor] = true
                }
            }
        }
        val reals = document.reals
        return withPath("$") {
            Problem(
                numBoolVars = document.bools,
                numIntVars = document.ints.size,
                declaredIntDomains = intDomains(document.ints),
                factors = factors,
                impliedFactorMask = implied,
                hasSymmetryBreaking = document.symmetryBreaking,
                numRealVars = reals.size,
                realLower = DoubleArray(reals.size) { reals[it].lower ?: Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(reals.size) { reals[it].upper ?: Double.POSITIVE_INFINITY },
                settings = document.settings?.toSettings() ?: ProblemSettings(),
                semantics = document.semantics?.toSemantics() ?: defaultIrSemantics,
            )
        }
    }

    private fun intDomains(columns: List<IntColumnDocument>): SourceIntDomains {
        val lower = LongArray(columns.size)
        val upper = LongArray(columns.size)
        val openLo = Bits(columns.size)
        val openHi = Bits(columns.size)
        columns.forEachIndexed { v, column ->
            withPath("$.ints[$v]") {
                require(column.lower == null || column.upper == null || column.lower <= column.upper) {
                    "lower bound ${column.lower} exceeds upper bound ${column.upper}"
                }
                require(column.values == null || (column.lower != null && column.upper != null)) {
                    "a value set needs a closed range; an open column states its finite box as 'box'"
                }
                require(column.box == null || column.lower == null || column.upper == null) {
                    "a closed column states its value set as 'values'"
                }
            }
            column.lower?.let { lower[v] = it } ?: openLo.set(v)
            column.upper?.let { upper[v] = it } ?: openHi.set(v)
        }
        val bounds = IntBounds.fromModelBounds(
            lower,
            upper,
            openLo.takeIf { it.cardinality() > 0 },
            openHi.takeIf { it.cardinality() > 0 },
        )
        if (columns.none { it.values != null || it.box != null }) return SourceIntDomains.ofBounds(bounds)
        val domains = Array(columns.size) { v ->
            val column = columns[v]
            val runs = column.values ?: column.box
            withPath("$.ints[$v]") {
                if (runs == null) {
                    require(column.lower != null && column.upper != null) {
                        "an open column needs a 'box' when another column states a value set"
                    }
                    IntDomain(column.lower, column.upper)
                } else {
                    domainOfRuns(runs)
                }
            }
        }
        return SourceIntDomains.ofDomains(domains, shared = true, modelBounds = bounds)
    }

    private fun domainOfRuns(runs: List<LongArray>): IntDomain {
        require(runs.isNotEmpty()) { "a value set needs at least one run" }
        val flat = LongArrayList(runs.size * 2)
        runs.forEachIndexed { i, run ->
            require(run.size == 2 && run[0] <= run[1]) { "run $i must be [lo, hi] with lo <= hi" }
            // A strict gap keeps the run list canonical, so its encoding is unique.
            require(i == 0 || (run[0] > flat.last() && run[0] - 1 > flat.last())) {
                "run $i must start past the previous run with a gap"
            }
            flat.add(run[0])
            flat.add(run[1])
        }
        return intDomainFromRuns(flat)
    }

    private fun intColumn(domains: SourceIntDomains, v: Int): IntColumnDocument {
        val bounds = domains.bounds
        val lower = if (bounds.hasLower(v)) bounds.lower(v) else null
        val upper = if (bounds.hasUpper(v)) bounds.upper(v) else null
        val closed = lower != null && upper != null
        val stated = if (closed) domains.declaredOrNull(v) else domains.statedOrNull(v)
        val runs = stated?.let { domain ->
            withPath("$.ints[$v]") { domain.runBounds() }.toList().chunked(2) { longArrayOf(it[0], it[1]) }
        }?.takeUnless { closed && it.size == 1 && it[0][0] == lower && it[0][1] == upper }
        return IntColumnDocument(lower, upper, values = runs.takeIf { closed }, box = runs.takeUnless { closed })
    }

    private fun realColumn(lower: Double, upper: Double, v: Int): RealColumnDocument = withPath("$.reals[$v]") {
        require(!lower.isNaN() && lower != Double.POSITIVE_INFINITY) { "lower bound $lower is not a real or -inf" }
        require(!upper.isNaN() && upper != Double.NEGATIVE_INFINITY) { "upper bound $upper is not a real or +inf" }
        RealColumnDocument(lower.takeIf { it.isFinite() }, upper.takeIf { it.isFinite() })
    }

    /** Shared instances. */
    companion object {
        /** An instance over [FactorCodecs.builtin]. */
        val default: ProblemJson by lazy { ProblemJson() }
    }
}

// Constructor invariants surface as IllegalArgumentException; the document path tells the reader where.
private inline fun <T> withPath(path: String, block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    throw JsonFormatException("$path: ${e.message}", e)
}
