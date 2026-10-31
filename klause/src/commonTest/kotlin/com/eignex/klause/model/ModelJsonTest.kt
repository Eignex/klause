@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.klause.model

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import com.eignex.skema.serialization.JsonNodeShape
import com.eignex.skema.serialization.toJsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelJsonTest {
    private val x = IntRef("x")
    private val y = IntRef("y")
    private val p = BoolRef("p")
    private val q = BoolRef("q")
    private val s = SetRef("s")
    private val t = SetRef("t")
    private val f = ContinuousRealExpr.Affine(mapOf("f" to 2.0), 1.5)

    @Test
    fun `schema entries have stable documented shapes`() {
        assertShapes(
            SchemaEntry.serializer(),
            BoolSpec to """"bool"""",
            PresenceSpec("x") to """{"presence":"x"}""",
            NominalSpec(listOf("a", "b")) to """{"nominal":["a","b"]}""",
            IntSpec(0, 9) to """{"int":[0,9]}""",
            FloatSpec(0.0, 1.0) to """{"float":{"min":0.0,"max":1.0}}""",
            FloatSpec(0.0, 1.0, 5) to """{"float":{"min":0.0,"max":1.0,"buckets":5}}""",
            SetSpec(listOf(1, 3)) to """{"set":[1,3]}""",
            MultipleSpec(listOf("a")) to """{"multiple":["a"]}""",
            NamedConstraint(p) to """{"constraint":{"ref":"p"}}""",
        )
    }

    @Test
    fun `variable declarations share the schema entry keys`() {
        assertShapes(VarSpec.serializer(), IntSpec(0, 9) to """{"int":[0,9]}""", BoolSpec to """"bool"""")
    }

    @Test
    fun `integer expressions have stable documented shapes`() {
        assertShapes(
            IntExpr.serializer(),
            x to """{"ref":"x"}""",
            IntLit(3) to "3",
            IntScale(2, x) to """{"mul":[2,{"ref":"x"}]}""",
            IntSum(listOf(x, IntLit(1))) to """{"add":[{"ref":"x"},1]}""",
            IntMul(x, y) to """{"mul":[{"ref":"x"},{"ref":"y"}]}""",
            IntMul(x, IntLit(2)) to """{"mul":[{"ref":"x"},2]}""",
            IntDiv(x, y) to """{"truncDiv":[{"ref":"x"},{"ref":"y"}]}""",
            IntMod(x, y) to """{"truncMod":[{"ref":"x"},{"ref":"y"}]}""",
            IntPower(x, 2) to """{"pow":[{"ref":"x"},2]}""",
            IntAbs(x) to """{"abs":{"ref":"x"}}""",
            IntMin(listOf(x, y)) to """{"min":[{"ref":"x"},{"ref":"y"}]}""",
            IntMax(listOf(x, y)) to """{"max":[{"ref":"x"},{"ref":"y"}]}""",
            IntIfThenElse(p, x, IntLit(0)) to """{"if":[{"ref":"p"},{"ref":"x"},0]}""",
            IntElement(x, listOf(IntLit(4), y)) to """{"element":{"index":{"ref":"x"},"items":[4,{"ref":"y"}]}}""",
            SetCard(s) to """{"card":{"ref":"s"}}""",
        )
    }

    @Test
    fun `logical and counting constraints have stable documented shapes`() {
        assertShapes(
            BoolExpr.serializer(),
            p to """{"ref":"p"}""",
            BoolRef("p", negated = true) to """{"notRef":"p"}""",
            NominalEq("c", "a") to """{"nominalEq":["c","a"]}""",
            Not(p) to """{"not":{"ref":"p"}}""",
            And(listOf(p, q)) to """{"and":[{"ref":"p"},{"ref":"q"}]}""",
            Or(listOf(p, q)) to """{"or":[{"ref":"p"},{"ref":"q"}]}""",
            XorExpr(listOf(p, q)) to """{"xor":[{"ref":"p"},{"ref":"q"}]}""",
            Implies(p, q) to """{"implies":[{"ref":"p"},{"ref":"q"}]}""",
            Iff(p, q) to """{"iff":[{"ref":"p"},{"ref":"q"}]}""",
            AtMost(listOf(p, q), 1) to """{"atMost":{"children":[{"ref":"p"},{"ref":"q"}],"k":1}}""",
            AtLeast(listOf(p, q), 1) to """{"atLeast":{"children":[{"ref":"p"},{"ref":"q"}],"k":1}}""",
            CardinalityExpr(listOf(p, q), 1, 2) to
                """{"cardinality":{"children":[{"ref":"p"},{"ref":"q"}],"min":1,"max":2}}""",
            PseudoBooleanExpr(listOf(2, 3), listOf(p, q), PbOp.LE, 4) to
                """{"pseudoBoolean":{"weights":[2,3],"lits":[{"ref":"p"},{"ref":"q"}],"op":"LE","bound":4}}""",
        )
    }

    @Test
    fun `comparisons use one key per operator`() {
        assertShapes(
            BoolExpr.serializer(),
            IntCompare(x, IntCmpOp.LE, IntLit(1)) to """{"le":[{"ref":"x"},1]}""",
            IntCompare(x, IntCmpOp.LT, IntLit(1)) to """{"lt":[{"ref":"x"},1]}""",
            IntCompare(x, IntCmpOp.GE, IntLit(1)) to """{"ge":[{"ref":"x"},1]}""",
            IntCompare(x, IntCmpOp.GT, IntLit(1)) to """{"gt":[{"ref":"x"},1]}""",
            IntCompare(x, IntCmpOp.EQ, y) to """{"eq":[{"ref":"x"},{"ref":"y"}]}""",
            IntCompare(IntLit(0), IntCmpOp.NE, y) to """{"ne":[0,{"ref":"y"}]}""",
        )
    }

    @Test
    fun `integer chains encode as left nested pairs`() {
        assertShapes(
            IntExpr.serializer(),
            IntSum(listOf(x, y, IntLit(1))) to """{"add":[{"add":[{"ref":"x"},{"ref":"y"}]},1]}""",
            IntMin(listOf(IntMin(listOf(x, y)), IntLit(1))) to """{"min":[{"min":[{"ref":"x"},{"ref":"y"}]},1]}""",
        )
    }

    @Test
    fun `n-ary extrema encode as their left nested chains`() {
        val encoded = listOf(IntMin(listOf(x, y, IntLit(1))), IntMax(listOf(x, y, IntLit(1), IntLit(2))))
            .map { SchemaJson.encodeToString(IntExpr.serializer(), it) }

        assertEquals(
            listOf(
                """{"min":[{"min":[{"ref":"x"},{"ref":"y"}]},1]}""",
                """{"max":[{"max":[{"max":[{"ref":"x"},{"ref":"y"}]},1]},2]}""",
            ),
            encoded,
        )
    }

    @Test
    fun `logical chains encode as left nested pairs`() {
        assertShapes(
            BoolExpr.serializer(),
            And(listOf(p, q, Not(p))) to """{"and":[{"and":[{"ref":"p"},{"ref":"q"}]},{"not":{"ref":"p"}}]}""",
            Or(listOf(p, q, Not(p))) to """{"or":[{"or":[{"ref":"p"},{"ref":"q"}]},{"not":{"ref":"p"}}]}""",
        )
    }

    @Test
    fun `chains nested in the right operand keep their grouping`() {
        assertShapes(
            IntExpr.serializer(),
            IntSum(listOf(x, IntSum(listOf(y, IntLit(1))))) to """{"add":[{"ref":"x"},{"add":[{"ref":"y"},1]}]}""",
        )
    }

    @Test
    fun `operand lists longer than two decode as one chain`() {
        val sum = SchemaJson.decodeFromString(IntExpr.serializer(), """{"add":[{"ref":"x"},{"ref":"y"},1]}""")
        val conjunction =
            SchemaJson.decodeFromString(BoolExpr.serializer(), """{"and":[{"ref":"p"},{"ref":"q"},{"ref":"p"}]}""")

        assertEquals(IntSum(listOf(x, y, IntLit(1))), sum)
        assertEquals(And(listOf(p, q, p)), conjunction)
    }

    @Test
    fun `real constraints keep binary64 coefficients and exact expressions`() {
        assertShapes(
            BoolExpr.serializer(),
            FloatLinearConstraint(doubleArrayOf(1.5), listOf("f"), IntCmpOp.GE, 0.5) to
                """{"floatLinear":{"coeffs":[1.5],"varNames":["f"],"op":"GE","bound":0.5}}""",
            ContinuousRealEquality(f, ContinuousRealExpr.Affine(emptyMap(), 2.0)) to
                """{"realEq":[{"affine":{"coefficients":{"f":2.0},"constant":1.5}},""" +
                """{"affine":{"coefficients":{},"constant":2.0}}]}""",
        )
        assertShapes(
            ContinuousRealExpr.serializer(),
            f to """{"affine":{"coefficients":{"f":2.0},"constant":1.5}}""",
            ContinuousRealExpr.Affine(mapOf("f" to 1.0)) to """{"affine":{"coefficients":{"f":1.0}}}""",
            ContinuousRealExpr.Multiply(f, f) to """{"mul":[$AFFINE,$AFFINE]}""",
            ContinuousRealExpr.Power(f, 3) to """{"pow":[$AFFINE,3]}""",
        )
    }

    @Test
    fun `global constraints have stable documented shapes`() {
        val xs = """[{"ref":"x"}]"""
        assertShapes(
            BoolExpr.serializer(),
            AllDifferent(listOf(x, y)) to """{"allDifferent":[{"ref":"x"},{"ref":"y"}]}""",
            SymmetricAllDifferent(listOf(x, y), 1) to
                """{"symmetricAllDifferent":{"terms":[{"ref":"x"},{"ref":"y"}],"indexOffset":1}}""",
            InverseChannel(listOf(x), listOf(y), fOffset = 1) to
                """{"inverse":{"f":$xs,"g":[{"ref":"y"}],"fOffset":1}}""",
            TableConstraint(listOf(x), listOf(listOf(1)), negative = true) to
                """{"table":{"terms":$xs,"tuples":[[1]],"negative":true}}""",
            RegularExpr(listOf(x), 1, 1, listOf(1), 1, listOf(1)) to
                """{"regular":{"seq":$xs,"numStates":1,"alphabetSize":1,"transitions":[1],"q0":1,"accepting":[1]}}""",
            CostRegularExpr(listOf(x), 1, 1, listOf(1), listOf(5), 1, listOf(1), y, symbolOffset = 0) to
                """{"costRegular":{"seq":$xs,"numStates":1,"numSymbols":1,"transitions":[1],"weights":[5],""" +
                """"initial":1,"accepting":[1],"cost":{"ref":"y"},"symbolOffset":0}}""",
            MddExpr(listOf(x), listOf(1, 1), listOf(0, 3), listOf(0, 1, 0), 0, listOf(0)) to
                """{"mdd":{"seq":$xs,"numStatesPerLayer":[1,1],"layerStarts":[0,3],"transitions":[0,1,0],""" +
                """"initial":0,"accepting":[0]}}""",
            CostMddExpr(listOf(x), listOf(1, 1), listOf(0, 4), listOf(0, 1, 0, 7), 0, listOf(0), y) to
                """{"costMdd":{"seq":$xs,"numStatesPerLayer":[1,1],"layerStarts":[0,4],"transitions":[0,1,0,7],""" +
                """"initial":0,"accepting":[0],"cost":{"ref":"y"}}}""",
            CircuitExpr(listOf(x, y), 1) to """{"circuit":{"succ":[{"ref":"x"},{"ref":"y"}],"valueOffset":1}}""",
            SubcircuitExpr(listOf(x)) to """{"subcircuit":{"succ":$xs}}""",
            CumulativeExpr(listOf(x), listOf(2), listOf(1), 3) to
                """{"cumulative":{"starts":$xs,"durations":[2],"resources":[1],"capacity":3}}""",
            DisjunctiveExpr(listOf(x), listOf(2)) to """{"disjunctive":{"starts":$xs,"durations":[2]}}""",
            SortExpr(listOf(x), listOf(y)) to """{"sort":{"xs":$xs,"ys":[{"ref":"y"}]}}""",
            IncreasingExpr(listOf(x), strict = true) to """{"increasing":{"xs":$xs,"strict":true}}""",
            DiffnExpr(listOf(x), listOf(y), listOf(2), listOf(3)) to
                """{"diffn":{"xs":$xs,"ys":[{"ref":"y"}],"widths":[2],"heights":[3]}}""",
        )
    }

    @Test
    fun `presence gated constraints have stable documented shapes`() {
        val xs = """[{"ref":"x"}]"""
        val ps = """[{"ref":"p"}]"""
        assertShapes(
            BoolExpr.serializer(),
            AllDifferentOpt(listOf(x, y), listOf(p, q)) to
                """{"allDifferentOpt":{"terms":[{"ref":"x"},{"ref":"y"}],"presents":[{"ref":"p"},{"ref":"q"}]}}""",
            CumulativeExprOpt(listOf(x), listOf(2), listOf(1), 3, listOf(p)) to
                """{"cumulativeOpt":{"starts":$xs,"durations":[2],"resources":[1],"capacity":3,"presents":$ps}}""",
            DisjunctiveExprOpt(listOf(x), listOf(2), listOf(p)) to
                """{"disjunctiveOpt":{"starts":$xs,"durations":[2],"presents":$ps}}""",
            NValueExprOpt(y, listOf(x), NValueMode.AT_MOST, listOf(p)) to
                """{"nValueOpt":{"n":{"ref":"y"},"xs":$xs,"mode":"AT_MOST","presents":$ps}}""",
            NValueExpr(y, listOf(x), except = listOf(5_000_000_000L)) to
                """{"nValue":{"n":{"ref":"y"},"xs":$xs,"except":[5000000000]}}""",
            GccExprOpt(listOf(x), listOf(1), listOf(0), listOf(1), closed = true, presents = listOf(p)) to
                """{"gccOpt":{"xs":$xs,"cover":[1],"low":[0],"high":[1],"closed":true,"presents":$ps}}""",
        )
    }

    @Test
    fun `set expressions and constraints have stable documented shapes`() {
        assertShapes(
            SetExpr.serializer(),
            s to """{"ref":"s"}""",
            SetLiteral(listOf(1, 2)) to """{"elements":[1,2]}""",
            SetNominalLiteral(listOf("a")) to """{"labels":["a"]}""",
            SetUnion(s, t) to """{"union":[{"ref":"s"},{"ref":"t"}]}""",
            SetIntersect(s, t) to """{"intersect":[{"ref":"s"},{"ref":"t"}]}""",
            SetDiff(s, t) to """{"diff":[{"ref":"s"},{"ref":"t"}]}""",
        )
        assertShapes(
            BoolExpr.serializer(),
            SetIn(x, s) to """{"in":[{"ref":"x"},{"ref":"s"}]}""",
            SetNominalIn("a", s) to """{"labelIn":["a",{"ref":"s"}]}""",
            SetSubsetOf(s, t) to """{"subset":[{"ref":"s"},{"ref":"t"}]}""",
            SetDisjoint(s, t) to """{"disjoint":[{"ref":"s"},{"ref":"t"}]}""",
            SetEq(s, t) to """{"setEq":[{"ref":"s"},{"ref":"t"}]}""",
        )
    }

    @Test
    fun `default fields round trip whether encoded or omitted`() {
        val defaults = Json { encodeDefaults = true }
        val entries = listOf(
            FloatSpec(0.0, 1.0),
            NamedConstraint(BoolRef("p", negated = false)),
            NamedConstraint(SymmetricAllDifferent(listOf(x, y))),
            NamedConstraint(InverseChannel(listOf(x), listOf(y))),
            NamedConstraint(CircuitExpr(listOf(x, y))),
            NamedConstraint(IncreasingExpr(listOf(x))),
            NamedConstraint(TableConstraint(listOf(x), listOf(listOf(1)))),
            NamedConstraint(NValueExprOpt(y, listOf(x), presents = listOf(p))),
            NamedConstraint(ContinuousRealEquality(ContinuousRealExpr.Affine(mapOf("f" to 1.0)), f)),
        )

        val serializer = SchemaEntry.serializer()

        for (entry in entries) {
            for (format in listOf(defaults, SchemaJson)) {
                assertEquals(entry, format.decodeFromString(serializer, format.encodeToString(serializer, entry)))
            }
        }
    }

    @Test
    fun `wide declarations and constraint roles have stable documented shapes`() {
        assertShapes(
            SchemaEntry.serializer(),
            IntDomainSpec(lower = -5_000_000_000) to """{"intDomain":{"lower":-5000000000}}""",
            IntDomainSpec(0, 9, listOf(listOf(0L, 2L), listOf(7L, 9L))) to
                """{"intDomain":{"lower":0,"upper":9,"values":[[0,2],[7,9]]}}""",
            FloatSpec(min = 0.0) to """{"float":{"min":0.0}}""",
            NamedConstraint(p, ConstraintRole.IMPLIED) to """{"implied":{"ref":"p"}}""",
            NamedConstraint(p, ConstraintRole.SYMMETRY_BREAKING) to """{"symmetryBreaking":{"ref":"p"}}""",
        )
    }

    @Test
    fun `native constraints have stable documented shapes`() {
        val node = Json.parseToJsonElement("""{"valuePrecede":{"s":1,"t":2,"xs":[0,1]}}""")
        assertShapes(
            BoolExpr.serializer(),
            LexExpr(listOf(x), listOf(y), strict = true) to
                """{"lexLess":{"xs":[{"ref":"x"}],"ys":[{"ref":"y"}],"strict":true}}""",
            VariableCumulativeExpr(listOf(x), listOf(y), listOf(IntLit(1)), IntLit(2)) to
                """{"cumulativeVars":{"starts":[{"ref":"x"}],"durations":[{"ref":"y"}],"demands":[1],"capacity":2}}""",
            FactorConstraint(node, ints = listOf("x", "y")) to
                """{"factor":{"node":{"valuePrecede":{"s":1,"t":2,"xs":[0,1]}},"ints":["x","y"]}}""",
        )
    }

    @Test
    fun `malformed payloads report their compact paths`() {
        val cases = listOf(
            """{"int":[0]}""" to "$.int: expected 2 operands",
            """{"int":[0,"9"]}""" to "$.int[1]: expected an integer",
            """{"int":[5,1]}""" to "$.int: IntSpec needs min <= max",
            """{"intDomain":{"lower":0,"upper":9,"values":[[0,2],[3,4]]}}""" to
                "$.intDomain: IntDomainSpec runs must ascend with gaps",
            """{"float":{"min":0.0,"max":1.0,"bucket":3}}""" to "$.float.bucket: unknown field",
            """{"constraint":{"and":[{"ref":"a"},{"bogus":1}]}}""" to "$.constraint.and[1].bogus: unknown operator",
            """{"constraint":{"ge":[{"ref":"x"},1.5]}}""" to "$.constraint.ge[1]: expected an integer",
            """{"constraint":{"ge":[{"mul":[2.5,{"ref":"x"}]},1]}}""" to
                "$.constraint.ge[0].mul[0]: expected an integer",
            """{"constraint":{"ge":{"left":{"ref":"x"},"right":1}}}""" to "$.constraint.ge: expected an operand array",
            """{"constraint":{"notRef":{"name":"p"}}}""" to "$.constraint.notRef: expected STRING",
            """{"${'$'}type":"int","min":0,"max":1}""" to "$: expected exactly one operator",
        )

        for ((payload, message) in cases) {
            val error = assertFailsWith<SerializationException>(payload) {
                SchemaJson.decodeFromString(SchemaEntry.serializer(), payload)
            }
            assertTrue(error.message.orEmpty().startsWith(message), error.message)
        }
    }

    @Test
    fun `binary formats retain the generated sealed tags`() {
        val definition = SchemaDef<SchemaEntry>(
            mapOf(
                "x" to IntSpec(0, 9),
                "f" to FloatSpec(0.0, 1.0),
                "c" to NamedConstraint(Implies(BoolRef("p", negated = true), IntCompare(x, IntCmpOp.GE, IntLit(3)))),
            ),
        )
        val serializer = SchemaDef.serializer(SchemaEntry.serializer())

        val encoded = ProtoBuf.encodeToByteArray(serializer, definition)

        val text = encoded.decodeToString()
        assertTrue("intcmp" in text && "intref" in text && "constraint" in text, text)
        assertEquals(definition, ProtoBuf.decodeFromByteArray(serializer, encoded))
    }

    @Test
    fun `every vocabulary JSON Schema lists exactly its operator keys`() {
        for (serializer in ModelJson.serializers) {
            val document = serializer.toJsonSchema()
            val root = document.getValue("\$defs").jsonObject.getValue(serializer.serialName).jsonObject

            val keys = root.getValue("anyOf").jsonArray.flatMap { operatorKeys(it.jsonObject) }.toSet()

            val expected = serializer.nodes.filter { it.shape != JsonNodeShape.Literal }.map { it.name }.toSet()
            assertEquals(expected, keys, serializer.serialName)
        }
    }

    @Test
    fun `comparison schemas describe operand pairs without the operator field`() {
        val document = BoolExprSerializer.toJsonSchema()
        val branches = document.getValue("\$defs").jsonObject.getValue(BoolExprSerializer.serialName).jsonObject
            .getValue("anyOf").jsonArray.map { it.jsonObject }

        val ge = branches.single { "ge" in operatorKeys(it) }
            .getValue("properties").jsonObject.getValue("ge").jsonObject

        assertEquals(JsonPrimitive("array"), ge["type"])
        assertEquals(JsonPrimitive(2), ge["minItems"])
        assertEquals(JsonPrimitive(2), ge["maxItems"])
    }

    private fun <T> assertShapes(serializer: KSerializer<T>, vararg cases: Pair<T, String>) {
        for ((value, expected) in cases) {
            assertEquals(expected, SchemaJson.encodeToString(serializer, value))
            assertEquals(value, SchemaJson.decodeFromString(serializer, expected))
        }
    }

    private fun operatorKeys(branch: JsonObject): Set<String> =
        branch["anyOf"]?.jsonArray?.flatMap { operatorKeys(it.jsonObject) }?.toSet()
            ?: branch["const"]?.let { setOf(it.jsonPrimitive.content) }
            ?: branch["properties"]?.jsonObject?.keys
            ?: emptySet()

    private companion object {
        const val AFFINE = """{"affine":{"coefficients":{"f":2.0},"constant":1.5}}"""
    }
}
