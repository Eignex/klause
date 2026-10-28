package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.conditionedRoot
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TableValueSubstitutionTest {
    @Test
    fun `strict support inclusion should remove an interior value`() {
        val table = Table(intArrayOf(0, 1, 2), longArrayOf(0, 1, 0, 0, 1, 1, 1, 1, 1, 2, 0, 1))
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1)), listOf(table)).bake()
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            transformObjective = true)

        val delta = TableValueSubstitution.reduce(problem, context)

        val domain = assertNotNull(delta.domains)[0]
        assertTrue(0L in domain)
        assertFalse(1L in domain)
        assertTrue(2L in domain)
    }

    @Test
    fun `every enforcing reader should admit the same replacement`() {
        val base = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1, 2, 0))
        val blockers = listOf(
            Table(intArrayOf(0, 2), longArrayOf(0, 0, 1, 1, 2, 0)),
            AllDifferent(intArrayOf(0, 2)),
            ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            Linear(longArrayOf(1, -1), intArrayOf(0, 2), LinearOp.GE, 0),
        )
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            transformObjective = true)
        for (blocker in blockers) {
            val problem = Problem(1, 3, Array(3) { IntDomain(0, 2) }, listOf(base, blocker)).bake()

            val delta = TableValueSubstitution.reduce(problem, context)

            assertTrue(1L in (delta.domains?.get(0) ?: problem.rootIntDomain(0)), blocker.toString())
        }
    }

    @Test
    fun `declared holes and root pins should exclude unavailable replacements`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1, 2, 0))
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)),
            transformObjective = true)
        for (domain in listOf(IntDomain(0, 2).excludeValue(0), IntDomain(1, 1))) {
            val problem = Problem(0, 2, arrayOf(domain, IntDomain(0, 1)), listOf(table)).bake()

            val delta = TableValueSubstitution.reduce(problem, context)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `strict exact descent should retain a replacement after batched chains`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0, 2, 0))
        for ((coefficient, expected) in listOf(1L to 0L, -1L to 2L, Long.MAX_VALUE to 0L,
            Long.MIN_VALUE to 2L)) {
            val problem = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)), listOf(table)).bake()
            val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(coefficient, 0)),
                transformObjective = true)

            val delta = TableValueSubstitution.reduce(problem, context)

            val domain = assertNotNull(delta.domains)[0]
            assertEquals(expected, domain.min)
            assertEquals(expected, domain.max)
        }
    }

    @Test
    fun `zero cost and unsupported table forms should retain all values`() {
        val forms = listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0)),
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0), longArrayOf(0, 0, 1, 0)),
            Table(intArrayOf(0, 0), longArrayOf(0, 0, 1, 1)),
        )
        for ((index, table) in forms.withIndex()) {
            val problem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 0)), listOf(table)).bake()
            val context = PresolveContext.of(
                LinearObjective(intCoefficients = longArrayOf(if (index == 0) 0 else 1, 0)),
                transformObjective = true,
            )

            assertTrue(TableValueSubstitution.reduce(problem, context).isEmpty)
        }
    }

    @Test
    fun `interrupted verification should publish no partial exclusion`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0, 2, 0))
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)), listOf(table)).bake()
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)),
            transformObjective = true)
        for (limit in listOf(0L, 5L, 20L, 40L)) {
            val budget = PresolveBudget(limit)

            val delta = TableValueSubstitution.reduce(problem,
                context.withCancellation(budget.orSpent(Cancellation.Never)))

            assertTrue(delta.isEmpty)
            assertTrue(budget.spent() <= limit)
        }
    }

    @Test
    fun `optimization should consume exclusions and reconstruct a source witness`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 1, 2, 0))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)), listOf(table))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0), constant = 17)
        val context = PresolveContext.of(objective, transformObjective = true)
        val baked = source.bake()
        val reduced = baked.withPassDelta(TableValueSubstitution.reduce(baked, context), BakeConfig.NONE)

        val result = BacktrackSolver(reduced).minimize(objective)
        val witness = assertNotNull(result.assignment)

        assertFalse(1L in reduced.rootIntDomain(0))
        assertEquals(17L, objective.evaluateLong(witness))
        assertFalse(source.propagate(Assumptions.None.withInt(0, witness.intValue(0))
            .withInt(1, witness.intValue(1))) is PropagationResult.Unsat)
    }

    @Test
    fun `solution sensitive and feasibility requests should reject explicit substitution`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 0)), listOf(table)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))
        val sensitive = PresolveContext.of(objective, solutionSetSensitive = true, transformObjective = true)
        val config = PresolveConfig.parse("off,+table-substitution")

        assertFalse(config.resolved(PresolvePass.SUBSTITUTE_TABLE_VALUES, sensitive))
        assertTrue(TableValueSubstitution.reduce(source, sensitive).isEmpty)
        assertTrue(TableValueSubstitution.reduce(source, PresolveContext.EMPTY).isEmpty)
        assertFalse(PresolveConfig.DEFAULT.resolved(PresolvePass.SUBSTITUTE_TABLE_VALUES,
            PresolveContext.of(objective)))
    }

    @Test
    fun `fresh preparation should bind exclusions to its objective and pins`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0, 2, 0))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)), listOf(table))
        val config = PresolveConfig.parse("off,+table-substitution")
        for ((coefficient, expected) in listOf(1L to 0L, -1L to 2L)) {
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient, 0))

            val prepared = PresolvePipeline.run(source, objective, config, false)

            val domain = prepared.problem.bake().rootIntDomain(0)
            assertEquals(expected, domain.min)
            assertEquals(expected, domain.max)
            assertTrue("table-substitution" in prepared.stats.passes)
        }
        val pinned = Problem(0, 2, arrayOf(IntDomain(1, 1), IntDomain(0, 0)), listOf(table))
        val prepared = PresolvePipeline.run(pinned, LinearObjective(intCoefficients = longArrayOf(1, 0)), config, false)
        assertEquals(1L, prepared.problem.bake().rootIntDomain(0).min)
        assertEquals(3L, source.finiteIntDomain(0).valueCount)
    }

    @Test
    fun `complete coverage should retain every original table assignment`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0, 2, 0))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)), listOf(table))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))

        val prepared = PresolvePipeline.run(source, objective, PresolveConfig.parse("off,+table-substitution"), true)
        val values = BacktrackSolver(prepared.problem.bake()).enumerate()
            .map { prepared.reconstruct(it).intValue(0) }.toSet()

        assertEquals(setOf(0L, 1L, 2L), values)
    }

    @Test
    fun `tuple comparisons should stop at the call work ceiling without publishing exclusions`() {
        val tuples = LongArray(4096) { cell ->
            if (cell % 2 == 0) (cell / 2048).toLong() else (cell / 2 % 1024).toLong()
        }
        val table = Table(intArrayOf(0, 1), tuples)
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1023)), listOf(table)).bake()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)),
            transformObjective = true).withCancellation(budget.orSpent(Cancellation.Never))

        val delta = TableValueSubstitution.reduce(problem, context)

        assertTrue(delta.isEmpty)
        assertEquals(262_144L, budget.spent())
    }

    @Test
    fun `captured opening pins should reject a cheaper unavailable value`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 0, 2, 0))
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 0)), listOf(table)).bake()
        val conditioned = source.conditionedRoot(Assumptions.None.withInt(0, 1), Cancellation.Never)
        val context = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)),
            transformObjective = true)

        val delta = TableValueSubstitution.reduce(conditioned, context)

        assertTrue(delta.isEmpty)
        assertEquals(1L, conditioned.rootIntDomain(0).min)
        assertEquals(3L, source.rootIntDomain(0).valueCount)
    }
}
