package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemTest {

    @Test
    fun `a raw problem keeps its declared domains unbaked`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
        )
        assertEquals(10, problem.requireFiniteIntDomains()[0].max, "a raw problem never folds the root bake")
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
    fun `materializing a model keeps its open bounds separate from search domains`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val problem = model.materialize(arrayOf(IntDomain(3, 8)))

        assertEquals(8, problem.requireFiniteIntDomains()[0].max)
        assertFalse(problem.intBounds.hasUpper(0))
    }

    @Test
    fun `a problem spec classifies linear open columns before search materialization`() {
        val openUpper = Bits(1).also { it.set(0) }
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )

        assertTrue(spec.variablePartition().isTheoryEligible(0))
    }

    @Test
    fun `component plan keeps open theory columns unmaterialized`() {
        val openUpper = Bits(3).also { it.set(1) }
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )

        val plan = spec.componentPlan()
        val problem = plan.materialize(spec, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))
        val cp = plan.cpProjection(spec, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(1))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(1))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(IntDomain(0, 3), problem.intDomainOrNull(0))
        assertEquals(null, problem.intDomainOrNull(1))
        assertEquals(
            IntColumn.Bounded(lower = 0, upper = null),
            problem.intColumns.column(1),
            "a theory column carries the source bounds, open side included",
        )
        assertFailsWith<IllegalArgumentException> { problem.requireFiniteIntDomains() }
        assertEquals(2, cp.problem.numIntVars)
        assertEquals(0, cp.cpId(0))
        assertEquals(-1, cp.cpId(1))
        assertEquals(0, cp.sourceId(0))
    }

    @Test
    fun `a bounded column reached only by a factor CP must hold is CP-owned`() {
        // The reified real atom reasons over its integer column's bounds, so it enumerates nothing —
        // but no theory lane takes it once its coefficients are not exactly representable, which
        // leaves CP holding the factor and therefore the column.
        val spec = ProblemSpec(
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
        val spec = ProblemSpec(
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
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(9, 0), null, openUpper),
            factors = arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)),
        )

        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, spec.sourceRoute())
        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, spec.componentPlan().theoryPipeline)
    }
}
