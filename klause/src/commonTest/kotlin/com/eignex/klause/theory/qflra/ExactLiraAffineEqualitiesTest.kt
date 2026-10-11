package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.exactForm
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactLiraAffineEqualitiesTest {
    @Test
    fun `open affine definitions imply a comparison with their guards and retract`() {
        for ((operator, truth) in listOf(LinearOp.EQ to true, LinearOp.NE to false)) {
            for (sign in listOf(1, -1)) {
                val open = Bits(5).also { bits -> repeat(5, bits::set) }
                val source = Problem(
                    4,
                    intBounds = IntBounds.fromModelBounds(LongArray(5), LongArray(5), open, open),
                    factors = arrayOf(
                        ReifiedLinear(0, intArrayOf(sign, -sign, -sign), intArrayOf(2, 0, 1), operator, 0),
                        ReifiedLinear(1, intArrayOf(sign, -sign, sign), intArrayOf(3, 0, 1), operator, 0),
                        ReifiedLinear(2, intArrayOf(sign, -sign, -sign), intArrayOf(4, 2, 3), operator, 0),
                        ReifiedLinear(3, intArrayOf(-2, 1), intArrayOf(0, 4), LinearOp.EQ, 0),
                    ),
                )
                val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
                    factor.linearRows.map { it.exactForm(0) }
                }) { _, _ -> ComponentResult.Consistent }
                val session = SearchSession(emptyList())
                for (guard in 0..2) session.push(SearchDecision.Bool(Lit.make(guard, truth)))

                assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

                assertEquals(true, session.boolValue(3))
                assertEquals(
                    (0..2).map { Lit.make(it, !truth) }.toSet() + Lit.make(3, true),
                    session.reasonFor(3)?.literals?.toSet(),
                )
                session.popTo(2)
                assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
                assertNull(session.boolValue(3))
            }
        }
    }

    @Test
    fun `inconsistent open affine definitions cite both source guards`() {
        val open = Bits(3).also { bits -> repeat(3, bits::set) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 1),
            ),
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        for (guard in 0..1) session.push(SearchDecision.Bool(Lit.make(guard, true)))

        val conflict = assertIs<ComponentResult.Conflict>(propagation.propagate(session, Cancellation.Never))

        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false)),
            assertNotNull(conflict.explanation).literals.toSet(),
        )
    }

    @Test
    fun `nonunit affine equations remain with the complete theory`() {
        val open = Bits(3).also { bits -> repeat(3, bits::set) }
        val source = Problem(
            2,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, 2, 2), intArrayOf(0, 1, 2), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ),
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))

        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertNull(session.boolValue(1))
    }

    @Test
    fun `wide contradictory affine constants refute the root without wrapped arithmetic`() {
        val open = Bits(3).also { bits -> repeat(3, bits::set) }
        val source = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                Linear(longArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, Long.MAX_VALUE),
                Linear(longArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, Long.MIN_VALUE),
            ),
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())

        val conflict = assertIs<ComponentResult.Conflict>(propagation.propagate(session, Cancellation.Never))

        assertContentEquals(intArrayOf(), assertNotNull(conflict.explanation).literals)
    }

    @Test
    fun `affine exclusions imply guarded comparisons and retract`() {
        for ((operator, negativeTruth) in listOf(LinearOp.EQ to false, LinearOp.NE to true)) {
            for (queryOperator in listOf(LinearOp.EQ, LinearOp.NE)) {
                for (sign in listOf(1, -1)) {
                    val open = Bits(3).also { bits -> repeat(3, bits::set) }
                    val source = Problem(
                        3,
                        intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                        factors = arrayOf(
                            ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                            ReifiedLinear(2, intArrayOf(sign, sign), intArrayOf(0, 1), queryOperator, sign),
                            ReifiedLinear(1, intArrayOf(-sign), intArrayOf(2), operator, -sign),
                        ),
                    )
                    val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
                        factor.linearRows.map { it.exactForm(0) }
                    }) { _, _ -> ComponentResult.Consistent }
                    val session = SearchSession(emptyList())
                    session.push(SearchDecision.Bool(Lit.make(0, true)))
                    session.push(SearchDecision.Bool(Lit.make(1, negativeTruth)))

                    repeat(2) {
                        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
                    }

                    val queryTruth = queryOperator == LinearOp.NE
                    assertEquals(queryTruth, session.boolValue(2))
                    assertEquals(
                        setOf(Lit.make(0, false), Lit.make(1, !negativeTruth), Lit.make(2, queryTruth)),
                        assertNotNull(session.reasonFor(2)).literals.toSet(),
                    )
                    session.popTo(1)
                    assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
                    assertNull(session.boolValue(2))
                    session.push(SearchDecision.Bool(Lit.make(1, !negativeTruth)))
                    assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
                    assertEquals(!queryTruth, session.boolValue(2))
                }
            }
        }
    }

    @Test
    fun `adding an affine definition invalidates an unresolved comparison reduction`() {
        val open = Bits(3).also { bits -> repeat(3, bits::set) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 7),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 7),
            ),
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
        assertNull(session.boolValue(2))

        session.push(SearchDecision.Bool(Lit.make(1, true)))
        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(2))
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true)),
            assertNotNull(session.reasonFor(2)).literals.toSet(),
        )
    }

    @Test
    fun `retracting an affine definition invalidates retained exclusions`() {
        val open = Bits(3).also { bits -> repeat(3, bits::set) }
        val source = Problem(
            3,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.NE, 1),
                ReifiedLinear(2, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            ),
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())
        session.push(SearchDecision.Bool(Lit.make(1, true)))
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
        assertEquals(false, session.boolValue(2))

        session.popTo(1)
        assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))

        assertEquals(true, session.boolValue(1))
        assertNull(session.boolValue(2))
    }

    @Test
    fun `bounded affine passes eventually imply comparisons beyond unresolved prefixes`() {
        val open = Bits(33).also { bits -> repeat(32, bits::set) }
        val source = Problem(
            601,
            intBounds = IntBounds.fromModelBounds(
                LongArray(33) { if (it == 32) 7 else 0 },
                LongArray(33) { if (it == 32) 7 else 0 },
                open,
                open,
            ),
            factors = Array(601) { guard ->
                if (guard == 600) {
                    ReifiedLinear(guard, intArrayOf(1), intArrayOf(32), LinearOp.EQ, 7)
                } else {
                    ReifiedLinear(guard, IntArray(32) { 1 }, IntArray(32) { it }, LinearOp.LE, 0)
                }
            },
        )
        val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
            factor.linearRows.map { it.exactForm(0) }
        }) { _, _ -> ComponentResult.Consistent }
        val session = SearchSession(emptyList())

        repeat(4) {
            assertIs<ComponentResult.Consistent>(propagation.propagate(session, Cancellation.Never))
        }

        assertEquals(true, session.boolValue(600))
        assertContentEquals(intArrayOf(Lit.make(600, true)), assertNotNull(session.reasonFor(600)).literals)
    }

    @Test
    fun `cancellation during an affine explanation withholds the implication`() {
        for (metered in listOf(false, true)) {
            val open = Bits(3).also { bits -> repeat(3, bits::set) }
            val source = Problem(
                2,
                intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open),
                factors = arrayOf(
                    ReifiedLinear(0, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                    ReifiedLinear(1, intArrayOf(1, -1, -1), intArrayOf(2, 0, 1), LinearOp.EQ, 0),
                ),
            )
            val propagation = ExactLiraAffineEqualities(source, source.factors.map { factor ->
                factor.linearRows.map { it.exactForm(0) }
            }) { _, _ -> ComponentResult.Consistent }
            val session = SearchSession(emptyList())
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            var cancelled = false
            val context = object : SearchContext by session {
                override fun atomLiteral(decision: SearchDecision): Int? {
                    if (decision == SearchDecision.Bool(Lit.make(1, true))) cancelled = true
                    return session.atomLiteral(decision)
                }
            }
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = cancelled
                override fun workMeter(): WorkMeter? = if (metered) WorkMeter { } else null
            }

            assertIs<ComponentResult.Indeterminate>(propagation.propagate(context, stop))

            assertNull(session.boolValue(1))
        }
    }
}
