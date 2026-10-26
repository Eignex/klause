package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.EqualityPredicateDefinition
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticBlockRemovalTest {
    @Test
    fun `complete source blocks should admit a coordinated strict repair`() {
        val center = Sample(booleanArrayOf(), longArrayOf(0, 1, 2, 0))
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 0))
        val cases = listOf(
            SemanticBlockRemoval.Kind.Assignment to AllDifferent(intArrayOf(0, 1, 2)),
            SemanticBlockRemoval.Kind.Resource to Cumulative(intArrayOf(0, 1, 2),
                longArrayOf(1, 1, 1), longArrayOf(1, 1, 1), 1),
            SemanticBlockRemoval.Kind.Placement to Diffn(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3),
                longArrayOf(1, 1, 1), longArrayOf(1, 1, 1)),
        )
        for ((kind, factor) in cases) {
            val problem = Problem(0, 4, arrayOf(IntDomain(0, 1), IntDomain(1, 2),
                IntDomain(0, 2).excludeValue(1), IntDomain(0, 0)), arrayOf(factor)).bake()
            val selector = SemanticBlockRemoval(kind)

            val freed = selector.destroy(Random(7), problem, center, objective, 0.75)
            val result = BacktrackSolver(problem).minimize(objective, BacktrackParams(
                assumptions = RepairScope.pins(problem, center, freed), maxDecisions = 100))

            assertContentEquals(intArrayOf(0, 1, 2), freed.ints)
            assertEquals(0.0, assertIs<MinimizeResult.Optimal>(result).objective)
            assertEquals(3, selector.lastSearchedInputs)
            assertNull(selector.lastDecline)
        }
    }

    @Test
    fun `required caller pins should decline an entire block`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment)

        val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(), longArrayOf(0, 1, 2)),
            LinearObjective(), 1.0, Assumptions(ints = mapOf(1 to 1L)), Cancellation.Never)

        assertTrue(freed.isEmpty)
        assertEquals(RepairBlockDecline.BlockedPin, selector.lastDecline)
    }

    @Test
    fun `oversized blocks should decline instead of truncating roles`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment, maxInputs = 2)

        val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(), longArrayOf(0, 1, 2)),
            LinearObjective(), 0.1)

        assertTrue(freed.isEmpty)
        assertEquals(RepairBlockDecline.Oversized, selector.lastDecline)
    }

    @Test
    fun `cancelled selection should preserve randomness and allow a fresh call`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(AllDifferent(intArrayOf(0, 1)))).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment)
        val random = Random(7)
        val untouched = Random(7)
        val center = Sample(booleanArrayOf(), longArrayOf(0, 1))

        val declined = selector.destroy(random, problem, center, LinearObjective(), 1.0,
            Assumptions.None, Cancellation { true })

        assertTrue(declined.isEmpty)
        assertEquals(RepairBlockDecline.Cancelled, selector.lastDecline)
        assertEquals(untouched.nextInt(), random.nextInt())
        assertContentEquals(intArrayOf(0, 1), selector.destroy(random, problem, center, LinearObjective(), 1.0).ints)
        assertNull(selector.lastDecline)
    }

    @Test
    fun `work exhaustion should return no partial selection`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment, maxVisits = 4)

        val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(), longArrayOf(0, 1, 2)),
            LinearObjective(), 1.0)

        assertTrue(freed.isEmpty)
        assertEquals(RepairBlockDecline.WorkLimit, selector.lastDecline)
        assertEquals(4, selector.lastVisits)
    }

    @Test
    fun `shared task choices should release complete optional role packets`() {
        val factor = Cumulative(intArrayOf(0, 1, 2), longArrayOf(2, 2, 2), longArrayOf(1, 1, 1), 2,
            presents = intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)),
            durationVars = intArrayOf(3, 4, 5))
        val problem = Problem(3, 7, Array(7) { IntDomain(0, 4) }, arrayOf(factor)).bake()
        val network = InvariantNetwork(listOf(
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(3,
                FunctionalObjective.Operand.v(6), FunctionalObjective.Operand.c(1)), intArrayOf(6)),
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(4,
                FunctionalObjective.Operand.v(6), FunctionalObjective.Operand.c(1)), intArrayOf(6)),
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Plus(5,
                FunctionalObjective.Operand.v(6), FunctionalObjective.Operand.c(1)), intArrayOf(6)),
            EqualityPredicateDefinition(0, 6, 0), EqualityPredicateDefinition(1, 6, 1),
            EqualityPredicateDefinition(2, 6, 0),
        ), 7, 3)
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Resource, network)

        val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(true, false, true),
            longArrayOf(0, 2, 4, 1, 1, 1, 0)), LinearObjective(), 0.1)

        assertContentEquals(intArrayOf(0, 1, 2), freed.bools)
        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6), freed.ints)
        assertEquals(4, selector.lastSearchedInputs)
    }

    @Test
    fun `unusable placement geometry should decline explicitly`() {
        for ((width, position, reason) in listOf(Triple(0L, 0L, RepairBlockDecline.UnsupportedShape),
            Triple(1L, Long.MAX_VALUE, RepairBlockDecline.Arithmetic))) {
            val problem = Problem(0, 3, arrayOf(IntDomain(position, position), IntDomain(0, 1), IntDomain(0, 0)),
                arrayOf(Diffn(intArrayOf(0, 1), intArrayOf(2, 2), longArrayOf(width, 1), longArrayOf(1, 1)))).bake()
            val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Placement)

            val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(), longArrayOf(position, 0, 0)),
                LinearObjective(), 1.0)

            assertTrue(freed.isEmpty)
            assertEquals(reason, selector.lastDecline)
        }
    }

    @Test
    fun `unsupported assignment exceptions should remain explicit`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(AllDifferent(intArrayOf(0, 1), exceptSet = longArrayOf(0)))).bake()
        val selector = SemanticBlockRemoval(SemanticBlockRemoval.Kind.Assignment)

        val freed = selector.destroy(Random(7), problem, Sample(booleanArrayOf(), longArrayOf(0, 0)),
            LinearObjective(), 1.0)

        assertTrue(freed.isEmpty)
        assertEquals(RepairBlockDecline.UnsupportedShape, selector.lastDecline)
    }

    @Test
    fun `default conditioned selectors should preserve their random streams`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2)))).bake()
        val center = Sample(booleanArrayOf(), longArrayOf(0, 1, 2))
        for (selector in DestroyOperator.Defaults) {
            val direct = Random(7)
            val conditioned = Random(7)

            val expected = selector.destroy(direct, problem, center, LinearObjective(), 0.5)
            val actual = selector.destroy(conditioned, problem, center, LinearObjective(), 0.5,
                Assumptions.None, Cancellation.Never)

            assertEquals(expected, actual)
            assertEquals(direct.nextInt(), conditioned.nextInt())
        }
    }
}
