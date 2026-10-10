package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenTheoryEngineTest {

    private fun sourceRoute(model: Problem): ProblemPipeline = model.sourceRoute()

    @Test
    fun `open difference route executes through the planned shared session`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(0L, assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints[0])
    }

    @Test
    fun `open route replans after a source factor rewrite`() {
        val open = Bits(3).also { bits -> repeat(3) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )

        assertIs<OpenTheoryResult.Sat>(OpenTheoryEngine(model, sourceRoute(model)).solve())
    }

    @Test
    fun `open route distinguishes external cancellation from a wall timeout`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(false, result.stats.run.timedOut)
        assertEquals(0L, result.stats.smt.sourceLp.operations)
    }

    @Test
    fun `a spent shared decision allowance reports budget exhaustion`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)
            .solve(TheoryParams(maxDecisions = 0))

        val unknown = assertIs<OpenTheoryResult.Unknown>(result)
        assertEquals(TerminationReason.BudgetExhausted, unknown.reason)
        assertEquals(0L, unknown.stats.openTheory.openBoolDecisions)
    }

    @Test
    fun `shared decision allowance spans feasibility rounds`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )
        val params = TheoryParams(maxDecisions = 1)
        val state = OpenTheorySolveState(params)
        val engine = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)

        assertIs<OpenTheoryResult.Sat>(engine.solve(params, state))
        val second = assertIs<OpenTheoryResult.Unknown>(engine.solve(params, state))

        assertEquals(TerminationReason.BudgetExhausted, second.reason)
        assertEquals(1L, second.stats.openTheory.openBoolDecisions)
    }

    @Test
    fun `open exact LIA route assembles its theory assignment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        // The `>= 1` row leaves both columns neither up- nor down-safe, so dual fixing declines and the
        // theory is what produces the witness.
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(
                Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve()

        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open exact LRA route assembles its theory assignment`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = arrayOf(
                ReifiedRealLinear(
                    aux = 0,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA).solve()

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertIs<OpenTheoryAssignment.ExactLra>(sat.assignment)
        assertEquals(sat.stats.smt.witnessCandidates, sat.stats.smt.witnessAccepted)
        assertTrue(sat.stats.smt.witnessCandidates > 0L)
    }

    @Test
    fun `open exact LIRA route assembles its theory assignment`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (+ (to_real x) (/ 1.0 3.0))))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))
        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `a cancelled exact LIRA run reports unknown before reduction work`() {
        // The open model has no finite root box. A stop must be observed before the exact reduction
        // classifies row directions or materializes its transformed system.
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (>= (* 1000003 x) 7))
                (assert (>= y (to_real x)))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model))
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
    }

    @Test
    fun `open pure LIA difference fragment keeps the difference route`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (<= (- x y) 4))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, sourceRoute(parsed.model))
    }

    @Test
    fun `a cancelled exact LIA run reports unknown rather than refuting a satisfiable model`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (= (+ x y) 10))
                (assert (>= x 0))
                (assert (>= y 0))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))
        assertIs<OpenTheoryResult.Sat>(OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve())

        val stopped = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model))
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(stopped)
    }

    // Three CP columns each admitting {0, 3}, so an all-different over them has no solution; the bound
    // hull [0, 3] admits four values and would. The fourth column is open, which is what puts the model
    // on the composed open route in the first place.
    private fun pigeonholeOverDeclaredHoles(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { column ->
            if (column == 3) IntDomain(0, 0) else IntDomain(0, 3).excludeValues(longArrayOf(1, 2))!!
        },
        factors = arrayOf(
            AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4),
            Linear(intArrayOf(1), intArrayOf(3), LinearOp.LE, 4),
        ),
        openIntHi = booleanArrayOf(false, false, false, true),
    )

    @Test
    fun `a CP column's domain is materialized from its declaration rather than its bound hull`() {
        val model = pigeonholeOverDeclaredHoles()

        val plan = model.componentPlan()

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(3))
        assertEquals(
            IntDomain(0, 3).excludeValues(longArrayOf(1, 2)),
            plan.cpSourceDomains(model)[0],
            "the hull would hand the search values the model excludes",
        )
    }

    @Test
    fun `the composed open route refuses a model its declared holes make infeasible`() {
        val model = pigeonholeOverDeclaredHoles()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
