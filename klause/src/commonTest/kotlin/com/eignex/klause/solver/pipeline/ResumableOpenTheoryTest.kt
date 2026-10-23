package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ResumableOpenTheoryTest {

    @Test
    fun `paused preparation should survive close and between slice cancellation`() {
        for (cancel in listOf(false, true)) {
            val source = preparedChain()
            var stopped = false
            val search = ResumableOpenTheory(
                OpenTheoryEngine(source, Cancellation.Never, reuseOpeningBoundAttempt = true),
                TheoryParams(),
            )
            val token = Cancellation { stopped }

            assertNull(search.runSlice(token, sliceMillis = Long.MAX_VALUE, sliceWork = 1))
            val paused = search.stats
            assertEquals(source.prepared.stats, paused.presolve)
            assertEquals(17.milliseconds, assertNotNull(paused.presolve?.effort).elapsed)
            assertEquals(listOf(PresolvePass.PROBE_OPEN_RANGES.id), paused.presolve?.passes)
            assertEquals(31L, paused.presolve?.effort?.work)
            assertEquals(0L, paused.run.wallMs)
            assertTrue(paused.openTheory.openWork > 0L)

            stopped = cancel
            search.close()
            search.close()

            assertEquals(paused, search.stats)
        }
    }

    @Test
    fun `cancelling a resumed search should retain preparation exactly once`() {
        val source = preparedChain()
        val search = ResumableOpenTheory(
            OpenTheoryEngine(source, Cancellation.Never, reuseOpeningBoundAttempt = true),
            TheoryParams(),
        )
        assertNull(search.runSlice(Cancellation.Never, sliceMillis = Long.MAX_VALUE, sliceWork = 1))
        val paused = search.stats

        val result = assertIs<OpenTheoryResult.Unknown>(search.runSlice(
            Cancellation { true }, sliceMillis = Long.MAX_VALUE, sliceWork = 1,
        ))
        search.close()

        assertEquals(TerminationReason.Cancelled, result.reason)
        assertEquals(paused.presolve, result.stats.presolve)
        assertEquals(paused.openTheory, result.stats.openTheory)
        assertEquals(paused.search, result.stats.search)
        assertEquals(result.stats, search.stats)
    }

    @Test
    fun `a decided search should retain its native preparation snapshot`() {
        val source = preparedChain()
        val search = ResumableOpenTheory(
            OpenTheoryEngine(source, Cancellation.Never, reuseOpeningBoundAttempt = true),
            TheoryParams(),
        )

        val result = assertIs<OpenTheoryResult.Sat>(search.runSlice(
            Cancellation.Never, sliceMillis = Long.MAX_VALUE, sliceWork = Long.MAX_VALUE,
        ))
        search.close()

        assertEquals(source.prepared.stats, result.stats.presolve)
        assertEquals(result.stats, search.stats)
    }

    @Test
    fun `setup cancellation should retain completed preparation`() {
        val source = preparedChain()
        var stopped = false
        val engine = OpenTheoryEngine(
            source.route,
            {
                stopped = true
                source
            },
            Cancellation.Never,
            reuseOpeningBoundAttempt = true,
            sourceSemantics = source.prepared.source.semantics,
        )
        val search = ResumableOpenTheory(engine, TheoryParams(cancellation = Cancellation { stopped }))

        val result = assertIs<OpenTheoryResult.Unknown>(search.runSlice(
            Cancellation.Never, sliceMillis = Long.MAX_VALUE, sliceWork = 1,
        ))
        search.close()

        assertEquals(TerminationReason.Cancelled, result.reason)
        assertEquals(source.prepared.stats, result.stats.presolve)
        assertEquals(0L, result.stats.openTheory.openWork)
        assertEquals(result.stats, search.stats)
    }

    private fun preparedChain(): OpenSourcePreparation.Planned {
        val problem = chain(8, total = 20)
        val prepared = PreparedSource(
            problem, problem, infeasible = false, objective = null,
            passesFired = listOf(PresolvePass.PROBE_OPEN_RANGES), rebuild = SourceRebuilds.NONE,
            budget = PresolveBudget(1_000).apply { charge(31) }, elapsed = 17.milliseconds,
        )
        return OpenSourcePreparation.Planned(prepared, problem, problem.componentPlan())
    }

    @Test
    fun `mixed point guidance should keep finite holes`() {
        val holes = assertNotNull(IntDomain(0, 2).excludeValues(longArrayOf(1)))
        val source = Problem(
            0,
            1,
            arrayOf(holes),
            arrayOf<Factor>(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val request = OpenTheoryRequest(source).withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `mixed point guidance should keep finite global enforcement`() {
        val source = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                Linear(intArrayOf(1), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val request = OpenTheoryRequest(source).withPresolve(PresolveConfig.NONE)

        val result = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request)).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    // b_i ⇔ x_i ≥ 5 over open x_i, every pair of neighbours with exactly one b true, and Σ x_i ≤ total.
    private fun chain(n: Int, total: Int): Problem {
        val open = Bits(n).also { bits -> for (v in 0 until n) bits.set(v) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors += ReifiedLinear(i, intArrayOf(1), intArrayOf(i), LinearOp.GE, 5)
        for (i in 0 until n - 1) {
            factors += Clause(intArrayOf(Lit.make(i, true), Lit.make(i + 1, true)))
            factors += Clause(intArrayOf(Lit.make(i, false), Lit.make(i + 1, false)))
        }
        for (i in 0 until n) factors += Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)
        factors += Linear(IntArray(n) { 1 }, IntArray(n) { it }, LinearOp.LE, total)
        return Problem(
            n,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), open, open),
            factors = factors.toTypedArray(),
        )
    }

    private fun sliced(problem: Problem): Pair<OpenTheoryResult, Int> {
        val request = OpenTheoryRequest(problem, componentPlan = problem.componentPlan())
            .withPresolve(com.eignex.klause.presolve.PresolveConfig.NONE)
        val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), TheoryParams())
        var slices = 0
        while (true) {
            slices++
            search.runSlice(
                Cancellation.Never,
                sliceMillis = Long.MAX_VALUE,
                sliceWork = 1,
            )?.let { return it to slices }
        }
    }

    @Test
    fun `sliced search should reach a satisfiable verdict across several slices`() {
        val (result, slices) = sliced(chain(8, total = 20))

        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(slices > 1, "slices=$slices")
    }

    @Test
    fun `closing a paused search should keep its learned progress exactly once`() {
        // b_i ⇔ x_i ≥ 5 over open x_i, every pair summing to at most 9, and two clauses each wanting one b true.
        val n = 4
        val open = Bits(n).also { bits -> for (v in 0 until n) bits.set(v) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) factors += ReifiedLinear(i, intArrayOf(1), intArrayOf(i), LinearOp.GE, 5)
        for (i in 0 until n) factors += Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, 0)
        for (i in 0 until n) {
            for (j in i + 1 until n) factors += Linear(intArrayOf(1, 1), intArrayOf(i, j), LinearOp.LE, 9)
        }
        factors += Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        factors += Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true)))
        val problem = Problem(
            n,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), open, open),
            factors = factors.toTypedArray(),
        )
        val request = OpenTheoryRequest(problem, componentPlan = problem.componentPlan())
            .withPresolve(com.eignex.klause.presolve.PresolveConfig.NONE)
        val search = ResumableOpenTheory(OpenTheoryPipeline.engineFor(request), TheoryParams())
        var pausedGlue = 0.0

        while (search.runSlice(Cancellation.Never, sliceMillis = Long.MAX_VALUE, sliceWork = 1) == null) {
            pausedGlue = maxOf(pausedGlue, search.stats.search.glueClauses.sum)
            if (pausedGlue > 0.0) break
        }

        assertTrue(pausedGlue > 0.0, "pausedGlue=$pausedGlue")
        val before = search.stats

        search.close()
        search.close()

        assertEquals(before, search.stats)
    }
}
