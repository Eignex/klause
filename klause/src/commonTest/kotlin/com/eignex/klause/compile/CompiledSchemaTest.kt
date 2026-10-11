package com.eignex.klause.compile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.schema.BoolHandle
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.NominalHandle
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompiledSchemaTest {
    @Test
    fun `captured source metadata survives constructor and exported map mutation`() {
        val booleans = linkedMapOf("a" to 0, "b" to 1)
        val integers = linkedMapOf("x" to 0, "y" to 1)
        val compiled = CompiledSchema(
            Problem(2, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), emptyArray()),
            booleans, integers, emptyMap(), emptyMap(),
        )
        val sample = Sample(booleanArrayOf(false, true), longArrayOf(1, 2))

        booleans["b"] = 0
        integers["y"] = 0
        (compiled.boolVarIdByName as MutableMap<String, Int>)["b"] = 0
        (compiled.intVarIdByName as MutableMap<String, Int>)["y"] = 0

        assertEquals(SourceBoolId(1), compiled.sourceId(BoolHandle("b")))
        assertEquals(SourceIntId(1), compiled.sourceId(IntHandle("y", 0, 2)))
        assertEquals(true, compiled.decode(BoolHandle("b"), sample))
        assertEquals(2L, compiled.decode(IntHandle("y", 0, 2), sample))
    }

    @Test
    fun `nested nominal metadata cannot change compiled decoding`() {
        val indicators = linkedMapOf("red" to 0, "green" to 1)
        val compiled = CompiledSchema(
            Problem(2, 0, emptyArray(), emptyArray()),
            emptyMap(), emptyMap(), mapOf("color" to indicators), emptyMap(),
        )
        val sample = Sample(booleanArrayOf(false, true), LongArray(0))

        indicators["green"] = 0
        (checkNotNull(compiled.nominalIndicators["color"]) as MutableMap<String, Int>)["green"] = 0

        assertEquals("green", compiled.decode(NominalHandle("color", listOf("red", "green")), sample))
    }

    @Test
    fun `bucketed floats have no continuous source identity`() {
        val compiled = CompiledSchema(
            Problem(
                0, 1, arrayOf(IntDomain(0, 2)), emptyArray(),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            ),
            emptyMap(), mapOf("bucket" to 0), emptyMap(), mapOf("bucket" to FloatSpec(0.0, 1.0, 3)),
            realVarIdByName = mapOf("real" to 0),
        )

        assertNull(compiled.sourceRealId(FloatHandle("bucket", 0.0, 1.0)))
        assertEquals(SourceRealId(0), compiled.sourceRealId(FloatHandle("real", 0.0, 1.0)))
    }
}
