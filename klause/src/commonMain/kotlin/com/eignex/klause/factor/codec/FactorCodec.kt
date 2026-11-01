package com.eignex.klause.factor.codec

import com.eignex.klause.ir.Factor
import com.eignex.skema.schemaJsonConfig
import com.eignex.skema.serialization.CompactSerializer
import com.eignex.skema.serialization.JsonNode
import com.eignex.skema.serialization.JsonNodeCatalog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * JSON representation of one [Factor] class.
 *
 * A factor is written as `{key: payload}`. The payload is a serializable class holding everything the
 * decoding function needs to rebuild an equivalent factor: its variables, constants and options, with no
 * engine projection or cache. Codecs match the factor's exact class, so a subclass without its own codec
 * fails rather than being written as its parent.
 */
class FactorCodec<F : Factor, P : Any>(
    /** Operator key naming the factor in a document. */
    val key: String,
    /** Exact factor class this codec writes. */
    val factorType: KClass<F>,
    /** Payload class; its serializer must describe a class or an object. */
    val payloadType: KClass<P>,
    /** Serializer of the payload's fields. */
    val payloadSerializer: KSerializer<P>,
    private val toPayload: (F) -> P,
    private val fromPayload: (P) -> F,
) {
    init {
        require(key.isNotEmpty()) { "a factor codec needs a key" }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun encode(factor: Factor): Any = toPayload(factor as F)

    @Suppress("UNCHECKED_CAST")
    internal fun decode(payload: Any): Factor = fromPayload(payload as P)
}

/** A [FactorCodec] over the payload's generated serializer. */
inline fun <reified F : Factor, reified P : Any> factorCodec(
    key: String,
    noinline toPayload: (F) -> P,
    noinline fromPayload: (P) -> F,
): FactorCodec<F, P> = FactorCodec(key, F::class, P::class, serializer<P>(), toPayload, fromPayload)

/**
 * The factor codecs one vocabulary knows. Keys, factor classes and payload classes are each unique, so a
 * document names one codec per factor and a factor has one encoding. Compose registries with [plus];
 * replacing a built-in codec means building a registry without it.
 *
 * Problem documents and schema factor constraints both write factors through a registry, so the two
 * share one factor vocabulary.
 */
class FactorCodecs(codecs: List<FactorCodec<*, *>>) {
    /** Registered codecs in registration order. */
    val codecs: List<FactorCodec<*, *>> = codecs.toList()

    private val byKey = this.codecs.associateBy { it.key }
    private val byType = this.codecs.associateBy { it.factorType }
    private val byPayload = this.codecs.associateBy { it.payloadType }

    init {
        require(byKey.size == this.codecs.size) { "factor codec keys must be distinct" }
        require(byType.size == this.codecs.size) { "factor codec factor classes must be distinct" }
        require(byPayload.size == this.codecs.size) { "factor codec payload classes must be distinct" }
    }

    private val json: Json = Json { schemaJsonConfig() }

    internal val catalog: JsonNodeCatalog = JsonNodeCatalog { listOf(nodeSerializer) }

    /** Skema vocabulary of `{key: payload}` nodes, one per codec. */
    internal val nodeSerializer: CompactSerializer<Any> by lazy {
        CompactSerializer("com.eignex.klause.factor.codec.Factor", Any::class, catalog) {
            this.codecs.map { codec ->
                JsonNode(type = codec.payloadType, name = codec.key, serializer = { codec.payloadSerializer })
            }
        }
    }

    /** The codec writing exactly [factor]'s class, or `null` when none is registered. */
    fun forFactor(factor: Factor): FactorCodec<*, *>? = byType[factor::class]

    /** The codec reading [key], or `null` when none is registered. */
    fun forKey(key: String): FactorCodec<*, *>? = byKey[key]

    /** This registry with [codec] added. */
    operator fun plus(codec: FactorCodec<*, *>): FactorCodecs = FactorCodecs(codecs + codec)

    /** This registry with every codec of [other] added. */
    operator fun plus(other: FactorCodecs): FactorCodecs = FactorCodecs(codecs + other.codecs)

    /** [factor] as one `{key: payload}` node; raises [MissingFactorCodecException] without a codec. */
    fun encodeToJsonElement(factor: Factor): JsonElement =
        json.encodeToJsonElement(nodeSerializer, payload(factor, "$"))

    /**
     * Rebuild the factor [element] names. A key without a codec raises [MissingFactorCodecException]; a
     * malformed payload raises a [SerializationException] naming its path, and a payload the factor's own
     * invariants reject raises an [IllegalArgumentException].
     */
    fun decodeFromJsonElement(element: JsonElement): Factor {
        requireKnownKey(element, "$")
        return factor(json.decodeFromJsonElement(nodeSerializer, element))
    }

    internal fun payload(factor: Factor, path: String): Any {
        val codec = forFactor(factor)
            ?: throw MissingFactorCodecException(path, "no factor codec for ${factor::class.simpleName ?: factor}")
        return codec.encode(factor)
    }

    internal fun codecFor(payload: Any): FactorCodec<*, *> = checkNotNull(byPayload[payload::class])

    internal fun factor(payload: Any): Factor = codecFor(payload).decode(payload)

    // Unknown keys fail before structural decoding, so the error names the missing codec.
    internal fun requireKnownKey(element: JsonElement, path: String) {
        val key = when (element) {
            is JsonObject -> element.keys.singleOrNull()
            is JsonPrimitive -> element.content.takeIf { element.isString }
            else -> null
        } ?: return
        if (forKey(key) == null) throw MissingFactorCodecException("$path.$key", "no factor codec for key '$key'")
    }

    /** Registries shipped with Klause. */
    companion object {
        /** Codecs of every native source factor; engine projections appended during solving have none. */
        val builtin: FactorCodecs by lazy { FactorCodecs(builtinFactorCodecs()) }
    }
}

/** Raised when a factor class or a document key has no registered [FactorCodec]; [path] locates it. */
class MissingFactorCodecException(
    /** Document path of the factor, such as `$.factors[3]`. */
    val path: String,
    detail: String,
) : SerializationException("$path: $detail")
