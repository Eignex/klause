package com.eignex.klause.schema

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignSchema : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}

class IntSetSchema : VariableSchema() {
    val x by intVar(0, 10)
    val y by intVar(0, 10)
    val s by setVar(0..5)
    val t by setVar(0..5)
    val sumBound by constraint { (x + y) le 5 }
    val scaled by constraint { (2 * x) ge 4 }
    val distinct by constraint { allDifferent(x, y) }
    val member by constraint { x inSet s }
    val subset by constraint { s subsetOf t }
    val setEq by constraint { s eq (t union setOfInts(1, 2)) }
}

class VariableSchemaTest {
    @Test
    fun `a definition should round trip through json`() {
        val schema = CampaignSchema()
        val def = schema.definition()
        val serializer = SchemaDef.serializer(SchemaEntry.serializer())
        val encoded = SchemaJson.encodeToString(serializer, def)
        val decoded = SchemaJson.decodeFromString(serializer, encoded)
        assertEquals(def, decoded)
    }

    @Test
    fun `int and set constraints should round trip through json`() {
        val def = IntSetSchema().definition()
        val serializer = SchemaDef.serializer(SchemaEntry.serializer())
        val encoded = SchemaJson.encodeToString(serializer, def)
        val decoded = SchemaJson.decodeFromString(serializer, encoded)
        assertEquals(def, decoded)
    }

    private class OptSchema : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
    }

    @Test
    fun `decode should return null when absent`() {
        val s = OptSchema()
        val compiled = s.compile()
        val sample = Sample(
            bools = booleanArrayOf(false),
            ints = longArrayOf(3),
        )
        assertNull(compiled.decode(s.x, sample))
    }

    @Test
    fun `decode should return the value when present`() {
        val s = OptSchema()
        val compiled = s.compile()
        val sample = Sample(
            bools = booleanArrayOf(true),
            ints = longArrayOf(3),
        )
        assertEquals(3, compiled.decode(s.x, sample))
    }

    private class MembershipSchema : VariableSchema() {
        val s by setVar(0..3)
        val x by intVar(0, 3)
        val c by constraint { x inSet s }
    }

    @Test
    fun `top-level inSet should force x to a present element`() {
        val schema = MembershipSchema()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem.bake())
        val samples = solver.enumerate(BacktrackParams()).take(50).toList()
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            val xv = compiled.decode(schema.x, sample)
            val sv = compiled.decode(schema.s, sample)
            assertTrue(xv.toInt() in sv, "x=$xv not in s=$sv")
        }
    }
}
