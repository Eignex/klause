package com.eignex.klause.formats.json

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.factor.codec.factorCodec
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.factor.codec.sampleWide
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.intdomain.intDomainFromRuns
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.bigIntOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemJsonTest {
    private val json = ProblemJson.default
    private val wide = sampleWide

    private fun problem(vararg factors: Factor): Problem = Problem(
        numBoolVars = 6,
        intBounds = IntBounds.fromModelBounds(LongArray(6) { -10 }, LongArray(6) { 10 }, null, null),
        factors = arrayOf(*factors),
        numRealVars = 4,
        realLower = DoubleArray(4) { -5.0 },
        realUpper = DoubleArray(4) { 5.0 },
    )

    private fun roundTrip(problem: Problem, codec: ProblemJson = json): Problem {
        val text = codec.encodeToString(problem)
        val decoded = codec.decodeFromString(text)
        assertEquals(text, codec.encodeToString(decoded))
        return decoded
    }

    private fun lit(variable: Int, positive: Boolean = true) = Lit.make(variable, positive)

    private fun factorDocument(factor: String) = """{"ints": [{"lower": 0, "upper": 3}], "factors": [$factor]}"""

    @Test
    fun `every built-in codec should round trip a factor with its native kind and payload`() {
        val samples = factorCodecSamples()
        val factors = samples.map { it.second }

        val decoded = roundTrip(problem(*factors.toTypedArray())).factors.toList()

        assertEquals(factors.map { it::class }, decoded.map { it::class })
        assertEquals(factors.map { it.structuralKey() }, decoded.map { it.structuralKey() })
        assertEquals(FactorCodecs.builtin.codecs.map { it.key }.toSet(), samples.map { it.first }.toSet())
    }

    @Test
    fun `a factor should be written as its key holding its payload`() {
        val document = json.encodeToJsonElement(problem(Linear(longArrayOf(2, -3), intArrayOf(0, 1), LinearOp.GE, 4)))

        assertEquals(
            Json.parseToJsonElement("""{"linear":{"vars":[0,1],"op":"LE","coefficients":[-2,3],"bound":-4}}"""),
            document.getValue("factors").jsonArray.single(),
        )
    }

    @Test
    fun `arbitrary width constants should be written as exact decimal strings`() {
        val row = ReifiedLinear(4, intArrayOf(0, 1), arrayOf(wide, BIG_ONE), LinearOp.EQ, wide)

        val text = json.encodeToString(problem(row))

        assertTrue("\"$wide\"" in text, text)
        val decoded = json.decodeFromString(text).factors.single() as ReifiedLinear
        assertEquals(wide, decoded.wideConstants?.bound)
    }

    @Test
    fun `integer columns should keep long endpoints open sides value sets and fallback boxes`() {
        val sparse = intDomainFromRuns(
            LongArrayList().apply {
                add(0)
                add(2)
                add(1_000_000_000_000_000)
                add(1_000_000_000_000_000)
                add(Long.MAX_VALUE)
                add(Long.MAX_VALUE)
            },
        )
        val openLo = Bits(5).apply {
            set(3)
            set(4)
        }
        val openHi = Bits(5).apply { set(4) }
        val source = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(Long.MIN_VALUE, Long.MAX_VALUE),
                IntDomain(0, 10),
                sparse,
                IntDomain(-1000, 5),
                IntDomain(-7, 7),
            ),
            factors = emptyArray(),
            modelBounds = IntBounds.fromModelBounds(
                longArrayOf(Long.MIN_VALUE, 0, 0, 0, 0),
                longArrayOf(Long.MAX_VALUE, 100, Long.MAX_VALUE, 5, 0),
                openLo,
                openHi,
            ),
        )

        val decoded = roundTrip(source)

        val bounds = decoded.intBounds
        assertEquals(Long.MIN_VALUE, bounds.lower(0))
        assertEquals(Long.MAX_VALUE, bounds.upper(0))
        assertEquals(100, bounds.upper(1))
        assertEquals(10, decoded.intDomainOrNull(1)?.max)
        val values = requireNotNull(decoded.intDomainOrNull(2))
        assertTrue(1_000_000_000_000_000 in values && Long.MAX_VALUE in values)
        assertFalse(999_999_999_999_999 in values)
        assertTrue(bounds.isOpenLower(3) && bounds.upper(3) == 5L && bounds.isOpenLower(4) && bounds.isOpenUpper(4))
        assertEquals(-1000, decoded.declaredIntDomains.finiteDomain(3).min)
        assertEquals(7, decoded.declaredIntDomains.finiteDomain(4).max)
    }

    @Test
    fun `real columns should keep infinite signed zero and subnormal bounds`() {
        val lower = doubleArrayOf(Double.NEGATIVE_INFINITY, -0.0, Double.MIN_VALUE)
        val upper = doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, Double.MAX_VALUE)
        val source = Problem(
            0,
            IntBounds.fromModelBounds(LongArray(0), LongArray(0), null, null),
            emptyArray(),
            numRealVars = 3,
            realLower = lower,
            realUpper = upper,
        )

        val decoded = roundTrip(source)

        assertEquals(lower.map { it.toRawBits() }, decoded.realLower.map { it.toRawBits() })
        assertEquals(upper.map { it.toRawBits() }, decoded.realUpper.map { it.toRawBits() })
    }

    @Test
    fun `model metadata should round trip`() {
        val semantics = SourceSemantics(
            SourceFormat.MPS,
            NumericAuthority.DECIMAL_LITERALS,
            floats = FloatSemantics.Grid(
                16,
                1000,
                UndeclaredRealBounds(SemanticRational.of(bigIntOf(-1), bigIntOf(3)), SemanticRational.ofLong(9)),
            ),
            undeclaredIntegers = UndeclaredIntegerBounds(0, null),
            undeclaredReals = UndeclaredRealBounds(null, SemanticRational.ofLong(5)),
            feasibilityTolerance = SourceTolerance(
                SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
                SemanticRational.ZERO,
            ),
            pinAbsentOptionalValues = true,
        ).resolve(
            SemanticOverrides(
                floats = FloatSemantics.Continuous,
                undeclaredIntegers = BoundsOverride(lower = BoundOverride.Open),
            ),
        )
        val settings = ProblemSettings(DomainStorageSettings(128), 10, 20, 30)
        val source = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(0), LongArray(0), null, null),
            factors = arrayOf(Clause(intArrayOf(lit(0))), Clause(intArrayOf(lit(1)))),
            impliedFactorMask = booleanArrayOf(false, true),
            hasSymmetryBreaking = true,
            settings = settings,
            semantics = semantics,
        )

        val decoded = roundTrip(source)

        assertEquals(listOf(false, true), decoded.impliedFactorMask?.toList())
        assertTrue(decoded.hasSymmetryBreaking)
        assertEquals(settings, decoded.settings)
        assertEquals(semantics.declared, decoded.semantics.declared)
        assertEquals(semantics.effective, decoded.semantics.effective)
    }

    @Test
    fun `a compiled schema problem should round trip`() {
        val schema = JsonSchema.parse(
            """
            {"entries": {
              "size": {"int": [1, 10]},
              "shift": {"nominal": ["day", "night"]},
              "rule": {"constraint": {"implies": [{"nominalEq": ["shift", "night"]}, {"ge": [{"ref": "size"}, 3]}]}}}}
            """.trimIndent(),
        )
        val source = schema.compile().problem

        val decoded = roundTrip(source)

        assertEquals(source.factors.map { it::class }, decoded.factors.map { it::class })
        assertEquals(source.factors.map { it.structuralKey() }, decoded.factors.map { it.structuralKey() })
    }

    @Test
    fun `a custom factor should round trip through a registered codec`() {
        val codec = ProblemJson(FactorCodecs.builtin + parityCodec)
        val source = problem(Parity(3, even = true), Clause(intArrayOf(lit(0))))

        val decoded = roundTrip(source, codec)

        val parity = decoded.factors.first() as Parity
        assertEquals(3, parity.x)
        assertTrue(parity.even)
    }

    @Test
    fun `encoding a factor without a codec should fail naming its position`() {
        val error = assertFailsWith<MissingFactorCodecException> {
            json.encodeToString(problem(Clause(intArrayOf(lit(0))), Parity(1, even = false)))
        }

        assertEquals("$.factors[1]", error.path)
    }

    @Test
    fun `decoding a key without a codec should fail naming its position`() {
        val text = """{"ints": [{"lower": 0, "upper": 3}], "factors": [{"parity": {"x": 0, "even": true}}]}"""

        val error = assertFailsWith<MissingFactorCodecException> { json.decodeFromString(text) }

        assertEquals("$.factors[0].parity", error.path)
    }

    @Test
    fun `malformed documents should fail at their path`() {
        val cases = listOf(
            """{"ints": [{"lower": 3, "upper": 0}]}""" to "$.ints[0]:",
            """{"ints": [{"lower": 0, "upper": 9, "values": [[0, 1], [2, 3]]}]}""" to "$.ints[0]:",
            """{"bools": 1, "factors": [{"clause": {"literals": [0]}}], "implied": [2]}""" to "$.implied[0]:",
            """{"bools": 1, "factors": [{"clause": {"literals": [4]}}]}""" to "$:",
            """{"bools": 1, "extra": 1}""" to "unknown key 'extra'",
            factorDocument("""{"linear": {"vars": [0], "op": "LE", "bound": 2, "wideBound": "5"}}""") to
                "$.factors[0].linear:",
            factorDocument("""{"divMod": {"dividend": 0, "quotient": 0, "remainder": 0, "constantDivisor": "x"}}""") to
                "expected a decimal integer string, got 'x'",
        )

        for ((text, path) in cases) {
            val error = assertFailsWith<JsonFormatException>(text) { json.decodeFromString(text) }
            assertTrue(path in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `the JSON Schema should describe every registered factor key`() {
        val schema = ProblemJson(FactorCodecs.builtin + parityCodec).jsonSchema().toString()

        val keys = FactorCodecs.builtin.codecs.map { it.key } + "parity"
        assertEquals(emptyList(), keys.filterNot { "\"$it\"" in schema })
        assertTrue("\$defs" in Json.parseToJsonElement(schema).jsonObject)
    }
}

private class Parity(val x: Int, val even: Boolean) : Factor {
    override val variables: VarList = IntVars(intArrayOf(x))

    override fun remap(mapping: VarRemap): Factor = Parity(mapping.int(x), even)

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) {
        int(x)
        bool(even)
    }
}

@Serializable
private class ParityPayload(val x: Int, val even: Boolean)

private val parityCodec = factorCodec<Parity, ParityPayload>(
    "parity",
    { ParityPayload(it.x, it.even) },
    { Parity(it.x, it.even) },
)
