package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProblemPipelineTest {

    @Test
    fun `public route overloads should retain canonical pure real ownership`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val expected = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.MIXED,
        ))

        for (real in listOf(false, true)) {
            for (linear in listOf(false, true)) {
                val routes = listOf(
                    source.pipelineRoute(routePureRealToTheory = real, routeLinearToTheory = linear),
                    source.pipelineRoute(routePureRealToTheory = real, routeLinearToTheory = linear, onLpStats = {}),
                    source.pipelineRoute(FactorProjectionProviders.Default,
                        routePureRealToTheory = real, routeLinearToTheory = linear),
                    source.pipelineRoute(Cancellation.Never, Cancellation.Never,
                        routePureRealToTheory = real, routeLinearToTheory = linear),
                )

                for (route in routes) {
                    val selected = assertIs<SourceProblemRoute.OpenTheory>(route)
                    assertEquals(assertIs<SourceProblemRoute.OpenTheory>(expected.route).request.route,
                        selected.request.route)
                    assertSame(source, selected.preparedPlan.originalSource)
                }
            }
        }
    }

    @Test
    fun `public route flags should retain exact ownership for wide source objectives`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf((1L shl 53) + 1, 0), constant = 7)

        for (real in listOf(false, true)) {
            for (linear in listOf(false, true)) {
                val selected = assertIs<SourceProblemRoute.OpenTheory>(source.pipelineRoute(
                    objective, maximize = true, routePureRealToTheory = real, routeLinearToTheory = linear,
                ))

                assertEquals(ProblemPipeline.EXACT_LIRA, selected.request.route)
                assertSame(source, selected.preparedPlan.originalSource)
                assertSame(objective, selected.request.objective)
                assertTrue(selected.request.maximize)
            }
        }
    }

    @Test
    fun `public route flags should retain finite ownership for exact integral objectives`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-1, 1), IntDomain(-1, 1)),
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L shl 53, 0))

        val selected = assertIs<SourceProblemRoute.Finite>(source.pipelineRoute(
            objective, routePureRealToTheory = true, routeLinearToTheory = true,
        ))

        assertSame(source, selected.preparedPlan.originalSource)
        assertSame(objective, selected.preparedPlan.objective)
    }

    @Test
    fun `public route defaults should retain baked source providers`() {
        val providers = FactorProjectionProviders()
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 2))).bake(providers)

        val routes = listOf(
            source.pipelineRoute(),
            source.pipelineRoute(onLpStats = {}),
            source.pipelineRoute(Cancellation.Never, Cancellation.Never),
        )

        for (route in routes) {
            val selected = assertIs<SourceProblemRoute.Finite>(route)
            assertSame(providers, selected.preparedPlan.factorProjections?.providers)
            assertSame(source, selected.preparedPlan.originalSource)
        }
    }


    private fun withNValueOverOpenColumn(): Problem {
        val openHi = Bits(2).also { it.set(1) }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 0), null, openHi),
            factors = arrayOf(NValue(0, intArrayOf(1))),
        )
    }

    @Test
    fun `refused model should name the column and the constraint that demanded it`() {
        val plan = withNValueOverOpenColumn().componentPlan()

        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, plan.theoryPipeline)
        val unplaceable = assertNotNull(plan.unplaceable)
        assertEquals(1, unplaceable.column)
        assertEquals("NValue", unplaceable.factorKind)
    }

    @Test
    fun `routable model should name no unplaceable column`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 5), null, null),
            factors = arrayOf(AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6)),
        )

        assertNull(model.componentPlan().unplaceable)
    }

    @Test
    fun `stop after unchanged bounds should not reuse runnable preparation`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intVars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.EQ,
                    bound = 1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        for (once in listOf(false, true)) {
            for ((objective, maximize) in listOf(
                null to false,
                LinearObjective(realCoefficients = doubleArrayOf(1.0), constant = 7) to true,
            )) {
                var stopped = false
                var stopObserved = false

                val route = assertIs<SourceProblemRoute.UnsupportedOpen>(
                    model.pipelineRoute(
                        objective = objective,
                        maximize = maximize,
                        routePureRealToTheory = true,
                        boundCancellation = Cancellation {
                            val answer = stopped
                            if (answer) stopObserved = true
                            if (once) stopped = false
                            answer
                        },
                        onLpStats = { stopped = true },
                    ),
                )

                assertTrue(stopObserved)
                assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
                assertNull(route.preparedPlan.componentPlan)
                assertSame(objective, route.preparedPlan.objective)
            }
        }
    }

    @Test
    fun `optional bound work exhaustion should retain complete linear admission`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val budget = PresolveBudget(1)
        val optional = budget.orSpent(Cancellation.Never)

        val route = assertIs<SourceProblemRoute.OpenTheory>(model.pipelineRoute(
            boundCancellation = optional,
            preparationCancellation = Cancellation.Never,
        ))

        assertTrue(optional())
        assertTrue(budget.spent() >= budget.allowance)
        assertEquals(ProblemPipeline.EXACT_LIRA, route.request.route)
        assertSame(model, route.preparedPlan.originalSource)
        assertNull(route.request.model.intDomainOrNull(0))
        assertNull(route.request.model.intDomainOrNull(1))
    }

    @Test
    fun `containing cancellation after optional work should decline complete admission`() {
        val open = Bits(2).also { it.set(0); it.set(1) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val budget = PresolveBudget(1)
        val containing = Cancellation { budget.spent() > 0 }

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(model.pipelineRoute(
            boundCancellation = budget.orSpent(containing),
            preparationCancellation = containing,
        ))

        assertTrue(containing())
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertNull(route.preparedPlan.componentPlan)
    }

    @Test
    fun `optional stop should retain genuine unsupported source refusal`() {
        val model = withNValueOverOpenColumn()
        val budget = PresolveBudget(0)

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(model.pipelineRoute(
            boundCancellation = budget.orSpent(Cancellation.Never),
            preparationCancellation = Cancellation.Never,
        ))

        assertEquals(1, assertNotNull(route.unplaceable).column)
        assertEquals("NValue", route.unplaceable.factorKind)
        assertTrue(route.preparedPlan.decline != PreparedPlanDecline.RESOURCE)
        assertFalse(route.problem.intBounds.hasUpper(1))
    }

    @Test
    fun `optional stop should retain fully proved bounds for finite admission`() {
        val open = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf(Linear(longArrayOf(2), intArrayOf(0), LinearOp.LE, 10)),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val optional = budget.orSpent(Cancellation.Never)

        val route = assertIs<SourceProblemRoute.Finite>(model.pipelineRoute(
            boundCancellation = optional,
            preparationCancellation = Cancellation.Never,
            onLpStats = { budget.charge(budget.remaining()) },
        ))

        assertTrue(optional())
        assertEquals(IntDomain(0, 5), route.problem.finiteIntDomains().single())
        assertSame(model, route.preparedPlan.originalSource)
    }

    @Test
    fun `optional stop should retain proved partial bounds for complete admission`() {
        val open = Bits(3).also { it.set(1); it.set(2) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(5, 0, 0), null, open),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val optional = budget.orSpent(Cancellation.Never)

        val route = assertIs<SourceProblemRoute.OpenTheory>(model.pipelineRoute(
            boundCancellation = optional,
            preparationCancellation = Cancellation.Never,
            onLpStats = { budget.charge(budget.remaining()) },
        ))

        assertTrue(optional())
        assertEquals(5L, route.request.model.intBounds.upper(1))
        assertEquals(IntVariableOwner.CP, route.request.componentPlan.intOwner(1))
        assertEquals(IntVariableOwner.THEORY, route.request.componentPlan.intOwner(2))
        assertSame(model, route.preparedPlan.originalSource)
    }

    @Test
    fun `refuted model should be decided rather than declined for an unplaceable column`() {
        val openHi = Bits(2).also { it.set(1) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 0), null, openHi),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
                Linear(longArrayOf(-1), intArrayOf(1), LinearOp.LE, -5),
            ),
        )

        for (stopAfterBounds in listOf(false, true)) {
            var stopped = false

            val route = model.pipelineRoute(
                boundCancellation = Cancellation { stopped },
                onLpStats = { stopped = stopAfterBounds },
            )

            assertIs<SourceProblemRoute.Refuted>(route)
            assertEquals(stopAfterBounds, stopped)
        }
    }

    @Test
    fun `column bounded by a row should be placed while another column stays open`() {
        val openHi = Bits(3).also {
            it.set(1)
            it.set(2)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(5, 0, 0),
                null,
                openHi,
            ),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )

        val objective = LinearObjective(intCoefficients = longArrayOf(0, 1, 0), constant = 7)

        val route = model.pipelineRoute(objective = objective, maximize = true)

        val planned = assertIs<SourceProblemRoute.OpenTheory>(route)
        val bounded = assertNotNull(planned.preparedPlan.model)
        val ownership = assertNotNull(planned.preparedPlan.componentPlan)
        assertEquals(5L, bounded.intBounds.upper(1))
        assertEquals(IntVariableOwner.CP, ownership.intOwner(1))
        assertEquals(IntVariableOwner.THEORY, ownership.intOwner(2))
    }

    @Test
    fun `interrupted preparation should keep a completed partial bound closure`() {
        val openHi = Bits(3).also {
            it.set(1)
            it.set(2)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(5, 0, 0), null, openHi),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )
        var stopped = false

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(
            model.pipelineRoute(
                boundCancellation = Cancellation { stopped },
                onLpStats = { stopped = true },
            ),
        )

        assertTrue(stopped)
        assertEquals(PreparedExecutionKind.DECLINED, route.preparedPlan.kind)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertNull(route.preparedPlan.componentPlan)
        assertSame(model, route.preparedPlan.originalSource)
        assertSame(route.problem, route.preparedPlan.model)
        assertEquals(5L, route.problem.intBounds.upper(1))
        assertEquals(false, model.intBounds.hasUpper(1))
        assertEquals(false, route.problem.intBounds.hasUpper(2))
    }

    @Test
    fun `shared polynomial routing should keep the affine objective and source direction`() {
        for (factor in listOf(ContinuousProduct(0, 0, 1), RealProduct(0, 0, 1, -1.0, 1.0))) {
            for (maximize in listOf(false, true)) {
                val source = Problem(
                    0,
                    1,
                    arrayOf(IntDomain(0, 1)),
                    arrayOf(factor),
                    numRealVars = 2,
                    realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY),
                )
                val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))

                val route = source.pipelineRoute(objective, maximize)

                val polynomial = assertIs<SourceProblemRoute.PolynomialTheory>(route)
                assertSame(objective, polynomial.objective)
                assertEquals(maximize, polynomial.maximize)
            }
        }
    }

    @Test
    fun `pure linear real sources should keep their routing and source objective`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(1.0))

        val route = source.pipelineRoute(objective, routePureRealToTheory = true, routeLinearToTheory = true)

        val linear = assertIs<SourceProblemRoute.OpenTheory>(route)
        assertSame(objective, linear.request.objective)
        assertEquals(ProblemPipeline.EXACT_LRA, linear.request.route)
    }

    @Test
    fun `open column with fractional coefficients should route to exact LIRA`() {
        val openUpper = Bits(1).also { it.set(0) }
        val spec = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf(
                ReifiedRealLinear(
                    aux = 0,
                    vars = intArrayOf(0),
                    intCoeffs = doubleArrayOf(0.5),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 3.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, spec.sourceRoute())
        assertEquals(
            ProblemPipeline.EXACT_LIRA,
            spec.componentPlan().theoryPipeline,
        )
    }

    @Test
    fun `open distinct should retain its exact arithmetic route and open declaration`() {
        val openUpper = Bits(2).also { it.set(1) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(9, 0), null, openUpper),
            factors = arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, spec.sourceRoute())
        assertEquals(ProblemPipeline.EXACT_LIRA, spec.componentPlan().theoryPipeline)
        assertIs<SourceProblemRoute.OpenTheory>(spec.pipelineRoute())
        assertEquals(null, spec.intDomainOrNull(1))
    }

    @Test
    fun `pipeline route should keep component plans for finite and open models`() {
        val finite = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(1), null, null),
            factors = emptyArray(),
        )
        val openUpper = Bits(1).also { it.set(0) }
        val open = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val finiteRoute = assertIs<SourceProblemRoute.Finite>(finite.pipelineRoute())
        val openRoute = assertIs<SourceProblemRoute.OpenTheory>(open.pipelineRoute())

        assertSame(finite, finiteRoute.problem)
        assertFalse(finiteRoute.problem is BakedProblem)
        assertEquals(ProblemPipeline.FINITE_CP, finiteRoute.componentPlan.theoryPipeline)
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, openRoute.request.componentPlan.theoryPipeline)
        assertEquals(null, openRoute.request.model.intDomainOrNull(0))
    }

    @Test
    fun `pipeline route should go finite when the LP relaxation bounds an open side`() {
        val openUpper = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 10)),
        )

        var lpPasses = 0.0
        val route = assertIs<SourceProblemRoute.Finite>(
            problem.pipelineRoute { stats ->
                lpPasses = stats.standalonePasses.sum + stats.componentPasses.sum
            },
        )

        assertEquals(IntDomain(0, 5), route.problem.finiteIntDomains().single())
        assertTrue(lpPasses > 0.0)
    }

    @Test
    fun `pipeline route should refute contradictory rows over open ranges`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(-1), intArrayOf(0), LinearOp.LE, -5),
            ),
        )

        assertIs<SourceProblemRoute.Refuted>(problem.pipelineRoute())
    }

    @Test
    fun `open route should keep LP proved bounds and leave unbounded columns open`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 10)),
        )

        val route = assertIs<SourceProblemRoute.OpenTheory>(problem.pipelineRoute())

        val model = route.request.model
        assertEquals(5, model.intBounds.upper(0))
        assertTrue(model.intBounds.isOpenUpper(1))
    }

    @Test
    fun `pipeline route should not bake a wide bounded model`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0),
                longArrayOf(1_000_000_000, 1_000_000_000),
                null,
                null,
            ),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, -1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 0), LinearOp.LE, -1),
            ),
        )

        val route = assertIs<SourceProblemRoute.Finite>(problem.pipelineRoute())

        assertSame(problem, route.problem)
        assertFalse(route.problem is BakedProblem)
    }
}
