package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.lowering.powerAsProducts
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.pow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.bigIntOf
import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PowerEncodersTest {
    @Test
    fun `linear powers require captured representable operand fixings`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-10, 100) }, arrayOf<Factor>(Power(0, 3, 1)))
        val prepared = FactorProjectionProviders.Default.prepare(source)
        val encoder = prepared.encoder(0, EncodingTarget.LINEAR)

        assertEquals(EncoderSupport.CONDITIONED, encoder.support)
        assertEquals(listOf(SourceIntId(0)), encoder.requiredIntegers)
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR))
        for (operand in listOf(-2L, 3L)) {
            val fragment = assertNotNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
                fixedIntegers = mapOf(SourceIntId(0) to bigIntOf(operand))))
            val row = fragment.factors.single().linearForm!!.rows.single()

            assertEquals(bigIntOf(operand * operand * operand), (row.constants as IntegralConstants).exactBound)
        }
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
            fixedIntegers = mapOf(SourceIntId(0) to bigIntOf(Long.MAX_VALUE))))
    }

    @Test
    fun `exact frontend power relations retract a conflicting real fixing`() {
        val x = FloatHandle("x", -3.0, 3.0)
        val sources = listOf(
            SchemaDef<SchemaEntry>(mapOf("x" to FloatSpec(-3.0, 3.0, 7),
                "power" to NamedConstraint(x.pow(2) eq 4.0))).compile().problem,
            parseFlatZinc("var -3.0..3.0: x; constraint float_pow(x, 2.0, 4.0); solve satisfy;",
                exactFloats = true).problem,
        )
        for (source in sources) {
            val power = source.factors.filterIsInstance<Power>().single()
            assertEquals(Power.Coordinate.Real(SourceRealId(0)), power.base)
            val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
            assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
            model.service.invocation().use { invocation ->
                val conflict = invocation.check(PolynomialCondition(lower = mapOf(0 to BigFraction.ZERO),
                    upper = mapOf(0 to BigFraction.ZERO)))
                val sibling = invocation.check(PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(-2)),
                    upper = mapOf(0 to BigFraction.ofLong(-2))))

                assertIs<QfNraResult.Unsatisfiable>(conflict)
                assertIs<QfNraResult.Satisfiable>(sibling)
            }
        }
    }

    @Test
    fun `selected multiplication fallback preserves joint conflicts and source attribution`() {
        val source = parseFlatZinc("var -3.0..3.0: x; var -27.0..27.0: y; " +
            "constraint float_pow(x, 3.0, y); constraint float_eq(y, 8.0); solve satisfy;",
            exactFloats = true).problem
        val fallback = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor !is Power || target != EncodingTarget.POLYNOMIAL) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                view as Power
                val base = encoding.real((view.base as Power.Coordinate.Real).id)
                val output = encoding.real((view.result as Power.Coordinate.Real).id)
                encoding.polynomial(PolynomialRealFormula.Atom(powerAsProducts(base, view.exponent),
                    PolynomialRelation.EQ, output))
                null
            }
        })
        for (providers in listOf(FactorProjectionProviders.Default, fallback)) {
            val model = assertNotNull(source.prepareExecutionPlan(providers).polynomialModel)
            val attribution = model.encodingAttributions.first()
            assertEquals(0, attribution.sourceFactorIndex)
            assertEquals(listOf(SourceRealId(0), SourceRealId(1)), attribution.reals)
            assertEquals(emptyList(), attribution.auxiliaries)
            model.service.invocation().use { invocation ->
                assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(
                    lower = mapOf(0 to BigFraction.ZERO), upper = mapOf(0 to BigFraction.ZERO))))
                val result = invocation.check(PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2)),
                    upper = mapOf(0 to BigFraction.ofLong(2))))

                assertIs<QfNraResult.Satisfiable>(result)
            }
        }
    }

    @Test
    fun `polynomial completion preserves finite power choices and holes`() {
        for (value in listOf(3.0, 4.0)) {
            val source = Problem(0, 2, arrayOf(IntDomain(-2, 2).excludeValue(0), IntDomain(0, 4)),
                arrayOf<Factor>(Power(0, 2, 1), RealProduct(1, 0, 1, 1.0, 1.0)), numRealVars = 2,
                realLower = doubleArrayOf(1.0, value), realUpper = doubleArrayOf(1.0, value))

            val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

            if (value == 3.0) assertIs<OpenTheoryResult.Unsat>(execution.result)
            else {
                val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
                assertEquals("4", witness.intValue(1))
                val input = witness.intValue(0).toLong()
                assertEquals(4L, input * input)
            }
        }
    }

    @Test
    fun `exact real power witnesses retain their algebraic coordinate`() {
        val source = parseFlatZinc("var -2.0..2.0: x; constraint float_pow(x, 2.0, 2.0); solve satisfy;",
            exactFloats = true).problem
        val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)

        val result = model.service.invocation().use { it.check() }

        val root = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertNull(root.rational)
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), root))
    }

    @Test
    fun `float powers reject variable negative and fractional exponents`() {
        for (exponent in listOf("y", "-1.0", "0.5")) {
            assertFailsWith<UnsupportedFlatZincException> {
                parseFlatZinc("var -3.0..3.0: x; var 0.0..2.0: y; " +
                    "constraint float_pow(x, $exponent, 4.0); solve satisfy;", exactFloats = true)
            }
        }
    }
}
