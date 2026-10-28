package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeBudgetTest {

    private fun php(pigeons: Int, holes: Int): Problem {
        fun x(p: Int, h: Int) = p * holes + h
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) {
            factors.add(Cardinality(IntArray(holes) { h -> Lit.make(x(p, h), true) }, min = 1, max = holes))
        }
        for (h in 0 until holes) {
            factors.add(Cardinality(IntArray(pigeons) { p -> Lit.make(x(p, h), true) }, min = 0, max = 1))
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }

    private fun params(budget: NodeBudget) = BacktrackParams(randomSeed = 1L, pbLearning = false, nodeBudget = budget)

    @Test
    fun `a spent allowance should stop the search short of a verdict`() {
        val budget = NodeBudget(limit = 1)
        val result = BacktrackSolver(php(pigeons = 8, holes = 7).bake()).solve(params(budget))
        assertIs<SolveResult.Unknown>(result, "the first decision exhausts the allowance before the refutation")
        assertTrue(budget.exhausted(), "the allowance is what stopped it, spent=${budget.spent}")
    }

    @Test
    fun `the allowance should not be renewed for a second search`() {
        val budget = NodeBudget(limit = 200)
        val problem = php(pigeons = 8, holes = 7).bake()
        BacktrackSolver(problem).solve(params(budget))
        val afterFirst = budget.spent
        BacktrackSolver(problem).solve(params(budget))
        assertTrue(
            budget.spent - afterFirst < budget.limit,
            "a re-entered search must not get a fresh allowance; it spent ${budget.spent - afterFirst} more",
        )
    }

    @Test
    fun `a local-search run should stop at the move its allowance covers`() {
        val budget = NodeBudget(limit = 100)
        val objective = LinearObjective(boolWeights = LongArray(8 * 7) { 1L })

        val result = LocalSearchSolver(php(pigeons = 8, holes = 7).bake())
            .minimize(objective, LocalSearchParams(randomSeed = 1L, nodeBudget = budget))

        assertEquals((100 * LS_INSTRUCTIONS_PER_WORK).toLong().toDouble(), result.stats.ls.moves.sum)
        assertTrue(budget.exhausted(), "spent=${budget.spent}")
    }

    @Test
    fun `a search that finishes inside its allowance should be unaffected`() {
        val budget = NodeBudget(limit = 1_000_000)
        val result = BacktrackSolver(php(pigeons = 5, holes = 4).bake()).solve(params(budget))
        assertIs<SolveResult.Unsat>(result, "a cap it never reaches must not change the verdict")
    }

    @Test
    fun `a spent allowance should stop an optimize short of a verdict`() {
        val budget = NodeBudget(limit = 1)
        val problem = php(pigeons = 8, holes = 7).bake()
        val objective = LinearObjective(boolWeights = LongArray(8 * 7) { 1L })
        val result = BacktrackSolver(problem).minimize(objective, params(budget))
        assertIs<MinimizeResult.Unknown>(result, "the first decision exhausts the allowance before the refutation")
        assertTrue(budget.exhausted(), "the allowance is what stopped it, spent=${budget.spent}")
    }

    @Test
    fun `an optimize should charge the allowance rather than only its per-slice limit`() {
        val problem = php(pigeons = 8, holes = 7).bake()
        val objective = LinearObjective(boolWeights = LongArray(8 * 7) { 1L })
        val spent = listOf(64L, 256L).map { limit ->
            val budget = NodeBudget(limit = limit)
            BacktrackSolver(problem).minimize(objective, params(budget))
            budget.spent
        }
        assertTrue(
            spent[0] < spent[1],
            "raising the cap must raise the work an optimize does; both caps spent $spent",
        )
    }

    @Test
    fun `a zero allowance should be exhausted before any charge`() {
        val budget = NodeBudget(0L)

        assertTrue(budget.exhausted())
        assertEquals(0L, budget.movesLeft())
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `fractional move prices should carry across segments`() {
        val budget = NodeBudget(5L)
        repeat(3) { budget.spendMoves(1L) }
        assertEquals(3L, budget.spent)

        budget.spendMoves(1L)

        assertEquals(5L, budget.spent)
        assertTrue(budget.exhausted())
    }

    @Test
    fun `remaining instructions should include every move covered by the integer charge`() {
        for (limit in 1L..9L) {
            val budget = NodeBudget(limit)
            val instructions = budget.movesLeft()

            budget.spendMoves(instructions)

            assertTrue(budget.spent <= limit)
            assertEquals(0L, budget.movesLeft())
            budget.spendMoves(1L)
            assertTrue(budget.spent > limit)
        }
    }

    @Test
    fun `remaining instructions should retain fractional capacity after decisions`() {
        val budget = NodeBudget(3L)
        budget.spendMoves(1L)
        budget.spend()

        assertEquals(1L, budget.movesLeft())
        budget.spendMoves(1L)
        assertEquals(3L, budget.spent)
    }

    @Test
    fun `large charges should saturate without restoring an allowance`() {
        val budget = NodeBudget(Long.MAX_VALUE)

        budget.spendMoves(Long.MAX_VALUE)
        budget.spend()

        assertEquals(Long.MAX_VALUE, budget.spent)
        assertEquals(0L, budget.movesLeft())
        assertTrue(budget.exhausted())
    }

    @Test
    fun `worker quotas should divide only the remaining solve allowance`() {
        val budget = NodeBudget(10L)
        budget.spend()

        val quotas = budget.quotas(2)
        repeat(5) { quotas[0].spend() }
        repeat(4) { quotas[1].spend() }

        assertEquals(listOf(5L, 4L), quotas.map { it.limit })
        assertEquals(10L, budget.spent)
        assertTrue(quotas.all { it.exhausted() })
    }

    @Test
    fun `reopening workers should retain their exhausted quotas`() {
        val budget = NodeBudget(10L)
        val first = budget.quotas(2)
        repeat(5) { first[0].spend() }

        val reopened = budget.quotas(2)

        assertTrue(reopened[0].exhausted())
        assertEquals(5L, reopened[1].limit)
        assertEquals(0L, reopened[1].spent)
        assertEquals(5L, budget.spent)
    }

    @Test
    fun `atomic overspend should be charged without donating another worker's quota`() {
        val budget = NodeBudget(10L)
        val quotas = budget.quotas(2)

        quotas[0].spendMoves(8L)

        assertEquals(10L, budget.spent)
        assertTrue(quotas[0].exhausted())
        assertEquals(4L, quotas[1].movesLeft())
        assertEquals(0L, quotas[1].spent)
    }

    @Test
    fun `a zero solve allowance should permit no backtrack decisions`() {
        val budget = NodeBudget(0L)

        val result = BacktrackSolver(php(5, 4).bake()).solve(params(budget))

        assertIs<SolveResult.Unknown>(result)
        assertEquals(0L, budget.spent)
        assertEquals(0.0, result.stats.search.nodes.sum)
    }

    @Test
    fun `parallel portfolio workers should spend separate persistent quotas`() {
        val budget = NodeBudget(6L)
        val scenario = PortfolioScenario(
            cores = 2,
            arms = 2,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            nodeBudget = budget,
            btPool = listOf({ BacktrackRecipe("bounded") { _, _ -> params(budget) } }),
        )
        val workers = PortfolioBuilder.build(php(5, 4).bake(), scenario)

        val results = try {
            workers.map { it.solve(Cancellation.Never) }
        } finally {
            workers.forEach { it.close() }
        }

        assertEquals(listOf(3.0, 3.0), results.map { it.stats.search.nodes.sum })
        assertEquals(6L, budget.spent)
    }

    @Test
    fun `a cancelled worker should leave its quota unused by another worker`() {
        val budget = NodeBudget(6L)
        val scenario = PortfolioScenario(
            cores = 2,
            arms = 2,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            nodeBudget = budget,
            btPool = listOf({ BacktrackRecipe("bounded") { _, _ -> params(budget) } }),
        )
        val workers = PortfolioBuilder.build(php(5, 4).bake(), scenario)

        val result = try {
            assertIs<SolveResult.Unknown>(workers[0].solve(Cancellation { true }))
            workers[1].solve(Cancellation.Never)
        } finally {
            workers.forEach { it.close() }
        }

        assertEquals(3.0, result.stats.search.nodes.sum)
        assertEquals(3L, budget.spent)
    }

    @Test
    fun `resource quotas and compatibility facades should share fractional accounting`() {
        val budget = NodeBudget(10)
        val facades = budget.quotas(2)
        val resources = budget.allowance.quotas(2)

        facades[0].spendMoves(1)
        resources[0].spendMoves(3)
        resources[1].spend()

        assertSame(resources[0], facades[0].allowance)
        assertEquals(5L, facades[0].spent)
        assertEquals(6L, budget.spent)
    }

    @Test
    fun `facade and resource views should not repartition an existing allowance`() {
        val budget = NodeBudget(10)
        budget.quotas(2)

        assertFailsWith<IllegalArgumentException> { budget.allowance.quotas(3) }
    }
}
