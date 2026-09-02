package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.OpenHintStats
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryHintTest {

    private val hintFlips = 1_000L

    private fun hinted(flips: Long? = hintFlips) = TheoryParams(openHintFlips = flips)

    private fun modelOf(body: String) = SmtLib.parse(
        """
            (set-logic QF_LIA)
            $body
            (check-sat)
        """.trimIndent(),
    )

    /** Three shared clauses over `b0..b2` alongside one open difference row. */
    private fun clausedDifferenceModel(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
    }

    private fun satisfiesClauses(model: Problem, assignment: OpenTheoryAssignment): Boolean =
        model.factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, assignment.boolValue(Lit.variable(it))) }
        }

    @Test
    fun `a hinted open route answers with the theory's own witness`() {
        val model = clausedDifferenceModel()

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertIs<OpenTheoryAssignment.Difference>(sat.assignment)
        assertTrue(satisfiesClauses(model, sat.assignment))
        assertEquals(1L, sat.stats.openHints.applied)
        assertEquals(3L, sat.stats.openHints.hintedVars)
    }

    @Test
    fun `a hinted route over real columns keeps its exact rational witness`() {
        val model = Problem(
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
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertIs<OpenTheoryAssignment.ExactLra>(sat.assignment)
        assertTrue(satisfiesClauses(model, sat.assignment))
        assertEquals(1L, sat.stats.openHints.applied)
    }

    @Test
    fun `an unhinted open route draws nothing`() {
        val model = clausedDifferenceModel()

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve()

        assertEquals(OpenHintStats(), assertIs<OpenTheoryResult.Sat>(result).stats.openHints)
    }

    @Test
    fun `a hint the theory refutes falls back to the complete search's model`() {
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int)
                (assert (or p q))
                (assert (=> p (= (* 2 x) 1)))
            """.trimIndent(),
        )
        val p = parsed.boolVarNames.getValue("p")
        val q = parsed.boolVarNames.getValue("q")

        val result = OpenTheoryEngine(parsed.model, parsed.model.sourceRoute()).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(false, sat.assignment.boolValue(p))
        assertEquals(true, sat.assignment.boolValue(q))
        assertEquals(1L, sat.stats.openHints.applied)
    }

    @Test
    fun `a hinted run still refutes an unsatisfiable model`() {
        // Both arms of the clause force the same parity-infeasible row, so the refutation is the exact
        // search's own: the shared clauses this hint was drawn from are satisfiable on their own.
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int) (declare-const z Int)
                (assert (or p q))
                (assert (=> p (= (* 2 x) 1)))
                (assert (=> q (= (* 2 x) 1)))
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.model.sourceRoute()).solve(hinted())

        assertEquals(1L, assertIs<OpenTheoryResult.Unsat>(result).stats.openHints.applied)
    }

    @Test
    fun `one hint serves every feasibility round of a descent`() {
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int)
                (assert (or p q))
                (assert (>= x 3))
            """.trimIndent(),
        )
        val x = parsed.intVarNames.getValue("x")
        val objective = LinearObjective(intCoefficients = LongArray(parsed.model.numIntVars).also { it[x] = 1L })

        val result = OpenTheoryMinimizer(parsed.model, objective).minimize(hinted())

        val optimal = assertIs<OpenTheoryOptimum.Optimal>(result)
        assertEquals("3", optimal.value.toString())
        assertEquals(1L, optimal.stats.openHints.draws)
    }

    @Test
    fun `a hinted witness keeps values past the long range`() {
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int) (declare-const y Int)
                (assert (or p q))
                (assert (= (- x y) 0))
                (assert (>= y 100000000000000000000))
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.model.sourceRoute()).solve(hinted())

        val ints = assertIs<OpenTheoryAssignment.ExactLira>(
            assertIs<OpenTheoryResult.Sat>(result).assignment,
        ).assignment.ints
        assertEquals(ints[parsed.intVarNames.getValue("y")], ints[parsed.intVarNames.getValue("x")])
        assertTrue(ints[parsed.intVarNames.getValue("y")] >= BigInteger.parseString("100000000000000000000"))
    }

    @Test
    fun `a zero allowance draws and proposes nothing`() {
        val model = clausedDifferenceModel()

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(hinted(flips = 0))

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(1L, sat.stats.openHints.draws)
        assertEquals(0L, sat.stats.openHints.applied)
        assertEquals(0L, sat.stats.openHints.hintedVars)
    }

    @Test
    fun `a cancelled draw steers nothing`() {
        val model = clausedDifferenceModel()
        val state = OpenTheorySolveState(hinted())

        val hints = state.candidateHints(model.componentPlan(), model, Cancellation { true })

        assertNull(hints.preferredBool(0))
        assertEquals(1L, state.hints.draws)
        assertEquals(0L, state.hints.applied)
    }

    @Test
    fun `the request's hint is drawn once and reused`() {
        val model = clausedDifferenceModel()
        val plan = model.componentPlan()
        val state = OpenTheorySolveState(hinted())

        val first = state.candidateHints(plan, model, Cancellation.Never)
        val second = state.candidateHints(plan, model, Cancellation.Never)

        assertEquals(first.preferredBool(0), second.preferredBool(0))
        assertEquals(1L, state.hints.draws)
    }
}
