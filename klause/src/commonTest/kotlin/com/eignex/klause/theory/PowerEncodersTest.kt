package com.eignex.klause.theory

import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import com.eignex.klause.formats.flatzinc.parseFlatZinc
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
    fun `float powers reject variable negative and fractional exponents`() {
        for (exponent in listOf("y", "-1.0", "0.5")) {
            assertFailsWith<UnsupportedFlatZincException> {
                parseFlatZinc("var -3.0..3.0: x; var 0.0..2.0: y; " +
                    "constraint float_pow(x, $exponent, 4.0); solve satisfy;", exactFloats = true)
            }
        }
    }
}
