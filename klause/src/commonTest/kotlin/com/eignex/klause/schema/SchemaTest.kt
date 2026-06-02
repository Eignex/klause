package com.eignex.klause.schema

import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.not
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
