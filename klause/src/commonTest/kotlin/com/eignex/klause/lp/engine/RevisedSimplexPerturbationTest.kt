package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RevisedSimplexPerturbationTest {
    @Test
    fun `original cost cleanup retains a source checked infeasibility ray`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 1L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 2L)
        val model = b.build(Sense.MINIMIZE)
        val solver = RevisedSimplex(model, perturbationOptions = CostPerturbationOptions(root = true))

        assertNull(solver.solve())

        assertEquals(1, solver.lastNumericalMetrics.cleanupSuccesses)
        assertNotNull(
            integerFarkasRay(
                model,
                assertNotNull(solver.infeasibleRay),
                basis = solver.infeasibleBasis,
                basisRow = solver.infeasibleRow,
            ),
        )
        solver.close()
    }

    @Test
    fun `legacy fixed columns do not acquire root shifts`() {
        val b = LpBuilder()
        b.addVar(1L, 1L, cost = 1L)
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        val model = b.build(Sense.MINIMIZE)
        val solver = RevisedSimplex(model, perturbationOptions = CostPerturbationOptions(root = true))

        val result = assertNotNull(solver.solve())

        assertEquals(1, solver.lastNumericalMetrics.rootShifts)
        assertEquals(2.0, result.objective)
        assertEquals(2L, integerDualLowerBoundCeil(model, result.duals))
        solver.close()
    }

    @Test
    fun `working scopes and bound pops do not retain perturbed objectives`() {
        val x = 0
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(ExactLpNumber.of(2L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver = RevisedSimplex(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                pricing = pricing,
                perturbationOptions = CostPerturbationOptions(root = true, stall = true),
            )
        }
        LpScopedSolver(state, context = LpSolveContext(engineFactory = factory)).use { owner ->
            repeat(3) {
                assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.push())
                assertTrue(owner.assertBound(x, false, ExactLpSide(one), 42L))
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                val temporary = LpWorkingModel.overrides(
                    owner.state,
                    ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
                )
                owner.withWorkingModel(temporary) { scope ->
                    val result = assertNotNull(scope.solve())
                    assertEquals(BigFraction.ofLong(-2L), result.lowerBound)
                    assertEquals(listOf(BigFraction.ofLong(2L)), result.exactPrimal)
                }
                assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
                assertTrue(owner.pop(0))
            }
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `root cleanup reverses a perturbed choice and certifies the original objective`() {
        for (primal in listOf(false, true)) {
            val b = LpBuilder()
            val x = b.addRealVar(0.0, 1.0, cost = 1.0)
            val y = b.addRealVar(0.0, 1.0, cost = 1.0001)
            b.addRealRow(intArrayOf(x, y), doubleArrayOf(1.0, 1.0), Relation.GE, 1.0)
            val model = b.build(Sense.MINIMIZE)
            val costs = model.cost.copyOf()
            val solver = RevisedSimplex(
                model,
                perturbationOptions = CostPerturbationOptions(root = true, relativeMagnitude = 0.01),
            )

            val result = assertNotNull(if (primal) solver.solvePrimal() else solver.solve())

            assertEquals(1.0, result.primal[x], 1e-9)
            assertEquals(0.0, result.primal[y], 1e-9)
            assertEquals(1.0, result.objective, 1e-9)
            assertEquals(BigFraction.ONE, certifyLpResult(model, solver, result).lowerBound)
            assertTrue(costs.contentEquals(model.cost))
            assertEquals(1, solver.lastNumericalMetrics.rootAttempts)
            assertEquals(1, solver.lastNumericalMetrics.cleanupSuccesses)
            assertEquals(0, solver.lastNumericalMetrics.stallAttempts)
            solver.close()
        }
    }

    @Test
    fun `stall perturbation escapes degenerate primal cycling with source checked cleanup`() {
        val b = LpBuilder()
        repeat(4) { b.addRealVar(0.0, Double.MAX_VALUE, cost = doubleArrayOf(-10.0, 57.0, 9.0, 24.0)[it]) }
        b.addRealRow(intArrayOf(0, 1, 2, 3), doubleArrayOf(0.5, -5.5, -2.5, 9.0), Relation.LE, 0.0)
        b.addRealRow(intArrayOf(0, 1, 2, 3), doubleArrayOf(0.5, -1.5, -0.5, 1.0), Relation.LE, 0.0)
        b.addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 1.0)
        val model = b.build(Sense.MINIMIZE)
        val solver = RevisedSimplex(
            model,
            iterationLimit = 128,
            scalingOptions = LpScalingOptions(enabled = false),
            perturbationOptions = CostPerturbationOptions(stall = true, stallIterations = 2),
        )

        val result = assertNotNull(solver.solvePrimal())

        assertEquals(1, solver.lastNumericalMetrics.stallAttempts)
        assertEquals(0, solver.lastNumericalMetrics.rootAttempts)
        assertEquals(1, solver.lastNumericalMetrics.cleanupSuccesses)
        assertEquals(-1.0, result.objective, 1e-7)
        assertEquals(BigFraction.ofLong(-1L), certifyLpResult(model, solver, result).lowerBound)
        val x = result.primal
        assertTrue(x.all { it >= 0.0 })
        assertTrue(0.5 * x[0] - 5.5 * x[1] - 2.5 * x[2] + 9.0 * x[3] <= 1e-7)
        assertTrue(0.5 * x[0] - 1.5 * x[1] - 0.5 * x[2] + x[3] <= 1e-7)
        assertTrue(x[0] <= 1.0)
        solver.close()
    }

    @Test
    fun `mixed seats retain exact bounds and costs through repeated objective changes`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val exact = ExactLpModel(
            List(4) { listOf(ExactLpEntry(0, one)) },
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(-1L)))),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(three), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds()),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, ExactLpNumber.of(-1L), zero, one, zero)),
        )
        val state = LpExactState(exact)
        val trail = LpBoundTrail(state)
        val solver = RevisedSimplex(
            assertNotNull(state.toWorkingModel()),
            perturbationOptions = CostPerturbationOptions(root = true),
        )
        repeat(4) { index ->
            val cost = if (index % 2 == 0) one else ExactLpNumber.of(-1L)
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(cost, ExactLpNumber.of(-1L), zero, one, zero))))
            assertTrue(solver.adopt(trail.state, Cancellation.Never))

            val result = assertNotNull(solver.resolveBounds())

            assertEquals(if (index % 2 == 0) 4.0 else 1.0, result.objective, 1e-9)
            assertEquals(-1.0, result.primal[1])
            assertEquals(0.0, result.primal[2])
            assertEquals(3.0, result.primal[3])
            assertEquals(if (index % 2 == 0) 0.0 else 3.0, result.primal[0])
            assertSame(trail.state, result.exactState)
            assertEquals(
                BigFraction.ofLong(if (index % 2 == 0) 4L else 1L),
                certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result).lowerBound,
            )
        }
        solver.close()
    }

    @Test
    fun `spent cleanup allowance withholds optimum and cuts`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        val model = b.build(Sense.MINIMIZE)
        val solver = RevisedSimplex(
            model,
            iterationLimit = 2,
            perturbationOptions = CostPerturbationOptions(root = true),
        )

        assertNull(solver.solve())

        assertEquals(1, solver.lastNumericalMetrics.capExits)
        assertEquals(0, solver.lastNumericalMetrics.cleanupSuccesses)
        assertNull(solver.solvedExactState)
        assertNull(solver.infeasibleRay)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        assertEquals(1L, model.cost[x])
        solver.close()
    }

    @Test
    fun `cleanup exception restores costs before reuse`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        val model = b.build(Sense.MINIMIZE)
        var fail = true
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            model,
            perturbationOptions = CostPerturbationOptions(root = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun btran(x: IndexedVector, expectedDensity: Double) {
                        if (fail && solver.lastNumericalMetrics.cleanupAttempts > 0) error("cleanup injected")
                        delegate.btran(x, expectedDensity)
                    }
                }
            },
        )

        assertFailsWith<IllegalStateException> { solver.solve() }
        fail = false
        val result = assertNotNull(solver.solve())

        assertEquals(1.0, result.objective)
        assertEquals(1L, integerDualLowerBoundCeil(model, result.duals))
        solver.close()
    }

    @Test
    fun `cancellation during cleanup withholds all source publication`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 2L, cost = 1L)
        b.addRow(mapOf(x to 1L), Relation.GE, 1L)
        var cancelled = false
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            cancellation = Cancellation { cancelled },
            perturbationOptions = CostPerturbationOptions(root = true),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun btran(x: IndexedVector, expectedDensity: Double) {
                        delegate.btran(x, expectedDensity)
                        if (solver.lastNumericalMetrics.cleanupAttempts > 0) cancelled = true
                    }
                }
            },
        )

        assertNull(solver.solve())

        assertNull(solver.infeasibleRay)
        assertNull(solver.solvedExactState)
        assertTrue(solver.gomoryCuts(1).isEmpty())
        cancelled = false
        solver.close()
    }
}
