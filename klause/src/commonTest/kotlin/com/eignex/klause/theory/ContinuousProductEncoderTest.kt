package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.hasCompleteGuardedLpCompletion
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.portfolio.verifyRealCoordinates
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuousProductEncoderTest {
    @Test
    fun `retained product completions retract each finite choice guard`() {
        val factor = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.125, 0.375),
            intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(factor), numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.5, 0.0), realUpper = doubleArrayOf(1.0, 0.5, 1.0))
        val replacement = FactorProjectionProviders(encoder = EncoderProvider { view, target ->
            val selected = BuiltInEncoderProvider.select(view, target)
            if (view !is ContinuousProduct || target != EncodingTarget.LINEAR) selected
            else Encoder(EncoderSupport.EQUIVALENT) { projected, encoding -> selected.install(projected, encoding) }
        })
        for (providers in listOf(FactorProjectionProviders.Default, replacement)) {
            LeafRealSolver(source.bake(providers), null).use { owner ->
                val first = owner.solve(Sample(booleanArrayOf(true, false), longArrayOf()))
                val second = owner.solve(Sample(booleanArrayOf(false, true), longArrayOf()))
                val restored = owner.solve(Sample(booleanArrayOf(true, false), longArrayOf()))

                assertEquals(BigFraction.ofLong(16).reciprocal(), assertNotNull(first.exactReals)[2])
                assertEquals(BigFraction.ofLong(3) / BigFraction.ofLong(16), assertNotNull(second.exactReals)[2])
                assertEquals(first.exactReals, restored.exactReals)
            }
        }
    }

    @Test
    fun `finite product guards enforce the complete operand choice relation`() {
        val factor = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.125, 0.375),
            intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(factor), numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.5, 0.0), realUpper = doubleArrayOf(1.0, 0.5, 1.0))
        for (guards in listOf(booleanArrayOf(false, false), booleanArrayOf(true, true))) {
            val point = Sample(guards, longArrayOf())

            val result = leafRealFeasibility(source, null, point)

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        }
    }

    @Test
    fun `zero numerical product violation still requires exact choice completion`() {
        val factor = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.125, 0.375),
            intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(factor), numRealVars = 3,
            realLower = doubleArrayOf(0.0, 0.5, 0.0), realUpper = doubleArrayOf(1.0, 0.5, 1.0))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        val input = 0.125 + 1e-12
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.assignment.setReal(0, input)
        state.assignment.setReal(1, 0.5)
        state.assignment.setReal(2, input * 0.5)
        state.recompute()
        val candidate = state.assignment.snapshot()
        val claimed = candidate.copy(exactReals = listOf(input, 0.5, input * 0.5).map {
            requireNotNull(BigFraction.ofDouble(it))
        })

        val completion = LeafRealCompletion(source, null).complete(candidate, Cancellation.Never)

        assertEquals(0L, state.cost)
        assertIs<Verification.Rejected>(verifyRealCoordinates(source, claimed))
        val checked = assertIs<Completion.Witness>(completion).sample
        val values = assertNotNull(checked.exactReals)
        assertEquals(BigFraction.ofLong(8).reciprocal(), values[0])
        assertEquals(BigFraction.ofLong(16).reciprocal(), values[2])
    }

    @Test
    fun `aliased product coefficients require an exact representable completion`() {
        for ((value, complete) in listOf(0.125 to true, 0.1 to false)) {
            val factor = ContinuousProduct(0, 1, 1, 0, doubleArrayOf(value), intArrayOf(Lit.make(0, true)))
            val source = Problem(1, 0, emptyArray(), arrayOf(factor), numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))

            val selected = FactorProjectionProviders.Default.prepare(source)

            assertEquals(complete, factor.hasCompleteGuardedLpCompletion())
            assertNotNull(selected.linearTheoryModel(EncodingTarget.LINEAR))
        }
    }

    @Test
    fun `cancelled product installation does not retain a partial choice cover`() {
        val factor = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.125, 0.375),
            intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val source = Problem(2, 0, emptyArray(), arrayOf(factor), numRealVars = 3,
            realLower = DoubleArray(3), realUpper = DoubleArray(3) { 1.0 })
        val selected = FactorProjectionProviders.Default.prepare(source)
        var work = 0L
        val stopped = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val interrupted = selected.linearTheoryModel(EncodingTarget.LINEAR, cancellation = stopped)
        val fresh = selected.linearTheoryModel(EncodingTarget.LINEAR)

        assertNull(interrupted)
        assertNotNull(fresh)
        assertTrue(work >= 2L)
    }
}
