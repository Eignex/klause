package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.FactorOwner
import com.eignex.klause.solver.pipeline.IntVariableOwner
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.pipeline.pipelineRoute
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.pipeline.variablePartition
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProblemTest {

    // x in [0, 10] with x <= 3: the root bake tightens the open upper bound to 3.
    private fun tighteningFactors(): List<Factor> = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3))

    @Test
    fun `bake folds the root deductions while the raw problem stays unbaked`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = tighteningFactors(),
        )
        assertEquals(10, problem.finiteIntDomain(0).max, "a raw problem never folds the root bake")
        assertEquals(3, problem.bake().rootIntDomain(0).max, "bake carries the x <= 3 tightening")
    }

    @Test
    fun `problem rejects a factor with an invalid Boolean variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 1,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf(Clause(intArrayOf(Lit.make(1, true)))),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references Boolean variable 1"))
    }

    @Test
    fun `problem rejects a factor with an invalid integer variable`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Problem(
                numBoolVars = 0,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(0, 1)),
                factors = arrayOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 0)),
            )
        }

        assertTrue(error.message!!.contains("factor 0 references integer variable 1"))
    }

    @Test
    fun `model bounds retain an open side behind the search clamp`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-8, 8)),
            factors = emptyArray(),
            openIntLo = booleanArrayOf(true),
        )

        assertFalse(problem.intBounds.hasLower(0))
        assertTrue(problem.intBounds.hasUpper(0))
        assertEquals(8, problem.intBounds.upper(0))
    }

    @Test
    fun `model bounds represent an open range without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val bounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper)

        assertTrue(bounds.hasLower(0))
        assertFalse(bounds.hasUpper(0))
        assertEquals(3, bounds.lower(0))
    }

    @Test
    fun `canonical problem keeps open bounds without a search domain`() {
        val openUpper = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        assertEquals(null, problem.intDomainOrNull(0))
        assertFalse(problem.intBounds.hasUpper(0))
    }

    @Test
    fun `a canonical problem classifies linear open columns before planning`() {
        val openUpper = Bits(1).also { it.set(0) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )

        assertTrue(spec.variablePartition().isTheoryEligible(0))
    }

    @Test
    fun `component plan keeps open theory columns unmaterialized`() {
        val openUpper = Bits(3).also { it.set(1) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )

        val plan = spec.componentPlan()
        val cp = plan.cpProjection(spec, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(1))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(1))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(0L, spec.intBounds.lower(1))
        assertFalse(spec.intBounds.hasUpper(1), "a theory column keeps its open side rather than a domain")
        assertEquals(2, cp.problem.numIntVars)
        assertEquals(0, cp.cpId(0))
        assertEquals(-1, cp.cpId(1))
        assertEquals(0, cp.sourceId(0))
    }

    @Test
    fun `a source rewrite keeps a non-contiguous declaration`() {
        val holey = IntDomain(0, 6).excludeValue(3)
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(holey),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)),
        )

        val rewritten = spec.withFactors(emptyArray())

        assertEquals(holey, rewritten.intDomainOrNull(0), "a rewrite states which rows it keeps, not which values")
        assertEquals(0, rewritten.numFactors)
    }

    @Test
    fun `a mixed plan hands the theory a fragment that keeps every declared value set`() {
        // A CP column excluding an interior value, alongside a column left open above: the fragment is
        // rebuilt from the source, and endpoints alone cannot say that 1 is absent from column 0.
        val holey = IntDomain(0, 3).excludeValue(1)
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(holey, IntDomain(0, 1L shl 40), holey),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
            openIntHi = booleanArrayOf(false, true, false),
        )

        val plan = spec.componentPlan()
        val fragment = plan.theoryFragment(spec)

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(1))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(holey, fragment.intDomainOrNull(0))
        assertFalse(fragment.intBounds.hasUpper(1), "the fragment keeps the open side the theory reasons over")
        assertEquals(1, fragment.numFactors)
    }

    @Test
    fun `a bounded column reached only by a factor CP must hold is CP-owned`() {
        // The reified real atom reasons over its integer column's bounds, so it enumerates nothing —
        // but no theory lane takes it once its coefficients are not exactly representable, which
        // leaves CP holding the factor and therefore the column.
        val spec = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
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

        val plan = spec.componentPlan()

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
    }

    @Test
    fun `an open column reached only by a factor CP must hold is unroutable rather than fatal`() {
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

        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, spec.sourceRoute())
        assertEquals(
            ProblemPipeline.UNSUPPORTED_OPEN,
            spec.componentPlan().theoryPipeline,
            "the plan answers the same verdict rather than asserting the model away",
        )
    }

    @Test
    fun `an unrepresentable model reads the same from either entry point`() {
        // A CP-only factor over an open column: the route and the plan are two doors onto one model, and
        // which door a frontend used must not decide whether it gets a verdict or a crash.
        val openUpper = Bits(2).also { it.set(1) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(9, 0), null, openUpper),
            factors = arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)),
        )

        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, spec.sourceRoute())
        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, spec.componentPlan().theoryPipeline)
        assertIs<SourceProblemRoute.UnsupportedOpen>(spec.pipelineRoute())
        assertEquals(null, spec.intDomainOrNull(1))
    }

    @Test
    fun `bounded and open routes retain their component plans`() {
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
    fun `an open side the relaxation bounds routes to the finite lane`() {
        val openUpper = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 10)),
        )

        val route = assertIs<SourceProblemRoute.Finite>(problem.pipelineRoute())

        assertEquals(IntDomain(0, 5), route.problem.finiteIntDomains().single())
    }

    @Test
    fun `a model with one side still open stays on the open lane untouched`() {
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

        assertSame(problem, route.request.model)
    }

    @Test
    fun `routing a wide bounded model does not run its root bake`() {
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
