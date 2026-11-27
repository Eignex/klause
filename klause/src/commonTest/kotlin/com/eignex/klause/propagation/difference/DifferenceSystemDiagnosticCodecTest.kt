package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.factor.codec.FactorCodecs
import kotlin.test.Test
import kotlin.test.assertEquals

class DifferenceSystemDiagnosticCodecTest {
    @Test
    fun `diagnostic snapshot preserves edge order and selectors`() {
        val system = DifferenceSystem(
            listOf(DifferenceEdge(50, 17, Long.MAX_VALUE), DifferenceEdge(-1, 17, 1, domainBound = true)),
            privateVertices = true,
            indexGuards = mapOf(0 to DifferenceIndexGuard(2, Long.MIN_VALUE)),
        )
        val codec = FactorCodecs.builtin + differenceSystemDiagnosticCodec()

        val snapshot = codec.encodeToJsonElement(system)
        val decoded = codec.decodeFromJsonElement(snapshot)

        assertEquals(system.structuralKey(), decoded.structuralKey())
        assertEquals(snapshot, codec.encodeToJsonElement(decoded))
    }
}
