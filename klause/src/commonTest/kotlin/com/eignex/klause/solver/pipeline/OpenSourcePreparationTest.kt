package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenSourcePreparationTest {

    private val aggregateOnly = PresolveConfig.parse(PresolvePass.AGGREGATE_SUB_SUMS.id)

    @Test
    fun `certified deletion should retain immutable rows with reduced source attribution`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(row, row, RealAbs(0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(-2.0, 0.0), realUpper = doubleArrayOf(2.0, 2.0))
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val original = requireNotNull(captured.encodedTheoryFragment(model)).factors
            .filterIsInstance<EncodedTheoryFactor>().filter { it.attribution.sourceFactorIndex == 2 }

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.parse("off,+dependent-rows"), projections = projections, capturedPlan = captured,
        ))

        assertNotSame(captured, planned.plan)
        assertEquals(2, planned.model.numFactors)
        planned.plan.requireSource(planned.model)
        val retained = requireNotNull(planned.plan.encodedTheoryFragment(planned.model)).factors
            .filterIsInstance<EncodedTheoryFactor>().filter { it.attribution.sourceFactorIndex == 1 }
        assertEquals(original.size, retained.size)
        assertTrue(retained.isNotEmpty())
        for (index in retained.indices) {
            assertSame(original[index].linearForm, retained[index].linearForm)
            assertSame(planned.model.factors[1], retained[index].source)
        }
        val fresh = planned.model.componentPlan()
        assertEquals(fresh.theoryPipeline, planned.plan.theoryPipeline)
        for (index in planned.model.factors.indices) {
            assertEquals(fresh.factorOwner(index), planned.plan.factorOwner(index))
            assertEquals(fresh.factorEncoded(index), planned.plan.factorEncoded(index))
        }
    }

    @Test
    fun `certified deletion with custom providers should reinstall retained rows`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(row, row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        var installations = 0
        val projections = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(selected.support) { view, encoding ->
                installations++
                selected.install(view, encoding)
            }
        }).prepare(model)
        val captured = model.componentPlan(projections)
        installations = 0

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.parse("off,+dependent-rows"), projections = projections, capturedPlan = captured,
        ))

        assertEquals(1, planned.model.numFactors)
        assertEquals(1, installations)
    }

    @Test
    fun `trailing deletion should retain rows whose attribution remains valid`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(row, row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val original = requireNotNull(captured.encodedTheoryFragment(model)).factors.first()

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.parse("off,+dependent-rows"), projections = projections, capturedPlan = captured,
        ))

        assertEquals(1, planned.model.numFactors)
        assertSame(original, requireNotNull(planned.plan.encodedTheoryFragment(planned.model)).factors.single())
    }

    @Test
    fun `uncertified deletion should prepare fresh rows`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(row, row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val reduced = model.withFactors(arrayOf(row))
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val original = requireNotNull(captured.encodedTheoryFragment(model)).factors.first()
        val preparation = PreparedSource(model, reduced, false, null, emptyList(), SourceRebuilds.NONE, null)

        val planned = preparation.planned(reduced, projections, capturedPlan = captured)

        assertNotSame(original, requireNotNull(planned.plan.encodedTheoryFragment(reduced)).factors.single())
    }

    @Test
    fun `deletion after resource declined capture should prepare complete rows`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0)
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(row, row), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections, cancellation = Cancellation { true })

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.parse("off,+dependent-rows"), projections = projections, capturedPlan = captured,
        ))

        assertEquals(1, planned.model.numFactors)
        assertNull(planned.plan.encodingDecline)
        assertEquals(1, requireNotNull(planned.plan.encodedTheoryFragment(planned.model)).numFactors)
    }

    @Test
    fun `unchanged source preparation should reuse its captured projection binding`() {
        val model = refutable()
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val budget = PresolveBudget(0L)
        var work = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.NONE, projections = projections, capturedPlan = captured,
            budget = budget, cancellation = token,
        ))

        assertSame(model, planned.model)
        assertSame(captured, planned.plan)
        assertEquals(1L, work)
        assertEquals(1L, budget.preparationStages.single().nativeWork)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `a changed source should select a fresh plan despite identical factors`() {
        val model = refutable()
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val changed = Problem(numBoolVars = 0, intBounds = model.intBounds, factors = model.factors.copyOf())

        val planned = PreparedSource.unchanged(model, null).planned(changed, projections, capturedPlan = captured)

        assertNotSame(captured, planned.plan)
        planned.plan.requireSource(changed)
    }

    @Test
    fun `an appended objective row should select a fresh plan`() {
        val model = refutable()
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val augmented = Problem(numBoolVars = 0, intBounds = model.intBounds,
            factors = model.factors + Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1))

        val planned = PreparedSource.unchanged(model, null).planned(augmented, projections, capturedPlan = captured)

        assertNotSame(captured, planned.plan)
        planned.plan.requireSource(augmented)
    }

    @Test
    fun `a different projection binding should select a fresh plan`() {
        val model = refutable()
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections)
        val different = projections.providers.prepare(model)

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.NONE, projections = different, capturedPlan = captured,
        ))

        assertNotSame(captured, planned.plan)
        assertSame(different, planned.plan.factorProjections)
    }

    @Test
    fun `a resource declined capture should retry planning under the current token`() {
        val model = refutable()
        val projections = FactorProjectionProviders.Default.prepare(model)
        val captured = model.componentPlan(projections, cancellation = Cancellation { true })
        assertEquals(EncoderDecline.RESOURCE, captured.encodingDecline)

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = PresolveConfig.NONE, projections = projections, capturedPlan = captured,
        ))

        assertNotSame(captured, planned.plan)
        assertNull(planned.plan.encodingDecline)
    }

    @Test
    fun `captured satisfaction plans should install once across independent executions`() {
        val model = Problem(0, 0, emptyArray(), arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        var installations = 0
        val projections = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val selected = BuiltInEncoderProvider.select(factor, target)
            if (!selected.enforcesSource) selected else Encoder(selected.support) { view, encoding ->
                installations++
                selected.install(view, encoding)
            }
        }).prepare(model)
        val captured = model.componentPlan(projections)
        val request = OpenTheoryRequest(model, componentPlan = captured).withPresolve(PresolveConfig.NONE)
        val first = OpenTheoryPipeline.engineFor(request)
        val second = OpenTheoryPipeline.engineFor(request)

        assertIs<OpenTheoryResult.Unknown>(first.solve(TheoryParams(maxLeaves = 0)))
        assertIs<OpenTheoryResult.Sat>(second.solve())
        assertIs<OpenTheoryResult.Sat>(first.solve())

        assertEquals(1, installations)
    }

    // Three AllDifferent columns, since structural reduction rewrites a two-column one into a disequality.
    private fun aggregatable(openColumns: Boolean): Problem {
        val open = if (openColumns) Bits(6).also { bits -> repeat(3) { bits.set(it) } } else null
        val upper = if (openColumns) longArrayOf(0, 0, 0, 3, 3, 3) else longArrayOf(9, 9, 9, 3, 3, 3)
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(6), upper, null, open),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1, -1, -2), intArrayOf(0, 1, 2), LinearOp.EQ, 0L),
                Linear(longArrayOf(1, 2), intArrayOf(1, 2), LinearOp.LE, 4L),
                AllDifferent(vars = intArrayOf(3, 4, 5), domainMin = 0, domainSize = 4),
            ),
        )
    }

    private fun refutable(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf<Factor>(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3L)),
        )
    }

    @Test
    fun `a source rewrite should move factor ownership with the factors`() {
        val model = aggregatable(openColumns = true)
        val declared = model.componentPlan()

        val planned = assertIs<OpenSourcePreparation.Planned>(model.prepareOpenSource(
            config = aggregateOnly, projections = declared.factorProjections, capturedPlan = declared,
        ))

        assertNotSame(declared, planned.plan)
        assertEquals(FactorOwner.CP, planned.plan.factorOwner(1))
        assertNotEquals(declared.factorOwner(1), planned.plan.factorOwner(1))
    }

    @Test
    fun `source refutation should be terminal for the open route`() {
        val model = refutable()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(true, result.stats.presolve?.infeasible)
    }

    @Test
    fun `a solve stopped before it starts should prepare nothing`() {
        val model = aggregatable(openColumns = true)

        val result = OpenTheoryEngine(model, model.sourceRoute())
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
        assertNull(result.stats.presolve)
    }
}
