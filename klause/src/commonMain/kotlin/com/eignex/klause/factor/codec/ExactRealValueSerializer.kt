package com.eignex.klause.factor.codec

import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.formats.json.ExactRealJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

internal object ExactRealValueSerializer : KSerializer<ExactRealValue> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ExactRealValue) {
        require(encoder is JsonEncoder) { "exact real values require JSON" }
        encoder.encodeJsonElement(ExactRealJson.encodeToJsonElement(value))
    }

    override fun deserialize(decoder: Decoder): ExactRealValue {
        require(decoder is JsonDecoder) { "exact real values require JSON" }
        return ExactRealJson.decodeFromJsonElement(decoder.decodeJsonElement())
    }
}
