package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lowering.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.sourceRoute
import com.eignex.klause.theory.TheoryParams
import com.eignex.klause.theory.lia.GeneralLiaSearchComponent
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryEngineTest {

    private fun sourceRoute(model: ProblemSpec): ProblemPipeline = model.sourceRoute()

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
        assertEquals(ProblemPipeline.GENERAL_LIA, sourceRoute(parsed.model))

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

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

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

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

        assertEquals(ProblemPipeline.GENERAL_LIA, sourceRoute(parsed.model))

        // Deciding it is a search question, tracked as #1579; what matters here is that a spent budget
        // reports unknown rather than the model being refused as outside coverage.
        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model))
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

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        assertEquals(ProblemPipeline.GENERAL_LIA, sourceRoute(parsed.model))
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

        val result = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve()

        assertEquals(ProblemPipeline.EXACT_LIRA, sourceRoute(parsed.model))
        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `a cancelled exact LIRA run reports unknown rather than encoding its whole witness box`() {
        // Every branch bound carries the witness box's magnitude, and the exact system decomposes each
        // into base-2^40 digits, one column and row per digit. A stop has to be seen inside that
        // decomposition, not after it: on an open mixed model the box runs to millions of bits.
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

        assertEquals(ProblemPipeline.GENERAL_LIA, sourceRoute(parsed.model))
        assertIs<OpenTheoryResult.Sat>(OpenTheoryEngine(parsed.model, sourceRoute(parsed.model)).solve())

        val stopped = OpenTheoryEngine(parsed.model, sourceRoute(parsed.model))
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

    @Test
    fun `general LIA refutes an asserted reified equality outside its integer lattice`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(
                lowerBounds = longArrayOf(0),
                upperBounds = longArrayOf(0),
                openLo = null,
                openHi = Bits(1).also { it.set(0) },
            ),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1),
                Clause(intArrayOf(0)),
            ),
        )

        val session = SearchSession(listOf(GeneralLiaSearchComponent(model)))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        assertNull(session.branchAlternatives())
    }
}
