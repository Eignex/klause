package com.eignex.klause.schema

import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.Implies
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.NominalSpec
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.inSet
import com.eignex.klause.schema.le
import com.eignex.klause.schema.not
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.setOfInts
import com.eignex.klause.schema.subsetOf
import com.eignex.klause.schema.times
import com.eignex.klause.schema.union
import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CampaignSchema : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}

/** Exercises the Int and Set expression families so the serialization round-trip covers
 *  arithmetic, comparison, global, and set-algebra nodes — not just the Bool family. */
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

class SchemaTest {

    @Test
    fun `delegate registers vars and constraints`() {
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
    fun `definition round trips through json`() {
        val schema = CampaignSchema()
        val def = schema.definition()
        val serializer = SchemaDef.serializer(SchemaEntry.serializer())
        val encoded = SchemaJson.encodeToString(serializer, def)
        val decoded = SchemaJson.decodeFromString(serializer, encoded)
        assertEquals(def, decoded)
    }

    @Test
    fun `int and set constraints round trip through json`() {
        val def = IntSetSchema().definition()
        val serializer = SchemaDef.serializer(SchemaEntry.serializer())
        val encoded = SchemaJson.encodeToString(serializer, def)
        val decoded = SchemaJson.decodeFromString(serializer, encoded)
        assertEquals(def, decoded)
    }

    @Test
    fun `handle operators build expected tree`() {
        val schema = CampaignSchema()
        val nc = schema.entries["noPremiumForA"] as NamedConstraint
        val imp = nc.expr as Implies
        val right = imp.right
        assertTrue(right is BoolRef)
        assertTrue(right.negated)
        assertEquals("premium", right.name)
    }
}
