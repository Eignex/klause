package com.eignex.klause.bench.tools

import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.implies
import com.eignex.klause.schema.le
import com.eignex.klause.schema.not
import com.eignex.klause.schema.VariableSchema
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.json.Json

/** Source of the bundled JSON instance — regenerate via `:klause-bench:dumpSchema`. */
private class CampaignSchema : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val budget by intVar(min = 100, max = 1000)
    val capWhenA by constraint { (type eq "a") implies (budget le 500) }
    val noPremForB by constraint { (type eq "b") implies !premium }
}

/** Entry point that dumps the campaign schema as JSON. */
fun main() {
    val schema = CampaignSchema()
    val json = Json {
        schemaJsonConfig()
        prettyPrint = true
    }
    println(json.encodeToString(SchemaDef.serializer(SchemaEntry.serializer()), schema.definition()))
}
