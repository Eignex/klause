package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridArithmeticEncoderTest {
    @Test
    fun `selected polynomial encodings preserve every rounded square pair`() {
        val input = GridCoordinate(0, -2.0, 2.0, 5)
        val output = GridCoordinate(1, 0.0, 4.0, 5)
        for (relation in listOf<Factor>(GridProduct(input, input, output), GridPower(input, 2, output))) {
            val source = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf<Factor>(
                relation, RealProduct(0, 0, 1, 0.0, 0.0),
            ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(0.0, 0.0))
            val model = assertNotNull(source.prepareExecutionPlan().polynomialModel)
            assertEquals(0, model.encodingAttributions.first().sourceFactorIndex)
            for (index in 0L..4L) for (result in 0L..4L) {
                val arithmetic = PolynomialArithmetic()
                val values = longArrayOf(index, result)
                val fixed = mutableMapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
                for ((id, column) in model.integerColumns) fixed[column] = BigFraction.ofLong(values[id])
                val normalized = PolynomialFormula.normalize(model.service.formula, arithmetic, fixed)
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()

                val truth = normalized.truth(intArrayOf(), signs, arithmetic)

                val expected = (index - 2L) * (index - 2L)
                assertEquals(if (result == expected) PolynomialFormula.TRUE else PolynomialFormula.FALSE, truth)
            }
        }
    }

    @Test
    fun `private linear grid relations retain conditioning and retraction`() {
        val input = GridCoordinate(0, -2.0, 2.0, 5)
        val output = GridCoordinate(1, 0.0, 4.0, 5)
        for (relation in listOf<Factor>(GridProduct(input, input, output), GridPower(input, 2, output))) {
            val source = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(relation))
            val encoded = assertNotNull(FactorProjectionProviders.Default.prepare(source)
                .linearTheoryModel(EncodingTarget.LINEAR))
            val queries = OpenExactNeighborhood(encoded, TheoryParams(),
                preparedSource = encoded.prepareExecutionPlan())
            queries.use {
                val conflictPins = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
                val negativePins = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ofLong(4))
                val zeroPins = mapOf(0 to BigFraction.ofLong(2), 1 to BigFraction.ZERO)
                val conflict = ExactNeighborhoodCondition(emptyMap(), conflictPins, conflictPins)
                val negative = ExactNeighborhoodCondition(emptyMap(), negativePins, negativePins)
                val zero = ExactNeighborhoodCondition(emptyMap(), zeroPins, zeroPins)
                assertIs<ExactNeighborhoodResult.Refuted>(it.check(conflict, Cancellation.Never))
                assertIs<ExactNeighborhoodResult.Undecided>(it.check(negative, Cancellation { true }))
                val first = assertIs<ExactNeighborhoodResult.Witness>(it.check(negative, Cancellation.Never)).sample
                assertIs<ExactNeighborhoodResult.Witness>(it.check(zero, Cancellation.Never))

                assertEquals(0L, first.intValue(0))
                assertEquals(4L, first.intValue(1))
            }
        }
    }

    @Test
    fun `binary private linear grids enforce the rounded product`() {
        val relation = GridProduct(GridCoordinate(0, 0.0, 1.0, 2), GridCoordinate(1, 0.0, 1.0, 2),
            GridCoordinate(2, 0.0, 1.0, 2))
        val source = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf<Factor>(relation))
        val encoded = assertNotNull(FactorProjectionProviders.Default.prepare(source)
            .linearTheoryModel(EncodingTarget.LINEAR))
        OpenExactNeighborhood(encoded, TheoryParams(), preparedSource = encoded.prepareExecutionPlan()).use { owner ->
            for (a in 0L..1L) for (b in 0L..1L) for (result in 0L..1L) {
                val fixed = mapOf(0 to BigFraction.ofLong(a), 1 to BigFraction.ofLong(b),
                    2 to BigFraction.ofLong(result))

                val checked = owner.check(ExactNeighborhoodCondition(emptyMap(), fixed, fixed), Cancellation.Never)

                if (a * b == result) assertIs<ExactNeighborhoodResult.Witness>(checked)
                else assertIs<ExactNeighborhoodResult.Refuted>(checked)
            }
        }
    }

    @Test
    fun `binary grid installation keeps a compact complete relation`() {
        val relation = GridProduct(GridCoordinate(0, 0.0, 1.0, 16), GridCoordinate(1, 0.0, 1.0, 16),
            GridCoordinate(2, 0.0, 1.0, 16))
        val source = Problem(0, 3, Array(3) { IntDomain(0, 15) }, arrayOf<Factor>(relation))

        val encoded = FactorProjectionProviders.Default.prepare(source).linearTheoryModel(EncodingTarget.LINEAR)

        assertNotNull(encoded)
    }

    @Test
    fun `oversized private encodings decline without dropping native finite support`() {
        val relation = GridPower(GridCoordinate(0, -1.0, 1.0, 10000), 2,
            GridCoordinate(1, 0.0, 1.0, 10000))
        val source = Problem(0, 2, Array(2) { IntDomain(0, 9999) }, arrayOf<Factor>(relation))
        val prepared = FactorProjectionProviders.Default.prepare(source)

        assertEquals(EncoderSupport.UNSUPPORTED, prepared.encoder(0, EncodingTarget.LINEAR).support)
        assertEquals(EncoderSupport.UNSUPPORTED, prepared.encoder(0, EncodingTarget.POLYNOMIAL).support)
        assertNull(prepared.linearTheoryModel(EncodingTarget.LINEAR))
        assertTrue(source.prepareExecutionPlan().components.isNotEmpty())
    }

    @Test
    fun `cancelled grid installation does not retain partial encoding state`() {
        val relation = GridPower(GridCoordinate(0, -2.0, 2.0, 5), 2, GridCoordinate(1, 0.0, 4.0, 5))
        val source = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf<Factor>(relation))
        val prepared = FactorProjectionProviders.Default.prepare(source)
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val stopped = prepared.linearTheoryModel(EncodingTarget.LINEAR, cancellation = cancellation)
        val fresh = prepared.linearTheoryModel(EncodingTarget.LINEAR)

        assertNull(stopped)
        assertNotNull(fresh)
        assertTrue(work >= 2L)
    }
}
