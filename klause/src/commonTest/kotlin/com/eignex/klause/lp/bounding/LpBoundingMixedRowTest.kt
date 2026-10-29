package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.lp.LpHints
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpBoundingMixedRowTest {
    @Test
    fun `dense mixed row deductions should retract with a defining integer branch`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(-1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.EQ,
                        0.0,
                    ),
                    Linear(
                        intArrayOf(1),
                        doubleArrayOf(-1.0),
                        intArrayOf(1),
                        doubleArrayOf(1.0),
                        LinearOp.EQ,
                        0.0,
                    ),
                    Linear(
                        intArrayOf(),
                        doubleArrayOf(),
                        intArrayOf(0, 1),
                        doubleArrayOf(1.0, 1.0),
                        LinearOp.GE,
                        0.75,
                    ),
                ),
                numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
            )
            val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0, 1.0))
            val session = PropagationSession(problem)
            session.pinInt(1, 0)
            val sink = SolveStatsSink(backend = "defined-row")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)
                engine.sparseSafePrune(
                    relaxer,
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )
                assertEquals(IntDomain(1, 1), session.intDomain(0))

                session.popToLevel(0)
                val result = engine.sparseSafePrune(
                    relaxer,
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )

                assertFalse(result.prune)
                assertEquals(IntDomain(0, 1), session.intDomain(0))
            }
        }
    }

    @Test
    fun `learned mixed row implications should propagate when a supporting branch is revisited`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        1.25,
                    ),
                    Linear(
                        intArrayOf(1),
                        doubleArrayOf(-1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.LE,
                        0.25,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            )
            val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
            val session = PropagationSession(problem)
            session.pinInt(1, 0)
            val sink = SolveStatsSink(backend = "row-learning")
            val params = LpParams(lpPlan = LpPlan(bounding = true, learn = learn))
            LpEngine(problem, objective, params, sink).use { engine ->
                engine.sparseSafePrune(
                    assertNotNull(engine.lpRelaxer),
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )
                session.popToLevel(0)
                assertEquals(IntDomain(0, 1), session.intDomain(0))
                session.pinInt(1, 1)
                assertEquals(IntDomain(0, 1), session.intDomain(0))
                session.popToLevel(0)

                session.pinInt(1, 0)

                assertEquals(IntDomain(if (learn) 1L else 0L, 1L), session.intDomain(0))
            }
        }
    }

    @Test
    fun `mixed row deductions should consume newly tightened integer endpoints in one node pass`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        0.75,
                    ),
                    Linear(
                        intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0),
                        intArrayOf(1),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        -0.25,
                    ),
                ),
                numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0),
                realUpper = doubleArrayOf(0.25, 0.25),
            )
            val objective = LinearObjective(realCoefficients = doubleArrayOf(0.5, 0.5))
            val session = PropagationSession(problem)
            val sink = SolveStatsSink(backend = "row-follow-up")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val result = engine.sparseSafePrune(
                    assertNotNull(engine.lpRelaxer),
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )

                assertFalse(result.prune)
                assertEquals(IntDomain(1, 1), session.intDomain(1))
                assertEquals(1.0, sink.lp.solves.read().sum)
            }
        }
    }

    @Test
    fun `follow-up mixed row deductions should retract with their supporting branch`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(
                0,
                3,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0, 2),
                        doubleArrayOf(1.0, 1.0),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        0.75,
                    ),
                    Linear(
                        intArrayOf(1, 0),
                        doubleArrayOf(1.0, -1.0),
                        intArrayOf(1),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        -0.25,
                    ),
                ),
                numRealVars = 2,
                realLower = doubleArrayOf(0.0, 0.0),
                realUpper = doubleArrayOf(0.25, 0.25),
            )
            val objective = LinearObjective(realCoefficients = doubleArrayOf(0.5, 0.5))
            val session = PropagationSession(problem)
            session.pinInt(2, 0)
            val sink = SolveStatsSink(backend = "row-follow-up")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)
                engine.sparseSafePrune(
                    relaxer,
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )
                assertEquals(IntDomain(1, 1), session.intDomain(1))

                session.popToLevel(0)
                val result = engine.sparseSafePrune(
                    relaxer,
                    session,
                    100.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                    learn = learn,
                )

                assertFalse(result.prune)
                assertEquals(IntDomain(0, 1), session.intDomain(1))
            }
        }
    }

    @Test
    fun `mixed row feasibility should fix an integer root domain before an incumbent`() {
        for (sign in listOf(-1.0, 1.0)) {
            val problem = Problem(
                0,
                1,
                arrayOf(IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0),
                        doubleArrayOf(sign),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.GE,
                        if (sign > 0.0) 0.75 else -0.25,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.25),
            )
            val objective = LinearObjective(
                intCoefficients = longArrayOf(sign.toLong()),
                realCoefficients = doubleArrayOf(0.5),
            )
            val session = PropagationSession(problem)
            val sink = SolveStatsSink(backend = "row-feasibility")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val result = engine.sparseSafePrune(
                    assertNotNull(engine.lpRelaxer),
                    session,
                    Double.POSITIVE_INFINITY,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                )

                assertFalse(result.prune)
                val value = if (sign > 0.0) 1L else 0L
                assertEquals(IntDomain(value, value), session.intDomain(0))
            }
        }
    }

    @Test
    fun `mixed row feasibility should fix an integer domain under a supporting branch`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(
                Linear(
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.5,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.25),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.5))
        val session = PropagationSession(problem)
        session.pinInt(1, 1)
        val sink = SolveStatsSink(backend = "row-feasibility")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val result = engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                -1,
                true,
                learn = true,
            )

            assertFalse(result.prune)
            assertEquals(IntDomain(0, 0), session.intDomain(0))
        }
    }

    @Test
    fun `mixed row deductions should retract when their supporting branch is undone`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf(
                Linear(
                    intArrayOf(0, 1),
                    doubleArrayOf(1.0, 1.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.5,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(0.25),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.5))
        val session = PropagationSession(problem)
        session.pinInt(1, 1)
        val sink = SolveStatsSink(backend = "row-feasibility")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)
            engine.sparseSafePrune(
                relaxer,
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                -1,
                true,
                learn = true,
            )
            assertEquals(IntDomain(0, 0), session.intDomain(0))

            session.popToLevel(0)
            val result = engine.sparseSafePrune(
                relaxer,
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                -1,
                true,
                learn = true,
            )

            assertFalse(result.prune)
            assertEquals(IntDomain(0, 1), session.intDomain(0))
        }
    }

    @Test
    fun `integral mixed fixing should exclude equal incumbents when the objective range exceeds Long`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, Long.MAX_VALUE / 2L + 1L)),
            arrayOf(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(-1.0),
                    LinearOp.GE,
                    0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(2L))
        val sink = SolveStatsSink(backend = "integral-cutoff")
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val result = engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                4.0,
                sink,
                Cancellation.Never,
                -1,
                true,
            )

            assertFalse(result.prune)
            assertEquals(IntDomain(0, 1), session.intDomain(0))
        }
    }

    @Test
    fun `integral mixed bounds should prune equal incumbents when the objective range exceeds Long`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(1, Long.MAX_VALUE / 2L + 1L)),
            arrayOf(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(-1.0),
                    LinearOp.GE,
                    0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(2L))
        val sink = SolveStatsSink(backend = "integral-cutoff")
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val result = engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                2.0,
                sink,
                Cancellation.Never,
                -1,
                true,
            )

            assertTrue(result.prune)
        }
    }

    @Test
    fun `fixing should retain a permitted far endpoint`() {
        for (sign in listOf(-1L, 1L)) {
            for (realCost in listOf(0.0, 0.5)) {
                val maximum = if (sign > 0L) 6.0 else 0.0
                val cutoff = maximum + realCost * 0.25 + if (realCost == 0.0) 1.0 else 0.0
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 3)),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.25),
                    realUpper = doubleArrayOf(1.25),
                )
                val objective = LinearObjective(
                    intCoefficients = longArrayOf(2L * sign),
                    realCoefficients = doubleArrayOf(realCost),
                )
                val sink = SolveStatsSink(backend = "endpoint-admission")
                val session = PropagationSession(problem)
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val result = engine.sparseSafePrune(
                        assertNotNull(engine.lpRelaxer),
                        session,
                        cutoff,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    )

                    assertFalse(result.prune)
                    assertEquals(IntDomain(0, 3), session.intDomain(0))
                }
            }
        }
    }

    @Test
    fun `fixing should remove a far endpoint beyond the cutoff`() {
        for (sign in listOf(-1L, 1L)) {
            for (realCost in listOf(0.0, 0.5)) {
                val cutoff = if (sign > 0L) 6.0 else 0.0
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 3)),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.25),
                    realUpper = doubleArrayOf(1.25),
                )
                val objective = LinearObjective(
                    intCoefficients = longArrayOf(2L * sign),
                    realCoefficients = doubleArrayOf(realCost),
                )
                val sink = SolveStatsSink(backend = "endpoint-admission")
                val session = PropagationSession(problem)
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val result = engine.sparseSafePrune(
                        assertNotNull(engine.lpRelaxer),
                        session,
                        cutoff,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    )

                    assertFalse(result.prune)
                    assertEquals(if (sign > 0L) IntDomain(0, 2) else IntDomain(1, 3), session.intDomain(0))
                }
            }
        }
    }

    @Test
    fun `objective equality fixing should bound an integral coordinate inside the LP face`() {
        for (sign in listOf(-1.0, 1.0)) {
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
                arrayOf(
                    Linear(
                        intArrayOf(0, 1),
                        doubleArrayOf(-4.0 * sign, -sign),
                        intArrayOf(0),
                        doubleArrayOf(1.0),
                        LinearOp.EQ,
                        0.0,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(if (sign > 0.0) 1.0 else -100.0),
                realUpper = doubleArrayOf(if (sign > 0.0) 100.0 else -1.0),
            )
            val objective = LinearObjective(realCoefficients = doubleArrayOf(sign))
            val sink = SolveStatsSink(backend = "objective-equality-fixing")
            val session = PropagationSession(problem)
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val result = engine.sparseSafePrune(
                    assertNotNull(engine.lpRelaxer),
                    session,
                    2.0,
                    sink,
                    Cancellation.Never,
                    -1,
                    true,
                )

                assertFalse(result.prune)
                assertEquals(IntDomain(0, 0), session.intDomain(0))
                assertEquals(IntDomain(0, 1), session.intDomain(1))
            }
        }
    }

    @Test
    fun `declared continuous objective ceilings should fix integral domains before an incumbent`() {
        for (sign in listOf(-1.0, 1.0)) {
            for (coefficient in listOf(3.0, 4.0)) {
                val lower = if (sign > 0.0) 0.0 else -3.0
                val upper = if (sign > 0.0) 3.0 else 0.0
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 10)),
                    arrayOf(
                        Linear(
                            intArrayOf(0),
                            doubleArrayOf(-sign * coefficient),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.EQ,
                            0.0,
                        ),
                    ),
                    numRealVars = 1,
                    realLower = doubleArrayOf(lower),
                    realUpper = doubleArrayOf(upper),
                )
                val objective = LinearObjective(realCoefficients = doubleArrayOf(sign))
                val sink = SolveStatsSink(backend = "objective-ceiling")
                val session = PropagationSession(problem)
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val result = engine.sparseSafePrune(
                        assertNotNull(engine.lpRelaxer),
                        session,
                        Double.POSITIVE_INFINITY,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    )

                    assertFalse(result.prune)
                    assertEquals(IntDomain(0, if (coefficient == 3.0) 1 else 0), session.intDomain(0))
                }
            }
        }
    }

    @Test
    fun `an absent continuous objective ceiling should not use a probe box for fixing`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(-4.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.EQ,
                    0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val sink = SolveStatsSink(backend = "absent-ceiling")
        val session = PropagationSession(problem)
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                -1,
                true,
            )

            assertEquals(IntDomain(0, 10), session.intDomain(0))
        }
    }

    @Test
    fun `integral continuous objective definitions should prune equal incumbent nodes`() {
        for ((coefficient, minimum) in listOf(1.0 to 1.0, -1.0 to -2.0)) {
            for (cutoff in listOf(minimum - 0.5, minimum, minimum + 0.5)) {
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(1, 2)),
                    arrayOf(
                        Linear(
                            intArrayOf(0),
                            doubleArrayOf(-1.0),
                            intArrayOf(0),
                            doubleArrayOf(1.0),
                            LinearOp.EQ,
                            0.0,
                        ),
                    ),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.0),
                    realUpper = doubleArrayOf(10.0),
                )
                val objective = LinearObjective(realCoefficients = doubleArrayOf(coefficient))
                val sink = SolveStatsSink(backend = "mixed-integral-prune")
                val session = PropagationSession(problem)
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val result = engine.sparseSafePrune(
                        assertNotNull(engine.lpRelaxer),
                        session,
                        cutoff,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    )

                    assertEquals(cutoff <= minimum, result.prune)
                }
            }
        }
    }

    @Test
    fun `mixed node pruning should require an exact bound beyond the rational cutoff`() {
        for ((coefficient, minimum) in listOf(0.5 to 3.125, -0.5 to 2.375)) {
            for (cutoff in listOf(minimum - 0.125, minimum, minimum + 0.125)) {
                val problem = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 1)),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(0.25),
                    realUpper = doubleArrayOf(1.25),
                )
                val objective = LinearObjective(
                    intCoefficients = longArrayOf(1L),
                    realCoefficients = doubleArrayOf(coefficient),
                    constant = 3L,
                )
                val sink = SolveStatsSink(backend = "mixed-rational-prune")
                val session = PropagationSession(problem)
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val result = engine.sparseSafePrune(
                        assertNotNull(engine.lpRelaxer),
                        session,
                        cutoff,
                        sink,
                        Cancellation.Never,
                        -1,
                        true,
                    )

                    assertEquals(cutoff < minimum, result.prune)
                }
            }
        }
    }

    @Test
    fun `noncanonical legacy node fallback should keep source hints and close displaced owners`() {
        val model = noncanonicalModel()
        val closes = ArrayList<Int>()
        LpEngine(
            Problem(0, 0, emptyArray(), emptyArray()),
            LinearObjective(),
            LpParams(),
            SolveStatsSink("fallback"),
            LpSolveContext(closeCountingFactory(closes)),
        ).use { engine ->
            for (lower in listOf(0L, 5L, 1L, 0L)) {
                val next = model.rebind(longArrayOf(lower), longArrayOf(10L))
                assertNull(next.authoritativeModel())

                val result = assertNotNull(engine.solveNode(next, null, Cancellation.Never))

                assertEquals(maxOf(4L, lower).toDouble(), assertNotNull(result.second).objective)
                assertEquals(List(closes.size - 1) { 1 } + 0, closes)
            }
        }
        assertEquals(listOf(1, 1, 1, 1), closes)
    }

    @Test
    fun `failed legacy replacement should preserve cleanup failures and close the staged owner`() {
        val model = noncanonicalModel()
        val failures = listOf(IllegalStateException("displaced"), IllegalStateException("staged"))
        val closes = ArrayList<Int>()
        LpEngine(
            Problem(0, 0, emptyArray(), emptyArray()),
            LinearObjective(),
            LpParams(),
            SolveStatsSink("fallback"),
            LpSolveContext(closeCountingFactory(closes) { throw failures[it] }),
        ).use { engine ->
            assertNotNull(engine.solveNode(model, null, Cancellation.Never)?.second)

            val failure = assertFailsWith<IllegalStateException> {
                engine.solveNode(model.rebind(longArrayOf(5L), longArrayOf(10L)), null, Cancellation.Never)
            }

            assertSame(failures[0], failure)
            assertSame(failures[1], failure.suppressedExceptions.single())
        }
        assertEquals(listOf(1, 1), closes)
    }

    @Test
    fun `a feasible strict precheck should preserve objective variable propagation`() {
        val problem = strictMixedProblem()
        val sink = SolveStatsSink(backend = "strict-precheck")
        val session = PropagationSession(problem)
        val params = LpParams(lpPlan = LpPlan(bounding = true))
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), params, sink).use { engine ->
            val result = engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                0,
                true,
            )

            assertFalse(result.prune)
            assertEquals(2L, session.intDomain(0).min)
        }
    }

    @Test
    fun `a feasible strict precheck should preserve fractional branching hints`() {
        val problem = strictMixedProblem()
        val sink = SolveStatsSink(backend = "strict-precheck")
        val session = PropagationSession(problem)
        val hints = LpHints(1, 0)
        val params = LpParams(lpPlan = LpPlan(bounding = true))
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), params, sink).use { engine ->
            val result = engine.sparseSafePrune(
                assertNotNull(engine.lpRelaxer),
                session,
                Double.POSITIVE_INFINITY,
                sink,
                Cancellation.Never,
                0,
                true,
                hints = hints,
            )

            assertFalse(result.prune)
            assertTrue(hints.branchScore(VarRef.IntVar(0)).isFinite())
        }
    }

    @Test
    fun `cancellation during strict leaf auxiliary preparation should withhold completion and close owners`() {
        val problem = strictMixedProblem()
        val sink = SolveStatsSink(backend = "strict-precheck")
        val session = PropagationSession(problem)
        session.pinInt(0, 2L)
        var cancelled = false
        var created = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val auxiliary = ++created > 1
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        delegate.prepareLogicals(token).also { if (auxiliary) cancelled = true }

                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val params = LpParams(lpPlan = LpPlan(bounding = true), cancellation = Cancellation { cancelled })
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        LpEngine(problem, objective, params, sink, LpSolveContext(factory)).use { engine ->
            val result = engine.leafCertify(session)

            assertTrue(cancelled)
            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertEquals(2L, session.intDomain(0).min)
        }
        assertEquals(created, closed)
    }
}

