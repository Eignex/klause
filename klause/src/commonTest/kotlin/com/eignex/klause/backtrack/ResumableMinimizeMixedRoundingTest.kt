package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumableMinimizeMixedRoundingTest {
    @Test
    fun `mixed rounding should publish completed reals without tree decisions`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> true }
        val offered = ArrayList<Sample>()
        val nodesAtPublication = ArrayList<Double>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(ExecutionControl.Never, 1000L, 100_000L) {
                offered += it.sample
                nodesAtPublication += search.stats.search.nodes.sum
            }
            val sample = offered.single()
            assertEquals(1L, sample.ints.single())
            assertEquals(0.5, sample.reals.single())
            assertEquals(BigFraction.ofDouble(0.5), sample.exactReals?.single())
            assertEquals(listOf(0.0), nodesAtPublication)
            assertTrue(search.stats.lp.standaloneWorkOps.sum > 0.0)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `completed mixed leaves should publish only an improvement over the peer cutoff`() {
        for ((cutoff, expected) in listOf(0.5 to 0, 1.0 to 1)) {
            val fixture = UnresolvedRealLeafFixture(true)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Sample>()
            val params = fixture.params.copy(
                maxDecisions = 0L,
                objectiveBoundSupplier = { cutoff },
                assumptions = Assumptions.None.withInt(0, 1L),
            )

            fixture.solver.resumable(fixture.objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, 1000L, 100_000L) { offered += it.sample }
            }

            assertEquals(expected, offered.size)
            assertTrue(offered.all { fixture.objective.evaluate(it) < cutoff })
            for (sample in offered) {
                assertEquals(1L, sample.ints.single())
                assertEquals(0.5, sample.reals.single())
            }
            assertEquals(fixture.opened, fixture.closed)
        }
    }

    @Test
    fun `mixed root probes should admit only an improvement over the peer cutoff`() {
        for ((cutoff, expected) in listOf(0.5 to 0, 1.0 to 1)) {
            val fixture = UnresolvedRealLeafFixture(true)
            fixture.acceptProof = { _, _ -> true }
            val offered = ArrayList<Sample>()
            val params = fixture.params.copy(
                maxDecisions = 0L,
                objectiveBoundSupplier = { cutoff },
                lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
            )

            fixture.solver.resumable(fixture.objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, 1000L, 100_000L) { offered += it.sample }
            }

            assertEquals(expected, offered.size)
            assertTrue(offered.all { fixture.objective.evaluate(it) < cutoff })
            assertEquals(fixture.opened, fixture.closed)
        }
    }

    @Test
    fun `mixed root proposals should improve a declined completion before tree branching`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
                Linear(
                    intArrayOf(1, 2),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
                Linear(
                    intArrayOf(0, 2),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1), realCoefficients = doubleArrayOf(10.0))
        for (initial in listOf(null, Sample(BooleanArray(0), longArrayOf(0, 1, 1), doubleArrayOf(0.3)))) {
            val offered = ArrayList<Sample>()
            val nodesAtPublication = ArrayList<Double>()
            val pool = initial?.let { sample -> IncumbentExchange.minimizing<Sample>().apply { offer(sample, 5.0) } }
            val params = BacktrackParams(
                randomSeed = 0L,
                maxDecisions = 0L,
                objectiveBoundSupplier = { 5.0 },
                pooledIncumbents = pool,
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
            )

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, 1000L, 100_000L) {
                    offered += it.sample
                    nodesAtPublication += search.stats.search.nodes.sum
                }
            }

            val sample = offered.single()
            assertEquals(if (initial == null) 3.0 else 2.0, objective.evaluate(sample))
            assertEquals(listOf(0.0), nodesAtPublication)
            assertEquals(BigFraction.ZERO, sample.exactReals?.single())
        }
    }

    @Test
    fun `mixed directed rounding should complete fractional capacity proposals before branching`() {
        for (sign in listOf(1L, -1L)) {
            val domain = if (sign > 0L) IntDomain(0L, 5L) else IntDomain(-5L, 0L)
            val problem = Problem(
                0,
                1,
                arrayOf(domain),
                arrayOf<Factor>(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(sign.toDouble()),
                        intArrayOf(0),
                        doubleArrayOf(-1.0),
                        LinearOp.GE,
                        0.0,
                    ),
                    Linear(
                        IntArray(0),
                        DoubleArray(0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        0.4,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
            ).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(sign))
            val params = BacktrackParams(
                maxDecisions = 0L,
                objectiveBoundSupplier = { 5.0 },
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
            )
            val offered = ArrayList<Sample>()
            val nodesAtPublication = ArrayList<Double>()

            BacktrackSolver(problem).resumable(objective, params).use { search ->
                search.runSlice(ExecutionControl.Never, 1000L, 100_000L) {
                    offered += it.sample
                    nodesAtPublication += search.stats.search.nodes.sum
                }
            }

            val sample = offered.single()
            assertEquals(sign, sample.ints.single())
            assertEquals(1.0, objective.evaluate(sample))
            assertTrue(sample.reals.single() >= 0.4)
            assertEquals(1, sample.exactReals?.size)
            assertEquals(listOf(0.0), nodesAtPublication)
        }
    }

    @Test
    fun `mixed tolerance rounding should complete its reals before any decision`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.EQ,
                    1.3,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.5),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val params = BacktrackParams(
            maxDecisions = 0L,
            objectiveBoundSupplier = { 2.0 },
            toleranceCheck = { sample ->
                sample.numRealVars == 1 && sample.reals.single() in 0.0..0.5 &&
                    kotlin.math.abs(sample.ints.single() + sample.reals.single() - 1.3) < 1e-8
            },
            lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false),
        )
        val offered = ArrayList<Sample>()
        val nodesAtPublication = ArrayList<Double>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(ExecutionControl.Never, 1000L, 100_000L) {
                offered += it.sample
                nodesAtPublication += search.stats.search.nodes.sum
            }
        }

        val sample = offered.single()
        assertEquals(1.0, objective.evaluate(sample))
        assertTrue(kotlin.math.abs(sample.reals.single() - 0.3) < 1e-8)
        assertTrue(assertNotNull(params.toleranceCheck)(sample))
        assertEquals(listOf(0.0), nodesAtPublication)
    }

    @Test
    fun `mixed rounding should not publish an uncertified completion`() {
        val fixture = UnresolvedRealLeafFixture(true)
        fixture.acceptProof = { _, _ -> false }
        val offered = ArrayList<Sample>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(ExecutionControl.Never, 1000L, 100_000L) { offered += it.sample }
        }

        assertTrue(offered.isEmpty())
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `mixed rounding should not publish after completion cancellation`() {
        val fixture = UnresolvedRealLeafFixture(true)
        var cancelled = false
        fixture.acceptProof = { leaf, _ ->
            if (leaf > 0) cancelled = true
            true
        }
        val offered = ArrayList<Sample>()
        val params = fixture.params.copy(
            maxDecisions = 0L,
            lpPlan = fixture.params.lpPlan.copy(bounding = true, probe = true, rootCutHarvest = false),
        )

        fixture.solver.resumable(fixture.objective, params).use { search ->
            search.runSlice(ExecutionControl { cancelled }, 1000L, 100_000L) { offered += it.sample }
        }

        assertTrue(fixture.visited > 0)
        assertTrue(offered.isEmpty())
        assertEquals(fixture.opened, fixture.closed)
    }
}
