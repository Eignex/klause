package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpBoundingContinuousTest {
    @Test
    fun `continuous node pruning should respect independently accepted objective certifiers`() {
        for (safe in listOf(false, true)) {
            for (integer in listOf(false, true)) {
                for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
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
                        intCoefficients = longArrayOf(sign),
                        constant = 3L * sign,
                        realCoefficients = doubleArrayOf(0.5 * sign),
                    )
                    val context = LpSolveContext(
                        certificationPolicy = LpCertificationPolicy { certifier, successful ->
                            successful && when (certifier) {
                                LpCertifier.SAFE_OBJECTIVE -> safe
                                LpCertifier.INTEGER -> integer
                                else -> false
                            }
                        },
                    )
                    val sink = SolveStatsSink(backend = "continuous-policy")
                    val session = PropagationSession(problem)
                    LpEngine(
                        problem,
                        objective,
                        LpParams(lpPlan = LpPlan(bounding = true)),
                        sink,
                        context,
                    ).use { engine ->
                        val result = engine.sparseSafePrune(
                            assertNotNull(engine.lpRelaxer),
                            session,
                            minimum - 0.125,
                            sink,
                            ExecutionControl.Never,
                            -1,
                            true,
                        )

                        assertEquals(safe || integer, result.prune, "safe=$safe integer=$integer sign=$sign")
                    }
                }
            }
        }
    }

    @Test
    fun `continuous recovery pruning and root publication should retain only accepted safe bounds`() {
        for (safe in listOf(false, true)) {
            for (integer in listOf(false, true)) {
                for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
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
                        intCoefficients = longArrayOf(sign),
                        constant = 3L * sign,
                        realCoefficients = doubleArrayOf(0.5 * sign),
                    )
                    val context = LpSolveContext(
                        certificationPolicy = LpCertificationPolicy { certifier, successful ->
                            successful && when (certifier) {
                                LpCertifier.SAFE_OBJECTIVE -> safe
                                LpCertifier.INTEGER -> integer
                                else -> false
                            }
                        },
                    )
                    val sink = SolveStatsSink(backend = "continuous-recovery")
                    LpEngine(
                        problem,
                        objective,
                        LpParams(lpPlan = LpPlan(bounding = true)),
                        sink,
                        context,
                    ).use { engine ->
                        val relaxer = assertNotNull(engine.lpRelaxer)
                        val result = engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            minimum - 0.125,
                            sink,
                            ExecutionControl.Never,
                        )
                        val root = engine.rootLpRelaxationBound(relaxer, emptyList())

                        assertEquals(safe, result.prune)
                        if (safe) assertEquals(minimum, root) else assertTrue(root.isNaN())
                    }
                }
            }
        }
    }

    @Test
    fun `safe continuous bounds should conservatively enclose cutoff equality and neighbors`() {
        for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
            for (cutoff in listOf(
                minimum.nextDown(),
                minimum,
                minimum.nextUp(),
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NaN,
            )) {
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
                    intCoefficients = longArrayOf(sign),
                    constant = 3L * sign,
                    realCoefficients = doubleArrayOf(0.5 * sign),
                )
                val context = LpSolveContext(
                    certificationPolicy = LpCertificationPolicy { certifier, successful ->
                        successful && certifier == LpCertifier.SAFE_OBJECTIVE
                    },
                )
                val sink = SolveStatsSink(backend = "continuous-cutoff")
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink, context).use { engine ->
                    val relaxer = assertNotNull(engine.lpRelaxer)
                    val expected = cutoff.isFinite() && cutoff < minimum

                    assertEquals(
                        expected,
                        engine.sparseSafePrune(
                            relaxer,
                            PropagationSession(problem),
                            cutoff,
                            sink,
                            ExecutionControl.Never,
                            -1,
                            true,
                        ).prune,
                    )
                    assertEquals(
                        expected,
                        engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            cutoff,
                            sink,
                            ExecutionControl.Never,
                        ).prune,
                    )
                }
            }
        }
    }

    @Test
    fun `continuous root bounds should preserve fractional source units beside wide constants`() {
        for (constant in listOf(3L, 9007199254740995L, -9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            for (coefficient in listOf(0.125, -0.125)) {
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
                    constant = constant,
                    realCoefficients = doubleArrayOf(coefficient),
                )
                val sink = SolveStatsSink(backend = "continuous-source")
                val exact = BigFraction.ofLong(constant) + assertNotNull(BigFraction.ofDouble(coefficient)) *
                    assertNotNull(BigFraction.ofDouble(if (coefficient > 0.0) 0.25 else 1.25))
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                    val relaxer = assertNotNull(engine.lpRelaxer)
                    val root = engine.rootLpRelaxationBound(relaxer, emptyList())

                    assertTrue(assertNotNull(BigFraction.ofDouble(root)) <= exact)
                    assertTrue(assertNotNull(BigFraction.ofDouble(root.nextUp())) > exact)
                    assertFalse(
                        engine.sparseSafePrune(
                            relaxer,
                            PropagationSession(problem),
                            exact.toDouble(),
                            sink,
                            ExecutionControl.Never,
                            -1,
                            true,
                        ).prune,
                    )
                    assertFalse(
                        engine.sparseCertifiedPrune(
                            relaxer,
                            PropagationSession(problem),
                            exact.toDouble(),
                            sink,
                            ExecutionControl.Never,
                        ).prune,
                    )
                }
            }
        }
    }

    @Test
    fun `continuous bounds should retain original MPS objective constants scales and sense`() {
        for ((sense, minimum) in listOf("MIN" to 0.5, "MAX" to -1.125)) {
            val compiled = Mps.parse(
                "NAME SOURCE\nOBJSENSE\n $sense\nROWS\n N COST\nCOLUMNS\n" +
                    " M0 'MARKER' 'INTORG'\n I COST 0.125\n M1 'MARKER' 'INTEND'\n X COST 0.5\n" +
                    "RHS\n R COST -0.375\nBOUNDS\n LO B I 0\n UP B I 1\n" +
                    " LO B X 0.25\n UP B X 1.25\nENDATA",
            ).toProblem(semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))
            val sourceObjective = assertNotNull(compiled.objective).toLinearObjective()
            val objective = if (compiled.maximize) sourceObjective.negated() else sourceObjective
            val sink = SolveStatsSink(backend = "continuous-original-source")
            val context = LpSolveContext(
                certificationPolicy = LpCertificationPolicy { certifier, successful ->
                    successful && certifier == LpCertifier.SAFE_OBJECTIVE
                },
            )
            val params = LpParams(lpPlan = LpPlan(bounding = true))
            LpEngine(compiled.model, objective, params, sink, context).use { engine ->
                val relaxer = assertNotNull(engine.lpRelaxer)

                assertEquals(minimum, engine.rootLpRelaxationBound(relaxer, emptyList()))
                assertTrue(
                    engine.sparseSafePrune(
                        relaxer,
                        PropagationSession(compiled.model),
                        minimum - 0.125,
                        sink,
                        ExecutionControl.Never,
                        -1,
                        true,
                    ).prune,
                )
                assertFalse(
                    engine.sparseCertifiedPrune(
                        relaxer,
                        PropagationSession(compiled.model),
                        minimum,
                        sink,
                        ExecutionControl.Never,
                    ).prune,
                )
            }
        }
    }

    @Test
    fun `nonfinite dual candidates should not publish a continuous root bound`() {
        for (dual in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val problem = Problem(
                0,
                0,
                emptyArray(),
                arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.25),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.25),
            )
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: ExecutionControl,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model,
                        cancellation,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : TableauCutSolver by delegate {
                        override fun solve(warm: Basis?): FloatLpResult? = delegate.solve(warm)?.also {
                            it.duals.fill(dual)
                        }
                    }
                }
            }
            val sink = SolveStatsSink(backend = "continuous-nonfinite")
            LpEngine(
                problem,
                LinearObjective(realCoefficients = doubleArrayOf(0.5)),
                LpParams(lpPlan = LpPlan(bounding = true)),
                sink,
                LpSolveContext(factory),
            ).use { engine ->
                assertTrue(engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList()).isNaN())
            }
        }
    }

    @Test
    fun `cancelled continuous bounding should neither prune nor publish a root bound`() {
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
            constant = 3L,
            realCoefficients = doubleArrayOf(0.5),
        )
        val sink = SolveStatsSink(backend = "continuous-cancelled")
        val cancelled = ExecutionControl { true }
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)

            assertFalse(
                engine.sparseSafePrune(
                    relaxer,
                    PropagationSession(problem),
                    3.0,
                    sink,
                    cancelled,
                    -1,
                    true,
                ).prune,
            )
            assertTrue(engine.rootLpRelaxationBound(relaxer, emptyList(), cancelled).isNaN())
        }
    }

    @Test
    fun `finite optimization should publish a continuous source root bound in either direction`() {
        for ((sign, minimum) in listOf(1L to 3.125, -1L to -4.625)) {
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
                intCoefficients = longArrayOf(sign),
                constant = 3L * sign,
                realCoefficients = doubleArrayOf(0.5 * sign),
            )
            val published = ArrayList<Double>()

            val result = BacktrackSolver(problem.bake()).minimize(
                objective,
                BacktrackParams(
                    randomSeed = 1L,
                    lpPlan = LpPlan(bounding = true),
                    objectiveLowerBoundSink = { published.add(it) },
                ),
            )

            assertEquals(minimum, published.firstOrNull())
            assertEquals(minimum, result.objectiveValue)
        }
    }

    @Test
    fun `a continuous root solver error should close its owner and remain an error`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.25),
            realUpper = doubleArrayOf(1.25),
        )
        val failure = IllegalStateException("root solve")
        var closes = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: ExecutionControl,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver {
                val delegate = ProductionLpEngineFactory.newTableauSolver(
                    model,
                    cancellation,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : TableauCutSolver by delegate {
                    override fun solve(warm: Basis?): FloatLpResult? = throw failure
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val sink = SolveStatsSink(backend = "continuous-error")
        LpEngine(
            problem,
            LinearObjective(realCoefficients = doubleArrayOf(0.5)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            sink,
            LpSolveContext(factory),
        ).use { engine ->
            val caught = assertFailsWith<IllegalStateException> {
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            }

            assertSame(failure, caught)
            assertEquals(1, closes)
        }
    }
}
