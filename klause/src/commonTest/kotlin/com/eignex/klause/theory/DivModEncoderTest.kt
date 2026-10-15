package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DivModEncoderTest {
    @Test
    fun `variable divisor linear installation requires and attributes its fixing`() {
        val factor = DivMod(0, 1, 2, 3)
        val source = Problem(0, 4, Array(4) { IntDomain(-8, 8) }, arrayOf<Factor>(factor))
        val prepared = FactorProjectionProviders.Default.prepare(source)

        assertEquals(EncoderSupport.CONDITIONED, prepared.encoder(0, EncodingTarget.LINEAR).support)
        assertEquals(EncoderSupport.EQUIVALENT, prepared.encoder(0, EncodingTarget.POLYNOMIAL).support)
        assertEquals(EncoderSupport.UNSUPPORTED, prepared.encoder(0, EncodingTarget.DIFFERENCE).support)
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR))
        val installed = assertNotNull(prepared.linearTheoryModel(EncodingTarget.LINEAR,
            fixedIntegers = mapOf(SourceIntId(1) to bigIntOf(-3))))
        assertTrue(installed.factors.all { (it as EncodedTheoryFactor).attribution.integerConditions ==
            mapOf(SourceIntId(1) to bigIntOf(-3)) })
    }

    @Test
    fun `correlated theory outputs conflict with a joint dividend equality`() {
        val parsed = SmtLib.parse("""
            (declare-const a Int) (assert (= (div a 3) 2))
            (assert (= (mod a 3) 2)) (assert (= a 7))
        """.trimIndent())
        val plan = parsed.model.prepareExecutionPlan()
        val queries = OpenExactNeighborhood(parsed.model, TheoryParams(), preparedSource = plan)

        val result = queries.use { it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()),
            Cancellation.Never) }

        assertIs<ExactNeighborhoodResult.Refuted>(result)
    }

    @Test
    fun `theory operand windows retract before checking a sibling`() {
        val parsed = SmtLib.parse("(declare-const a Int) (assert (= (div a 3) 2))")
        val source = parsed.model
        val index = parsed.intVarNames.getValue("a")
        val factor = source.factors.filterIsInstance<DivMod>().single()
        val plan = source.prepareExecutionPlan()
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = plan)
        queries.use {
            val five = mapOf(index to BigFraction.ofLong(5))
            assertIs<ExactNeighborhoodResult.Refuted>(it.check(ExactNeighborhoodCondition(emptyMap(), five, five),
                Cancellation.Never))
            val eight = mapOf(index to BigFraction.ofLong(8))
            val sample = assertIs<ExactNeighborhoodResult.Witness>(it.check(
                ExactNeighborhoodCondition(emptyMap(), eight, eight), Cancellation.Never)).sample

            assertEquals(8L, sample.intValue(index))
            assertEquals(2L, sample.intValue(factor.remainder))
        }
    }

    @Test
    fun `wide Euclidean source terms retain their original exact correlation`() {
        val parsed = SmtLib.parse("""
            (declare-const a Int) (assert (<= 0 a 2))
            (assert (= (mod (+ 1000000000000000000000000000000 a) 1000000000000000000000000000000) 2))
        """.trimIndent())
        val source = parsed.model
        val factor = source.factors.filterIsInstance<DivMod>().single()
        val queries = OpenExactNeighborhood(source, TheoryParams(), preparedSource = source.prepareExecutionPlan())

        val sample = queries.use { assertIs<ExactNeighborhoodResult.Witness>(it.check(
            ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), Cancellation.Never)).sample }

        assertEquals(DivisionConvention.EUCLIDEAN, factor.convention)
        assertEquals(2L, sample.intValue(parsed.intVarNames.getValue("a")))
    }

    @Test
    fun `cancelled relation installation reports a resource decline`() {
        val source = Problem(0, 3, Array(3) { IntDomain(-8, 8) },
            arrayOf<Factor>(DivMod(0, bigIntOf(3), 1, 2)))
        var decline: EncoderDecline? = null
        val prepared = FactorProjectionProviders.Default.prepare(source)

        val installed = prepared.linearTheoryModel(EncodingTarget.LINEAR, cancellation = Cancellation { true },
            onDecline = { decline = it })

        assertNull(installed)
        assertEquals(EncoderDecline.RESOURCE, decline)
    }
}
