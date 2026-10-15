package com.eignex.klause.schema

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.Implies
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.MultipleSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.NominalSpec
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.model.SetSpec
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
    fun `delegates should register vars and constraints`() {
        val schema = CampaignSchema()
        val entries = schema.entries.entries.toList()
        assertEquals(3, entries.size)
        assertEquals("premium", entries[0].key)
        assertTrue(entries[0].value is BoolSpec)
        assertEquals("type", entries[1].key)
        assertTrue(entries[1].value is NominalSpec)
        assertEquals("noPremiumForA", entries[2].key)
        val nc = entries[2].value
        assertTrue(nc is NamedConstraint)
        assertTrue(nc.expr is Implies)
    }

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

    @Test
    fun `handle operators should build the expected tree`() {
        val schema = CampaignSchema()
        val nc = schema.entries["noPremiumForA"] as NamedConstraint
        val imp = nc.expr as Implies
        val right = imp.right
        assertTrue(right is BoolRef)
        assertTrue(right.negated)
        assertEquals("premium", right.name)
    }

    private class OptSchema : VariableSchema() {
        val x by optIntVar(min = 0, max = 5)
    }

    @Test
    fun `optIntVar should register presence then value`() {
        val s = OptSchema()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        assertEquals("x__present", entries[0].key)
        val presence = entries[0].value
        assertTrue(presence is PresenceSpec)
        assertEquals("x", presence.valueName)
        assertEquals("x", entries[1].key)
        assertTrue(entries[1].value is IntSpec)
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

    private class SetSchema : VariableSchema() {
        val chosen by setVar(0..3)
        val pickedLabels by multiple("a", "b", "c")
    }

    @Test
    fun `declarators should register SetSpec and MultipleSpec`() {
        val s = SetSchema()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        assertEquals("chosen", entries[0].key)
        assertTrue(entries[0].value is SetSpec)
        assertEquals(listOf(0, 1, 2, 3), (entries[0].value as SetSpec).universe)
        assertEquals("pickedLabels", entries[1].key)
        assertTrue(entries[1].value is MultipleSpec)
        assertEquals(listOf("a", "b", "c"), (entries[1].value as MultipleSpec).labels)
    }

    @Test
    fun `compile should allocate one indicator bool per universe element`() {
        val s = SetSchema()
        val compiled = s.compile()
        assertEquals(7, compiled.problem.numBoolVars)
        assertEquals(4, compiled.setLayouts.getValue("chosen").size)
        assertEquals(3, compiled.setLayouts.getValue("pickedLabels").size)
        assertEquals(listOf("a", "b", "c"), compiled.setNominalLabels["pickedLabels"])
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
