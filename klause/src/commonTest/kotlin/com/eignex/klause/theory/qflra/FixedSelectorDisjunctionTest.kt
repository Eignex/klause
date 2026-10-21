package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.constsOf
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedSelectorDisjunctionTest {
    @Test
    fun `a fixing should retain both published sides including extreme integer values`() {
        for (value in listOf(-2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val model = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
                factors = arrayOf(ComparisonClause(intArrayOf(0, 0), arrayOf(LinearOp.NE, LinearOp.EQ),
                    longArrayOf(value, 0))),
            )
            val selector = assertNotNull(FixedSelectorDisjunction.prepare(model, Cancellation.Never)).single()
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(2))
            session.push(SearchDecision.Bool(0))
            session.publish(SearchDecision.IntAtLeast(0, value), SearchAtomPremise.Asserted(SearchDecision.Bool(0)))
            session.push(SearchDecision.Bool(2))
            session.publish(SearchDecision.IntAtMost(0, value), SearchAtomPremise.Asserted(SearchDecision.Bool(2)))

            val explanation = session.explainAtoms(assertNotNull(selector.premise(session)))

            assertEquals(setOf(1, 3), assertNotNull(explanation).literals.toSet())
            session.popTo(1)
            assertNull(selector.premise(session))
        }
    }

    @Test
    fun `absent unequal or unsupported fixing bounds should withhold the premise`() {
        val selector = FixedSelectorDisjunction(0, 1, 0, 0L)
        for (kind in listOf("absent", "unequal", "unavailable")) {
            val session = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
            session.push(SearchDecision.Bool(0))
            val premise = if (kind == "unavailable") SearchAtomPremise.Unavailable else {
                SearchAtomPremise.Asserted(SearchDecision.Bool(0))
            }
            session.publish(SearchDecision.IntAtLeast(0, 0L), premise)
            if (kind != "absent") {
                session.publish(SearchDecision.IntAtMost(0, if (kind == "unequal") 1L else 0L), premise)
            }

            assertNull(selector.premise(session), kind)
        }
    }

    @Test
    fun `unsupported guard shapes should not enter the source candidate list`() {
        val constants = IntegerConstants(constsOf(longArrayOf(1)), 0L)
        val plain = TaggedLinearRow(intArrayOf(Term.ofIntVar(0)), constants, LinearOp.NE)
        val guards = listOf(
            TaggedLinearRow(intArrayOf(Term.ofIntVar(0)), constants, LinearOp.EQ),
            TaggedLinearRow(intArrayOf(Term.ofIntVar(0)), constants, LinearOp.NE, activator = 0),
            TaggedLinearRow(intArrayOf(Term.ofLit(Lit.make(0, true))), constants, LinearOp.NE),
            TaggedLinearRow(intArrayOf(Term.ofIntVar(1)), constants, LinearOp.NE),
            TaggedLinearRow(intArrayOf(Term.ofIntVar(0)), IntegerConstants(constsOf(longArrayOf(2)), 0L), LinearOp.NE),
            object : LinearRow by plain { override val strict: Boolean = true },
            TaggedLinearRow(intArrayOf(Term.ofIntVar(0)),
                WideConstants(WideConsts(arrayOf(BIG_ONE)), bigIntOf(Long.MAX_VALUE) + BIG_ONE), LinearOp.NE),
        )
        for (guard in guards) {
            val declaration = object : Factor by ComparisonClause(intArrayOf(0), arrayOf(LinearOp.EQ), longArrayOf(0)) {
                override val linearForm: LinearForm = LinearForm.Disjunction(listOf(guard,
                    LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.EQ, 0)))
            }
            val model = Problem(numBoolVars = 1,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
                factors = arrayOf(declaration))

            assertTrue(assertNotNull(FixedSelectorDisjunction.prepare(model, Cancellation.Never)).isEmpty())
        }
    }

    @Test
    fun `a disjunction with more than two rows should retain its existing path`() {
        val model = Problem(numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 0, 0),
                arrayOf(LinearOp.NE, LinearOp.EQ, LinearOp.EQ), longArrayOf(0, 0, 1))))

        assertTrue(assertNotNull(FixedSelectorDisjunction.prepare(model, Cancellation.Never)).isEmpty())
    }

    @Test
    fun `stopped candidate preparation should retain its debit without returning a partial list`() {
        val model = Problem(numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = arrayOf(ComparisonClause(intArrayOf(0, 0),
                arrayOf(LinearOp.NE, LinearOp.EQ), longArrayOf(0, 1))))
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 10L
            override fun workMeter(): WorkMeter = meter
        }

        assertNull(FixedSelectorDisjunction.prepare(model, stop))
        val spent = charged
        assertTrue(spent >= 10L)
        assertNull(FixedSelectorDisjunction.prepare(model, stop))
        assertEquals(spent, charged)
    }
}
