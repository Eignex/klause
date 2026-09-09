package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexNumericsTest {

    @Test
    fun `an underestimated Devex weight is corrected before the solve continues`() {
        lateinit var factors: DistortingBasisSolver
        val simplex = RevisedSimplex(
            multiPivotFeasibilityModel(),
            basisSolverFactory = { matrix ->
                DistortingBasisSolver(KotlinBasisSolver(matrix)).also { factors = it }
            },
        )

        val result = assertNotNull(simplex.solve())

        assertTrue(factors.distorted, "the fixture must underestimate a selected Devex weight")
        assertTrue(simplex.lastDevexWeightCorrections > 0)
        assertTrue(result.primal.all { it >= -1e-7 }, "the correction must retain a valid primal point")
        assertEquals(0L, integerDualLowerBoundCeil(multiPivotFeasibilityModel(), result.duals))
    }

    @Test
    fun `Devex reselection observes cancellation`() {
        lateinit var factors: DistortingBasisSolver
        var polls = 0
        val simplex = RevisedSimplex(
            multiPivotFeasibilityModel(),
            cancellation = Cancellation { ++polls == 2 },
            basisSolverFactory = { matrix ->
                DistortingBasisSolver(KotlinBasisSolver(matrix)).also { factors = it }
            },
        )

        val result = assertNotNull(simplex.solve())

        assertTrue(factors.distorted)
        assertEquals(1, simplex.lastDevexWeightCorrections)
        assertFalse(result.optimal, "cancellation during reselection must not claim an optimum")
        assertEquals(1, result.pivots)
    }

    @Test
    fun `scaled Harris ministep chooses the stable finishing pivot`() {
        val b = LpBuilder()
        val narrow = b.addRealVar(0.0, 2_000_000.0, cost = 1e-6)
        val stable = b.addRealVar(0.0, 2.0, cost = 1.0005)
        b.addRealRow(intArrayOf(narrow, stable), doubleArrayOf(1e-6, 1.0), Relation.GE, 1.0)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)

        val result = assertNotNull(simplex.solve())

        assertEquals(stable, result.basis.basicVars.single())
        assertEquals(1, simplex.lastHarrisMinistepSelections)
        assertTrue(result.primal[stable] >= 1.0 - 1e-7)
        assertDualFeasible(model, result)
    }

    @Test
    fun `a nonfinishing boxed column still limits the Harris step`() {
        val b = LpBuilder()
        val narrow = b.addRealVar(0.0, 2_000_000.0, cost = 1e-6)
        val boxed = b.addRealVar(0.0, 0.01, cost = 10.00001)
        val late = b.addRealVar(0.0, 2.0, cost = 1.0001)
        b.addRealRow(
            intArrayOf(narrow, boxed, late),
            doubleArrayOf(1e-6, 10.0, 1.0),
            Relation.GE,
            1.0,
        )
        val model = b.build(Sense.MINIMIZE)

        val result = assertNotNull(RevisedSimplex(model).solve())

        assertEquals(narrow, result.basis.basicVars.single())
        assertEquals(VarStatus.AT_LOWER, result.basis.status[boxed])
        assertEquals(VarStatus.AT_LOWER, result.basis.status[late])
        val sourceActivity = 1e-6 * result.primal[narrow] +
            10.0 * result.primal[boxed] + result.primal[late]
        val sourceObjective = 1e-6 * result.primal[narrow] +
            10.00001 * result.primal[boxed] + 1.0001 * result.primal[late]
        assertTrue(sourceActivity >= 1.0 - 1e-7)
        assertEquals(1.0, sourceObjective, 1e-9)
        assertEquals(1.0, result.objective, 1e-9)
        assertDualFeasible(model, result)
    }

    @Test
    fun `Harris does not enter a boxed column that cannot finish the long step`() {
        val b = LpBuilder()
        val flip = b.addRealVar(0.0, 1.0, cost = 0.0)
        val finishing = b.addRealVar(0.0, 20.0, cost = 1.0)
        val short = b.addRealVar(0.0, 0.1, cost = 10.00000001)
        b.addRealRow(intArrayOf(flip, finishing, short), doubleArrayOf(1.0, 1.0, 10.0), Relation.GE, 10.0)

        val model = b.build(Sense.MINIMIZE)
        val result = assertNotNull(RevisedSimplex(model).solve())

        assertEquals(finishing, result.basis.basicVars.single())
        assertEquals(VarStatus.AT_UPPER, result.basis.status[flip])
        assertEquals(VarStatus.AT_LOWER, result.basis.status[short])
        assertTrue(result.primal[flip] + result.primal[finishing] + 10.0 * result.primal[short] >= 10.0 - 1e-7)
        assertDualFeasible(model, result)
    }

    @Test
    fun `exhausted boxed capacity does not enter a nonfinishing column`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 1L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 10L)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)

        assertNull(simplex.solve())

        assertEquals(0, simplex.lastPivots)
        assertEquals(VarStatus.AT_LOWER, assertNotNull(simplex.infeasibleBasis).status[x])
        assertNotNull(
            integerFarkasRay(
                model,
                assertNotNull(simplex.infeasibleRay),
                basis = simplex.infeasibleBasis,
                basisRow = simplex.infeasibleRow,
            ),
        )
    }

    @Test
    fun `near tolerance infeasibility rebuilds once before producing a certificate`() {
        val model = nearToleranceInfeasibleModel()
        val simplex = RevisedSimplex(model, iterationLimit = 2)

        assertNull(simplex.solve())

        assertEquals(1, simplex.lastMetrics.numericalRecoveryRefactorizations)
        assertEquals(2, simplex.lastRefactorizations)
        val ray = assertNotNull(simplex.infeasibleRay)
        assertNotNull(
            integerFarkasRay(model, ray, basis = simplex.infeasibleBasis, basisRow = simplex.infeasibleRow),
            "the retried float candidate must still certify against authoritative source data",
        )
    }

    @Test
    fun `a fresh basis does not repeat near tolerance recovery`() {
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, -5e-7)
        val model = b.build(Sense.MINIMIZE)
        val simplex = RevisedSimplex(model)

        assertNull(simplex.solve())

        assertEquals(0, simplex.lastMetrics.numericalRecoveryRefactorizations)
        assertEquals(1, simplex.lastRefactorizations)
        assertNotNull(
            integerFarkasRay(
                model,
                assertNotNull(simplex.infeasibleRay),
                basis = simplex.infeasibleBasis,
                basisRow = simplex.infeasibleRow,
            ),
        )
    }

    @Test
    fun `failed near tolerance rebuild declines without an infeasibility claim`() {
        lateinit var factors: FailingRefactorBasisSolver
        val simplex = RevisedSimplex(
            nearToleranceInfeasibleModel(),
            basisSolverFactory = { matrix ->
                FailingRefactorBasisSolver(KotlinBasisSolver(matrix), failAt = 2).also { factors = it }
            },
        )

        assertNull(simplex.solve())

        assertEquals(2, factors.refactorizations)
        assertEquals(1, simplex.lastMetrics.numericalRecoveryRefactorizations)
        assertEquals(1, simplex.lastSingularRefactorizations)
        assertNull(simplex.infeasibleBasis)
        assertNull(simplex.infeasibleRay)
        simplex.close()
        assertTrue(factors.closed)
    }

    @Test
    fun `near tolerance recovery observes cancellation before rebuilding`() {
        var polls = 0
        val simplex = RevisedSimplex(
            nearToleranceInfeasibleModel(),
            cancellation = Cancellation { ++polls == 2 },
        )

        val result = assertNotNull(simplex.solve())

        assertFalse(result.optimal)
        assertEquals(0, simplex.lastMetrics.numericalRecoveryRefactorizations)
        assertNull(simplex.infeasibleBasis)
        assertNull(simplex.infeasibleRay)
    }

    private fun multiPivotFeasibilityModel(): LpModel {
        val b = LpBuilder()
        val x1 = b.addVar(0L, 10L)
        val x2 = b.addVar(0L, 10L)
        val x3 = b.addVar(0L, 10L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        b.addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        b.addRow(intArrayOf(x1, x3), longArrayOf(1L, 1L), Relation.GE, 5L)
        return b.build(Sense.MINIMIZE)
    }

    private fun nearToleranceInfeasibleModel(): LpModel {
        val b = LpBuilder()
        val x = b.addRealVar(0.0, 2.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 0.9999995)
        return b.build(Sense.MINIMIZE)
    }

    private fun assertDualFeasible(model: LpModel, result: FloatLpResult) {
        for (j in 0 until model.numVars) {
            if (result.basis.status[j] == VarStatus.BASIC) continue
            var activity = 0.0
            if (j < model.n) {
                model.forEachInColumnD(j) { i, value -> activity += result.duals[i] * value }
            } else {
                activity = result.duals[j - model.n]
            }
            val reducedCost = model.costD(j) - activity
            when (result.basis.status[j]) {
                VarStatus.AT_LOWER -> assertTrue(reducedCost >= -1e-7, "column $j reduced cost $reducedCost")
                VarStatus.AT_UPPER -> assertTrue(reducedCost <= 1e-7, "column $j reduced cost $reducedCost")
                VarStatus.BASIC -> Unit
            }
        }
    }
}

private class DistortingBasisSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    var distorted = false
        private set

    override fun btran(x: IndexedVector, expectedDensity: Double) {
        delegate.btran(x, expectedDensity)
        if (!distorted && delegate.updateCount > 0) {
            val scaled = x.toDoubleArray()
            for (i in scaled.indices) scaled[i] *= 10.0
            x.scatter(scaled)
            distorted = true
        }
    }
}

private class FailingRefactorBasisSolver(private val delegate: BasisSolver, private val failAt: Int) :
    BasisSolver by delegate {
    var refactorizations = 0
        private set
    var closed = false
        private set

    override fun refactorize(basicIndex: IntArray): Boolean {
        refactorizations++
        return refactorizations != failAt && delegate.refactorize(basicIndex)
    }

    override fun close() {
        closed = true
        delegate.close()
    }
}
