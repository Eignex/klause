package com.eignex.klause.formats.json

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.factor.codec.factorCodec
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.Sort
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
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
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.intdomain.intDomainFromRuns
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.shl
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
    private val wide = bigIntOf(1) shl 100

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

    private fun ratio(num: Long, den: Long) = BigFraction.of(bigIntOf(num), bigIntOf(den))

    private fun samples(): List<Pair<String, Factor>> = listOf(
        "clause" to Clause(intArrayOf(lit(0), lit(1, false))),
        "cardinality" to Cardinality(intArrayOf(lit(0), lit(1), lit(2)), 1, 2),
        "xor" to Xor(intArrayOf(lit(0), lit(1), lit(2, false)), 1),
        "gaussianXor" to GaussianXor(listOf(Xor(intArrayOf(lit(0), lit(1)), 1), Xor(intArrayOf(lit(1), lit(2)), 0))),
        "pseudoBoolean" to PseudoBoolean(longArrayOf(3, -5), intArrayOf(lit(0), lit(1, false)), PbOp.LE, 2),
        "reifiedCardinality" to ReifiedCardinality(5, intArrayOf(lit(0), lit(1)), 1, 2),
        "reifiedPseudoBoolean" to ReifiedPseudoBoolean(
            5, arrayOf(wide, bigIntOf(-3)), intArrayOf(lit(0), lit(1)), PbOp.GE, bigIntOf(7),
        ),
        "linear" to Linear(longArrayOf(2, -3), intArrayOf(0, 1), LinearOp.GE, 4),
        "linear" to Linear(intArrayOf(0, 1), arrayOf(wide, bigIntOf(-1)), LinearOp.EQ, wide),
        "linear" to Linear(
            intArrayOf(0), doubleArrayOf(0.5), intArrayOf(1), doubleArrayOf(-1.25), LinearOp.LE, 2.5, true,
        ),
        "reifiedLinear" to ReifiedLinear(4, intArrayOf(0, 1), arrayOf(wide, BIG_ONE), LinearOp.EQ, wide),
        "exactLinear" to ExactLinear.of(
            intArrayOf(Term.ofIntVar(0), Term.ofRealVar(1)),
            listOf(ratio(1, 3), BigFraction.ONE),
            LinearOp.GE,
            ratio(5, 2),
            strict = true,
            activator = 3,
        ),
        "linearIndicator" to LinearIndicator(
            2,
            true,
            ExactLinear.of(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ofLong(4)),
        ),
        "reifiedRealLinear" to ReifiedRealLinear(
            1, intArrayOf(0), doubleArrayOf(1.5), intArrayOf(0), doubleArrayOf(-0.25), LinearOp.LE, 3.75, true,
        ),
        "comparisonClause" to ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.LE, LinearOp.GE), longArrayOf(3, -2)),
        "product" to Product(0, 1, 2),
        "power" to Power(0, 2, 1),
        "power" to Power(Power.Coordinate.Real(SourceRealId(0)), 3, Power.Coordinate.Real(SourceRealId(1))),
        "realProduct" to RealProduct(0, 0, 1, -5.0, Double.POSITIVE_INFINITY),
        "continuousProduct" to ContinuousProduct(0, 1, 2),
        "continuousProduct" to ContinuousProduct(
            0, 1, 2, 0, doubleArrayOf(0.5, -1.0), intArrayOf(lit(0), lit(1, false)),
        ),
        "abs" to Abs(0, 1),
        "realAbs" to RealAbs(0, 1),
        "affineAbs" to AffineAbs(
            AbsAffineValue(mapOf(0 to wide, 1 to bigIntOf(-2)), bigIntOf(3)),
            AbsAffineValue(mapOf(2 to BIG_ONE), BIG_ZERO),
        ),
        "gridAbs" to GridAbs(0, 1, -2.0, 2.0, 5, 0.0, 2.0, 3),
        "arrayMinMax" to ArrayMinMax(0, intArrayOf(1, 2, 2), true),
        "realArrayMinMax" to RealArrayMinMax(0, intArrayOf(1, 2), false),
        "gridArrayMinMax" to GridArrayMinMax(
            intArrayOf(0, 1), doubleArrayOf(0.5, 0.25), doubleArrayOf(0.0, -1.0), booleanArrayOf(false, true), 4, true,
        ),
        "gridProduct" to GridProduct(
            GridCoordinate(0, 0.0, 1.0, 3), GridCoordinate(1, -1.0, 1.0, 5), GridCoordinate(2, -1.0, 1.0, 9),
        ),
        "gridPower" to GridPower(GridCoordinate(0, -1.0, 1.0, 5), 2, GridCoordinate(1, 0.0, 1.0, 5)),
        "divMod" to DivMod(0, 1, 2, 3),
        "divMod" to DivMod(0, wide, 1, 2, DivisionConvention.EUCLIDEAN),
        "wideDivMod" to WideDivMod(
            DivModExpression(mapOf(0 to wide), BIG_ONE),
            bigIntOf(7),
            DivModExpression(mapOf(1 to BIG_ONE), BIG_ZERO),
            DivModExpression(mapOf(2 to BIG_ONE), BIG_ZERO),
        ),
        "realFiniteDomain" to RealFiniteDomain(0, doubleArrayOf(1.5, -0.0, 0.1)),
        "allDifferent" to AllDifferent(
            intArrayOf(0, 1, 2), intArrayOf(lit(0), lit(1), lit(2)), longArrayOf(0, -4), boundsConsistent = true,
        ),
        "allDifferentTuples" to AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3))),
        "distinct" to Distinct(
            listOf(
                Distinct.Operand(
                    intArrayOf(Term.ofIntVar(0), Term.ofLit(lit(1, false))),
                    listOf(BigFraction.ONE, ratio(1, 2)),
                    ratio(-1, 3),
                ),
                Distinct.Operand(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.of(wide, BIG_ONE))),
            ),
            activator = 2,
        ),
        "globalCardinality" to GlobalCardinality(intArrayOf(0, 1, 2), longArrayOf(1, 2), countVars = intArrayOf(3, 4)),
        "globalCardinality" to GlobalCardinality(
            intArrayOf(0, 1),
            longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
            countLow = intArrayOf(0, 1),
            countHigh = intArrayOf(1, 2),
            closed = true,
            presents = intArrayOf(lit(0), lit(1)),
        ),
        "nValue" to NValue(2, intArrayOf(0, 1, 2), NValue.Mode.AtMost, exceptSet = longArrayOf(0)),
        "increasing" to Increasing(intArrayOf(0, 1, 2), true),
        "sort" to Sort(intArrayOf(0, 1), intArrayOf(2, 3)),
        "valuePrecede" to ValuePrecede(Long.MIN_VALUE, Long.MAX_VALUE, intArrayOf(0, 1, 2)),
        "lexLess" to LexLess(intArrayOf(0, 1), intArrayOf(2, 3), false),
        "inverse" to Inverse(intArrayOf(0, 1), intArrayOf(2, 3), 1, 0),
        "symmetricAllDifferent" to SymmetricAllDifferent(intArrayOf(0, 1, 2, 3), 1),
        "circuit" to Circuit(intArrayOf(0, 1, 2), subcircuit = true),
        "cumulative" to Cumulative(
            intArrayOf(0, 1), longArrayOf(2, 3), longArrayOf(1, 1), 2,
            durationVars = intArrayOf(2, 3), resourceVars = intArrayOf(4, 5), capacityVar = 5,
        ),
        "diffn" to Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(1, 2), longArrayOf(2, 1), nonStrict = true),
        "element" to Element(0, 1, longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE), false, 0),
        "element" to Element(0, 1, longArrayOf(2, 3), true),
        "realElement" to RealElement(0, 1, intArrayOf(2, 3), 1),
        "realElement" to RealElement(0, 1, doubleArrayOf(-0.5, 1e-300), 0),
        "table" to Table(intArrayOf(0, 1), longArrayOf(1, 2, 3, 4), longArrayOf(1, 5, 3, 4)),
        "regular" to Regular(intArrayOf(0, 1), 2, 2, longArrayOf(1, 2, 2, 0), 1, intArrayOf(2)),
        "mdd" to Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 1), longArrayOf(0, 5, 0), 0, intArrayOf(0), 3),
        "symmetryHandling" to SymmetryHandling(listOf(intArrayOf(1, 0, 2, 3, 4, 5) to intArrayOf(0, 1, 2, 3, 4, 5))),
    )

    @Test
    fun `every built-in codec should round trip a factor with its native kind and payload`() {
        val samples = samples()

        for ((key, factor) in samples) {
            val decoded = roundTrip(problem(factor)).factors.single()

            assertEquals(factor::class, decoded::class, key)
            assertEquals(factor.structuralKey(), decoded.structuralKey(), key)
        }
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
            """{"bools": 1, "extra": 1}""" to "$.extra:",
            factorDocument("""{"linear": {"vars": [0], "op": "LE", "bound": 2, "wideBound": "5"}}""") to
                "$.factors[0].linear:",
            factorDocument("""{"divMod": {"dividend": 0, "quotient": 0, "remainder": 0, "constantDivisor": "x"}}""") to
                "$.factors[0].divMod",
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
