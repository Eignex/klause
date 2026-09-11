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
        val simplex = RevisedSimplex(model)
        assertNotNull(simplex.solve(null))

        assertTrue(simplex.rebind(model.rebind(longArrayOf(2L, 0L), longArrayOf(10L, 10L)), Cancellation.Never))
        val again = assertNotNull(simplex.resolveBounds())

        assertEquals(0, again.refactorizations, "the kept factorization must carry the re-solve")
    }

    @Test
    fun `a re-solved bound revision agrees with solving it cold`() {
        val lo = longArrayOf(2L, 1L)
        val hi = longArrayOf(10L, 10L)
        val model = base()
        val simplex = RevisedSimplex(model)
        assertNotNull(simplex.solve(null))

        assertTrue(simplex.rebind(model.rebind(lo, hi), Cancellation.Never))
        val reused = assertNotNull(simplex.resolveBounds())
        val cold = assertNotNull(RevisedSimplex(base().rebind(lo, hi)).solve(null))

        assertEquals(cold.objective, reused.objective, 1e-9, "reuse changes the pivot path, not the optimum")
    }

    @Test
    fun `a model with a different matrix is refused rather than reused`() {
        val simplex = RevisedSimplex(base())
        assertNotNull(simplex.solve(null))

        // A second build is an equal model over its own arrays, which is exactly what a rebuilt or
        // cut-augmented relaxation is — reusing a factorization across it would be unsound.
        assertFalse(
            simplex.rebind(base(), Cancellation.Never),
            "only a shared matrix and objective may reuse the factorization",
        )
    }

    @Test
    fun `closing a rebound engine releases its injected basis`() {
        val model = base()
        lateinit var factors: KotlinBasisSolver
        val simplex = RevisedSimplex(model, basisSolverFactory = { matrix ->
            KotlinBasisSolver(matrix).also { factors = it }
        })
        assertNotNull(simplex.solve())
        assertTrue(simplex.rebind(model.rebind(longArrayOf(2L, 1L), longArrayOf(10L, 10L)), Cancellation.Never))
        val result = assertNotNull(simplex.resolveBounds())
        assertEquals(4.0, result.objective, 1e-9)
        assertEquals(0, result.refactorizations)

        simplex.close()
        simplex.close()

        assertFailsWith<IllegalStateException> { factors.refactorize(intArrayOf(0)) }
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
                assertNotNull(trail.state.toWorkingModel()), solver, result, Cancellation { cancelled },
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

    @Test
    fun `bounded native trail and legacy rebind traces agree on exact source optima`() {
        val measurements = ArrayList<List<Long>>()
        repeat(3) { repetition ->
            val legacySource = base()
            val zero = ExactLpNumber.of(0L)
            val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))
            val source = ExactLpModel(
                List(2) { listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))) },
                listOf(ExactLpNumber.of(-3L)),
                listOf(ExactLpColumn(box), ExactLpColumn(box), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(ExactLpNumber.of(1L), ExactLpNumber.of(2L), zero)),
            )
            val trail = LpBoundTrail(source)
            RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { native ->
                RevisedSimplex(legacySource).use { legacy ->
                    assertNotNull(native.solve())
                    assertNotNull(legacy.solve())
                    val nativeInitialWork = native.lastWorkOps
                    val legacyInitialWork = legacy.lastWorkOps
                    var nativeFactors = native.lastRefactorizations.toLong()
                    var legacyFactors = legacy.lastRefactorizations.toLong()
                    val nativeInitialFactors = nativeFactors
                    val legacyInitialFactors = legacyFactors
                    var nativeWork = 0L
                    var legacyWork = 0L
                    var attempts = 0L
                    var nativeSolved = 0L
                    var legacySolved = 0L
                    repeat(8) { cycle ->
                        for (step in 0..4) {
                            when (step) {
                                0 -> {
                                    assertTrue(trail.push())
                                    assertTrue(
                                        trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(1L)), cycle * 3L),
                                    )
                                }

                                1 -> {
                                    assertTrue(trail.push())
                                    assertTrue(
                                        trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(3L)), cycle * 3L + 1L),
                                    )
                                }

                                2 -> {
                                    assertTrue(trail.push())
                                    assertTrue(
                                        trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L)), cycle * 3L + 2L),
                                    )
                                }

                                3 -> assertTrue(trail.pop(1))

                                else -> assertTrue(trail.pop(0))
                            }
                            assertTrue(native.adopt(trail.state, Cancellation.Never))
                            val nativeResult = native.resolveBounds()
                            val lower = longArrayOf(if (step == 2) 1L else 0L, if (step in 1..2) 3L else 0L)
                            val upper = longArrayOf(if (step == 4) 10L else 1L, 10L)
                            val expectedX = maxOf(lower[0], minOf(upper[0], 3L - lower[1]))
                            val expectedY = maxOf(lower[1], 3L - expectedX)
                            val expectedPoint = listOf(expectedX, expectedY).map(BigFraction::ofLong)
                            val expectedBound = BigFraction.ofLong(expectedX + 2L * expectedY)
                            val legacyModel = legacySource.rebind(lower, upper)
                            assertTrue(legacy.rebind(legacyModel, Cancellation.Never))
                            val legacyResult = legacy.resolveBounds()
                            attempts++
                            if (nativeResult != null) nativeSolved++
                            if (legacyResult != null) legacySolved++
                            nativeWork += native.lastWorkOps
                            legacyWork += legacy.lastWorkOps
                            nativeFactors += native.lastRefactorizations
                            legacyFactors += legacy.lastRefactorizations
                            if (repetition == 0) {
                                val certified = certifyLpResult(
                                    assertNotNull(trail.state.toWorkingModel()),
                                    native,
                                    nativeResult,
                                )
                                val baseline = certifyLpResult(legacyModel, legacy, legacyResult)
                                val checked = listOfNotNull(
                                    certified,
                                    baseline,
                                    if (cycle == 0) solveAndCertify(trail.state.model) else null,
                                )
                                for (result in checked) {
                                    assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict, "cycle=$cycle step=$step")
                                    assertEquals(expectedPoint, result.exactPrimal)
                                    assertEquals(expectedBound, result.lowerBound)
                                }
                                for (side in assertNotNull(assertNotNull(certified.bound).support).sides) {
                                    val active = assertNotNull(trail.state.activeSide(side.column, side.upper))
                                    assertEquals(active.side, side.side)
                                    assertEquals(active.witness, side.witness)
                                }
                            }
                        }
                    }
                    val measurement = listOf(
                        nativeInitialWork, legacyInitialWork, nativeWork, legacyWork, nativeFactors, legacyFactors,
                        nativeFactors - nativeInitialFactors, legacyFactors - legacyInitialFactors,
                        attempts, nativeSolved, legacySolved,
                    )
                    measurements += measurement
                    assertEquals(40L, attempts)
                    assertEquals(attempts, nativeSolved)
                    assertEquals(attempts, legacySolved)
                    assertTrue(nativeFactors - nativeInitialFactors <= attempts / 8L)
                    assertTrue(legacyFactors - legacyInitialFactors <= attempts / 8L)
                }
            }
        }
        assertTrue(measurements.all { it == measurements.first() })
    }
}
