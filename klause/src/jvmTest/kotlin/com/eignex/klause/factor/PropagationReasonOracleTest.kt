package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PropagationReasonOracleTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun prepareSourceFixtures() {
            val source = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true)))))
            check(FactorPropagationOracle.enumerateSat(source, "source", null).isNotEmpty())
            PropagationReasonOracle.assertReasonsImply(source, "source") { true }
        }
    }

    @Test
    fun `root pruning should not hide a counterexample to a boolean deduction`() {
        val relation = Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false)))
        val faulty = object : Factor by relation, Invariant, Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean = state.pinBool(0, true)
        }
        val source = Problem(1, 0, emptyArray(), listOf(faulty))

        val failure = assertFailsWith<AssertionError> {
            PropagationReasonOracle.assertReasonsImply(source, "boolean deduction") { true }
        }

        assertTrue(failure.message.orEmpty().contains("pinning b0=true"))
    }

    @Test
    fun `root pruning should not hide a counterexample to an integer deduction`() {
        val relation = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val faulty = object : Factor by relation, Invariant by relation.invariantProjection(), Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean = state.tightenIntMin(0, 1)
        }
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), listOf(faulty))

        val failure = assertFailsWith<AssertionError> {
            PropagationReasonOracle.assertReasonsImply(source, "integer deduction") { true }
        }

        assertTrue(failure.message.orEmpty().contains("removing 0 from x0"))
    }
}
