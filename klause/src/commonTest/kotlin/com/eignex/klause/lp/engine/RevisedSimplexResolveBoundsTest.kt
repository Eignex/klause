package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RevisedSimplexResolveBoundsTest {

    private fun base(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 1L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a bound-only revision should re-solve without rebuilding the factorization`() {
        val model = base()
        val initial = LpExactState(assertNotNull(model.authoritativeModel()))
        val simplex = RevisedSimplex(assertNotNull(initial.toWorkingModel()))
        assertNotNull(simplex.solve(null))

        val next = LpExactState(
            assertNotNull(model.rebind(longArrayOf(2L, 0L), longArrayOf(10L, 10L)).authoritativeModel()),
            boundRevision = 1L,
        )
        assertTrue(simplex.adopt(next, Cancellation.Never))
        val again = assertNotNull(simplex.resolveBounds())

        assertEquals(0, again.refactorizations)
    }

    @Test
    fun `a model with a different matrix should be refused rather than reused`() {
        val initial = LpExactState(assertNotNull(base().authoritativeModel()))
        val simplex = RevisedSimplex(assertNotNull(initial.toWorkingModel()))
        assertNotNull(simplex.solve(null))
        val changed = base()
        changed.csc.colVal[0] = -2L

        assertFalse(simplex.adopt(LpExactState(assertNotNull(changed.authoritativeModel())), Cancellation.Never))
        assertEquals(3.0, assertNotNull(simplex.resolveBounds()).objective)
    }

    @Test
    fun `cancellation during a bound update solve should retire claims but keep the assertion`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(source)
        var cancelDuringFtran = false
        var cancelled = false
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel()), basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    delegate.ftran(x, expectedDensity)
                    if (cancelDuringFtran) cancelled = true
                }
            }
        }).use { solver ->
            assertNotNull(solver.solve())
            assertTrue(trail.assertBound(0, false, ExactLpSide(one), 1L))
            assertTrue(solver.adopt(trail.state, Cancellation { cancelled }))
            cancelDuringFtran = true

            val result = solver.resolveBounds()

            assertNull(result)
            assertNull(solver.solvedExactState)
            assertTrue(solver.gomoryCuts(1).isEmpty())
            assertEquals(1L, trail.state.assertions.single().witness)
        }
    }

    @Test
    fun `losing an active side should repair a nonzero objective with the retained factors`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        for (upper in listOf(false, true)) {
            val source = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(ExactLpNumber.of(10L)),
                listOf(
                    ExactLpColumn(ExactLpBounds()),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(ExactLpNumber.of(if (upper) -1L else 1L), zero)),
            )
            val trail = LpBoundTrail(source)
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, upper, ExactLpSide(ExactLpNumber.of(5L)), 1L))
            val child = assertNotNull(trail.state.toWorkingModel())
            RevisedSimplex(child).use { solver ->
                assertNotNull(solver.solve())
                assertTrue(trail.pop(0))
                assertTrue(solver.adopt(trail.state, Cancellation.Never))

                val result = assertNotNull(solver.resolveBounds())

                val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
                assertEquals(BigFraction.ofLong(if (upper) -10L else 0L), certified.lowerBound)
                assertEquals(0, result.refactorizations)
                assertFalse(assertNotNull(assertNotNull(certified.bound).support).sides.any { it.witness == 1L })
            }
        }
    }

    @Test
    fun `a basic only bound edit should need neither FTRAN nor refactorization`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(ExactLpNumber.of(3L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(source)
        var forwardSolves = 0
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel()), basisSolverFactory = { matrix ->
            val delegate = KotlinBasisSolver(matrix)
            object : BasisSolver by delegate {
                override fun ftran(x: IndexedVector, expectedDensity: Double) {
                    forwardSolves++
                    delegate.ftran(x, expectedDensity)
                }
            }
        }).use { solver ->
            assertEquals(VarStatus.BASIC, assertNotNull(solver.solve()).basis.status[0])
            val before = forwardSolves
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))

            assertTrue(solver.adopt(trail.state, Cancellation.Never))
            val result = assertNotNull(solver.resolveBounds())

            assertEquals(before, forwardSolves)
            assertEquals(0, result.refactorizations)
            val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
            assertEquals(BigFraction.ofLong(3L), certified.lowerBound)
        }
    }

    @Test
    fun `work allowances should change between retained invocations and leave construction limits intact`() {
        val builder = LpBuilder()
        repeat(4) {
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
        }
        RevisedSimplex(builder.build(Sense.MINIMIZE), workLimit = 1L).use { solver ->
            val first = assertNotNull(solver.resolveBounds(LpFloatAllowance(100_000L, 0)))
            assertTrue(first.optimal)
            assertEquals(4.0, first.objective)
            assertEquals(LpFloatTermination.OPTIMAL_CANDIDATE, solver.lastTermination)

            assertFalse(solver.resolveBounds(LpFloatAllowance(1L, 0))?.optimal == true)
            assertEquals(LpFloatTermination.WORK, solver.lastTermination)
            assertTrue(solver.lastWorkOps >= 1L)
            assertTrue(assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 0))).optimal)
            assertEquals(LpFloatTermination.OPTIMAL_CANDIDATE, solver.lastTermination)

            assertFalse(solver.resolveBounds()?.optimal == true)
            assertEquals(LpFloatTermination.WORK, solver.lastTermination)
            assertTrue(solver.lastWorkOps >= 1L)
        }
    }

    @Test
    fun `cancelled and throwing invocations should restore construction allowances`() {
        for (throws in listOf(false, true)) {
            val builder = LpBuilder()
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 1)
            var stopped = false
            val failure = IllegalStateException("cancel callback failed")
            val cancellation = Cancellation {
                if (stopped && throws) throw failure
                stopped
            }
            RevisedSimplex(builder.build(Sense.MINIMIZE), cancellation, workLimit = 1L).use { solver ->
                stopped = true

                if (throws) {
                    assertSame(
                        failure,
                        assertFailsWith<IllegalStateException> {
                            solver.resolveBounds(LpFloatAllowance(0L, 0))
                        },
                    )
                    assertNull(solver.lastTermination)
                } else {
                    assertNull(solver.resolveBounds(LpFloatAllowance(0L, 0)))
                    assertEquals(LpFloatTermination.CANCELLED, solver.lastTermination)
                }
                stopped = false

                assertFalse(solver.resolveBounds()?.optimal == true)
                assertEquals(LpFloatTermination.WORK, solver.lastTermination)
                assertTrue(solver.lastWorkOps >= 1L)
            }
        }
    }

    @Test
    fun `primal feasibility and optimization should share the invocation iteration allowance`() {
        val builder = LpBuilder()
        repeat(4) {
            val x = builder.addVar(0, 4, cost = 1)
            builder.addRow(intArrayOf(x), longArrayOf(1), Relation.GE, 0)
        }
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val initial = LpExactState(source)
        val changed = ExactLpModel(
            List(4) { source.entries(it) },
            List(4) { ExactLpNumber.of(-1L) },
            List(8) { source.column(it) },
            List(4) { source.row(it) },
            ExactLpObjective(List(8) { ExactLpNumber.of(if (it < 4) -1L else 0L) }),
        )
        RevisedSimplex(assertNotNull(initial.toWorkingModel())).use { solver ->
            assertEquals(0.0, assertNotNull(solver.resolveBounds()).objective)
            assertTrue(solver.adopt(LpExactState(changed, boundRevision = 1L, objectiveRevision = 1L)))

            val limited = solver.resolveBounds(LpFloatAllowance(0L, 5))

            assertFalse(limited?.optimal == true)
            assertTrue(solver.lastPivots <= 5)
            val completed = assertNotNull(solver.resolveBounds(LpFloatAllowance(0L, 100)))
            assertTrue(completed.optimal)
            assertEquals(-16.0, completed.objective)
        }
    }
}
