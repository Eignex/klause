package com.eignex.klause.compile

import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaFloatPolicyTest {
    @Test
    fun `constant strict contradiction remains false beside an exact component`() {
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 3),
            "row" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(), emptyList(), IntCmpOp.LT, 0.0)),
        ))

        assertFailsWith<IllegalArgumentException> { definition.compile() }
    }

    @Test
    fun `connected strict row selects one representation for all its floats`() {
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(0.0, 1.0, 3), "y" to FloatSpec(0.0, 1.0, 3),
            "row" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0, 1.0), listOf("x", "y"), IntCmpOp.LT, 1.0)),
        )).compile()

        assertEquals(compiled.floatRepresentations.getValue("x"), compiled.floatRepresentations.getValue("y"))
        assertTrue(compiled.floatRepresentations.getValue("x").approximate)
        assertEquals(0, compiled.problem.numRealVars)
    }
}
