package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Re-solving a bound-only revision of the model on the same engine.
 *
 * A search node differs from its parent in column bounds alone, and neither the basis matrix nor the
 * reduced costs read a bound — so the parent's factorization is still valid and re-solving should not
 * rebuild it. That saving is the whole point: the factorization is the expensive half of a node solve.
 */
class RevisedSimplexResolveBoundsTest {

    /** `x + y >= 3` over `[0, 10]²`, minimising `x + 2y`. */
    private fun base(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 1L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `a bound-only revision re-solves without rebuilding the factorization`() {
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

        assertEquals(0, again.refactorizations, "the kept factorization must carry the re-solve")
    }

    @Test
    fun `a model with a different matrix is refused rather than reused`() {
        val initial = LpExactState(assertNotNull(base().authoritativeModel()))
        val simplex = RevisedSimplex(assertNotNull(initial.toWorkingModel()))
        assertNotNull(simplex.solve(null))
        val changed = base()
        changed.csc.colVal[0] = -2L

        assertFalse(simplex.adopt(LpExactState(assertNotNull(changed.authoritativeModel())), Cancellation.Never))
        assertEquals(3.0, assertNotNull(simplex.resolveBounds()).objective)
    }

    @Test
    fun `cancellation during a bound update solve retires claims but keeps the assertion`() {
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

            assertTrue(cancelled)
            assertNull(result)
            assertNull(solver.solvedExactState)
            assertTrue(solver.gomoryCuts(1).isEmpty())
            assertTrue(solver.mirCuts(1).isEmpty())
            assertNull(solver.infeasibleRay)
            assertEquals(1L, trail.state.assertions.single().witness)
            val certified = certifyLpResult(
                assertNotNull(trail.state.toWorkingModel()),
                solver,
                result,
                Cancellation { cancelled },
            )
            assertEquals(LpVerdict.INDETERMINATE, certified.verdict)
        }
    }

    @Test
    fun `losing an active side repairs nonzero objective with the retained factors`() {
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
                val initial = assertNotNull(solver.solve())
                val childCertificate = certifyLpResult(child, solver, initial)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, childCertificate.verdict)
                assertEquals(BigFraction.ofLong(if (upper) -5L else 5L), childCertificate.lowerBound)
                assertEquals(1L, assertNotNull(assertNotNull(childCertificate.bound).support).sides.single().witness)

                assertTrue(trail.pop(0))
                assertTrue(solver.adopt(trail.state, Cancellation.Never))
                val result = assertNotNull(solver.resolveBounds())
                val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
                val fresh = solveAndCertify(source)

                assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
                assertEquals(listOf(BigFraction.ofLong(if (upper) 10L else 0L)), certified.exactPrimal)
                assertEquals(BigFraction.ofLong(if (upper) -10L else 0L), certified.lowerBound)
                assertEquals(fresh.exactPrimal, certified.exactPrimal)
                assertEquals(fresh.lowerBound, certified.lowerBound)
                assertEquals(0, result.refactorizations)
                assertTrue(result.warmStarted)
                assertTrue(result.pivots > 0)
                assertFalse(assertNotNull(assertNotNull(certified.bound).support).sides.any { it.witness == 1L })
            }
        }
    }

    @Test
    fun `a basic only bound edit needs neither FTRAN nor refactorization`() {
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
            val initial = assertNotNull(solver.solve())
            assertEquals(VarStatus.BASIC, initial.basis.status[0])
            val before = forwardSolves
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))

            assertTrue(solver.adopt(trail.state, Cancellation.Never))
            val result = assertNotNull(solver.resolveBounds())

            assertEquals(before, forwardSolves)
            assertEquals(0, result.refactorizations)
            val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
            assertEquals(listOf(BigFraction.ofLong(3L)), certified.exactPrimal)
            assertEquals(BigFraction.ofLong(3L), certified.lowerBound)
        }
    }

}
