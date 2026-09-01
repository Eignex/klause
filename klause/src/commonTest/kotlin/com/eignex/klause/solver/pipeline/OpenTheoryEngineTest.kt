package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraSolver
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
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
    fun `source normalization records the replanned theory backend`() {
        val openUpper = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), LongArray(2), null, openUpper),
            factors = arrayOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 3)),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(model))
        val result = assertIs<OpenTheoryResult.Sat>(OpenTheoryEngine(model, sourceRoute(model)).solve())

        assertIs<OpenTheoryAssignment.Difference>(result.assignment)
        assertEquals("difference-theory", result.stats.run.backend)
    }

    @Test
    fun `disabled open presolve retains the requested theory route`() {
        val openUpper = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), LongArray(2), null, openUpper),
            factors = arrayOf(Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.NE, 3)),
        )

        val execution = OpenTheoryPipeline.execute(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))
        val result = assertIs<OpenTheoryResult.Sat>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)

        assertIs<OpenTheoryAssignment.ExactLira>(result.assignment)
        assertEquals("exact-lira", result.stats.run.backend)
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
    fun `clause telemetry accumulates across feasibility rounds`() {
        val state = OpenTheorySolveState(TheoryParams())
        val first = SearchSession(emptyList())
        first.learn(com.eignex.klause.solver.search.SearchExplanation(intArrayOf(0, 2)))
        val second = SearchSession(emptyList())
        second.learn(com.eignex.klause.solver.search.SearchExplanation(intArrayOf(4, 6)))
        second.learn(com.eignex.klause.solver.search.SearchExplanation(intArrayOf(8, 10)))

        state.capture(first)
        state.capture(second)

        assertEquals(3L, state.clauses.learned)
        assertEquals(3L, state.clauses.retained)
        assertEquals(2L, state.clauses.peakRetained)
    }

    @Test
    fun `open route reports a wall timeout without external cancellation`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(
            TheoryParams(timeout = Cancellation { true }),
        )

        assertEquals(TerminationReason.Timeout, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(true, result.stats.run.timedOut)
    }

    @Test
    fun `unlimited open work counters are repeatable`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                lowerBounds = longArrayOf(0),
                upperBounds = longArrayOf(0),
                openLo = null,
                openHi = Bits(1).also { it.set(0) },
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Clause(intArrayOf(0)),
            ),
        )

        val first = OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve()
        val second = OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve()

        assertIs<OpenTheoryResult.Sat>(first)
        assertIs<OpenTheoryResult.Sat>(second)
        assertEquals(first.stats.openTheory, second.stats.openTheory)
    }

    @Test
    fun `an open conjunctive integer model is answered with a witness satisfying its rows`() {
        val parsed = SmtLib.parse(
            """
                (declare-const x Int) (declare-const y Int)
                (assert (<= (+ (* 3 x) (* 5 y)) 100))
                (assert (>= (+ (* 2 x) y) 7))
                (check-sat)
            """.trimIndent(),
        )
        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        val ints = assertIs<OpenTheoryAssignment.ExactLira>(
            assertIs<OpenTheoryResult.Sat>(result).assignment,
        ).assignment.ints
        val x = ints[parsed.intVarNames.getValue("x")].longValue()
        val y = ints[parsed.intVarNames.getValue("y")].longValue()
        assertTrue(3 * x + 5 * y <= 100, "the witness satisfies the first row")
        assertTrue(2 * x + y >= 7, "the witness satisfies the second row")
    }

    @Test
    fun `double bounded coordinates extend to an unbounded arbitrary precision witness`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (= (- x y) 0))
                (assert (>= y 100000000000000000000))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        val ints = assertIs<OpenTheoryAssignment.ExactLira>(
            assertIs<OpenTheoryResult.Sat>(result).assignment,
        ).assignment.ints
        val x = ints[parsed.intVarNames.getValue("x")]
        val y = ints[parsed.intVarNames.getValue("y")]
        assertEquals(y, x)
        assertTrue(y >= BigInteger.parseString("100000000000000000000"))
    }

    @Test
    fun `a finite global alongside an open column routes and answers`() {
        // The chain collapses to an Element, which needs finite domains, while `r` stays open. A
        // whole-model classifier calls that unroutable; ownership per column gives CP the Element and
        // the theory the open row.
        val chain = (0..3).toList().foldRight("0") { k, rest -> "(ite (= s $k) ${k * 3} $rest)" }
        val parsed = SmtLib.parse(
            """
                (declare-const s Int) (declare-const r Int)
                (assert (>= s 0)) (assert (<= s 3))
                (assert (= r $chain))
                (assert (= s 2))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        val assignment = assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
        assertEquals("6", assignment.assignment.ints[parsed.intVarNames.getValue("r")].toString())
    }

    @Test
    fun `an open column inside a chain keeps the chain the theory can decide`() {
        // The arms name an open column, so no [Element] is collapsed: CP could not hold one over a column
        // it cannot own, and the rows the chain lowers to are inside the theory's fragment. Declining the
        // global is what makes the model routable at all.
        val chain = (0..19).toList().foldRight("0") { k, rest -> "(ite (= s $k) (+ t $k) $rest)" }
        val parsed = SmtLib.parse(
            """
                (declare-const s Int) (declare-const t Int) (declare-const r Int)
                (assert (>= s 0)) (assert (<= s 19))
                (assert (> t 100))
                (assert (= r $chain))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))

        // Deciding it is a search question, tracked as #1579; what matters here is that a spent budget
        // reports unknown rather than the model being refused as outside coverage.
        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model))
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
    }

    @Test
    fun `open exact LIA route assembles its theory assignment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve()

        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open exact LIA refutes an integral split through the shared trail`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const z Int)
                (assert (= (* 2 x) 1))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))
        assertIs<OpenTheoryResult.Unsat>(result)
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

        assertIs<OpenTheoryAssignment.ExactLra>(assertIs<OpenTheoryResult.Sat>(result).assignment)
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
    fun `open exact LIRA refutes an integral split through the shared trail`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const z Int) (declare-const y Real)
                (assert (= y 0.0))
                (assert (= (* 2 x) 1))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, ProblemPipeline.EXACT_LIRA).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `open pure LIA linear row selects exact LIRA`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (= (+ x (* 2 y)) 7))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))
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
    fun `pure LIA exact component finds an integral witness`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, null),
            factors = arrayOf(Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val plan = model.componentPlan()

        assertEquals(ProblemPipeline.EXACT_LIRA, plan.theoryPipeline)
        assertIs<ExactLiraSearchComponent>(plan.theoryComponent(model))
        val assignment = assertIs<TheoryCheck.Sat<ExactLiraAssignment>>(
            ExactLiraSolver(model).check(
                BooleanArray(model.numBoolVars),
                object : TheoryContext {
                    override fun consumeCheck(): Boolean = true

                    override fun cancelled(): Boolean = false
                },
            ),
        ).assignment

        assertEquals(BigInteger.ZERO, assignment.ints[0])
        assertEquals(BigInteger.ZERO, assignment.ints[1])
    }

    @Test
    fun `exact LIRA component refutes a fractional pure LIA relaxation`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (= (+ (* 2 x) (* 4 y)) 1))
                (check-sat)
            """.trimIndent(),
        )
        val plan = parsed.model.componentPlan()

        assertIs<ExactLiraSearchComponent>(plan.theoryComponent(parsed.model))

        assertIs<OpenTheoryResult.Unsat>(OpenTheoryEngine(parsed.model, plan.theoryPipeline).solve())
    }

    @Test
    fun `exact LIA decides comparison clauses over open columns`() {
        val open = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), open, open),
            factors = arrayOf(
                ComparisonClause(
                    intArrayOf(0, 1),
                    arrayOf(LinearOp.EQ, LinearOp.NE),
                    longArrayOf(1, 2),
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
            ),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(model))
        assertIs<OpenTheoryResult.Unsat>(OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve())
    }

    @Test
    fun `an order chain over open columns stays inside the theory fragment`() {
        // `x < y < z` over unbounded integers is pure difference logic. Posting it as an Increasing —
        // a global no theory holds — would have made the model unroutable for no gain.
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int) (declare-const z Int)
                (assert (< x y z))
                (assert (> x 100))
                (check-sat)
            """.trimIndent(),
        )

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, sourceRoute(parsed.model))

        val assignment = assertIs<OpenTheoryResult.Sat>(
            OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve(),
        ).assignment
        val ints = assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints

        assertTrue(ints[parsed.intVarNames.getValue("x")] < ints[parsed.intVarNames.getValue("y")])
        assertTrue(ints[parsed.intVarNames.getValue("y")] < ints[parsed.intVarNames.getValue("z")])
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

    @Test
    fun `shared clauses still refute an exact LIA fragment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(
                Clause(intArrayOf(0)),
                Clause(intArrayOf(1)),
                Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.EQ, 0),
            ),
        )

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(model))
        assertIs<OpenTheoryResult.Unsat>(OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve())
    }
}
