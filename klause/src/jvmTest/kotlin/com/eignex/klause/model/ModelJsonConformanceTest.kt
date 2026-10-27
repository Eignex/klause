package com.eignex.klause.model

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.and
import com.eignex.klause.schema.ge
import com.eignex.klause.schema.gt
import com.eignex.klause.schema.le
import com.eignex.klause.schema.lt
import com.eignex.klause.schema.min
import com.eignex.klause.schema.minus
import com.eignex.klause.schema.or
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import com.eignex.klause.schema.unaryMinus
import com.eignex.skema.SchemaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelJsonConformanceTest {
    private val cases: List<JsonObject> = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/portable-expression/conformance.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    @Test
    fun `integer environment evaluates accepted fixtures to their values`() {
        val evaluated = cases.withIndex().filter { (_, case) -> !case.integer.jsonPrimitive.isString }
        val compiled = JsonSchema.parse(document(evaluated.flatMap { (i, case) -> entries(case, i) })).compile()
        val results = evaluated.associate { (i, case) -> case.name to compiled.intVarIdByName.getValue("r$i") }

        val observed = results.mapValues { mutableSetOf<Long>() }
        for (sample in BacktrackSolver(compiled.problem.bake()).enumerate(BacktrackParams(maxDecisions = 1_000))) {
            for ((name, id) in results) observed.getValue(name) += sample.intValue(id)
        }

        val expected = evaluated.associate { (_, case) -> case.name to setOf(case.integer.integerValue()) }
        assertEquals(expected, observed)
    }

    @Test
    fun `integer environment rejects fixtures marked as rejected`() {
        val rejected = cases.withIndex().filter { (_, case) -> case.integer == REJECT }

        val accepted = rejected.filter { (i, case) ->
            val document = document(entries(case, i))
            runCatching { JsonSchema.parse(document).compile() }.isSuccess
        }

        assertEquals(emptyList(), accepted.map { it.value.name })
    }

    @Test
    fun `accepted fixtures re-encode to their canonical form`() {
        for (case in cases.filter { it.integer != REJECT }) {
            val expr = case.getValue("expr")

            val encoded = if (case.isBoolean) {
                roundTrip(BoolExpr.serializer(), expr)
            } else {
                roundTrip(IntExpr.serializer(), expr)
            }

            assertEquals(case["canonical"] ?: expr, encoded, case.name)
        }
    }

    @Test
    fun `DSL expressions encode to their fixture forms`() {
        val dslCases = cases.filter { "dsl" in it }

        val encoded = dslCases.associate { case ->
            val source = case.getValue("dsl").jsonPrimitive.content
            val json = if (case.isBoolean) {
                SchemaJson.encodeToJsonElement(BoolExpr.serializer(), BOOL_DSL.getValue(source))
            } else {
                SchemaJson.encodeToJsonElement(IntExpr.serializer(), INT_DSL.getValue(source))
            }
            source to json
        }

        assertEquals(INT_DSL.keys + BOOL_DSL.keys, encoded.keys)
        assertEquals(dslCases.associate { it.getValue("dsl").jsonPrimitive.content to it.getValue("expr") }, encoded)
    }

    @Test
    fun `fixture forms of DSL expressions decode to the DSL trees`() {
        for (case in cases.filter { "dsl" in it }) {
            val source = case.getValue("dsl").jsonPrimitive.content
            val expr = case.getValue("expr")

            val decoded: Any = if (case.isBoolean) {
                SchemaJson.decodeFromJsonElement(BoolExpr.serializer(), expr)
            } else {
                SchemaJson.decodeFromJsonElement(IntExpr.serializer(), expr)
            }

            assertEquals(INT_DSL[source] ?: BOOL_DSL[source], decoded, source)
        }
    }

    private fun entries(case: JsonObject, index: Int): List<Pair<String, JsonElement>> {
        val bindings = case.getValue("bindings").jsonObject.map { (name, value) ->
            val fixed = value.jsonPrimitive.int
            "c${index}_$name" to obj("int" to JsonArray(listOf(JsonPrimitive(fixed), JsonPrimitive(fixed))))
        }
        val expr = renamed(case.getValue("expr"), "c${index}_")
        val value = if (case.isBoolean) obj("if" to JsonArray(listOf(expr, ONE, ZERO))) else expr
        val equation = obj("eq" to JsonArray(listOf(value, obj("ref" to JsonPrimitive("r$index")))))
        return bindings + listOf(
            "r$index" to obj("int" to JsonArray(listOf(JsonPrimitive(-RESULT_BOUND), JsonPrimitive(RESULT_BOUND)))),
            "k$index" to obj("constraint" to equation),
        )
    }

    private fun renamed(element: JsonElement, prefix: String): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { renamed(it, prefix) })
        is JsonObject -> {
            val reference = element["ref"]?.takeIf { element.size == 1 }?.jsonPrimitive
            if (reference?.isString == true) {
                obj("ref" to JsonPrimitive(prefix + reference.content))
            } else {
                JsonObject(element.mapValues { (_, child) -> renamed(child, prefix) })
            }
        }
        else -> element
    }

    private fun document(entries: List<Pair<String, JsonElement>>): String =
        obj("entries" to JsonObject(entries.toMap())).toString()

    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))

    private fun <T> roundTrip(serializer: KSerializer<T>, element: JsonElement): JsonElement =
        SchemaJson.encodeToJsonElement(serializer, SchemaJson.decodeFromJsonElement(serializer, element))

    private fun JsonElement.integerValue(): Long =
        jsonPrimitive.booleanOrNull?.let { if (it) 1L else 0L } ?: jsonPrimitive.long

    private val JsonObject.name get() = getValue("name").jsonPrimitive.content
    private val JsonObject.integer get() = getValue("integer")
    private val JsonObject.isBoolean get() = getValue("type").jsonPrimitive.content == "boolean"

    private companion object {
        val REJECT = JsonPrimitive("reject")
        val ONE = JsonPrimitive(1)
        val ZERO = JsonPrimitive(0)
        const val RESULT_BOUND = 1_000

        val a = IntRef("a")
        val b = IntRef("b")
        val c = IntRef("c")
        val INT_DSL: Map<String, IntExpr> = mapOf(
            "a + 1 + 2" to a + 1 + 2,
            "a - 1" to a - 1,
            "a - b" to a - b,
            "a - b + 1" to a - b + 1,
            "-a" to -a,
            "a * 2" to a * 2,
            "2 * a - b" to 2 * a - b,
            "min(a, b, c)" to min(a, b, c),
        )
        val BOOL_DSL: Map<String, BoolExpr> = mapOf(
            "(a ge 0) and (a le 9) and (b ge 0)" to ((a ge 0) and (a le 9) and (b ge 0)),
            "(a lt 0) or (a gt 9) or (b lt 0)" to ((a lt 0) or (a gt 9) or (b lt 0)),
        )
    }
}
