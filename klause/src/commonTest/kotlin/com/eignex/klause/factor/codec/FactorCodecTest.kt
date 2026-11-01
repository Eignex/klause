package com.eignex.klause.factor.codec

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactorCodecTest {
    private val codecs = FactorCodecs.builtin

    @Test
    fun `a factor should encode as its key holding its payload and decode back`() {
        val factor = ValuePrecede(Long.MIN_VALUE, 3, intArrayOf(0, 1))

        val element = codecs.encodeToJsonElement(factor)
        val decoded = codecs.decodeFromJsonElement(element)

        assertEquals(
            Json.parseToJsonElement("""{"valuePrecede":{"s":${Long.MIN_VALUE},"t":3,"xs":[0,1]}}"""),
            element,
        )
        assertEquals(factor.structuralKey(), decoded.structuralKey())
    }

    @Test
    fun `a subclass without its own codec should fail to encode`() {
        val error = assertFailsWith<MissingFactorCodecException> {
            (codecs + markerCodec).encodeToJsonElement(Other(0))
        }

        assertEquals("$", error.path)
    }

    @Test
    fun `a key without a codec should fail to decode naming the key`() {
        val error = assertFailsWith<MissingFactorCodecException> {
            codecs.decodeFromJsonElement(Json.parseToJsonElement("""{"marker": {"x": 0}}"""))
        }

        assertEquals("$.marker", error.path)
    }

    @Test
    fun `a malformed payload should name its field`() {
        val error = assertFailsWith<SerializationException> {
            codecs.decodeFromJsonElement(Json.parseToJsonElement("""{"clause": {"literals": "0"}}"""))
        }

        assertTrue("$.literals" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a registered codec should extend the vocabulary`() {
        val extended = codecs + markerCodec

        val decoded = extended.decodeFromJsonElement(extended.encodeToJsonElement(Marker(4)))

        assertEquals(4, (decoded as Marker).x)
    }

    @Test
    fun `a registry should reject a second codec for a key factor class or payload class`() {
        val duplicates = listOf(
            factorCodec<Marker, MarkerPayload>("clause", { MarkerPayload(it.x) }, { Marker(it.x) }),
            factorCodec<Clause, OtherPayload>("otherClause", { OtherPayload(0) }, { Clause(intArrayOf(it.x)) }),
            factorCodec<Other, MarkerPayload>("other", { MarkerPayload(it.x) }, { Other(it.x) }),
        )

        for (duplicate in duplicates) {
            assertFailsWith<IllegalArgumentException>(duplicate.key) { codecs + markerCodec + duplicate }
        }
    }
}

private open class Marker(val x: Int) : Factor {
    override val variables: VarList = IntVars(intArrayOf(x))

    override fun remap(mapping: VarRemap): Factor = Marker(mapping.int(x))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(x) }
}

private class Other(x: Int) : Marker(x)

@Serializable
private class MarkerPayload(val x: Int)

@Serializable
private class OtherPayload(val x: Int)

private val markerCodec = factorCodec<Marker, MarkerPayload>("marker", { MarkerPayload(it.x) }, { Marker(it.x) })
