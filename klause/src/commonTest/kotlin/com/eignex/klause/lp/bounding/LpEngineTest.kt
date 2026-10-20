package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpEngineTest {
    @Test
    fun `a compacted source conflict should exclude weaker guards and preserve a satisfying sibling`() {
        val problem = Problem(3, 0, emptyArray(), Array(1002) { row ->
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                if (row == 1001) LinearOp.GE else LinearOp.LE,
                when (row) {
                    0 -> BigFraction.ZERO
                    1001 -> BigFraction.ONE
                    else -> BigFraction.ofLong(10)
                }, activator = when (row) {
                    0 -> 0
                    1001 -> 2
                    else -> 1
                })
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val sink = SolveStatsSink(backend = "tight-pinned-source-parents")
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, learn = true)), sink)
        val rejected = PropagationSession(problem)
        for (variable in 0 until 3) assertIs<PropagationResult.Implied>(rejected.pinBool(variable, true))

        engine.use {
            assertEquals(LpVerdict.INFEASIBLE, it.leafCertify(rejected).verdict)
            val clause = assertNotNull(it.lpNogoods).drain().single().literals

            assertEquals(setOf(Lit.make(0, false), Lit.make(2, false)), clause.toSet())
            val sibling = PropagationSession(problem)
            assertIs<PropagationResult.Implied>(sibling.addLearnedClause(Clause(clause), lbd = 2, permanent = true))
            assertIs<PropagationResult.Implied>(sibling.pinBool(1, true))
            assertIs<PropagationResult.Implied>(sibling.pinBool(2, true))
            assertEquals(false, sibling.boolValue(0))
            val leaf = it.leafCertify(sibling)
            assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
            val point = Sample(booleanArrayOf(false, true, true), longArrayOf(), leaf.reals,
                exactReals = assertNotNull(leaf.exactReals))
            assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
        }
    }

    @Test
    fun `a compacted conflict above the total prefix limit should preserve a feasible sibling`() {
        val problem = Problem(5000, 0, emptyArray(), Array(1002) { row ->
            if (row == 0) ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ZERO, activator = 0)
            else ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ONE)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, learn = true)),
            SolveStatsSink(backend = "compacted-source-clause"))
        val rejected = PropagationSession(problem)
        for (variable in 0 until problem.numBoolVars) {
            assertIs<PropagationResult.Implied>(rejected.pinBool(variable, true))
        }

        engine.use {
            assertEquals(LpVerdict.INFEASIBLE, it.leafCertify(rejected).verdict)
            val clause = assertNotNull(it.lpNogoods).drain().single().literals

            assertEquals(setOf(Lit.make(0, false)), clause.toSet())
            val sibling = PropagationSession(problem)
            assertIs<PropagationResult.Implied>(sibling.addLearnedClause(Clause(clause), lbd = 1, permanent = true))
            for (variable in 1 until problem.numBoolVars) {
                assertIs<PropagationResult.Implied>(sibling.pinBool(variable, true))
            }
            assertEquals(false, sibling.boolValue(0))
            val leaf = it.leafCertify(sibling)
            assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
            val point = Sample(BooleanArray(problem.numBoolVars) { variable -> variable != 0 },
                longArrayOf(), leaf.reals,
                exactReals = assertNotNull(leaf.exactReals))
            assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
        }
    }

    @Test
    fun `fixed fractional real leaf should retain its exact contradiction`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(0.1),
                    LinearOp.EQ,
                    0.010000000000000002,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.1),
            realUpper = doubleArrayOf(0.1),
        )
        val sink = SolveStatsSink(backend = "fractional-leaf")
        val session = PropagationSession(problem)

        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            sink,
        ).use { engine ->
            val relaxation = assertNotNull(engine.nodeRelaxation(assertNotNull(engine.lpRelaxer), session))
            assertNotNull(relaxation.model.exactState)
            val result = engine.leafCertify(session)

            assertEquals(LpVerdict.INFEASIBLE, result.verdict, "LP evidence: ${sink.snapshot().lp}")
        }
    }

    @Test
    fun `a small pinned real residual should complete above the root factor estimate`() {
        val problem = Problem(1, 0, emptyArray(), Array(1001) { row ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(row + 1L), activator = 0)
        }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        val sink = SolveStatsSink(backend = "pinned-real-leaf")
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)), sink)
        assertTrue(engine.residualOversized)

        val leaf = engine.use { it.leafCertify(session) }

        assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
        val point = Sample(booleanArrayOf(false), longArrayOf(), leaf.reals,
            exactReals = assertNotNull(leaf.exactReals))
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
        assertEquals(1L, sink.snapshot().lp.phases["PINNED_REAL_LEAF_ADMISSION"]?.outcomes?.get("READY"))
    }

    @Test
    fun `an active real residual above the row cap should not open a native solver`() {
        val problem = Problem(0, 0, emptyArray(), Array(1001) { row ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ofLong(row + 1L)),
                LinearOp.LE, BigFraction.ofLong(row + 1L))
        }, numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink(backend = "pinned-real-row-cap")
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)), sink,
            LpSolveContext(engineFactory = factory))

        val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

        assertEquals(LpVerdict.INDETERMINATE, leaf.verdict)
        assertTrue(factory.calls.isEmpty())
        assertEquals(1L, sink.snapshot().lp.phases["PINNED_REAL_LEAF_ADMISSION"]?.outcomes?.get("ROW_LIMIT"))
    }

    @Test
    fun `parallel real rows should compact before the native row cap`() {
        val problem = Problem(0, 0, emptyArray(), Array(1001) { row ->
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ofLong(row + 1L))
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "compacted-real-leaf"), LpSolveContext(engineFactory = factory))

        val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
        assertTrue(factory.calls.isNotEmpty())
        val point = Sample(booleanArrayOf(), longArrayOf(), leaf.reals,
            exactReals = assertNotNull(leaf.exactReals))
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
    }

    @Test
    fun `compacted strict intervals should retain original source feasibility`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val problem = Problem(0, 0, emptyArray(), Array(1002) { row ->
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                if (row % 2 == 0) LinearOp.GE else LinearOp.LE,
                if (row % 2 == 0) third else third + third, strict = true)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "compacted-strict-real-leaf"))

        val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
        val point = Sample(booleanArrayOf(), longArrayOf(), leaf.reals,
            exactReals = assertNotNull(leaf.exactReals))
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
    }

    @Test
    fun `compacted fixed coordinate rows should preserve translated source values`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(2, 2)), Array(1002) { row ->
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0), Term.ofRealVar(1), Term.ofRealVar(2)),
                List(4) { BigFraction.ONE }, if (row % 2 == 0) LinearOp.GE else LinearOp.LE,
                BigFraction.ofLong(if (row % 2 == 0) 3L else 4L))
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "compacted-fixed-real-leaf"))

        val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
        val point = Sample(booleanArrayOf(), longArrayOf(2), leaf.reals,
            exactReals = assertNotNull(leaf.exactReals))
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
    }

    @Test
    fun `compacted conditional conflicts should leave feasible siblings available`() {
        val problem = Problem(1, 0, emptyArray(), Array(1002) { row ->
            if (row == 0) ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.LE, BigFraction.ZERO, activator = 0)
            else ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ONE)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "compacted-conditional-real-leaf"))

        engine.use {
            for (truth in listOf(true, false, true)) {
                val session = PropagationSession(problem)
                assertIs<PropagationResult.Implied>(session.pinBool(0, truth))

                val leaf = it.leafCertify(session)

                if (truth) assertEquals(LpVerdict.INFEASIBLE, leaf.verdict)
                else {
                    assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
                    val point = Sample(booleanArrayOf(false), longArrayOf(), leaf.reals,
                        exactReals = assertNotNull(leaf.exactReals))
                    assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
                }
            }
        }
    }

    @Test
    fun `unpinned discrete coordinates should withhold oversized-root real completion`() {
        for (openInteger in listOf(false, true)) {
            val problem = Problem(if (openInteger) 1 else 2, if (openInteger) 1 else 0,
                if (openInteger) arrayOf(IntDomain(0, 1)) else emptyArray(), Array(1001) { row ->
                    ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                        LinearOp.EQ, BigFraction.ofLong(row + 1L), activator = 0)
                }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val session = PropagationSession(problem)
            assertIs<PropagationResult.Implied>(session.pinBool(0, false))
            val factory = RecordingLpEngineFactory()
            val sink = SolveStatsSink(backend = "unpinned-real-leaf")
            val engine = LpEngine(problem, LinearObjective(),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)), sink,
                LpSolveContext(engineFactory = factory))

            val leaf = engine.use { it.leafCertify(session) }

            assertEquals(LpVerdict.INDETERMINATE, leaf.verdict)
            assertTrue(factory.calls.isEmpty())
            assertEquals(1L, sink.snapshot().lp.phases["PINNED_REAL_LEAF_ADMISSION"]?.outcomes?.get("UNPINNED"))
        }
    }

    @Test
    fun `stopped pinned assembly should retain its construction charge without a native solve`() {
        val problem = Problem(1, 0, emptyArray(), Array(1001) { row ->
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(row + 1L), activator = 0)
        }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        var stopped = false
        var armed = false
        var work = 0L
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink(backend = "stopped-pinned-real-leaf")
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true),
                cancellation = Cancellation { stopped }), sink, LpSolveContext(engineFactory = factory),
            containingWork = { units ->
                work += units
                if (armed) stopped = true
            })
        val before = work
        armed = true

        val leaf = engine.use { it.leafCertify(session) }

        assertEquals(LpVerdict.INDETERMINATE, leaf.verdict)
        assertTrue(work > before)
        assertTrue(factory.calls.isEmpty())
        assertEquals(1L, sink.snapshot().lp.phases["PINNED_REAL_LEAF_ADMISSION"]?.outcomes?.get("CANCELLED"))
    }

    @Test
    fun `pinned real leaf assembly should follow each table selection across siblings`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), Array<Factor>(1002) { row ->
            if (row == 1001) RealElement(0, 1, doubleArrayOf(2.0, 3.0), indexOffset = 0)
            else ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(row + 1L), activator = 0)
        }, numRealVars = 2, realLower = doubleArrayOf(0.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(0.0, Double.POSITIVE_INFINITY))
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "pinned-real-siblings"))

        engine.use {
            for (index in listOf(0L, 1L, 0L)) {
                val session = PropagationSession(problem)
                assertIs<PropagationResult.Implied>(session.pinBool(0, false))
                assertIs<PropagationResult.Implied>(session.pinInt(0, index))

                val leaf = it.leafCertify(session)

                assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
                val point = Sample(booleanArrayOf(false), longArrayOf(index), leaf.reals,
                    exactReals = assertNotNull(leaf.exactReals))
                assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
                assertEquals(BigFraction.ofLong(index + 2L), point.exactReals?.get(1))
            }
        }
    }

    @Test
    fun `a refuted pinned real leaf should leave a feasible sibling available`() {
        val problem = Problem(2, 0, emptyArray(), Array<Factor>(1003) { row ->
            when (row) {
                1001 -> ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.LE, BigFraction.ZERO)
                1002 -> ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.GE, BigFraction.ONE, activator = 1)
                else -> ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.EQ, BigFraction.ofLong(row + 1L), activator = 0)
            }
        }, numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val engine = LpEngine(problem, LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true)),
            SolveStatsSink(backend = "pinned-real-refuted-sibling"))

        engine.use {
            for (truth in listOf(true, false)) {
                val session = PropagationSession(problem)
                assertIs<PropagationResult.Implied>(session.pinBool(0, false))
                assertIs<PropagationResult.Implied>(session.pinBool(1, truth))

                val leaf = it.leafCertify(session)

                if (truth) assertEquals(LpVerdict.INFEASIBLE, leaf.verdict)
                else {
                    assertTrue(leaf.verdict == LpVerdict.FEASIBLE || leaf.verdict == LpVerdict.ATTAINED_OPTIMUM)
                    val point = Sample(booleanArrayOf(false, false), longArrayOf(), leaf.reals,
                        exactReals = assertNotNull(leaf.exactReals))
                    assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(problem, point))
                }
            }
        }
    }

    private val decline = LpCertificationPolicy { _, _ -> false }

    private fun boundedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 1) },
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    private fun metricSolver(workOps: Long = 0L): LpSolver = object : LpSolver {
        override val infeasibleRay: DoubleArray? = null
        override val lastWorkOps: Long = workOps
        override fun solve(warm: Basis?) = null
        override fun solvePrimal(warm: Basis?) = null
    }

    private fun budgetedEngine(sink: SolveStatsSink, plan: LpPlan = LpPlan(bounding = true)): LpEngine {
        val problem = boundedProblem()
        return LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = plan, solveBudgetMillis = 4L),
            sink,
        )
    }

    @Test
    fun `root work spending the LP allowance should be reported before node work`() {
        val sink = SolveStatsSink(backend = "root-allowance")

        budgetedEngine(sink).use { it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 20_000L)) }

        assertTrue(sink.lp.snapshot().workAllowanceSpent)
    }

    @Test
    fun `node solves should be capped at the remaining LP allowance`() {
        val engine = budgetedEngine(SolveStatsSink(backend = "node-cap"))

        val budget = engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            it.nodeWorkBudget()
        }

        assertEquals(15_000L, budget)
    }

    @Test
    fun `root primal queries should use the smaller remaining root and LP allowance`() {
        for (
        (rootLimit, spent, expected) in listOf(
            Triple(12_000L, 5_000L, 7_000L),
            Triple(50_000L, 5_000L, 15_000L),
            Triple(0L, 5_000L, 15_000L),
            Triple(12_000L, 20_000L, 1L),
        )
        ) {
            val plan = LpPlan(bounding = true, rootMaxWork = rootLimit)
            budgetedEngine(SolveStatsSink(backend = "primal-root-cap"), plan).use { engine ->
                engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = spent))

                assertEquals(expected, engine.rootPrimalWorkBudget())
            }
        }
    }

    @Test
    fun `private root probes should charge assembly and solves once to the containing engine`() {
        val sink = SolveStatsSink(backend = "private-root")
        budgetedEngine(sink, LpPlan(bounding = true, rootMaxWork = 12_000L)).use { parent ->
            parent.forObjective(LinearObjective(), Cancellation.Never, rootProbe = true).use { child ->
                child.noteNodeOverhead(3_000L)
                child.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 2_000L))

                assertEquals(5_000L, parent.totalSolveWork())
                assertEquals(5_000L, child.totalSolveWork())
                assertEquals(7_000L, child.nodeQueryWorkBudget())
                assertEquals(2_000.0, sink.snapshot().lp.rootWorkOps.sum)
                assertEquals(3_000.0, sink.snapshot().lp.overheadOps.sum)
            }
        }
    }

    @Test
    fun `standalone completion should reduce remaining root work without duplicate accounting`() {
        budgetedEngine(
            SolveStatsSink(backend = "completion-root"),
            LpPlan(bounding = true, rootMaxWork = 12_000L),
        ).use { engine ->
            engine.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))

            assertEquals(4_000L, engine.rootPrimalWorkBudget(additionalWork = 3_000L))
            assertEquals(5_000L, engine.totalSolveWork())
            assertFalse(engine.workSpentExceeds(12_000L, additionalWork = 6_999L))
            assertTrue(engine.workSpentExceeds(12_000L, additionalWork = 7_000L))
            assertFalse(engine.lpWorkAllowanceSpent(additionalWork = 14_999L))
            assertTrue(engine.lpWorkAllowanceSpent(additionalWork = 15_000L))
        }
    }

    @Test
    fun `root primal work should be independent of the adaptive node allowance`() {
        val problem = boundedProblem()
        val plan = LpPlan(bounding = true, rootMaxWork = 50_000_000L)
        val engine = LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = plan),
            SolveStatsSink(backend = "primal-root-allowance"),
        )

        engine.use {
            assertEquals(50_000_000L, it.rootPrimalWorkBudget())
            assertTrue(it.nodeWorkBudget() < it.rootPrimalWorkBudget())
        }
    }

    @Test
    fun `node queries should use the smaller containing and offered native allowances`() {
        val problem = boundedProblem()
        var allowance: Long? = null
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true), solveBudgetMillis = 4L),
            SolveStatsSink(backend = "slice-cap"),
            nodeWorkAllowance = { allowance },
        )

        engine.use {
            it.observeRootSolve(metricSolver(), LpSolveMetrics(workOps = 5_000L))
            for ((offered, expected) in listOf(null to null, 30_000L to 15_000L, 500L to 500L, 0L to 1L)) {
                allowance = offered
                assertEquals(15_000L, it.nodeWorkBudget())
                assertEquals(expected, it.nodeQueryWorkBudget())
            }
        }
    }

    @Test
    fun `optional node queries should receive the offered cap after retained construction`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "native-slice-cap"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 500L },
        )

        val pruned = engine.use { it.pruneNode(PropagationSession(problem), 1.5, -1, true) }

        assertFalse(pruned)
        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 500L)
        assertEquals(500L, assertNotNull(factory.floatAllowances.single()).work)
    }

    @Test
    fun `sliced LP queries should receive a native slice cap only behind a finite external cutoff`() {
        for (cutoff in listOf(Double.POSITIVE_INFINITY, 100.0)) {
            val problem = boundedProblem()
            val factory = RecordingLpEngineFactory()
            val params = BacktrackParams(
                lpPlan = LpPlan(bounding = true, rootCutHarvest = false),
                objectiveBoundSupplier = { cutoff },
            )
            val solver = BacktrackSolver(problem.bake(), LpSolveContext(factory, decline))

            solver.resumable(LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L)), params).use { search ->
                while (factory.floatAllowances.isEmpty() && !search.isDone) {
                    search.runSlice(Cancellation.Never, 1_000L, 64L) {}
                }
            }

            assertTrue(factory.floatAllowances.isNotEmpty())
            assertEquals(cutoff.isFinite(), factory.floatAllowances.first() != null)
        }
    }

    @Test
    fun `node construction and certification should stay isolated per engine`() {
        val problem = boundedProblem()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val params = LpParams(
            lpPlan = LpPlan(bounding = true),
            randomSeed = 31L,
        )
        val rejectingFactory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(rejectingFactory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            params,
            SolveStatsSink(backend = "production"),
        )

        val rejected = rejecting.pruneNode(PropagationSession(problem), 1.5, -1, true)
        val accepted = production.pruneNode(PropagationSession(problem), 1.5, -1, true)

        assertFalse(rejected)
        assertTrue(accepted)
        assertEquals(1, rejectingFactory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        val construction = rejectingFactory.calls.single { it.kind == EngineConstruction.PERSISTENT }
        assertEquals(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, construction.zeroObjectivePricing)
        assertEquals(31L, construction.tieSeed)
    }

    @Test
    fun `root bound should use the injected tableau factory and policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(5, 9)),
            factors = emptyArray(),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 37L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "root"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        val bound = engine.rootLpRelaxationBound(checkNotNull(engine.lpRelaxer), emptyList())

        assertTrue(bound.isNaN())
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(37L, construction.tieSeed)
        assertTrue(construction.workLimit > 1L)
    }

    @Test
    fun `root infeasibility should not bypass the injected policy`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0L))
        val factory = RecordingLpEngineFactory()
        val rejecting = LpEngine(
            problem,
            objective,
            LpParams(
                lpPlan = LpPlan(bounding = true),
                randomSeed = 41L,
                zeroObjectivePricing = LpZeroObjectivePricing.LARGEST_PIVOT,
            ),
            SolveStatsSink(backend = "rejecting"),
            LpSolveContext(factory, decline),
        )
        val production = LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "production"),
        )

        assertFalse(rejecting.rootLpInfeasibleNoBake(Cancellation.Never))
        assertTrue(production.rootLpInfeasibleNoBake(Cancellation.Never))
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.TABLEAU })
        val construction = factory.calls.single { it.kind == EngineConstruction.TABLEAU }
        assertEquals(LpZeroObjectivePricing.LARGEST_PIVOT, construction.zeroObjectivePricing)
        assertEquals(41L, construction.tieSeed)
    }

    @Test
    fun `leaf certification should report unbounded only with a ray the factors keep`() {
        // r >= 0 with no row above it: minimizing -r descends without limit, minimizing nothing does not.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val cases = listOf(doubleArrayOf(-1.0) to LpVerdict.UNBOUNDED, doubleArrayOf(0.0) to LpVerdict.ATTAINED_OPTIMUM)
        for ((costs, verdict) in cases) {
            val engine = LpEngine(
                problem,
                LinearObjective(realCoefficients = costs),
                LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
                SolveStatsSink(backend = "leaf-unbounded"),
            )

            val leaf = engine.use { it.leafCertify(PropagationSession(problem)) }

            assertEquals(verdict, leaf.verdict)
            assertEquals(verdict == LpVerdict.UNBOUNDED, leaf.direction != null)
        }
    }

    @Test
    fun `leaf certification should report an accepted float optimum as a tolerance optimum`() {
        // 3x = 1 with x in [0, 2], minimizing x; every exact certifier is vetoed, so only float evidence can decide.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val engine = LpEngine(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-tolerance"),
            LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }),
        )

        val leaf = engine.use { it.leafCertify(PropagationSession(problem), toleranceCheck = { true }) }

        assertEquals(LpVerdict.TOLERANCE_OPTIMUM, leaf.verdict)
        assertEquals(1.0 / 3.0, leaf.reals.single(), 1e-12)
    }

    @Test
    fun `leaf certification should cap its solve by work`() {
        val problem = boundedProblem()
        val factory = RecordingLpEngineFactory()
        val engine = LpEngine(
            problem,
            LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
            LpParams(lpPlan = LpPlan(bounding = true, realResidual = true, componentSplit = false)),
            SolveStatsSink(backend = "leaf-work"),
            LpSolveContext(factory, decline),
            nodeWorkAllowance = { 1L },
        )

        engine.use { it.leafCertify(PropagationSession(problem)) }

        assertTrue(factory.calls.single { it.kind == EngineConstruction.PERSISTENT }.workLimit > 1L)
    }

    @Test
    fun `open bound probes should use one injected retained owner and preserve proof policy`() {
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        val bounds = arrayOf(OpenIntBounds(null, null), OpenIntBounds(null, null))
        val factory = RecordingLpEngineFactory()

        val rejected = tightenOpenIntBounds(
            bounds,
            rows,
            context = LpSolveContext(factory, decline),
        )
        val accepted = tightenOpenIntBounds(bounds, rows)

        assertEquals(null, rejected.bounds[0].hi)
        assertEquals(5L, accepted.bounds[0].hi)
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
    }
}
