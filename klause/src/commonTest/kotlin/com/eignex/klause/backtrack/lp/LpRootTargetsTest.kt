package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRootTargetsTest {
    @Test
    fun `mixed advice containing only real coordinates preserves the rounding owner`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), doubleArrayOf(1.0),
                intArrayOf(0), LinearOp.GE, 1L)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(2.0))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("real-only-root-advice"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val owner = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n) { 1.0 },
                knownColumns = BooleanArray(relaxation.model.n) { relaxation.colRealId[it] >= 0 })
            engine.rootRoundingWarmStart = owner
            val queries = factory.floatAllowances.size

            assertTrue(!engine.retainRootPrimalTargets(Cancellation.Never, allowMixed = true))

            assertSame(owner, engine.rootRoundingWarmStart)
            assertNull(engine.rootPrimalTargets)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `mixed root targets require opt in and preserve unknown integer choices without another solve`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), doubleArrayOf(1.0),
                intArrayOf(0), LinearOp.GE, 1L)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2),
            realCoefficients = doubleArrayOf(3.0))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("mixed-root-value-order"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val known = BooleanArray(relaxation.model.n)
            known[relaxation.intColOf[0]] = true
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n) { 1.0 }, knownColumns = known)
            val queries = factory.floatAllowances.size

            assertTrue(!engine.retainRootPrimalTargets(Cancellation.Never))
            assertNull(engine.rootPrimalTargets)
            assertTrue(engine.retainRootPrimalTargets(Cancellation.Never, allowMixed = true))
            val before = engine.totalSolveWork()

            assertEquals(listOf(1L, 0L), engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList())
            assertEquals(listOf(0L, 1L), engine.orderRootValues(VarRef.IntVar(1), sequenceOf(0L, 1L)).toList())
            assertEquals(queries, factory.floatAllowances.size)
            assertEquals(before + 2L, engine.totalSolveWork())
        }
    }

    @Test
    fun `mixed root target transfer refuses cancelled or mismatched objectives`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), doubleArrayOf(1.0),
                intArrayOf(0), LinearOp.GE, 1L)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(2.0))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("mixed-root-transfer-refusal")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            for (mismatched in listOf(false, true)) {
                val owner = if (mismatched) LinearObjective(intCoefficients = longArrayOf(2)) else objective
                engine.rootRoundingWarmStart = RootRoundingWarmStart(owner, relaxation,
                    primal = DoubleArray(relaxation.model.n))

                assertTrue(!engine.retainRootPrimalTargets(Cancellation { !mismatched }, allowMixed = true))
                assertNull(engine.rootPrimalTargets)
            }
        }
    }

    @Test
    fun `finished masked rounding guides ordinary values without reading unknown payloads or solving again`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("masked-root-value-order"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val known = BooleanArray(relaxation.model.n)
            known[relaxation.intColOf[0]] = true
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n) { 1.0 }, knownColumns = known)
            val queries = factory.floatAllowances.size
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            assertNull(engine.rootRoundingWarmStart)
            val before = engine.totalSolveWork()

            val guided = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()
            val ordinary = engine.orderRootValues(VarRef.IntVar(1), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), guided)
            assertEquals(listOf(0L, 1L), ordinary)
            assertEquals(queries, factory.floatAllowances.size)
            assertEquals(before + 2L, engine.totalSolveWork())
            assertTrue(engine.orderRootValues(VarRef.IntVar(0), emptySequence()).none())
        }
    }

    @Test
    fun `fresh node values take precedence over retained root targets`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("fresh-node-before-root-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val hints = LpHints(2, 0)
            val nodePoint = DoubleArray(relaxation.model.n)
            nodePoint[relaxation.intColOf[1]] = 1.0
            hints.record(relaxation, nodePoint, DoubleArray(relaxation.model.m))
            val before = engine.totalSolveWork()

            val ordered = hints.order(VarRef.IntVar(0),
                engine.orderRootValues(VarRef.IntVar(0), sequenceOf(1L, 0L)))

            assertEquals(0L, ordered.first())
            assertEquals(before, engine.totalSolveWork())
        }
    }

    @Test
    fun `replacing a root guide expires earlier ordinary value targets`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("replaced-root-value-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            assertEquals(1L, engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).first())
            val earlier = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L))

            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n))

            assertEquals(0L, earlier.first())
            assertEquals(0L, engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).first())
        }
    }

    @Test
    fun `disabling lp branching preserves ordinary value order after root rounding`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, branching = false,
                componentOwnership = true, rootCutHarvest = false)),
            SolveStatsSink("disabled-root-value-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L))

            assertEquals(listOf(0L, 1L), ordered.toList())
        }
    }
}
