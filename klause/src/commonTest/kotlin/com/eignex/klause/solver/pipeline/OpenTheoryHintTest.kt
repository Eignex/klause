package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryHintTest {

    private val hintFlips = 1_000L

    private fun hinted(flips: Long? = hintFlips, minSplits: Long = 1) =
        TheoryParams(openHintFlips = flips, openHintMinSplits = minSplits)

    private fun modelOf(body: String) = SmtLib.parse(
        """
            (set-logic QF_LIA)
            $body
            (check-sat)
        """.trimIndent(),
    )

    /** [clauses] over [numBoolVars] shared Boolean columns alongside one open difference row. */
    private fun clausedModel(numBoolVars: Int, vararg clauses: Clause): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = numBoolVars,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf<Factor>(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3), *clauses),
        )
    }

    /** Three shared clauses over `b0..b2` alongside one open difference row. */
    private fun clausedDifferenceModel(): Problem = clausedModel(
        numBoolVars = 3,
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
    )

    /** Shared clauses no assignment satisfies, so a draw over them spends its whole allowance. */
    private fun refutedClauseModel(): Problem = clausedModel(
        numBoolVars = 2,
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
    )

    private fun satisfiesClauses(model: Problem, assignment: OpenTheoryAssignment): Boolean =
        model.factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, assignment.boolValue(Lit.variable(it))) }
        }

    @Test
    fun `a hinted open route answers with the theory's own witness`() {
        val model = clausedDifferenceModel()

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        // Source presolve projects a singleton column out of this model, so the theory's witness arrives
        // wrapped with that column rebuilt on top; the hint must not have replaced the witness underneath.
        val witness = (sat.assignment as? OpenTheoryAssignment.Rebuilt)?.base ?: sat.assignment
        assertIs<OpenTheoryAssignment.Difference>(witness)
        assertTrue(satisfiesClauses(model, sat.assignment))
        assertEquals(1L, sat.stats.openHints.produced)
        assertEquals(3L, sat.stats.openHints.hintedVars)
        assertTrue(sat.stats.openHints.steeredSplits > 0, "the hint ordered at least one split")
    }

    @Test
    fun `the allowance is spent only once the split threshold is reached`() {
        val model = clausedDifferenceModel()
        val plan = model.componentPlan()
        val state = OpenTheorySolveState(hinted(minSplits = 3))
        val hints = state.candidateHints(plan, model, Cancellation.Never)

        hints.preferredBool(0)
        hints.preferredBool(1)
        val beforeThreshold = state.hints.draws
        hints.preferredBool(2)

        assertEquals(0L, beforeThreshold, "two splits leave the allowance unspent")
        assertEquals(1L, state.hints.draws)
    }

    @Test
    fun `a hint the theory refutes falls back to the complete search's model`() {
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int) (declare-const y Int)
                (assert (or p q))
                (assert (=> p (= (+ (* 2 x) (* 4 y)) 1)))
            """.trimIndent(),
        )
        val p = parsed.boolVarNames.getValue("p")
        val q = parsed.boolVarNames.getValue("q")

        val result = OpenTheoryEngine(parsed.model, parsed.model.sourceRoute()).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(false, sat.assignment.boolValue(p))
        assertEquals(true, sat.assignment.boolValue(q))
        assertEquals(1L, sat.stats.openHints.produced)
    }

    @Test
    fun `one hint serves every feasibility round of a descent`() {
        // The row under p tells p and q apart, so ordering them cannot settle the exactly-one while probing.
        val parsed = modelOf(
            """
                (declare-const p Bool) (declare-const q Bool)
                (declare-const x Int)
                (assert (or p q))
                (assert (or (not p) (not q)))
                (assert (>= x 3))
                (assert (=> p (>= x 4)))
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
    fun `a cancelled draw steers nothing`() {
        val model = clausedDifferenceModel()
        val state = OpenTheorySolveState(hinted())

        val hints = state.candidateHints(model.componentPlan(), model, Cancellation { true })

        assertNull(hints.preferredBool(0))
        assertEquals(1L, state.hints.draws)
        assertEquals(0L, state.hints.produced)
        assertEquals(0L, state.hints.steeredSplits)
    }

    @Test
    fun `the moves a draw spends are charged against the work budget`() {
        val model = refutedClauseModel()
        val state = OpenTheorySolveState(hinted(flips = 64))

        state.candidateHints(model.componentPlan(), model, Cancellation.Never).preferredBool(0)

        val charged = state.work.snapshot().openWork
        assertTrue(charged > 0, "the draw spent moves the budget never saw")
        assertEquals(state.hints.moves, charged)
    }
}
