package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.search
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpSharedEpochTest {
    @Test
    fun `root source refresh retains conditional reasons and registered atom names`() {
        val source = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(2.0), LinearOp.GE, 0.0),
                ReifiedRealLinear(
                    0,
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(2.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
        )
        val stats = LpStatsSink()
        source.componentPlan().search(
            source,
            emptyMap(),
            Long.MAX_VALUE,
            Cancellation.Never,
            SearchLearnedDbParams(),
            null,
            lpEpochs = true,
            lpStats = stats,
        ).use { planned ->
            val session = planned.session
            assertIs<ComponentResult.Consistent>(session.initialize())
            val split = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertIs<ComponentResult.Consistent>(session.publish(SearchDecision.Bool(Lit.make(0, true))))
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertIs<ComponentResult.Consistent>(session.restart())
            val conflict = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Theory(split.positive)))

            assertEquals(1L, stats.snapshot().epochs["shared_replacements"])
            assertTrue(assertNotNull(stats.snapshot().epochs["shared_total_ns"]) > 0L)
            assertContentEquals(
                intArrayOf(Lit.make(0, false), split.negative.literal).sortedArray(),
                assertNotNull(conflict.explanation).literals.sortedArray(),
            )
            val registered = assertNotNull(
                SourceBoundAtom.rationalSplit(
                    session,
                    listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)),
                    BigFraction.ZERO,
                ),
            )
            assertEquals(split.positive.literal, registered.positive.literal)
        }
    }

    @Test
    fun `unchanged root restarts skip source owner replacement`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
            factors = arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(3.0), LinearOp.GE, 1.0),
            ),
        )
        val stats = LpStatsSink()
        source.componentPlan().search(
            source,
            emptyMap(),
            Long.MAX_VALUE,
            Cancellation.Never,
            SearchLearnedDbParams(),
            null,
            lpEpochs = true,
            lpStats = stats,
        ).use { planned ->
            assertIs<ComponentResult.Consistent>(planned.session.initialize())

            repeat(2) { assertIs<ComponentResult.Consistent>(planned.session.restart()) }

            assertEquals(2L, stats.snapshot().epochs["shared_unchanged"])
            assertEquals(2L, stats.snapshot().epochs["shared_restart_opportunities"])
            assertEquals(null, stats.snapshot().epochs["shared_replacements"])
        }
    }
}
