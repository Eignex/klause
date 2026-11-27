package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DifferenceSystemDiagnosticCodecTest {
    @Test
    fun `diagnostic snapshot preserves edge order and selectors`() {
        val system = DifferenceSystem(
            listOf(DifferenceEdge(50, 17, Long.MAX_VALUE), DifferenceEdge(-1, 17, 1, guard = 7, domainBound = true)),
            privateVertices = true,
            indexGuards = mapOf(0 to DifferenceIndexGuard(2, Long.MIN_VALUE)),
        )
        val codec = FactorCodecs.builtin + differenceSystemDiagnosticCodec()

        val snapshot = codec.encodeToJsonElement(system)
        val decoded = codec.decodeFromJsonElement(snapshot)
        val graph = snapshot.jsonObject.getValue("diagnosticDifferenceSystem").jsonObject
        val edges = graph.getValue("edges").jsonArray

        assertEquals(listOf(50, -1), edges.map { it.jsonObject.getValue("source").jsonPrimitive.int })
        assertEquals(listOf(DifferenceEdge.ALWAYS, 7), edges.map { it.jsonObject.getValue("guard").jsonPrimitive.int })
        assertEquals(system.structuralKey(), decoded.structuralKey())
        assertEquals(snapshot, codec.encodeToJsonElement(decoded))
    }

    @Test
    fun `parallel difference edges contribute to the minimum snapshot size`() {
        val edge = DifferenceEdge(0, 0, 0, 0, domainBound = true)
        val system = DifferenceSystem(List(5_000) { edge })
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(system))

        assertTrue(differenceSystemDiagnosticBytesLowerBound(problem) > 262_144)
    }
}
