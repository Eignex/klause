package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoverColumnDominanceTest {
    @Test
    fun `a strictly cheaper covering column should replace the expensive column in both lanes`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        val costs = mapOf(0 to 2L, 1 to 1L)

        val source = Presolve.fixDominatedSourceVariables(problem, emptyMap(), costs)
        val finite = Presolve.fixDominatedVariables(problem.bake(), emptyMap(), costs)

        assertEquals(listOf(Lit.make(0, false)), source.addedFactors.map { (it as Clause).literals.single() })
        assertEquals(listOf(Lit.make(0, false)), finite.addedFactors.map { (it as Clause).literals.single() })
    }

    @Test
    fun `all small positive covers and signed costs should preserve feasibility and the optimum`() {
        val covers = listOf(3, 5, 6, 7)
        for (selection in 1 until 16) {
            val clauses = covers.filterIndexed { i, _ -> selection and (1 shl i) != 0 }.map { mask ->
                Clause((0..2).filter { mask and (1 shl it) != 0 }.map { Lit.make(it, true) }.toIntArray())
            }
            val problem = Problem(3, 0, emptyArray(), clauses)
            val original = (0 until 8).filter { mask -> clauses.all { holds(it, mask) } }
            for (encoded in 0 until 64) {
                val costs = (0..2).associateWith { ((encoded shr (2 * it)) and 3).toLong() - 1L }
                val pins = CoverColumnDominance.pins(problem, costs, Cancellation.Never)
                val reduced = original.filter { mask -> pins.all { holds(it as Clause, mask) } }
                assertTrue(reduced.isNotEmpty())
                fun cost(mask: Int): Long = costs.entries.sumOf { (v, c) -> if (mask and (1 shl v) != 0) c else 0L }
                assertEquals(original.minOf(::cost), reduced.minOf(::cost))
            }
        }
    }

    @Test
    fun `strict replacement chains should retain a covering terminal regardless of column order`() {
        for (costs in listOf(mapOf(0 to 3L, 1 to 2L, 2 to 1L), mapOf(0 to 1L, 1 to 2L, 2 to 3L))) {
            val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))
            val problem = Problem(3, 0, emptyArray(), listOf(clause))

            val pins = CoverColumnDominance.pins(problem, costs, Cancellation.Never)

            assertEquals(2, pins.size)
            val remaining = (0 until 8).filter { mask -> holds(clause, mask) && pins.all { holds(it as Clause, mask) } }
            assertEquals(1, remaining.size)
            assertEquals(1L, costs.entries.sumOf { (v, c) -> if (remaining.single() and (1 shl v) != 0) c else 0L })
        }
    }

    @Test
    fun `ties negative replacements and maximization should not admit cost dominance`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))
        for (objective in listOf(
            LinearObjective(boolWeights = longArrayOf(2, 2)),
            LinearObjective(boolWeights = longArrayOf(2, -1)),
            LinearObjective(boolWeights = longArrayOf(2, 1)).negated(),
        )) {
            val context = PresolveContext.of(objective)

            val pins = CoverColumnDominance.pins(problem, context.objectiveBoolCoeffs, Cancellation.Never)

            assertTrue(pins.isEmpty())
        }
    }

    @Test
    fun `an absent replacement cost should mean zero`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))))

        val pins = CoverColumnDominance.pins(problem, mapOf(0 to 2L), Cancellation.Never)

        assertEquals(listOf(Lit.make(0, false)), pins.map { (it as Clause).literals.single() })
    }

    @Test
    fun `negative literals conditional rows and other readers should exclude either column`() {
        for (v in 0..1) {
            val literal = Lit.make(v, true)
            val readers = listOf(
                Clause(intArrayOf(Lit.make(v, false), Lit.make(2, true))),
                Clause(intArrayOf(literal)),
                Cardinality(intArrayOf(literal, Lit.make(2, true)), min = 1, max = 1),
                PseudoBoolean(longArrayOf(1), intArrayOf(literal), PbOp.GE, 0),
                ReifiedPseudoBoolean(2, longArrayOf(1), intArrayOf(literal), PbOp.GE, 1),
            )
            for (reader in readers) {
                val problem = Problem(
                    3, 0, emptyArray(),
                    listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    reader,
                )
                )

                assertTrue(CoverColumnDominance.pins(problem, mapOf(0 to 2L, 1 to 1L), Cancellation.Never).isEmpty())
            }
        }
    }

    @Test
    fun `signature collisions should still require exact incidence inclusion`() {
        val factors = ArrayList<Factor>()
        factors.add(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))
        repeat(63) { factors.add(Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true)))) }
        factors.add(Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))))
        factors.add(Clause(intArrayOf(Lit.make(1, true), Lit.make(3, true))))
        val problem = Problem(4, 0, emptyArray(), factors)

        val pins = CoverColumnDominance.pins(problem, mapOf(0 to 2L, 1 to 1L, 2 to 3L, 3 to 3L), Cancellation.Never)

        assertTrue(pins.isEmpty())
    }

    @Test
    fun `interruption at every work boundary should keep only sound completed pins`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(3, true))),
        )
        val problem = Problem(4, 0, emptyArray(), clauses)
        val costs = mapOf(0 to 3L, 1 to 2L, 2 to 1L, 3 to 1L)
        var completePins = false
        for (limit in 0L..100L) {
            val budget = PresolveBudget(limit)

            val pins = CoverColumnDominance.pins(problem, costs, budget.orSpent(Cancellation.Never))

            assertTrue(budget.spent() <= limit)
            val feasible = (0 until 16).filter { mask -> clauses.all { holds(it, mask) } }
            val reduced = feasible.filter { mask -> pins.all { holds(it as Clause, mask) } }
            fun cost(mask: Int) = costs.entries.sumOf { (v, c) -> if (mask and (1 shl v) != 0) c else 0L }
            assertEquals(feasible.minOf(::cost), reduced.minOf(::cost))
            if (budget.spent() < limit) completePins = pins.isNotEmpty()
        }
        assertTrue(completePins)
    }

    @Test
    fun `an interrupted index should not overlook a late unsupported reader`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        )
        )
        for (limit in 0L..40L) {
            val budget = PresolveBudget(limit)

            val pins = CoverColumnDominance.pins(
                problem,
                mapOf(0 to 2L, 1 to 1L),
                budget.orSpent(Cancellation.Never),
            )

            assertTrue(pins.isEmpty())
        }
    }

    @Test
    fun `both scheduled lanes should retain the supplied cancellation`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        )
        )
        val context = PresolveContext(
            objectiveBoolCoeffs = mapOf(0 to 2L, 1 to 1L),
            cancellation = Cancellation { true },
        )

        assertTrue(PresolvePass.DUAL_FIX.applySource(problem, context).isEmpty)
        assertTrue(PresolvePass.DUAL_FIX.applyFinite(problem.bake(), context).isEmpty)
    }

    @Test
    fun `unaffordable broad candidate scans should stop at a linear work ceiling`() {
        val width = 128
        val factors = (0 until width).map { row ->
            Clause((0 until width).filter { it != row }.map { Lit.make(it, true) }.toIntArray())
        }
        val problem = Problem(width, 0, emptyArray(), factors)
        val budget = PresolveBudget(Long.MAX_VALUE)
        val costs = (0 until width).associateWith { it.toLong() + 1L }

        val pins = CoverColumnDominance.pins(problem, costs, budget.orSpent(Cancellation.Never))

        assertTrue(pins.isEmpty())
        val indexEntries = 1L + width + width + 2L * width * (width - 1)
        assertTrue(budget.spent() <= 17L * indexEntries)
        assertFalse(budget.spent() == 0L)
    }

    @Test
    fun `an original WCNF optimum should retain its source witness and objective after cover fixing`() {
        val document = Dimacs.parseWcnf("p wcnf 2 4 10\n10 1 2 0\n1 -1 0\n1 -1 0\n1 -2 0\n")
        val source = document.toProblem()
        val objective = assertNotNull(source.objective).toLinearObjective()

        val prepared = PresolvePipeline.run(source.problem, objective, PresolveConfig.DEFAULT, false)
        val result = BacktrackSolver(prepared.problem.bake()).minimize(
            prepared.objective ?: objective,
            BacktrackParams(randomSeed = 0L),
        )
        val witness = prepared.reconstruct(assertNotNull(result.assignment))

        assertTrue(prepared.changed)
        assertTrue("dual-fix" in prepared.stats.passes)
        assertFalse(witness.boolValue(0))
        assertTrue(witness.boolValue(1))
        assertEquals(1L, objective.evaluateLong(witness))
        val mask = (if (witness.boolValue(0)) 1 else 0) or (if (witness.boolValue(1)) 2 else 0)
        assertTrue(document.hardClauses.all { holds(Clause(it), mask) })
        assertEquals(
            objective.evaluateLong(witness),
            document.fixedCost + document.softClauses
            .filter { !holds(Clause(it.literals), mask) }.sumOf { it.weight }
        )
        val assumptions = Assumptions.None.withBool(0, witness.boolValue(0)).withBool(1, witness.boolValue(1))
        assertFalse(source.problem.propagate(assumptions) is PropagationResult.Unsat)
    }

    @Test
    fun `complete solution queries should retain every assignment despite an explicit dual fix override`() {
        val clause = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(clause))
        val objective = LinearObjective(boolWeights = longArrayOf(2, 1))
        val context = PresolveContext.of(objective, solutionSetSensitive = true)
        for (config in listOf(
            PresolveConfig.DEFAULT,
            PresolveConfig.parse("off,+dual-fix"),
            PresolveConfig.parse("all"),
        )) {
            assertFalse(config.resolved(PresolvePass.DUAL_FIX, context))
        }
        val config = PresolveConfig.parse("off,+dual-fix")

        val source = Presolver.runSource(problem, config, context)
        val finite = PresolvePipeline.run(problem, objective, config, solutionSetSensitive = true)

        assertTrue(source.passesFired.isEmpty())
        assertFalse(finite.changed)
        assertEquals(
            listOf(1, 2, 3),
            (0 until 4).filter { mask ->
            finite.problem.factors.all { holds(it as Clause, mask) }
        }
        )
    }

    private fun holds(clause: Clause, mask: Int): Boolean = clause.literals.any {
        (mask and (1 shl Lit.variable(it)) != 0) == Lit.isPositive(it)
    }
}
