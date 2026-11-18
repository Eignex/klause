package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagatorProjection
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictReasonOracleTest {
    companion object {
        private val clause = Clause(intArrayOf(Lit.make(0, true)))
        private val satisfiableSource = Problem(1, 0, emptyArray(), listOf(clause))
        private val sourceSolutions = FactorPropagationOracle.enumerateSat(satisfiableSource, "source", null)
        private val reasonlessSource = Problem(
            0, 1, arrayOf(IntDomain(0, 1)), listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)),
        )
        private val reasonlessState = PropagationState(reasonlessSource, Assumptions.None)

        @BeforeClass
        @JvmStatic
        fun prepareSourceFixtures() {
            check(sourceSolutions.isNotEmpty())
            check(!reasonlessState.factorAt(0).propagate(reasonlessState, 0))
        }
    }

    @Test
    fun `missing conflict reasons should fail for satisfiable and unsatisfiable sources`() {
        for (unsatisfiable in listOf(false, true)) {
            val unexplained = object : Factor by clause, Propagator by clause.propagatorProjection() {
                override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
            }
            val factors = if (unsatisfiable) {
                listOf(unexplained, Clause(intArrayOf(Lit.make(0, false))))
            } else {
                listOf(unexplained)
            }
            val problem = Problem(1, 0, emptyArray(), factors)

            val failure = assertFailsWith<AssertionError> {
                PropagationReasonOracle.assertReasonsImply(problem, "unexplained") { state ->
                    state.pinBool(0, false)
                }
            }

            assertTrue(failure.message.orEmpty().contains("unexplained: factor 0 returned no conflict reason"))
        }
    }

    @Test
    fun `an explicit reasonless root conflict should be checked against original domains`() {
        ConflictReasonOracle.assertEntailed(
            reasonlessSource, reasonlessState, 0, allowReasonlessRootConflict = true,
        )
    }

    @Test
    fun `a reasonless allowance should reject a source with a satisfying assignment`() {
        val unexplained = object : Factor by clause, Propagator by clause.propagatorProjection() {
            override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        }
        val state = PropagationState(Problem(1, 0, emptyArray(), listOf(unexplained)), Assumptions.None)
        assertTrue(sourceSolutions.isNotEmpty())
        assertTrue(state.pinBool(0, false))
        assertFalse(state.factorAt(0).propagate(state, 0))

        val failure = assertFailsWith<AssertionError> {
            ConflictReasonOracle.assertEntailed(satisfiableSource, state, 0, allowReasonlessRootConflict = true)
        }

        assertTrue(failure.message.orEmpty().contains("reasonless conflict is not implied by the original source"))
    }

    @Test
    fun `root pruning should not hide a counterexample to a conflict clause`() {
        val relation = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        val faulty = object : Factor by relation, Invariant, Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean = state.pinBool(0, true)
            override fun conflictReason(state: PropagationState, factorId: Int): IntArray =
                intArrayOf(Lit.make(0, true))
        }
        val source = Problem(1, 0, emptyArray(), listOf(faulty))
        val state = PropagationState(source, Assumptions.None)
        assertTrue(state.pinBool(0, false))
        assertFalse(state.factorAt(0).propagate(state, 0))

        val failure = assertFailsWith<AssertionError> {
            ConflictReasonOracle.assertEntailed(source, state, 0)
        }

        assertTrue(failure.message.orEmpty().contains("unsound nogood"))
    }
}
