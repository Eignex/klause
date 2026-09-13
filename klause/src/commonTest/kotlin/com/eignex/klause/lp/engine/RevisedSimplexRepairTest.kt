package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisRepair
import com.eignex.klause.simplex.basis.BasisRepairControl
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexRepairTest {
    @Test
    fun `arithmetic cancellation callback failures escape the outer numerical fallback`() {
        val primary = BasisArithmeticException("callback")
        var armed = false
        var factorCalls = 0
        var closes = 0
        RevisedSimplex(
            model(),
            cancellation = Cancellation { if (armed) throw primary else false },
            basisSolverFactory = { matrix ->
                val delegate = KotlinBasisSolver(matrix)
                object : BasisSolver by delegate {
                    override fun refactorize(basicIndex: IntArray): Boolean {
                        factorCalls++
                        return false
                    }

                    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
                        armed = true
                        return delegate.refactorizeRepairing(basicIndex, control)
                    }

                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            },
        ).use { solver ->
            val actual = assertFailsWith<BasisArithmeticException> { solver.solve() }

            assertTrue(actual === primary)
            assertEquals(1, factorCalls)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `stopped repair cannot start a cold or unscaled fallback`() {
        for (primal in listOf(false, true)) {
            for (reason in listOf("cancel", "work", "unknown", "throw")) {
                val builder = LpBuilder()
                val x = builder.addVar(0L, 10L, cost = 1L)
                builder.addRow(intArrayOf(x), longArrayOf(1000L), Relation.GE, 3000L)
                val model = builder.build(Sense.MINIMIZE)
                var factorCalls = 0
                var repairCalls = 0
                var owners = 0
                var cancelled = false
                RevisedSimplex(
                    model,
                    workLimit = 10000,
                    cancellation = Cancellation { cancelled },
                    basisSolverFactory = { matrix ->
                        owners++
                        val delegate = KotlinBasisSolver(matrix)
                        object : BasisSolver by delegate {
                            override fun refactorize(basicIndex: IntArray): Boolean {
                                factorCalls++
                                return false
                            }

                            override fun refactorizeRepairing(
                                basicIndex: IntArray,
                                control: BasisRepairControl,
                            ): BasisRepair? {
                                repairCalls++
                                when (reason) {
                                    "cancel" -> cancelled = true

                                    "work" -> control.charge(assertNotNull(control.maxWork))

                                    "unknown" -> control.charge(1, complete = false)

                                    "throw" -> {
                                        control.charge(assertNotNull(control.maxWork))
                                        throw BasisArithmeticException("stopped repair")
                                    }
                                }
                                control.check()
                                return null
                            }
                        }
                    },
                ).use { solver ->
                    val result = if (primal) solver.solvePrimal() else solver.solve()

                    assertNull(result, "primal=$primal reason=$reason")
                    assertEquals(1, factorCalls)
                    assertEquals(1, repairCalls)
                    assertEquals(1, owners)
                    assertNull(solver.infeasibleRay)
                }
            }
        }
    }

    @Test
    fun `dual and primal restart after a repaired refactorization changes headings`() {
        for (primal in listOf(false, true)) {
            val model = model()
            lateinit var factors: PermutingRepairSolver
            RevisedSimplex(
                model,
                refactorUpdateLimit = 1,
                basisSolverFactory = { matrix ->
                    PermutingRepairSolver(KotlinBasisSolver(matrix), model.n).also { factors = it }
                },
            ).use { solver ->
                val result = assertNotNull(if (primal) solver.solvePrimal() else solver.solve())
                val fresh = RevisedSimplex(model).use {
                    assertNotNull(if (primal) it.solvePrimal() else it.solve())
                }

                assertEquals(1, factors.repairAttempts)
                assertTrue(factors.installedPermutedUnits)
                assertEquals(fresh.objective, result.objective, 1e-9)
                assertTrue(result.primal.all { it >= -1e-7 })
                assertEquals(model.m, result.basis.basicVars.distinct().size)
                assertEquals(model.m, result.basis.status.count { it == VarStatus.BASIC })
            }
        }
    }

    @Test
    fun `a null repair falls back to logicals and continues without a false claim`() {
        val model = model()
        lateinit var factors: NullRepairSolver
        RevisedSimplex(
            model,
            refactorUpdateLimit = 1,
            basisSolverFactory = { matrix ->
                NullRepairSolver(KotlinBasisSolver(matrix)).also { factors = it }
            },
        ).use { solver ->
            val result = assertNotNull(solver.solve())
            val fresh = RevisedSimplex(model).use { assertNotNull(it.solve()) }

            assertEquals(1, factors.repairAttempts)
            assertTrue(factors.logicalFallbackInstalled)
            assertEquals(fresh.objective, result.objective, 1e-9)
            assertNull(solver.infeasibleRay)
        }
    }

    private fun model(): LpModel {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        val y = builder.addVar(0L, 10L, cost = 2L)
        val z = builder.addVar(0L, 10L, cost = 3L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(y, z), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(x, z), longArrayOf(1L, 1L), Relation.GE, 5L)
        return builder.build(Sense.MINIMIZE)
    }
}

private class NullRepairSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    private var ordinaryAttempts = 0
    var repairAttempts = 0
        private set
    var logicalFallbackInstalled = false
        private set

    override fun refactorize(basicIndex: IntArray): Boolean {
        ordinaryAttempts++
        if (ordinaryAttempts == 2) return false
        return delegate.refactorize(basicIndex).also {
            if (ordinaryAttempts == 3 && it) logicalFallbackInstalled = true
        }
    }

    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
        repairAttempts++
        return null
    }
}

private class PermutingRepairSolver(private val delegate: BasisSolver, private val structuralColumns: Int) :
    BasisSolver by delegate {
    private var ordinaryAttempts = 0
    var repairAttempts = 0
        private set
    var installedPermutedUnits = false
        private set

    override fun refactorize(basicIndex: IntArray): Boolean {
        ordinaryAttempts++
        return ordinaryAttempts != 2 && delegate.refactorize(basicIndex)
    }

    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
        repairAttempts++
        val unitRows = IntArray(n) { n - it - 1 }
        val logicals = IntArray(n) { structuralColumns + unitRows[it] }
        if (!delegate.refactorize(logicals)) return null
        installedPermutedUnits = true
        return BasisRepair(IntArray(n) { -1 }, unitRows)
    }
}
