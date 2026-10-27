package com.eignex.klause.formats.json

import com.eignex.klause.ir.Factor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * JSON representation of one [Factor] class in a [ProblemJson] document.
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
 * The factor codecs a [ProblemJson] instance knows. Keys, factor classes and payload classes are each
 * unique, so a document names one codec per factor and a factor has one encoding. Compose registries with
 * [plus]; replacing a built-in codec means building a registry without it.
 */
class FactorCodecs(codecs: List<FactorCodec<*, *>>) {
    /** Registered codecs in registration order. */
    val codecs: List<FactorCodec<*, *>> = codecs.toList()

    private val byKey = this.codecs.associateBy { it.key }
    private val byType = this.codecs.associateBy { it.factorType }

    init {
        require(byKey.size == this.codecs.size) { "factor codec keys must be distinct" }
        require(byType.size == this.codecs.size) { "factor codec factor classes must be distinct" }
        require(this.codecs.map { it.payloadType }.toSet().size == this.codecs.size) {
            "factor codec payload classes must be distinct"
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

    /** Registries shipped with Klause. */
    companion object {
        /** Codecs of every native source factor; see `docs/03-input-semantics.md` for the exclusions. */
        val builtin: FactorCodecs by lazy { FactorCodecs(builtinFactorCodecs()) }
    }
}

/** Raised when a factor class or a document key has no registered [FactorCodec]; [path] locates it. */
class MissingFactorCodecException(
    /** Document path of the factor, such as `$.factors[3]`. */
    val path: String,
    detail: String,
) : JsonFormatException("$path: $detail")
