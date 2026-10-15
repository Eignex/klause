package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.ExactNeighborhoodCondition
import com.eignex.klause.solver.pipeline.ExactNeighborhoodResult
import com.eignex.klause.solver.pipeline.OpenExactNeighborhood
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SelectionEncoderTest {
    @Test
    fun `exact extrema reject values beyond every operand`() {
        for (max in listOf(false, true)) {
            val open = Bits(3).also { for (id in 0..2) it.set(id) }
            val relation = ArrayMinMax(2, intArrayOf(0, 1, 0), max)
            val source = Problem(0, IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                arrayOf<Factor>(relation,
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
                    Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, if (max) 3 else -1)))
            val prepared = source.prepareExecutionPlan()
            val plan = assertNotNull(prepared.componentPlan)

            val result = plan.search(source, emptyMap()).use {
                if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
                else it.session.solve(0)
            }

            assertIs<SearchResult.Exhausted>(result)
            assertSame(relation, source.factors[0])
            val installed = assertNotNull(FactorProjectionProviders.Default.prepare(source)
                .installLinearFactor(0, EncodingTarget.LINEAR))
            assertEquals(setOf(0), installed.map { it.attribution.sourceFactorIndex }.toSet())
        }
    }

    @Test
    fun `exact element excludes indexes outside its offset range`() {
        for (index in listOf(-3, 0)) {
            val open = Bits(2).also { it.set(0); it.set(1) }
            val source = Problem(0, IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                arrayOf<Factor>(Element(0, 1, longArrayOf(4, 9), arrIsVars = false, indexOffset = -2),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, index)))
            val plan = assertNotNull(source.prepareExecutionPlan().componentPlan)

            val result = plan.search(source, emptyMap()).use {
                if (it.session.initialize() is ComponentResult.Conflict) SearchResult.Exhausted
                else it.session.solve(0)
            }

            assertIs<SearchResult.Exhausted>(result)
        }
    }

    @Test
    fun `element branch conditions retract before a sibling query`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val source = Problem(1, IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            arrayOf<Factor>(Element(0, 1, longArrayOf(4, 9), arrIsVars = false, indexOffset = -2),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, -2),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 9)))
        val prepared = source.prepareExecutionPlan()

        OpenExactNeighborhood(source, TheoryParams(), preparedSource = prepared).use {
            val refuted = it.check(ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap()),
                Cancellation.Never)
            val sibling = it.check(ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap()),
                Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Refuted>(refuted)
            val sample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
            assertFalse(sample.boolValue(0))
            assertEquals(-1L, sample.intValue(0))
            assertEquals(9L, sample.intValue(1))
        }
    }

    @Test
    fun `variable element and extrema share one polynomial point`() {
        val source = Problem(0, 4, arrayOf(IntDomain(1, 1), IntDomain(0, 0), IntDomain(2, 2), IntDomain(0, 3)),
            arrayOf<Factor>(Element(0, 3, longArrayOf(1, 2, 1), arrIsVars = true),
                ArrayMinMax(3, intArrayOf(1, 2, 1), max = true),
                RealProduct(3, 0, 1, 1.0, 1.0)),
            numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 3.0))
        val shared = assertNotNull(source.prepareExecutionPlan().polynomialModel)

        val result = shared.service.invocation().use { it.check() }

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(listOf(0, 1, 2), shared.encodingAttributions.map { it.sourceFactorIndex })
    }

    @Test
    fun `interrupted element encoding exposes no partial relation`() {
        val source = Problem(0, 2, arrayOf(IntDomain(1, 4), IntDomain(0, 4)),
            arrayOf<Factor>(Element(0, 1, longArrayOf(0, 1, 2, 3), arrIsVars = false)))
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val declines = mutableListOf<EncoderDecline>()

        val installed = FactorProjectionProviders.Default.prepare(source).installLinearFactor(0,
            EncodingTarget.LINEAR, cancellation = cancellation, onDecline = declines::add)

        assertNull(installed)
        assertEquals(listOf(EncoderDecline.RESOURCE), declines)
    }
}
