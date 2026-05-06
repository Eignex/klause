package com.eignex.klause.schema

import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.SchemaDef
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.not
import kotlinx.serialization.json.Json
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
    fun delegateRegistersVarsAndConstraints() {
        val schema = CampaignSchema()
        assertEquals(2, schema.vars.size)
        assertTrue(schema.vars[0] is BoolSpec && (schema.vars[0] as BoolSpec).name == "premium")
        assertTrue(schema.vars[1] is NominalSpec && (schema.vars[1] as NominalSpec).name == "type")
        assertEquals(1, schema.constraints.size)
        assertEquals("noPremiumForA", schema.constraints[0].name)
        assertTrue(schema.constraints[0].expr is Implies)
    }

    @Test
    fun definitionRoundTripsThroughJson() {
        val schema = CampaignSchema()
        val def = schema.definition()
        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(SchemaDef.serializer(), def)
        val decoded = json.decodeFromString(SchemaDef.serializer(), encoded)
        assertEquals(def, decoded)
    }

    @Test
    fun handleOperatorsBuildExpectedTree() {
        val schema = CampaignSchema()
        val nc = schema.constraints[0]
        val imp = nc.expr as Implies
        // left: NominalEq(type, a)
        // right: BoolRef(premium, negated=true)  (because !premium folds the Not into the ref)
        val right = imp.right
        assertTrue(right is com.eignex.klause.ast.BoolRef)
        assertTrue(right.negated)
        assertEquals("premium", right.name)
    }
}