private fun strictMixedProblem() = Problem(
    0,
    1,
    arrayOf(IntDomain(0, 5)),
    arrayOf(
        Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.5, strict = true),
    ),
    numRealVars = 1,
    realLower = doubleArrayOf(0.0),
    realUpper = doubleArrayOf(1.0),
)

private fun noncanonicalModel(): LpModel = LpBuilder().apply {
    addVar(0L, 10L, cost = 1L)
    addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 3L)
    addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 4L)
}.build(Sense.MINIMIZE).also {
    it.csc.rowIdx[0] = 1
    it.csc.rowIdx[1] = 0
}

private fun closeCountingFactory(closes: MutableList<Int>, afterClose: (Int) -> Unit = {}) =
    object : LpEngineFactory by ProductionLpEngineFactory {
        override fun newPersistentSolver(
            model: LpModel,
            cancellation: Cancellation,
            refactorUpdateLimit: Int,
            iterationLimit: Int,
            workLimit: Long,
            trackDegeneracy: Boolean,
            pricing: LpPricingOptions,
        ): PersistentLpSolver {
            val id = closes.size
            closes.add(0)
            val delegate = ProductionLpEngineFactory.newPersistentSolver(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                pricing,
            )
            return object : PersistentLpSolver by delegate {
                override fun close() {
                    closes[id]++
                    delegate.close()
                    afterClose(id)
                }
            }
        }
    }
