package com.eignex.klause.schema

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.codec.factorCodecSamples
import com.eignex.klause.factor.codec.sampleWide
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.PbOp
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeFactorsTest {
    private val bools = List(6) { BoolHandle("b$it") }
    private val ints = List(6) { IntHandle("i$it", -10, 10) }
    private val reals = List(4) { FloatHandle("r$it", -5.0, 5.0) }
    private val serializer = SchemaDef.serializer(SchemaEntry.serializer())

    private fun definition(constraints: List<BoolExpr>): SchemaDef<SchemaEntry> = SchemaDef(
        buildMap<String, SchemaEntry> {
            bools.forEach { put(it.name, BoolSpec) }
            ints.forEach { put(it.name, IntSpec(-10, 10)) }
            reals.forEach { put(it.name, FloatSpec(-5.0, 5.0)) }
            constraints.forEachIndexed { i, constraint -> put("c$i", NamedConstraint(constraint)) }
        },
    )

    private fun ratio(num: Long, den: Long) = BigFraction.of(bigIntOf(num), bigIntOf(den))

    private fun compiledFactors(vararg constraints: BoolExpr): List<Factor> =
        definition(constraints.toList()).compile().problem.factors.toList()

    @Test
    fun `every built-in factor should pass through schema JSON and compilation with its kind and payload`() {
        val samples = factorCodecSamples().map { it.second }
        val schema = definition(samples.map { factor(it, bools, ints, reals) })

        val parsed = JsonSchema.parse(SchemaJson.encodeToString(serializer, schema))
        val problem = parsed.compile().problem
        val written = ProblemJson.default.decodeFromString(ProblemJson.default.encodeToString(problem))

        assertEquals(schema, parsed)
        assertEquals(samples.map { it::class }, written.factors.map { it::class })
        assertEquals(samples.map { it.structuralKey() }, written.factors.map { it.structuralKey() })
    }

    @Test
    fun `each named builder should post its native factor`() {
        val (x, y, z) = ints
        val w = ints[3]
        val (b, c) = bools
        val (r, s, t) = reals
        val cases: List<Pair<BoolExpr, KClass<out Factor>>> = listOf(
            valuePrecede(1, 2, listOf(x, y, z)) to ValuePrecede::class,
            allDifferentTuples(listOf(listOf(x, y), listOf(z, w))) to AllDifferentTuples::class,
            allDifferentExcept(listOf(x, y, z), setOf(0L)) to AllDifferent::class,
            nValue(w, listOf(x, y, z), NValue.Mode.AtMost, setOf(0L)) to NValue::class,
            distinct(
                listOf(
                    AffineOperand(ints = listOf(x to ratio(1, 2)), constant = ratio(1, 3)),
                    AffineOperand(bools = listOf(!b to BigFraction.ONE), reals = listOf(r to ratio(-2, 1))),
                ),
            ) to Distinct::class,
            linear(listOf(sampleWide, BIG_ONE), listOf(x, y), LinearOp.EQ, sampleWide) to Linear::class,
            exactLinear(listOf(x to ratio(1, 3)), listOf(r to BigFraction.ONE), LinearOp.LE, ratio(5, 2), true) to
                ExactLinear::class,
            pseudoBoolean(listOf(sampleWide, BIG_ONE), listOf(b, !c), PbOp.GE, BIG_ONE) to PseudoBoolean::class,
            realAbs(r, s) to RealAbs::class,
            realMin(r, listOf(s, t)) to RealArrayMinMax::class,
            realMax(r, listOf(s, t)) to RealArrayMinMax::class,
            realElement(x, listOf(s, t), r) to RealElement::class,
            realElement(x, listOf(0.5, -1.5), r) to RealElement::class,
            (r inValues listOf(0.25, 1.0)) to RealFiniteDomain::class,
        )

        val factors = compiledFactors(*cases.map { it.first }.toTypedArray())

        assertEquals(cases.map { it.second }, factors.map { it::class })
    }

    @Test
    fun `a bound name should map the factor local ids to the compiled columns`() {
        val factor = compiledFactors(valuePrecede(1, 2, listOf(ints[4], ints[2]))).single() as ValuePrecede

        assertEquals(listOf(4, 2), factor.xs.toList())
    }

    @Test
    fun `a constant distinct count should restrict the number of distinct values`() {
        class S : VariableSchema() {
            val x by intVar(1, 3)
            val y by intVar(1, 3)
            val z by intVar(1, 3)
            val count by constraint { nValue(2, listOf(x, y, z)) }
        }
        val definition = S().definition()
        val parsed = JsonSchema.parse(SchemaJson.encodeToString(serializer, definition))
        val compiled = parsed.compile()
        val ids = listOf("x", "y", "z").map { compiled.intVarIdByName.getValue(it) }

        val samples = BacktrackSolver(compiled.problem.bake()).enumerate().toList()

        assertEquals(definition, parsed)
        assertEquals(18, samples.size)
        samples.forEach { sample -> assertEquals(2, ids.map { sample.intValue(it) }.distinct().size) }
    }

    @Test
    fun `a variable distinct count should exclude the requested values`() {
        class S : VariableSchema() {
            val n by intVar(0, 2)
            val x by intVar(0, 2)
            val y by intVar(0, 2)
            val count by constraint { nValue(n, listOf(x, y), except = setOf(0L)) }
        }
        val definition = S().definition()
        val parsed = JsonSchema.parse(SchemaJson.encodeToString(serializer, definition))
        val compiled = parsed.compile()
        val n = compiled.intVarIdByName.getValue("n")
        val ids = listOf("x", "y").map { compiled.intVarIdByName.getValue(it) }

        val samples = BacktrackSolver(compiled.problem.bake()).enumerate().toList()

        assertEquals(definition, parsed)
        assertEquals(9, samples.size)
        samples.forEach { sample ->
            assertEquals(ids.map { sample.intValue(it) }.filter { it != 0L }.distinct().size.toLong(), sample.intValue(n))
        }
    }

    @Test
    fun `an exact distinct operand should keep its rational coefficients`() {
        val operands = listOf(
            AffineOperand(ints = listOf(ints[0] to ratio(1, 3))),
            AffineOperand(reals = listOf(reals[1] to ratio(-7, 2)), constant = ratio(1, 5)),
        )

        val factor = compiledFactors(distinct(operands)).single() as Distinct

        assertEquals(listOf(listOf(ratio(1, 3)), listOf(ratio(-7, 2))), factor.operands.map { it.coefficients })
        assertEquals(ratio(1, 5), factor.operands[1].constant)
    }
}
