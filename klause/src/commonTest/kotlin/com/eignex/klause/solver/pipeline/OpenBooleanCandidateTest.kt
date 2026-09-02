package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenBooleanCandidateTest {

    private val sharedClauses = arrayOf(
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
    )

    private fun differenceModel(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                *sharedClauses,
            ),
        )
    }

    private fun exactLraModel(): Problem = Problem(
        numBoolVars = 4,
        intBounds = IntBounds.fromModelBounds(LongArray(0), LongArray(0), null, null),
        factors = arrayOf(
            ReifiedRealLinear(
                aux = 3,
                vars = intArrayOf(),
                intCoeffs = doubleArrayOf(),
                realVars = intArrayOf(0, 1),
                realCoeffs = doubleArrayOf(1.0, -1.0),
                op = LinearOp.LE,
                bound = 2.0,
            ),
            *sharedClauses,
        ),
        numRealVars = 2,
        realLower = doubleArrayOf(0.0, 0.0),
        realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
    )

    private fun exactLiraModel(): Problem {
        val openUpper = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), LongArray(2), null, openUpper),
            factors = arrayOf(
                Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 3),
                *sharedClauses,
            ),
        )
    }

    private fun hybridModel(): Problem {
        val openUpper = Bits(4).also { bits -> for (v in 2..3) bits.set(v) }
        return Problem(
            numBoolVars = 4,
            intBounds = IntBounds.fromModelBounds(LongArray(4), longArrayOf(3, 3, 0, 0), null, openUpper),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 4),
                ReifiedLinear(
                    auxBoolVar = 3,
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(2, 3),
                    op = LinearOp.LE,
                    bound = 5,
                ),
                *sharedClauses,
            ),
        )
    }

    private fun satisfiesSharedClauses(model: Problem, candidate: OpenBooleanCandidate): Boolean {
        val bools = BooleanArray(model.numBoolVars)
        for (i in candidate.boolVars.indices) bools[candidate.boolVars[i]] = candidate.values[i]
        return model.factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, bools[Lit.variable(it)]) }
        }
    }

    @Test
    fun `a difference plan proposes an assignment satisfying the shared clauses`() {
        val model = differenceModel()
        val plan = model.componentPlan()

        val candidate = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertTrue(satisfiesSharedClauses(model, candidate))
    }

    @Test
    fun `an exact lra plan proposes an assignment satisfying the shared clauses`() {
        val model = exactLraModel()
        val plan = model.componentPlan()

        val candidate = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertEquals(ProblemPipeline.EXACT_LRA, plan.theoryPipeline)
        assertTrue(satisfiesSharedClauses(model, candidate))
    }

    @Test
    fun `an exact lira plan proposes an assignment satisfying the shared clauses`() {
        val model = exactLiraModel()
        val plan = model.componentPlan()

        val candidate = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertEquals(ProblemPipeline.EXACT_LIRA, plan.theoryPipeline)
        assertTrue(satisfiesSharedClauses(model, candidate))
    }

    @Test
    fun `a hybrid plan proposes over the shared clauses and no other column`() {
        val model = hybridModel()
        val plan = model.componentPlan()

        val candidate = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertTrue(plan.hasCpComponent && plan.hasTheoryComponent)
        assertContentEquals(intArrayOf(0, 1, 2), candidate.boolVars)
        assertTrue(satisfiesSharedClauses(model, candidate))
    }

    @Test
    fun `the skeleton keeps source boolean ids and drops every other column`() {
        val model = hybridModel()

        val skeleton = assertNotNull(model.componentPlan().booleanSkeleton(model))

        assertEquals(model.numBoolVars, skeleton.problem.numBoolVars)
        assertEquals(0, skeleton.problem.numIntVars)
        assertEquals(0, skeleton.problem.numRealVars)
        assertContentEquals(
            sharedClauses.map { it.literals.toList() },
            skeleton.problem.factors.map { (it as Clause).literals.toList() },
        )
    }

    @Test
    fun `two draws from one plan and allowance agree`() {
        val model = differenceModel()
        val plan = model.componentPlan()

        val first = assertNotNull(plan.openBooleanDraw(model).candidate)
        val second = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertContentEquals(first.boolVars, second.boolVars)
        assertContentEquals(first.values.toList(), second.values.toList())
    }

    @Test
    fun `a plan sharing no clause proposes nothing`() {
        val openUpper = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), null, openUpper),
            factors = arrayOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )

        assertNull(model.componentPlan().openBooleanDraw(model).candidate)
    }

    @Test
    fun `a zero allowance proposes nothing`() {
        val model = differenceModel()

        val draw = model.componentPlan()
            .openBooleanDraw(model, OpenCandidateParams(maxFlips = 0))

        assertNull(draw.candidate)
    }

    @Test
    fun `a cancelled draw proposes nothing`() {
        val model = differenceModel()

        val draw = model.componentPlan()
            .openBooleanDraw(model, OpenCandidateParams(cancellation = Cancellation { true }))

        assertNull(draw.candidate)
    }
}
