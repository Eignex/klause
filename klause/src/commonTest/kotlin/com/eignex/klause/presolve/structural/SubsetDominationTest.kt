package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubsetDominationTest {
    @Test
    fun `uniform support widths should skip comparisons on either side of the old row cap`() {
        for (count in listOf(1_500, 1_501)) {
            val problem = Problem(0, count * 2, Array(count * 2) { IntDomain(0, 1) },
                List(count) { Linear(intArrayOf(1, 1), intArrayOf(it * 2, it * 2 + 1), LinearOp.LE, 1) })
            val budget = PresolveBudget(Long.MAX_VALUE)
            val factors = problem.factors.asList()

            val reduced = SubsetDomination().removeDominated(
                problem, ColumnRanges.of(problem.intBounds), factors, budget.orSpent(Cancellation.Never),
            )

            val call = budget.invocations.single()
            assertSame(factors, reduced)
            assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, call.outcome)
            assertEquals(count * 100L, call.work)
            assertEquals(budget.spent(), call.work)
        }
    }

    @Test
    fun `indexed subsets should remain productive above the old row cap`() {
        val count = 1_600
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val large = Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val problem = Problem(0, count + 2, Array(count + 2) { IntDomain(0, 1) },
            listOf(small, large) + List(count) { Linear(intArrayOf(1), intArrayOf(it + 2), LinearOp.LE, 0) })
        val budget = PresolveBudget(Long.MAX_VALUE)

        val reduced = SubsetDomination().removeDominated(
            problem, ColumnRanges.of(problem.intBounds), problem.factors.asList(), budget.orSpent(Cancellation.Never),
        )

        val call = budget.invocations.single()
        assertEquals(problem.factors.filter { it !== large }, reduced)
        assertEquals(PresolveInvocationOutcome.CHANGED, call.outcome)
        assertEquals(1, call.input.factors - call.output.factors)
        assertTrue(call.work < count * 120L)
        assertEquals(budget.spent(), call.work)
    }

    @Test
    fun `unrelated rows should not repeat completed comparisons`() {
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val large = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0)
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(small, large))
        val unrelated = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            listOf(small, large, Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 0)))
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)

        val first = state.removeDominated(problem, ColumnRanges.of(problem.intBounds), problem.factors.asList(), token)
        val second = state.removeDominated(
            unrelated, ColumnRanges.of(unrelated.intBounds), unrelated.factors.asList(), token,
        )

        assertEquals(problem.factors.toList(), first)
        assertEquals(unrelated.factors.toList(), second)
        assertEquals(listOf(PresolveInvocationOutcome.COMPLETE_MISS, PresolveInvocationOutcome.ELIGIBILITY_SKIP),
            budget.invocations.map { it.outcome })
        assertEquals(budget.spent(), budget.invocations.sumOf { it.work })
    }

    @Test
    fun `changed bounds rows and dominators should invalidate completed misses`() {
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val large = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 0)
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(small, large))
        val changes = listOf(
            Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 0)), listOf(small, large)),
            Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(small, Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1))),
            Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 0), large)),
        )
        for ((index, changed) in changes.withIndex()) {
            val state = SubsetDomination()
            val budget = PresolveBudget(Long.MAX_VALUE)
            val token = budget.orSpent(Cancellation.Never)
            state.removeDominated(source, ColumnRanges.of(source.intBounds), source.factors.asList(), token)

            val reduced = state.removeDominated(
                changed, ColumnRanges.of(changed.intBounds), changed.factors.asList(), token,
            )

            assertEquals(if (index == 2) changed.factors.toList() else listOf(small), reduced)
            assertEquals(if (index == 2) PresolveInvocationOutcome.COMPLETE_MISS else PresolveInvocationOutcome.CHANGED,
                budget.invocations.last().outcome)
        }
    }

    @Test
    fun `a new related dominator should not repeat a completed pair`() {
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)
        val large = Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val tighter = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(small, large))
        val changed = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(small, large, tighter))
        var extraReads = 0
        val ranges = object : ColumnRanges by ColumnRanges.of(problem.intBounds) {
            override fun max(v: Int): Long {
                if (v == 1) extraReads++
                return 1L
            }
        }
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        state.removeDominated(problem, ranges, problem.factors.asList(), token)

        val reduced = state.removeDominated(changed, ranges, changed.factors.asList(), token)

        assertEquals(listOf(small, tighter), reduced)
        assertEquals(4, extraReads)
        assertEquals(PresolveInvocationOutcome.CHANGED, budget.invocations.last().outcome)
    }

    @Test
    fun `bounded scans should resume untested pairs and skip after completion`() {
        val count = 128
        val problem = Problem(0, count * 3 + 4, Array(count * 3 + 4) { IntDomain(0, 1) },
            List(count) { Linear(intArrayOf(1, 1), intArrayOf(0, it + 1), LinearOp.LE, 0) } +
                List(count) { Linear(intArrayOf(1, 1, 1), intArrayOf(0, count + it + 1, 2 * count + it + 1),
                    LinearOp.LE, 0) } +
                Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, count * 3 + 1, count * 3 + 2, count * 3 + 3),
                    LinearOp.LE, 0))
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        val factors = problem.factors.asList()

        repeat(3) {
            assertSame(factors, state.removeDominated(problem, ColumnRanges.of(problem.intBounds), factors, token))
        }

        val calls = budget.invocations
        assertEquals(listOf(PresolveInvocationOutcome.INTERRUPTED, PresolveInvocationOutcome.COMPLETE_MISS,
            PresolveInvocationOutcome.ELIGIBILITY_SKIP), calls.map { it.outcome })
        assertTrue(calls.all { it.work <= 700_000L })
        assertTrue(calls[1].work < calls[0].work)
        assertTrue(calls[2].work < calls[1].work)
        assertEquals(budget.spent(), calls.sumOf { it.work })
    }

    @Test
    fun `removing a wider row should not rescan unchanged smaller pairs`() {
        val count = 64
        val rows = List(count) { Linear(intArrayOf(1, 1), intArrayOf(0, it + 1), LinearOp.LE, 0) } +
            List(count) { Linear(intArrayOf(1, 1, 1), intArrayOf(0, count + it + 1, 2 * count + it + 1),
                LinearOp.LE, 0) }
        val wide = Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, count * 3 + 1, count * 3 + 2, count * 3 + 3),
            LinearOp.LE, 0)
        val domains = Array(count * 3 + 4) { IntDomain(0, 1) }
        val source = Problem(0, domains.size, domains, rows + wide)
        val changed = Problem(0, domains.size, domains, rows)
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        state.removeDominated(source, ColumnRanges.of(source.intBounds), source.factors.asList(), token)

        val reduced = state.removeDominated(
            changed, ColumnRanges.of(changed.intBounds), changed.factors.asList(), token,
        )

        assertEquals(rows, reduced)
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.last().outcome)
        assertTrue(budget.invocations.last().work < 35_000L)
    }

    @Test
    fun `bounded calls should resume row normalization until the comparison drops its target`() {
        val count = 14_000
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val large = Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val problem = Problem(0, count + 2, Array(count + 2) { IntDomain(0, 1) },
            listOf(small, large) + List(count) { Linear(intArrayOf(1), intArrayOf(it + 2), LinearOp.LE, 0) })
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        val factors = problem.factors.asList()

        val first = state.removeDominated(problem, ColumnRanges.of(problem.intBounds), factors, token)
        val second = state.removeDominated(problem, ColumnRanges.of(problem.intBounds), factors, token)

        val calls = budget.invocations
        assertSame(factors, first)
        assertEquals(factors.filter { it !== large }, second)
        assertEquals(listOf(PresolveInvocationOutcome.INTERRUPTED, PresolveInvocationOutcome.CHANGED),
            calls.map { it.outcome })
        assertTrue(calls[1].work < calls[0].work)
        assertEquals(budget.spent(), calls.sumOf { it.work })
    }

    @Test
    fun `a dominator replaced after an interrupted normalization should not supply a drop`() {
        val count = 14_000
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val loose = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)
        val large = Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val unary = List(count) { Linear(intArrayOf(1), intArrayOf(it + 2), LinearOp.LE, 0) }
        val domains = Array(count + 2) { IntDomain(0, 1) }
        val source = Problem(0, count + 2, domains, listOf(small, large) + unary)
        val replaced = Problem(0, count + 2, domains, listOf(loose, large) + unary)
        val state = SubsetDomination()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        state.removeDominated(source, ColumnRanges.of(source.intBounds), source.factors.asList(), token)

        val reduced = state.removeDominated(
            replaced, ColumnRanges.of(replaced.intBounds), replaced.factors.asList(), token,
        )

        assertEquals(replaced.factors.toList(), reduced)
        assertEquals(listOf(PresolveInvocationOutcome.INTERRUPTED, PresolveInvocationOutcome.COMPLETE_MISS),
            budget.invocations.map { it.outcome })
    }

    @Test
    fun `cancellation should retain completed drops and report interruption`() {
        val count = 200
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)
        val problem = Problem(0, count + 1, Array(count + 1) { IntDomain(0, 1) },
            listOf(small) + List(count) { Linear(intArrayOf(1, 1), intArrayOf(0, it + 1), LinearOp.LE, 1) })
        var stop = false
        var reads = 0
        val ranges = object : ColumnRanges by ColumnRanges.of(problem.intBounds) {
            override fun max(v: Int): Long {
                if (++reads > 2 * count) stop = true
                return 1L
            }
        }
        val budget = PresolveBudget(Long.MAX_VALUE)

        val reduced = SubsetDomination().removeDominated(
            problem, ranges, problem.factors.asList(), budget.orSpent(Cancellation { stop }),
        )

        val call = budget.invocations.single()
        assertSame(small, reduced.first())
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, call.outcome)
        assertTrue(call.input.factors - call.output.factors in 1 until count)
        assertEquals(budget.spent(), call.work)
    }

    @Test
    fun `a cancelled scan should publish no unfinished analysis`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
        ))
        val factors = problem.factors.asList()
        val budget = PresolveBudget(Long.MAX_VALUE)

        val reduced = SubsetDomination().removeDominated(
            problem, ColumnRanges.of(problem.intBounds), factors, budget.orSpent(Cancellation { true }),
        )

        assertSame(factors, reduced)
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, budget.invocations.single().outcome)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `cheap duplicate drops should not change subset cost or yield in either lane`() {
        val small = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)
        val large = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, listOf(small, large, large))
        for (lane in PresolveLane.entries) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val token = budget.orSpent(Cancellation.Never)
            val context = PresolveContext(presolveBudget = budget)
            val config = PresolveConfig.parse("off,+subsume")

            when (lane) {
                PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
            }

            val calls = budget.invocations.filter { it.pass == "subsume-subset" }
            assertEquals(PresolveInvocationOutcome.CHANGED, budget.invocations.first { it.pass == "subsume" }.outcome)
            assertEquals(listOf(PresolveInvocationOutcome.COMPLETE_MISS, PresolveInvocationOutcome.ELIGIBILITY_SKIP),
                calls.map { it.outcome })
            assertTrue(calls.all { it.lane == lane && it.input.factors == it.output.factors })
            assertEquals(budget.spent(),
                budget.invocations.sumOf { it.work } + budget.preparationStages.sumOf { it.work })
        }
    }
}
