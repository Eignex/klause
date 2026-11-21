package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.boundedLpEngineProblem
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalRoundingTest {
    @Test
    fun `automatic continued root points survive a slice cancelled during bound checking`() {
        val problem = boundedLpEngineProblem().withSettings(
            ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L),
        ).bake()
        val objective = LinearObjective(intCoefficients = LongArray(3) { 2L })
        var checkingArmed = false
        var checkingStarted = false
        val query = ExecutionControl { checkingStarted }
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ ->
            if (checkingArmed) checkingStarted = true
            !checkingArmed
        })
        LpEngine(problem, objective, LpParams(lpConfig = LpConfig.AUTO),
            SolveStatsSink("continued-root-point-before-bound-check"), context,
            nodeWorkAllowance = { 1_000_000L }).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertTrue(engine.automaticSparseTrial)
            val completed = assertNotNull(engine.rootRoundingWarmStart)
            assertNotNull(completed.primal)
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, completed.relaxation,
                completed.basis, sourceProblem = problem)
            checkingArmed = true

            val sample = engine.lpRoundingProbe(objective, query)
            val retained = engine.retainRootPrimalTargets(ExecutionControl.Never)

            assertTrue(checkingStarted)
            assertNull(sample)
            assertTrue(retained)
        }
    }

    @Test
    fun `ordinary integer rounding keeps nearest targets without a retained root plan`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(4) { v ->
                    if (v == 0 || mask and (1 shl (v - 1)) == 0) 1 else -1
                }
                Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                    1 - coefficients.drop(1).count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n) { 0.4 }
        primal[relaxation.intColOf[0]] = 0.25
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("ordinary-nearest-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal)

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertNull(sample)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `completed declined directions wait for a fresh handoff without repeating propagation`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(3) { v -> if (mask and (1 shl v) == 0) 1 else -1 }
                Linear(coefficients, IntArray(3) { it }, LinearOp.GE,
                    1 - coefficients.count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val root = PropagationSession(problem)
        assertTrue(!root.isUnsatAtRoot)
        val relaxation = CpToLpRelaxation(problem, objective).build(root)
        val guidance = RootRoundingWarmStart(objective, relaxation,
            primal = DoubleArray(relaxation.model.n) { 0.4 })
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("completed-rounding-directions"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            var changes = 0
            var paid = 0L
            val slice = ExecutionControl {
                if (engine.primalPropagationWork > paid) {
                    paid = engine.primalPropagationWork
                    changes++
                }
                changes >= 3
            }
            assertNull(engine.lpRoundingProbe(objective, slice))
            assertTrue(engine.rootRoundingInterrupted)
            val completedWork = engine.primalPropagationWork

            assertNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertEquals(completedWork, engine.primalPropagationWork)
            assertNull(engine.rootRoundingWarmStart)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `interrupted source admission retries completed pins in the same direction`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = LongArray(2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n)
        primal[relaxation.intColOf[0]] = 0.4
        primal[relaxation.intColOf[1]] = 0.6
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("pending-rounding-admission"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            var expired = false
            assertNull(engine.lpRoundingProbe(objective, ExecutionControl { expired }) {
                expired = true
                null
            })
            val prefix = assertNotNull(guidance.pinning)
            assertEquals(IntDomain(0, 0), prefix.intDomain(0))
            assertEquals(IntDomain(1, 1), prefix.intDomain(1))
            val paid = engine.primalPropagationWork

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
            assertEquals(paid, engine.primalPropagationWork)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `completed declined integer rounding is charged before another direction starts`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(4) { v ->
                    if (v == 0 || mask and (1 shl (v - 1)) == 0) 1 else -1
                }
                Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                    1 - coefficients.drop(1).count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n) { 0.4 }
        primal[relaxation.intColOf[0]] = 0.25
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("charged-rounding-direction"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            val slice = ExecutionControl { engine.primalPropagationWork > 0L }

            assertNull(engine.lpRoundingProbe(objective, slice))

            assertTrue(engine.rootRoundingInterrupted)
            assertTrue(engine.primalPropagationWork > 0L)
            assertNull(guidance.pinning)
            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)
            assertEquals(List(4) { 1L }, assertNotNull(sample).ints.toList())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `directed integer rounding escapes a declined nearest pin prefix without another lp query`() {
        for (upward in listOf(true, false)) {
            val upper = if (upward) 1L else 2L
            val problem = Problem(0, 4, arrayOf(IntDomain(0, upper),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (upward) 1 else -1
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                        (if (upward) 1 else -1) - coefficients.drop(1).count { it < 0 })
                })
            val objective = LinearObjective(intCoefficients = LongArray(4))
            val root = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(problem, objective).build(root)
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 4) {
                primal[relaxation.intColOf[v]] = if (v == 0) {
                    if (upward) 0.25 else 1.75
                } else if (upward) 0.4 else 0.6
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
            val factory = RecordingLpEngineFactory()
            assertEquals(IntDomain(0, upper), root.intDomain(0))

            val sample = LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
                SolveStatsSink("directed-integer-rounding"), LpSolveContext(factory)).use { engine ->
                engine.rootRoundingWarmStart = guidance

                engine.lpRoundingProbe(objective, ExecutionControl.Never)
            }

            assertEquals(listOf(1L) + List(3) { if (upward) 1L else 0L }, assertNotNull(sample).ints.toList())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `interrupted directed integer rounding resumes its direction and completed pins`() {
        for (upward in listOf(true, false)) {
            val upper = if (upward) 1L else 2L
            val problem = Problem(0, 4, arrayOf(IntDomain(0, upper),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (upward) 1 else -1
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                        (if (upward) 1 else -1) - coefficients.drop(1).count { it < 0 })
                })
            val objective = LinearObjective(intCoefficients = LongArray(4))
            val root = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(problem, objective).build(root)
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 4) {
                primal[relaxation.intColOf[v]] = if (v == 0) {
                    if (upward) 0.25 else 1.75
                } else if (upward) 0.4 else 0.6
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
            val factory = RecordingLpEngineFactory()
            assertEquals(IntDomain(0, upper), root.intDomain(0))
            var expired = false
            val slice = ExecutionControl {
                val pins = guidance.pinning
                if (pins != null && pins.decisionLevel >= 2 && pins.intDomain(0).min == 1L) expired = true
                expired
            }
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
                SolveStatsSink("resumed-directed-rounding"), LpSolveContext(factory)).use { engine ->
                engine.rootRoundingWarmStart = guidance
                assertNull(engine.lpRoundingProbe(objective, slice))
                val prefix = assertNotNull(guidance.pinning)
                assertTrue(expired)
                assertEquals(IntDomain(1, 1), prefix.intDomain(0))
                val paid = engine.primalPropagationWork

                val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

                assertEquals(listOf(1L) + List(3) { if (upward) 1L else 0L }, assertNotNull(sample).ints.toList())
                assertEquals(prefix.work, engine.primalPropagationWork)
                assertTrue(engine.primalPropagationWork > paid)
                assertTrue(factory.calls.isEmpty())
            }
        }
    }

    @Test
    fun `completed rounding pins resume under a fresh slice without repeated propagation charges`() {
        val n = 4
        val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
            Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
        var expired = false
        val slice = ExecutionControl {
            if ((guidance.pinning?.decisionLevel ?: 0) > 0) expired = true
            expired
        }
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("resumed-rounding-pins")).use { engine ->
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, slice))
            val prefix = assertNotNull(guidance.pinning)
            assertTrue(prefix.probeCancelled)
            assertEquals(IntDomain(0, 0), prefix.intDomain(0))
            val paid = engine.primalPropagationWork
            assertTrue(paid > 0L)
            assertNull(engine.lpRoundingProbe(objective, ExecutionControl { true }))
            assertEquals(paid, engine.primalPropagationWork)

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertTrue(expired)
            assertEquals(List(n) { 0L }, assertNotNull(sample).ints.toList())
            assertEquals(prefix.work, engine.primalPropagationWork)
            assertTrue(engine.primalPropagationWork > paid)
            assertNull(engine.rootRoundingWarmStart)
        }
    }

    @Test
    fun `changed rounding guidance or objective discards earlier completed pins`() {
        for (changedObjective in listOf(false, true)) {
            val n = 4
            val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
                Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
            ))
            val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
            val slice = ExecutionControl { (guidance.pinning?.decisionLevel ?: 0) > 0 }
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("changed-rounding-pins")).use { engine ->
                engine.rootRoundingWarmStart = guidance
                assertNull(engine.lpRoundingProbe(objective, slice))
                assertEquals(IntDomain(0, 0), assertNotNull(guidance.pinning).intDomain(0))
                val next = if (changedObjective) LinearObjective(intCoefficients = LongArray(n) { 2L }) else objective
                val layout = CpToLpRelaxation(problem, next).build(PropagationSession(problem))
                engine.rootRoundingWarmStart = RootRoundingWarmStart(next, layout,
                    primal = DoubleArray(layout.model.n) { 1.0 })

                val sample = engine.lpRoundingProbe(next, ExecutionControl.Never)

                assertEquals(List(n) { 1L }, assertNotNull(sample).ints.toList())
            }
        }
    }

    @Test
    fun `rounding rebuilds initial propagation interrupted before a complete root`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("interrupted-rounding-root")).use { engine ->
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, ExecutionControl { true }))
            assertNull(guidance.pinning)

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `masked rounding guidance respects source domains and unknown columns`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val known = BooleanArray(relaxation.model.n)
        known[relaxation.intColOf[0]] = true

        for ((target, expected) in listOf(-1.0 to listOf(0L, 1L), 1.0 to listOf(1L, 0L))) {
            val guide = DoubleArray(relaxation.model.n) { 1.0 }
            guide[relaxation.intColOf[0]] = target
            val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("masked-rounding-guidance")).use { engine ->
                engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                    primal = guide, knownColumns = known)

                engine.lpRoundingProbe(objective, ExecutionControl.Never)
            }

            assertEquals(expected, assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `unknown binary rounding columns try the other value after a conflicting zero pin`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 1))
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        val known = BooleanArray(relaxation.model.n)
        known[relaxation.intColOf[2]] = true
        val guide = DoubleArray(relaxation.model.n) { 1.0 }
        guide[relaxation.intColOf[2]] = 0.0
        assertEquals(IntDomain(0, 1), session.intDomain(0))

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("unknown-binary-rounding")).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = guide, knownColumns = known)
            engine.lpRoundingProbe(objective, ExecutionControl.Never)
        }

        assertEquals(listOf(1L, 0L, 0L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `an optimal rounding point survives interruption while its solver closes`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var stopped = false
        var solves = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel, cancellation: ExecutionControl, iterationLimit: Int, workLimit: Long,
                trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): TableauCutSolver {
                solves++
                val delegate = ProductionLpEngineFactory.newTableauSolver(
                    model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : TableauCutSolver by delegate {
                    override fun close() {
                        delegate.close()
                        stopped = true
                    }
                }
            }
        }
        val token = ExecutionControl { stopped }
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("closed-rounding-point"), LpSolveContext(factory),
            nodeWorkAllowance = { if (stopped) 0L else 100_000L }).use { engine ->
            assertNull(engine.lpRoundingProbe(objective, token))
            assertTrue(engine.rootRoundingInterrupted)
            stopped = false

            val sample = engine.lpRoundingProbe(objective, token)

            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(1, solves)
        }
    }

    @Test
    fun `completed rounding LP resumes without solving its original objective again`() {
        for (sameObjective in listOf(true, false)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            val factory = RecordingLpEngineFactory()
            var stopped = false
            val token = ExecutionControl { stopped }

            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("resumed-rounding-point"), LpSolveContext(factory)).use { engine ->
                val first = engine.lpRoundingProbe(objective, token, accept = {
                    stopped = true
                    null
                })
                assertNull(first)
                assertTrue(engine.rootRoundingInterrupted)
                val constructed = factory.calls.size
                stopped = false
                val next = if (sameObjective) objective else LinearObjective(intCoefficients = longArrayOf(2, 1))

                val sample = engine.lpRoundingProbe(next, token)

                val expected = if (sameObjective) listOf(1L, 0L) else listOf(0L, 1L)
                assertEquals(expected, assertNotNull(sample).ints.toList())
                if (sameObjective) assertEquals(constructed, factory.calls.size)
                else assertTrue(factory.calls.size > constructed)
            }
        }
    }

    @Test
    fun `completed root guidance avoids another rounding LP solve`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("root-guidance"), LpSolveContext(factory)).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val constructed = factory.calls.size

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(constructed, factory.calls.size)
        }
    }

    @Test
    fun `rounding reads returned child coordinates after the query invocation expires`() {
        for ((clearView, cancelChild) in listOf(
            false to false, false to true, true to false, true to true,
        )) {
            val problem = Problem(0, 7, Array(7) { IntDomain(0, 1) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(3, 4), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(5, 6), LinearOp.GE, 1),
            )).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 2, 1, 2))
            var expired = false
            var childCalls = 0
            val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                            if (cancelChild && childCalls == 2) expired = true
                            return delegate.resolveBounds(LpFloatAllowance(allowance?.work ?: 0L, 1)).also {
                                if (++childCalls == 3) expired = true
                            }
                        }
                    }
                }
            })
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("deferred-child-guidance"),
                LpSolveContext(factory)).use { engine ->
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                val token = ExecutionControl { expired }
                val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), token)
                val queries = factory.floatAllowances.size
                val constructions = factory.calls.size
                assertTrue(expired)
                assertTrue(bound.isNaN())
                assertTrue(queries > 0)

                if (clearView) engine.cpAdapter.retract(0)

                val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

                assertEquals(queries, factory.floatAllowances.size)
                assertEquals(constructions, factory.calls.size)
                assertTrue(sample.ints.all { it in 0L..1L } &&
                    listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4, 5 to 6).all { (left, right) ->
                        sample.ints[left] + sample.ints[right] >= 1L
                    })
            }
        }
    }

    @Test
    fun `rounding reuses prepared components after a cancelled root query`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 1, 2))
        val factory = RecordingLpEngineFactory()

        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("prepared-component-rounding"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), ExecutionControl { true })
            val constructed = factory.calls.size

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertEquals(listOf(1L, 0L, 1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(constructed, factory.calls.size)
        }
    }

    @Test
    fun `prepared component rounding respects a changed objective`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))

        val sample = LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("changed-prepared-rounding")).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))

            engine.lpRoundingProbe(LinearObjective(intCoefficients = longArrayOf(2, 1)), ExecutionControl.Never)
        }

        assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `resumed rounding owns its guidance independently of the live solver`() {
        for (stopDuringPinning in listOf(false, true)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var ownerPoint: DoubleArray? = null
            var stopped = false
            var activeEngine: LpEngine? = null
            val token = ExecutionControl {
                if (stopDuringPinning && (activeEngine?.rootRoundingWarmStart?.pinning?.decisionLevel ?: 0) > 0) {
                    stopped = true
                }
                stopped
            }
            val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                            delegate.resolveBounds(allowance)?.also { ownerPoint = it.primal }
                    }
                }
            })
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("owned-rounding-guidance"),
                LpSolveContext(factory)).use { engine ->
                activeEngine = engine
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                assertNull(engine.lpRoundingProbe(objective, token, accept = {
                    stopped = true
                    null
                }))
                val prefix = engine.rootRoundingWarmStart?.pinning
                if (stopDuringPinning) {
                    assertTrue(assertNotNull(prefix).decisionLevel > 0)
                }
                val queries = factory.floatAllowances.size
                val constructions = factory.calls.size
                assertNotNull(ownerPoint).fill(1.0)

                val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

                assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
                assertEquals(queries, factory.floatAllowances.size)
                assertEquals(constructions, factory.calls.size)
                if (stopDuringPinning) assertEquals(assertNotNull(prefix).work, engine.primalPropagationWork)
            }
        }
    }

    @Test
    fun `retained root guidance still requires consumer acceptance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var offered = 0

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("declined-root-guidance")).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            engine.lpRoundingProbe(objective, ExecutionControl.Never, accept = {
                offered++
                null
            })
        }

        assertEquals(1, offered)
        assertNull(sample)
    }

    @Test
    fun `changed objective replaces completed root guidance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("changed-root-guidance")).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            engine.lpRoundingProbe(LinearObjective(intCoefficients = longArrayOf(2, 1)), ExecutionControl.Never)
        }

        assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `refused root certificates grant no bound authority to rounding guidance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("uncertified-root-guidance"), context).use { engine ->
            val lower = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            val sample = engine.lpRoundingProbe(objective, ExecutionControl.Never)

            assertTrue(lower.isNaN())
            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `LP pinning should withhold a proposal when one cumulative fire is interrupted`() {
        val n = 32
        val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
            Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
        var armed = false
        var polls = 0
        val session = PropagationSession(problem, ExecutionControl { armed && ++polls >= 4 })
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        armed = true

        val proposal = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "interrupted-cumulative-pinning")).use { engine ->
            engine.pinToward(session, relaxation) { 0.0 }
        }

        assertNull(proposal)
        assertTrue(session.probeCancelled)
        assertEquals(4, polls)
        assertEquals(IntDomain(0, 101), session.intDomain(1))
    }

    @Test
    fun `LP pinning should stop before another mutation after an interrupted equality`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 255) }, emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        var armed = false
        lateinit var session: PropagationSession
        session = PropagationSession(problem, ExecutionControl {
            armed && session.litTruth(session.boundGeLit(0, 16, true)) == true
        })
        repeat(128) { session.boundGeLit(0, it.toLong() + 1, true) }
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        armed = true

        val proposal = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "interrupted-pinning")).use { engine ->
            engine.pinToward(session, relaxation) { 129.0 }
        }

        assertNull(proposal)
        assertTrue(session.probeCancelled)
        assertEquals(IntDomain(0, 255), session.intDomain(1))
    }

    @Test
    fun `interrupted rounding should resume only its original LP objective`() {
        for (sameObjective in listOf(true, false)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var stopped = false
            var solves = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val first = solves++ == 0
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                    )
                    return object : TableauCutSolver by delegate {
                        override val lastTermination get() =
                            if (first) LpFloatTermination.CANCELLED else delegate.lastTermination
                        override fun solve(warm: Basis?): FloatLpResult? {
                            if (!first) return if (warm == null) null else delegate.solve(warm)
                            val result = delegate.solve(warm) ?: return null
                            stopped = true
                            return FloatLpResult(result.basis, result.objective, result.duals, result.primal,
                                optimal = false)
                        }
                    }
                }
            }
            val sink = SolveStatsSink(backend = "resumed-rounding")
            val token = ExecutionControl { stopped }

            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
                LpSolveContext(factory)).use { engine ->
                assertNull(engine.lpRoundingProbe(objective, token))
                stopped = false
                val next = if (sameObjective) objective else LinearObjective(intCoefficients = longArrayOf(2, 1))

                val proposal = engine.lpRoundingProbe(next, token)

                if (sameObjective) {
                    assertEquals(listOf(1L, 0L), assertNotNull(proposal).ints.toList())
                } else {
                    assertNull(proposal)
                }
            }
        }
    }
}
