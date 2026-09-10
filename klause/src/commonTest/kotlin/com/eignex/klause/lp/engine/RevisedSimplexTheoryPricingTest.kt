package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.BasisUpdate
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexTheoryPricingTest {
    @Test
    fun `theory pricing prefers less transformed non-free support`() {
        val model = disturbedSupportModel()
        val defaultUpdates = ArrayList<Int>()
        val theoryUpdates = ArrayList<Int>()
        val baseline = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(defaultUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT),
        )
        val candidate = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(theoryUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val baselineResult = assertNotNull(baseline.solve())
        val candidateResult = assertNotNull(candidate.solve())

        assertEquals(0, defaultUpdates.first())
        assertEquals(1, theoryUpdates.first())
        assertSourceFeasible(baselineResult)
        assertSourceFeasible(candidateResult)
    }

    @Test
    fun `warm transformed support outranks source sparsity and ignores free basics`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))
        val fixed = ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))
        val boundedBasic = 0
        val freeBasic = 1
        val sparseCandidate = 2
        val transformedCandidate = 3
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, one), ExactLpEntry(2, one)),
                listOf(ExactLpEntry(0, one), ExactLpEntry(1, one)),
                listOf(ExactLpEntry(0, minusOne)),
                listOf(ExactLpEntry(1, one), ExactLpEntry(2, minusOne)),
            ),
            listOf(minusOne, zero, minusOne),
            listOf(ExactLpColumn(box), ExactLpColumn(ExactLpBounds()), ExactLpColumn(box), ExactLpColumn(box)) +
                List(3) { ExactLpColumn(fixed) },
            List(3) { ExactLpRow() },
            ExactLpObjective(List(7) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val warm = Basis(
            intArrayOf(boundedBasic, freeBasic, model.slackCol(2)),
            arrayOf(
                VarStatus.BASIC,
                VarStatus.BASIC,
                VarStatus.AT_LOWER,
                VarStatus.AT_LOWER,
                VarStatus.FIXED,
                VarStatus.FIXED,
                VarStatus.BASIC,
            ),
            captureEligible = false,
        )
        val defaultUpdates = ArrayList<Int>()
        val theoryUpdates = ArrayList<Int>()
        val baseline = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(defaultUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT),
        )
        val theory = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(theoryUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val baselineResult = assertNotNull(baseline.solve(warm))
        val theoryResult = assertNotNull(theory.solve(warm))

        assertTrue(baselineResult.warmStarted)
        assertTrue(theoryResult.warmStarted)
        assertEquals(sparseCandidate, defaultUpdates.first())
        assertEquals(transformedCandidate, theoryUpdates.first())
        assertEquals(transformedCandidate, theory.lastTheorySelectedColumn)
    }

    @Test
    fun `seeded ties are repeatable and can choose different columns`() {
        val first = solveTie(7L)
        val repeated = solveTie(7L)
        val alternate = solveTie(19L)

        assertEquals(first, repeated)
        assertNotEquals(first, alternate)
        assertEquals(2, first)
        assertEquals(0, alternate)
    }

    @Test
    fun `magnitude floor falls back to ordinary Harris selection`() {
        val b = LpBuilder()
        val x0 = b.addRealVar(0.0, 2e8)
        val x1 = b.addRealVar(0.0, 2e8)
        b.addRealRow(intArrayOf(x0, x1), doubleArrayOf(5e-7, 4e-7), Relation.GE, 1.0)
        val model = b.build(Sense.MINIMIZE)
        val baselineUpdates = ArrayList<Int>()
        val theoryUpdates = ArrayList<Int>()
        val baseline = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(baselineUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT),
        )
        val theory = RevisedSimplex(
            model,
            basisSolverFactory = recordingFactory(theoryUpdates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        assertNotNull(baseline.solve())
        assertNotNull(theory.solve())

        assertEquals(baselineUpdates.first(), theoryUpdates.first())
        assertEquals(1, theory.lastTheoryPricingDeclines)
        assertEquals(0, theory.lastTheoryPricingSamples)
    }

    @Test
    fun `sampling is capped and accepted update uses a freshly prepared spike`() {
        val b = LpBuilder()
        val columns = IntArray(12) { b.addVar(0L, 10L) }
        b.addRow(columns, LongArray(columns.size) { 1L }, Relation.GE, 1L)
        val checked = ArrayList<Int>()
        val simplex = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            basisSolverFactory = recordingFactory(checked, requireFreshSpike = true),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        assertNotNull(simplex.solve())

        assertEquals(8, simplex.lastTheoryPricingSamples)
        assertEquals(1, checked.size)
        assertTrue(simplex.lastTheoryPricingWorkOps > simplex.lastTheoryPricingEstimatedFtranWorkOps)
    }

    @Test
    fun `current free and fixed headings retain their native status`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, one)),
                listOf(ExactLpEntry(1, minusOne)),
                listOf(ExactLpEntry(0, one), ExactLpEntry(1, minusOne)),
            ),
            listOf(ExactLpNumber.of(2L), ExactLpNumber.of(-2L)),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            List(2) { ExactLpRow() },
            ExactLpObjective(List(5) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val warm = Basis(
            intArrayOf(0, 4),
            arrayOf(VarStatus.BASIC, VarStatus.FIXED, VarStatus.AT_LOWER, VarStatus.FIXED, VarStatus.BASIC),
            captureEligible = false,
        )
        val simplex = RevisedSimplex(
            model,
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val result = assertNotNull(simplex.solve(warm))

        assertTrue(result.warmStarted)
        assertEquals(VarStatus.FIXED, result.basis.status[1])
        assertEquals(2, simplex.lastTheorySelectedColumn)
        val certified = certifyLpResult(model, simplex, result)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
    }

    @Test
    fun `cancellation before the first probe preserves the current iterate`() {
        var polls = 0
        val updates = ArrayList<Int>()
        val simplex = RevisedSimplex(
            disturbedSupportModel(),
            cancellation = Cancellation { ++polls == 2 },
            basisSolverFactory = recordingFactory(updates),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val result = assertNotNull(simplex.solve())

        assertFalse(result.optimal)
        assertEquals(0, simplex.lastTheoryPricingSamples)
        assertEquals(1, simplex.lastTheoryPricingResourceStops)
        assertTrue(updates.isEmpty())
        assertNull(simplex.infeasibleBasis)
    }

    @Test
    fun `cancellation after a successful probe stops before flips and pivot`() {
        var probes = 0
        val updates = ArrayList<Int>()
        val simplex = RevisedSimplex(
            disturbedSupportModel(),
            cancellation = Cancellation { probes > 0 },
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        delegate.ftran(x, expectedDensity)
                        if (simplexProbeCandidate(x, matrix.rows)) probes++
                    }

                    override fun update(
                        pivotRow: Int,
                        entering: Int,
                        spike: IndexedVector,
                        pivotEta: IndexedVector?,
                    ): BasisUpdate {
                        updates += entering
                        return delegate.update(pivotRow, entering, spike, pivotEta)
                    }
                }
            },
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val result = assertNotNull(simplex.solve())

        assertFalse(result.optimal)
        assertTrue(simplex.lastTheoryPricingSamples > 0)
        assertEquals(1, simplex.lastTheoryPricingResourceStops)
        assertTrue(updates.isEmpty())
        assertNull(simplex.infeasibleBasis)
        assertNull(simplex.infeasibleRay)
    }

    @Test
    fun `work exhaustion after a successful probe stops before pivot`() {
        val full = RevisedSimplex(
            disturbedSupportModel(),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )
        assertNotNull(full.solve())
        var stopped: RevisedSimplex? = null
        for (limit in 1L..full.lastWorkOps) {
            val attempt = RevisedSimplex(
                disturbedSupportModel(),
                workLimit = limit,
                pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
            )
            attempt.solve()
            if (attempt.lastTheoryPricingSamples > 0 && attempt.lastTheoryPricingResourceStops == 1 &&
                attempt.lastPivots == 0
            ) {
                stopped = attempt
                break
            }
            attempt.close()
        }

        val exhausted = assertNotNull(stopped)
        assertNull(exhausted.infeasibleBasis)
        assertNull(exhausted.infeasibleRay)
        exhausted.close()
    }

    @Test
    fun `failed sample falls back to ordinary Harris`() {
        var ftrans = 0
        val updates = ArrayList<Int>()
        val simplex = RevisedSimplex(
            disturbedSupportModel(),
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        ftrans++
                        if (ftrans == 2) throw BasisArithmeticException("sample decline")
                        delegate.ftran(x, expectedDensity)
                    }

                    override fun update(
                        pivotRow: Int,
                        entering: Int,
                        spike: IndexedVector,
                        pivotEta: IndexedVector?,
                    ): BasisUpdate {
                        updates += entering
                        return delegate.update(pivotRow, entering, spike, pivotEta)
                    }
                }
            },
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        assertNotNull(simplex.solve())

        assertEquals(1, simplex.lastTheoryPricingDeclines)
        assertTrue(updates.isNotEmpty(), "ordinary Harris must continue after the sample decline")
    }

    @Test
    fun `cancellation during a failed sample stops before Harris fallback`() {
        var cancelled = false
        var ftrans = 0
        val updates = ArrayList<Int>()
        val simplex = RevisedSimplex(
            disturbedSupportModel(),
            cancellation = Cancellation { cancelled },
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        ftrans++
                        if (ftrans == 2) {
                            cancelled = true
                            throw BasisArithmeticException("cancelled sample decline")
                        }
                        delegate.ftran(x, expectedDensity)
                    }

                    override fun update(
                        pivotRow: Int,
                        entering: Int,
                        spike: IndexedVector,
                        pivotEta: IndexedVector?,
                    ): BasisUpdate {
                        updates += entering
                        return delegate.update(pivotRow, entering, spike, pivotEta)
                    }
                }
            },
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 7L),
        )

        val result = assertNotNull(simplex.solve())

        assertFalse(result.optimal)
        assertEquals(1, simplex.lastTheoryPricingResourceStops)
        assertEquals(0, simplex.lastTheoryPricingDeclines)
        assertTrue(updates.isEmpty())
        assertNull(simplex.infeasibleBasis)
        assertNull(simplex.infeasibleRay)
    }

    @Test
    fun `nonzero objective adoption disables minimum bound support pricing`() {
        val source = exactCoverModel()
        val trail = LpBoundTrail(source)
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { simplex ->
            assertNotNull(simplex.solve())
            val one = ExactLpNumber.of(1L)
            val zero = ExactLpNumber.of(0L)
            val objective = ExactLpObjective(listOf(one, ExactLpNumber.of(2L), zero))
            assertTrue(trail.replaceObjective(objective))
            assertTrue(simplex.adopt(trail.state, Cancellation.Never))
            val current = assertNotNull(simplex.resolveBounds())

            assertEquals(0, simplex.lastTheoryPricingAttempts)
            val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), simplex, current)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
            assertEquals(
                listOf(
                    BigFraction.ONE,
                    BigFraction.ZERO,
                ),
                certified.exactPrimal,
            )
        }
    }

    private fun solveTie(seed: Long): Int {
        val b = LpBuilder()
        val columns = IntArray(3) { b.addVar(0L, 10L) }
        b.addRow(columns, longArrayOf(1L, 1L, 1L), Relation.GE, 1L)
        val simplex = RevisedSimplex(
            b.build(Sense.MINIMIZE),
            pricing = LpPricingOptions(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, seed),
        )
        assertNotNull(simplex.solve())
        return simplex.lastTheorySelectedColumn
    }

    private fun disturbedSupportModel(): LpModel {
        val b = LpBuilder()
        val x = IntArray(4) { b.addVar(0L, 10L) }
        b.addRow(intArrayOf(x[0], x[1], x[2]), longArrayOf(1L, 1L, 1L), Relation.GE, 1L)
        b.addRow(intArrayOf(x[0], x[2], x[3]), longArrayOf(1L, 1L, 1L), Relation.GE, 1L)
        b.addRow(intArrayOf(x[0], x[3]), longArrayOf(1L, 1L), Relation.GE, 1L)
        return b.build(Sense.MINIMIZE)
    }

    private fun exactCoverModel(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))
        return ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, minusOne)),
                listOf(ExactLpEntry(0, minusOne)),
            ),
            listOf(minusOne),
            listOf(ExactLpColumn(box), ExactLpColumn(box), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, zero)),
        )
    }

    private fun recordingFactory(
        updates: MutableList<Int>,
        requireFreshSpike: Boolean = false,
    ): (com.eignex.koblas.SparseMatrix) -> BasisSolver = { matrix ->
        val delegate = KotlinBasisSolver(matrix)
        object : BasisSolver by delegate {
            private var lastForward: IndexedVector? = null

            override fun ftran(x: IndexedVector, expectedDensity: Double) {
                delegate.ftran(x, expectedDensity)
                lastForward = x
            }

            override fun update(
                pivotRow: Int,
                entering: Int,
                spike: IndexedVector,
                pivotEta: IndexedVector?,
            ): BasisUpdate {
                if (requireFreshSpike) assertTrue(lastForward === spike)
                updates += entering
                return delegate.update(pivotRow, entering, spike, pivotEta)
            }
        }
    }

    private fun simplexProbeCandidate(vector: IndexedVector, dimension: Int): Boolean =
        vector.density < 1.0 || dimension == 1

    private fun assertSourceFeasible(result: FloatLpResult) {
        val x = result.primal
        assertTrue(x.all { it in -1e-7..10.0000001 })
        assertTrue(x[0] + x[1] + x[2] >= 1.0 - 1e-7)
        assertTrue(x[0] + x[2] + x[3] >= 1.0 - 1e-7)
        assertTrue(x[0] + x[3] >= 1.0 - 1e-7)
    }
}
