package com.eignex.klause.bench.tools

import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.ast.implies
import com.eignex.klause.ast.le
import com.eignex.klause.ast.not
import com.eignex.klause.schema.VariableSchema
import com.eignex.skema.SchemaDef
import kotlinx.serialization.json.Json

/**
 * One-shot generator for the bundled JSON-SchemaDef instance under
 * `klause-bench/src/main/resources/schema/`. Run as
 * `./gradlew :klause-bench:run -PtoolsMain=com.eignex.klause.bench.tools.SchemaDumperKt`
 * (or pipe directly via `gradle run` and the bundled-resource path) to refresh the
 * canonical sample. Kept around so the JSON file stays reproducible.
 */
private class CampaignSchema : VariableSchema() {
    val premium by boolVar()
    val type by nominal("a", "b", "c")
    val budget by intVar(min = 100, max = 1000)
    val capWhenA by constraint { (type eq "a") implies (budget le 500) }
    val noPremForB by constraint { (type eq "b") implies !premium }
}

fun main() {
    val schema = CampaignSchema()
    val json = Json { prettyPrint = true; encodeDefaults = false }
    println(json.encodeToString(SchemaDef.serializer(SchemaEntry.serializer()), schema.definition()))
}
