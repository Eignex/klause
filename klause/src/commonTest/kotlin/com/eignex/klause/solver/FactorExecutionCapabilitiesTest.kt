package com.eignex.klause.solver

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.*
import com.eignex.klause.factor.objective.MutableObjectiveBound
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.scheduling.*
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FactorExecutionCapabilitiesTest {
    @Test
    fun `built in families materialize their declared execution routes`() {
        val literals = intArrayOf(Lit.make(0, true), Lit.make(1, false))
        val factors = listOf<Factor>(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)),
            Table(intArrayOf(0, 1), longArrayOf(0, 0), longArrayOf(1, 1)),
            Element(0, 1, longArrayOf(0, 1), false),
            Element(0, 1, longArrayOf(2, 3), true),
            Mdd(intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 3), longArrayOf(0, 0, 0), 0, intArrayOf(0), 3),
            Regular(intArrayOf(0), 2, 2, longArrayOf(1, 2, 2, 1), 1, intArrayOf(2)),
            AllDifferent(intArrayOf(0, 1), 0, 2),
            AllDifferent(intArrayOf(0, 1), 0, 2, presents = literals, boundsConsistent = true),
            GlobalCardinality(
                intArrayOf(0, 1), longArrayOf(0, 1), countLow = intArrayOf(0, 0), countHigh = intArrayOf(2, 2),
            ),
            NValue(0, intArrayOf(1, 2)),
            Inverse(intArrayOf(0, 1), intArrayOf(2, 3)),
            SymmetricAllDifferent(intArrayOf(0, 1)),
            ValuePrecede(0, 1, intArrayOf(0, 1)),
            ArrayMinMax(0, intArrayOf(1, 2), true),
            ArrayMinMax(0, intArrayOf(1, 2), false),
            Product(0, 1, 2),
            Increasing(intArrayOf(0, 1), true),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Clause(literals),
            Cardinality(literals, 0, 1),
            PseudoBoolean(longArrayOf(1, 2), literals, PbOp.LE, 2),
            ReifiedCardinality(2, literals, 0, 1),
            ReifiedPseudoBoolean(2, longArrayOf(1, 2), literals, PbOp.LE, 2),
            ComparisonClause(intArrayOf(0), arrayOf(LinearOp.LE), longArrayOf(1)),
            Xor(literals, 1),
            Circuit(intArrayOf(0, 1)),
            Circuit(intArrayOf(0, 1), true),
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            Cumulative.unary(intArrayOf(0, 1), longArrayOf(1, 1)),
            Diffn(intArrayOf(0, 1), intArrayOf(2, 3), longArrayOf(1, 1), longArrayOf(1, 1)),
            LexLess(intArrayOf(0), intArrayOf(1), true),
            Sort(intArrayOf(0, 1), intArrayOf(2, 3)),
            RealProduct(0, 0, 1, 0.0, 1.0),
            ReifiedRealLinear(
                0, intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0,
            ),
            ObjectiveBoundFactor(
                intArrayOf(0), longArrayOf(1), intArrayOf(0), longArrayOf(1), MutableObjectiveBound(0),
            ),
            GaussianXor(listOf(Xor(literals, 1))),
            DifferenceSystem(listOf(DifferenceEdge(0, 1, 0))),
            SymmetryHandling(listOf(intArrayOf(1, 0) to intArrayOf(1, 0))),
        )

        for (factor in factors) {
            val capabilities = assertNotNull(factor.builtInExecutionCapabilities(), factor::class.simpleName)
            assertEquals(
                capabilities.propagation == PropagationCapability.INERT,
                factor.propagatorProjection() === NoPropagator,
                factor::class.simpleName,
            )
            assertEquals(
                capabilities.scoring == ScoringCapability.INERT,
                factor.invariantProjection() === NoInvariant,
                factor::class.simpleName,
            )
            if (capabilities.propagation == PropagationCapability.INERT ||
                capabilities.scoring == ScoringCapability.INERT
            ) {
                assertNotNull(capabilities.inertReason, factor::class.simpleName)
            }
        }
    }

    @Test
    fun `a structural only custom factor refuses execution with a route reason`() {
        val factor = object : Factor by Clause(intArrayOf(Lit.make(0, true))) {}

        val propagation = assertFailsWith<IllegalStateException> { factor.propagatorProjection() }
        val localSearch = assertFailsWith<IllegalStateException> { factor.invariantProjection() }

        assertTrue(propagation.message.orEmpty().contains("unsupported propagation route"))
        assertTrue(localSearch.message.orEmpty().contains("unsupported local-search route"))
    }

    @Test
    fun `custom engine implementations retain their execution paths`() {
        val factor = object : Factor by Clause(intArrayOf(Lit.make(0, true))), Propagator, Invariant {}

        assertSame(factor, factor.propagatorProjection())
        assertSame(factor, factor.invariantProjection())
    }

    @Test
    fun `a custom propagation only factor deliberately omits local scoring`() {
        val factor = object : Factor by Clause(intArrayOf(Lit.make(0, true))), Propagator {}

        assertSame(factor, factor.propagatorProjection())
        assertSame(NoInvariant, factor.invariantProjection())
        assertEquals(ScoringCapability.INERT, factor.executionCapabilities().scoring)
    }

    @Test
    fun `continuous arithmetic scores require source certification`() {
        val factor = RealProduct(0, 0, 1, 0.0, 1.0)

        val capabilities = factor.executionCapabilities()

        assertEquals(AssignmentCheckCapability.SOURCE_CERTIFICATION, capabilities.assignmentCheck)
        assertEquals(ScoringCapability.HEURISTIC, capabilities.scoring)
        assertEquals(RelaxationCapability.SOUND, capabilities.relaxation)
    }

    @Test
    fun `custom linear declarations retain their sound relaxation path`() {
        val factor = object : Factor by Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) {}
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(factor))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        val relaxation = CpToLpRelaxation(problem, objective).build(RootDomains(problem))

        assertEquals(RelaxationCapability.SOUND, factor.executionCapabilities().relaxation)
        assertEquals(1, relaxation.model.m)
        assertEquals(1L, relaxation.model.rhs[0])
    }
}
