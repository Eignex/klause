package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.theory.lia.GeneralLiaSearchComponent
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenTheoryEngineTest {

    @Test
    fun `open difference route executes through the planned shared session`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(0L, assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints[0])
    }

    @Test
    fun `open route reports a spent budget as a timed-out run`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
        assertEquals(true, result.stats.run.timedOut, "a cancelled open run reports its budget as spent")
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
        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        val ints = assertIs<OpenTheoryAssignment.GeneralLia>(
            assertIs<OpenTheoryResult.Sat>(result).assignment,
        ).assignment.ints
        val x = ints[parsed.intVarNames.getValue("x")].longValue()
        val y = ints[parsed.intVarNames.getValue("y")].longValue()
        assertTrue(3 * x + 5 * y <= 100, "the witness satisfies the first row")
        assertTrue(2 * x + y >= 7, "the witness satisfies the second row")
    }

    @Test
    fun `a finite global alongside an open column routes and answers`() {
        // The chain collapses to an Element, which needs finite domains, while `r` stays open. A
        // whole-model classifier calls that unroutable; ownership per column gives CP the Element and
        // the theory the open row.
        val chain = (0..19).toList().foldRight("0") { k, rest -> "(ite (= s $k) ${k * 3} $rest)" }
        val parsed = SmtLib.parse(
            """
                (declare-const s Int) (declare-const r Int)
                (assert (>= s 0)) (assert (<= s 19))
                (assert (= r $chain))
                (assert (= s 7))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        val assignment = assertIs<OpenTheoryAssignment.GeneralLia>(assertIs<OpenTheoryResult.Sat>(result).assignment)
        assertEquals("21", assignment.assignment.ints[parsed.intVarNames.getValue("r")].toString())
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

        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)

        // Deciding it is a search question, tracked as #1579; what matters here is that a spent budget
        // reports unknown rather than the model being refused as outside coverage.
        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline)
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(result)
    }

    @Test
    fun `open general LIA route assembles its theory assignment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.GENERAL_LIA).solve()

        assertIs<OpenTheoryAssignment.GeneralLia>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open general LIA refutes an integral split through the shared trail`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const z Int)
                (assert (= (* 2 x) 1))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `open exact LRA route assembles its theory assignment`() {
        val model = ProblemSpec(
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

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
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

        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, parsed.sourcePipeline)

        val assignment = assertIs<OpenTheoryResult.Sat>(
            OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve(),
        ).assignment
        val ints = assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints

        assertTrue(ints[parsed.intVarNames.getValue("x")] < ints[parsed.intVarNames.getValue("y")])
        assertTrue(ints[parsed.intVarNames.getValue("y")] < ints[parsed.intVarNames.getValue("z")])
    }

    @Test
    fun `a cancelled general LIA run reports unknown rather than refuting a satisfiable model`() {
        // The equality fixpoint narrows domains until nothing moves, and a stop inside it must not read
        // as the emptiness that means infeasible.
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

        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
        assertIs<OpenTheoryResult.Sat>(OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve())

        val stopped = OpenTheoryEngine(parsed.model, parsed.sourcePipeline)
            .solve(TheoryParams(cancellation = Cancellation { true }))

        assertIs<OpenTheoryResult.Unknown>(stopped)
    }

    @Test
    fun `general LIA builds its witness bound under the session cancellation`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int)
                (assert (>= x 0))
                (check-sat)
            """.trimIndent(),
        )

        val component = GeneralLiaSearchComponent(parsed.model)
        val session = SearchSession(listOf(component), cancellation = Cancellation { true })

        assertIs<ComponentResult.Indeterminate>(session.initialize())
    }
}
