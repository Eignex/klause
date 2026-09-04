package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
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

    private fun hinted(flips: Long? = hintFlips, minSplits: Long = 1) =
        TheoryParams(openHintFlips = flips, openHintMinSplits = minSplits)

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

    /** Shared clauses no assignment satisfies, so a draw over them spends its whole allowance. */
    private fun refutedClauseModel(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
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
        assertEquals(1L, sat.stats.openHints.produced)
        assertEquals(3L, sat.stats.openHints.hintedVars)
        assertTrue(sat.stats.openHints.steeredSplits > 0, "the hint ordered at least one split")
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
        assertEquals(1L, sat.stats.openHints.produced)
    }

    @Test
    fun `a hinted hybrid plan steers the shared split beside both components`() {
        val openUpper = Bits(4).also { bits -> for (v in 2..3) bits.set(v) }
        val model = Problem(
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
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val plan = model.componentPlan()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(plan.hasCpComponent && plan.hasTheoryComponent, "the plan owns both a CP and a theory half")
        assertTrue(satisfiesClauses(model, sat.assignment))
        // The hint covers only the clause columns; the finite and theory halves keep their own columns.
        assertEquals(3L, sat.stats.openHints.hintedVars)
        assertTrue(sat.stats.openHints.steeredSplits > 0, "the hint ordered at least one shared split")
    }

    @Test
    fun `a model propagation settles never spends the allowance`() {
        // Unit clauses leave the shared component nothing to split, so no split ever asks for a hint and
        // the producer is never run — the case an up-front draw paid for and could not use.
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, true))),
            ),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(hinted())

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(OpenHintStats(), sat.stats.openHints)
    }

    @Test
    fun `a threshold no split reaches never spends the allowance`() {
        val model = clausedDifferenceModel()

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)
            .solve(hinted(minSplits = 10_000))

        val sat = assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(OpenHintStats(), sat.stats.openHints)
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
        assertEquals(1L, sat.stats.openHints.produced)
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

        assertEquals(1L, assertIs<OpenTheoryResult.Unsat>(result).stats.openHints.produced)
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
        assertEquals(0L, sat.stats.openHints.produced)
        assertEquals(0L, sat.stats.openHints.hintedVars)
        assertEquals(0L, sat.stats.openHints.steeredSplits)
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
    fun `the request's hint is drawn once and reused`() {
        val model = clausedDifferenceModel()
        val plan = model.componentPlan()
        val state = OpenTheorySolveState(hinted())

        val first = state.candidateHints(plan, model, Cancellation.Never)
        val second = state.candidateHints(plan, model, Cancellation.Never)

        assertEquals(first.preferredBool(0), second.preferredBool(0))
        assertEquals(1L, state.hints.draws)
    }

    @Test
    fun `the draw's allowance is what the work budget has left`() {
        val model = refutedClauseModel()
        val budget = 64L
        val state = OpenTheorySolveState(
            TheoryParams(openWorkLimit = budget, openHintFlips = 100_000, openHintMinSplits = 1),
        )

        state.candidateHints(model.componentPlan(), model, Cancellation.Never).preferredBool(0)

        assertTrue(state.hints.moves in 1..budget, "the draw spent ${state.hints.moves} of a $budget budget")
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
