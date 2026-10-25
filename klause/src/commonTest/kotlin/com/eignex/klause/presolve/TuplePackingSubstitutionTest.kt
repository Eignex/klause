package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuplePackingSubstitutionTest {
    @Test
    fun `mandatory occupied values should refute disjoint tables with scalar support`() {
        val rows = longArrayOf(0, 1, 1, 0, 0, 2, 2, 0, 0, 3, 3, 0)
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Table(intArrayOf(0, 1), rows), Table(intArrayOf(2, 3), rows), AllDifferent(intArrayOf(0, 1, 2, 3)),
        )).bake()
        assertFalse(source.baked is PropagationResult.Unsat)

        val result = assertNotNull(TuplePackingSubstitution.substitute(source, emptySet()))

        assertIs<PropagationResult.Unsat>(result.problem.baked)
    }

    @Test
    fun `shared capacities should refute a relaxation with no mandatory individual value`() {
        val tables = (0..2).map { group ->
            val low = 2L + group * 2
            Table(intArrayOf(group * 2, group * 2 + 1), longArrayOf(0, low, low, 0, 1, low + 1, low + 1, 1))
        }
        val source = Problem(0, 6, Array(6) { IntDomain(0, 7) },
            tables + AllDifferent(IntArray(6) { it })).bake()
        val scalar = CpToLpRelaxation(source, null, tableHull = true).build(RootDomains(source))
        assertEquals(FloatLpStatus.OPTIMAL, solveLp(scalar.model).status)

        val result = assertNotNull(TuplePackingSubstitution.substitute(source, emptySet()))
        val packing = CpToLpRelaxation(result.problem, null).build(RootDomains(result.problem))

        assertEquals(LpVerdict.INFEASIBLE, solveAndCertify(packing.model).verdict)
    }

    @Test
    fun `a table with no internally distinct placement should refute the packing`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1)),
            Table(intArrayOf(2, 3), longArrayOf(2, 3, 3, 2)), AllDifferent(intArrayOf(0, 1, 2, 3)),
        )).bake()
        assertFalse(source.baked is PropagationResult.Unsat)

        val result = assertNotNull(TuplePackingSubstitution.substitute(source, emptySet()))

        assertIs<PropagationResult.Unsat>(result.problem.baked)
    }

    @Test
    fun `placement branching should preserve each distinct source assignment once`() {
        val rows = longArrayOf(0, 1, 1, 0, 2, 3, 3, 2, 0, 1, 2, 2)
        val source = Problem(1, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Table(intArrayOf(0, 1), rows), Table(intArrayOf(2, 3), rows), AllDifferent(intArrayOf(0, 1, 2, 3)),
            Clause(intArrayOf(Lit.make(0, true))),
        ))
        val expected = BacktrackSolver(source.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.toList() }.toList()
        val result = assertNotNull(TuplePackingSubstitution.substitute(source.bake(), emptySet()))
        assertEquals(8, result.choices)
        val reconstruct = assertNotNull(result.rebuild.asSampleLift())

        val actual = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map(reconstruct).onEach { assertEquals(listOf(true), it.bools.toList()) }
            .map { it.ints.toList() }.toList()

        assertEquals(8, actual.size)
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(actual.size, actual.toSet().size)
    }

    @Test
    fun `packing in a larger universe should retain unused values`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 5) }, listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 1, 1, 0)),
            Table(intArrayOf(2, 3), longArrayOf(2, 3, 4, 5)),
            AllDifferent(intArrayOf(0, 1, 2, 3)),
        )).bake()
        val result = assertNotNull(TuplePackingSubstitution.substitute(source, emptySet()))
        val reconstruct = assertNotNull(result.rebuild.asSampleLift())

        val assignments = BacktrackSolver(result.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map(reconstruct).map { it.ints.toList() }.toList()

        assertEquals(4, assignments.size)
        assertTrue(assignments.all { it.toSet().size == 4 })
    }

    @Test
    fun `placement capacity deductions should retract between sibling choices`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)), AllDifferent(intArrayOf(0, 1, 2, 3)),
        )).bake()
        val result = assertNotNull(TuplePackingSubstitution.substitute(source, emptySet()))
        val selections = result.rebuild.steps.filterIsInstance<RebuildStep.TupleValue>()
        val first = selections.first { it.variable == 0 }.literals.map(Lit::variable)
        val second = selections.first { it.variable == 2 }.literals.map(Lit::variable)
        val session = PropagationSession(result.problem)

        session.pinBool(first[0], true)
        assertEquals(false, session.boolValue(second[0]))
        assertEquals(true, session.boolValue(second[1]))
        session.popLast()
        session.pinBool(first[1], true)

        assertEquals(true, session.boolValue(second[0]))
        assertEquals(false, session.boolValue(second[1]))
    }

    @Test
    fun `optional and excepted distinctness should retain original relations`() {
        val tables = listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)))
        for (distinct in listOf(
            AllDifferent(intArrayOf(0, 1, 2, 3), presents = IntArray(4) { Lit.make(0, true) }),
            AllDifferent(intArrayOf(0, 1, 2, 3), exceptSet = longArrayOf(0)),
        )) {
            val source = Problem(1, 4, Array(4) { IntDomain(0, 3) }, tables + distinct).bake()

            assertNull(TuplePackingSubstitution.substitute(source, emptySet()))
        }
    }

    @Test
    fun `repeated coordinates and interval tables should decline substitution`() {
        val repeated = Problem(0, 1, arrayOf(IntDomain(0, 3)), listOf(AllDifferent(intArrayOf(0, 0)))).bake()
        val interval = Problem(0, 4, Array(4) { IntDomain(0, 3) }, listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 1), longArrayOf(2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)), AllDifferent(intArrayOf(0, 1, 2, 3)),
        )).bake()

        assertNull(TuplePackingSubstitution.substitute(repeated, emptySet()))
        assertNull(TuplePackingSubstitution.substitute(interval, emptySet()))
    }

    @Test
    fun `outside readers and protected objectives should decline substitution`() {
        val tables = listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)))
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) },
            tables + AllDifferent(intArrayOf(0, 1, 2, 3)))
        val external = Problem(0, 4, Array(4) { IntDomain(0, 3) }, source.factors.toList() +
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 4))

        assertNull(TuplePackingSubstitution.substitute(source.bake(), setOf(0)))
        assertNull(TuplePackingSubstitution.substitute(external.bake(), emptySet()))
    }

    @Test
    fun `invented finite endpoints should retain the original source relations`() {
        val factors = listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)), AllDifferent(intArrayOf(0, 1, 2, 3)))
        for (upper in listOf(false, true)) {
            val source = Problem(0, 4, Array(4) { IntDomain(0, 3) }, factors,
                openIntLo = BooleanArray(4) { it == 0 && !upper },
                openIntHi = BooleanArray(4) { it == 0 && upper }).bake()

            assertNull(TuplePackingSubstitution.substitute(source, emptySet()))
        }
    }

    @Test
    fun `incomplete admission should retain the source model`() {
        val tables = listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)))
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) },
            tables + AllDifferent(intArrayOf(0, 1, 2, 3))).bake()
        for (limits in listOf(TuplePackingSubstitution.Limits(work = 20),
            TuplePackingSubstitution.Limits(cells = 7), TuplePackingSubstitution.Limits(choices = 3),
            TuplePackingSubstitution.Limits(scope = 3))) {
            assertNull(TuplePackingSubstitution.substitute(source, emptySet(), limits = limits))
        }
        assertNull(TuplePackingSubstitution.substitute(source, emptySet(), Cancellation { true }))
    }

    @Test
    fun `typed tuple reconstruction should preserve exact values`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.TupleValue(0,
            intArrayOf(Lit.make(0, true), Lit.make(1, true)), longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE))))
        val ints = arrayOf(bigIntOf(0))

        rebuild.rebuildInto(booleanArrayOf(false, true), ints)

        assertEquals(bigIntOf(Long.MAX_VALUE), ints.single())
    }

    @Test
    fun `tuple reconstruction should require exactly one selected choice`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.TupleValue(0,
            intArrayOf(Lit.make(0, true), Lit.make(1, true)), longArrayOf(1, 2))))

        for (bools in listOf(booleanArrayOf(false, false), booleanArrayOf(true, true))) {
            assertFailsWith<IllegalArgumentException> { rebuild.rebuildInto(bools, longArrayOf(0)) }
        }
    }

    @Test
    fun `pipeline should retain packing reconstruction with solution sensitive admission`() {
        val tables = listOf(Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)))
        val source = Problem(0, 4, Array(4) { IntDomain(0, 3) },
            tables + AllDifferent(intArrayOf(0, 1, 2, 3)))

        val result = PresolvePipeline.run(source, null, PresolveConfig.parse("off,+tuple-packing"), true)
        val assignments = BacktrackSolver(result.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map(result.reconstruct).map { it.ints.toList() }.toList()

        assertTrue("tuple-packing" in result.stats.passes)
        assertEquals(setOf(listOf(0L, 1L, 2L, 3L), listOf(2L, 3L, 0L, 1L)), assignments.toSet())
        assertEquals(2, assignments.size)
    }

    @Test
    fun `packing and binary substitution should compose their Boolean namespaces`() {
        val source = Problem(0, 6, Array(6) { IntDomain(0, if (it < 4) 3L else 1L) }, listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 1, 2, 3)),
            Table(intArrayOf(2, 3), longArrayOf(0, 1, 2, 3)), AllDifferent(intArrayOf(0, 1, 2, 3)),
            Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 1),
        ))
        val result = PresolvePipeline.run(source, null,
            PresolveConfig.parse("off,+tuple-packing,+binary-columns"), true)

        val assignments = BacktrackSolver(result.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map(result.reconstruct).onEach { assertTrue(it.bools.isEmpty()) }.map { it.ints.toList() }.toList()

        assertTrue("tuple-packing" in result.stats.passes && "binary-columns" in result.stats.passes)
        assertEquals(6, assignments.size)
        assertEquals(assignments.size, assignments.toSet().size)
        assertTrue(assignments.all { it.take(4).toSet().size == 4 && it[4] + it[5] >= 1L })
    }
}
