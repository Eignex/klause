package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.followedBy
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheoryHintTest {

    @Test
    fun `closing a difference opening withholds its completed point`() {
        val skeleton = clausedModel(1)
        val model = Problem(numBoolVars = 1, intBounds = skeleton.intBounds,
            factors = arrayOf<Factor>(*skeleton.factors,
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)))
        val planned = model.componentPlan().search(model, emptyMap())
        val component = assertIs<DifferenceSearchComponent>(planned.theory)
        val hints = assertNotNull(component.potentialHints(Cancellation.Never)).second
        assertIs<ComponentResult.Consistent>(planned.session.initialize())
        assertNull(planned.candidateHints.preferredBool(0))
        assertEquals(true, hints.preferredBool(0))

        planned.close()

        assertNull(hints.preferredBool(0))
        model.componentPlan().search(model, emptyMap()).use { sibling ->
            val siblingComponent = assertIs<DifferenceSearchComponent>(sibling.theory)
            val siblingHints = assertNotNull(siblingComponent.potentialHints(Cancellation.Never)).second
            assertIs<ComponentResult.Consistent>(sibling.session.initialize())
            assertEquals(true, siblingHints.preferredBool(0))
            assertNull(sibling.candidateHints.preferredBool(0))
            assertIs<SearchRunEvent.Satisfied>(sibling.session.openRun(1,
                candidateHints = siblingHints).next())
        }
    }

    @Test
    fun `a live graph point guides a declared Boolean through its semantic implications`() {
        val skeleton = clausedModel(3, Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4)))
        val model = Problem(numBoolVars = 3, intBounds = skeleton.intBounds,
            factors = arrayOf<Factor>(*skeleton.factors,
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 1),
            ))
        for (guided in listOf(false, true)) {
            val stats = SmtStatsSink()
            val planned = model.componentPlan().search(model, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats)
            val component = assertIs<DifferenceSearchComponent>(planned.theory)
            val (variables, primitive) = assertNotNull(component.potentialHints(Cancellation.Never))
            val hints = assertNotNull(SourceConjunctionHints.create(model.factors.filterIsInstance<Clause>(),
                model.numBoolVars, variables, primitive, Cancellation.Never, usage = stats.hintUsage,
                available = component::potentialHintAvailable, knownBool = component::potentialHintBool))
            try {
                assertIs<ComponentResult.Consistent>(planned.session.initialize())
                val result = planned.session.openRun(3, SearchSolveParams(maxDecisions = 1),
                    candidateHints = if (guided) hints else SearchCandidateHints.None).next()

                assertIs<SearchRunEvent.Satisfied>(result)
                assertEquals(guided, planned.session.boolValue(0))
                assertEquals(guided, planned.session.boolValue(1))
                assertEquals(!guided, planned.session.boolValue(2))
            } finally {
                hints.close()
                planned.close()
            }
            assertEquals(if (guided) 1L else 0L, stats.hintUsage.snapshot().clauseSelections)
        }
    }

    @Test
    fun `graph preferences settle nested nary source gates within one decision`() {
        val skeleton = clausedModel(7,
            Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(3, 8)),
            Clause(intArrayOf(2, 5, 7, 9)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 10)), Clause(intArrayOf(1, 12)),
            Clause(intArrayOf(0, 3, 11, 13)),
        )
        val comparisons = Array<Factor>(5) { index ->
            ReifiedLinear(index + 2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0)
        }
        val model = Problem(numBoolVars = 7, intBounds = skeleton.intBounds,
            factors = arrayOf(*skeleton.factors, *comparisons))
        for (guided in listOf(false, true)) {
            val stats = SmtStatsSink()
            val planned = model.componentPlan().search(model, emptyMap(), Long.MAX_VALUE, Cancellation.Never,
                SearchLearnedDbParams(), stats)
            val component = assertIs<DifferenceSearchComponent>(planned.theory)
            val (variables, primitive) = assertNotNull(component.potentialHints(Cancellation.Never))
            val hints = assertNotNull(SourceConjunctionHints.create(model.factors.filterIsInstance<Clause>(),
                model.numBoolVars, variables, primitive, Cancellation.Never, usage = stats.hintUsage,
                available = component::potentialHintAvailable, knownBool = component::potentialHintBool))
            try {
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val result = planned.session.openRun(7, SearchSolveParams(maxDecisions = 1),
                    candidateHints = if (guided) observedCandidateHints(SearchCandidateHints.None,
                        hints, stats.hintUsage) else SearchCandidateHints.None).next()

                if (guided) {
                    assertIs<SearchRunEvent.Satisfied>(result)
                    for (variable in 0 until 7) assertEquals(true, planned.session.boolValue(variable))
                } else {
                    assertEquals(SearchRunEvent.Indeterminate.Budget, result)
                }
            } finally {
                hints.close()
                planned.close()
            }
            assertEquals(1L, stats.hintUsage.snapshot().indexesAdmitted)
            assertEquals(if (guided) 1L else 0L, stats.hintUsage.snapshot().componentSelections)
            assertEquals(if (guided) 1L else 0L, stats.hintUsage.snapshot().gateSelections)
        }
    }

    @Test
    fun `proved comparison guards settle signed equality gates without Boolean decisions`() {
        for (negativeTarget in listOf(false, true)) {
            val target = Lit.make(0, !negativeTarget)
            val model = Problem(
                numBoolVars = 3,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, null),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.negate(target), 2)),
                    Clause(intArrayOf(Lit.negate(target), 4)),
                    Clause(intArrayOf(target, 3, 5)),
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
                ),
            )
            val planned = model.componentPlan().search(model, emptyMap())
            try {
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val result = planned.session.openRun(3, SearchSolveParams(maxDecisions = 0)).next()

                assertIs<SearchRunEvent.Satisfied>(result)
                assertEquals(!negativeTarget, planned.session.boolValue(0))
                assertEquals(true, planned.session.boolValue(1))
                assertEquals(true, planned.session.boolValue(2))
            } finally {
                planned.close()
            }
        }
    }

    @Test
    fun `graph derived equality settles its atoms within one decision`() {
        val skeleton = clausedModel(3,
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
        )
        val model = Problem(numBoolVars = 3, intBounds = skeleton.intBounds,
            factors = arrayOf<Factor>(*skeleton.factors,
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ))
        for (guided in listOf(false, true)) {
            val planned = model.componentPlan().search(model, emptyMap())
            val component = assertIs<DifferenceSearchComponent>(planned.theory)
            val (variables, primitive) = assertNotNull(component.potentialHints(Cancellation.Never))
            val hints = assertNotNull(SourceConjunctionHints.create(model.factors.filterIsInstance<Clause>(),
                model.numBoolVars, variables, primitive, Cancellation.Never,
                available = component::potentialHintAvailable, knownBool = component::potentialHintBool))
            try {
                assertIs<ComponentResult.Consistent>(planned.session.initialize())

                val result = planned.session.openRun(3, SearchSolveParams(maxDecisions = 1),
                    candidateHints = if (guided) hints else SearchCandidateHints.None).next()

                if (guided) {
                    assertIs<SearchRunEvent.Satisfied>(result)
                    assertEquals(true, planned.session.boolValue(0))
                } else {
                    assertEquals(SearchRunEvent.Indeterminate.Budget, result)
                }
            } finally {
                hints.close()
                planned.close()
            }
        }
    }

    @Test
    fun `ordinary difference splits retain signed equality semantics without point guidance`() {
        for ((negativeInput, negativeTarget) in listOf(
            false to false, false to true, true to false, true to true,
        )) {
            val target = Lit.make(0, !negativeTarget)
            val left = Lit.make(1, !negativeInput)
            val right = Lit.make(2, true)
            val skeleton = clausedModel(3,
                Clause(intArrayOf(Lit.negate(target), left)),
                Clause(intArrayOf(right, Lit.negate(target))),
                Clause(intArrayOf(Lit.negate(right), target, Lit.negate(left))),
            )
            val model = Problem(numBoolVars = 3, intBounds = skeleton.intBounds,
                factors = arrayOf<Factor>(*skeleton.factors,
                    ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                    ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
                ))

            val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve(TheoryParams())

            val sat = assertIs<OpenTheoryResult.Sat>(result)
            assertEquals(false, sat.assignment.boolValue(0))
            assertTrue(satisfiesClauses(model, sat.assignment))
            assertEquals(1L, sat.stats.openHints.usage.noProvider)
            assertEquals(0L, sat.stats.openHints.usage.componentSelections)
        }
    }

    @Test
    fun `a declined gate index keeps the complete Boolean alternatives`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)))
        val primitive = SearchCandidateHints.ofLiterals(intArrayOf(2, 4))
        for (capacity in listOf(2, 3)) {
            val hints = SourceConjunctionHints.create(clauses, 3, intArrayOf(1, 2), primitive,
                Cancellation.Never, maxClauses = capacity)
            val session = SearchSession(listOf(ClauseSearchComponent(clauses)))
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = session.openRun(3, candidateHints = hints ?: SearchCandidateHints.None).next()

            assertIs<SearchRunEvent.Satisfied>(result)
            assertEquals(capacity == 3, session.boolValue(0))
            hints?.close()
        }
    }

    @Test
    fun `missing gate implications supply no derived source preference`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)))
        for (missing in clauses.indices) {
            val remaining = clauses.filterIndexed { index, _ -> index != missing }
            val hints = assertNotNull(SourceConjunctionHints.create(remaining, 3, intArrayOf(1, 2),
                SearchCandidateHints.ofLiterals(intArrayOf(2, 4)), Cancellation.Never))

            assertNull(hints.preferredBool(0))
            val session = SearchSession(listOf(ClauseSearchComponent(remaining)))
            assertIs<ComponentResult.Consistent>(session.initialize())
            assertIs<SearchRunEvent.Satisfied>(session.openRun(3, candidateHints = hints).next())
            hints.close()
        }
    }

    @Test
    fun `ambiguous gate definitions retain chronological Boolean choices`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
            Clause(intArrayOf(1, 6)), Clause(intArrayOf(0, 3, 7)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(clauses, 4, intArrayOf(1, 2, 3),
            SearchCandidateHints.ofLiterals(intArrayOf(2, 4, 6)), Cancellation.Never))
        val session = SearchSession(listOf(ClauseSearchComponent(clauses)))
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.openRun(4, candidateHints = hints).next()

        assertNull(hints.preferredBool(0))
        assertIs<SearchRunEvent.Satisfied>(result)
        assertEquals(false, session.boolValue(0))
        hints.close()
    }

    @Test
    fun `closing planned search releases primitive and compound preferences`() {
        val skeleton = clausedModel(3,
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
        )
        val model = Problem(numBoolVars = 3, intBounds = skeleton.intBounds,
            factors = arrayOf<Factor>(*skeleton.factors,
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ))
        val planned = model.componentPlan().search(model, emptyMap())
        val component = assertIs<DifferenceSearchComponent>(planned.theory)
        val (variables, primitive) = assertNotNull(component.potentialHints(Cancellation.Never))
        val hints = assertNotNull(SourceConjunctionHints.create(model.factors.filterIsInstance<Clause>(),
            model.numBoolVars, variables, primitive, Cancellation.Never,
            available = component::potentialHintAvailable, knownBool = component::potentialHintBool))
        assertIs<ComponentResult.Consistent>(planned.session.initialize())
        assertEquals(true, hints.preferredBool(0))
        assertNull(planned.candidateHints.preferredBool(0))

        planned.close()

        assertNull(hints.preferredBool(0))
        assertNull(hints.preferredBool(1))
        hints.close()
    }

    @Test
    fun `a caller preferred equality can fail without excluding its clause guided complement`() {
        val skeleton = clausedModel(4,
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
            Clause(intArrayOf(1, 6)), Clause(intArrayOf(1, 7)),
        )
        val model = Problem(numBoolVars = 4, intBounds = skeleton.intBounds,
            factors = arrayOf<Factor>(*skeleton.factors,
                ReifiedLinear(1, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                ReifiedLinear(2, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
            ))
        val planned = model.componentPlan().search(model, emptyMap())
        val component = assertIs<DifferenceSearchComponent>(planned.theory)
        val (variables, primitive) = assertNotNull(component.potentialHints(Cancellation.Never))
        val hints = assertNotNull(SourceConjunctionHints.create(model.factors.filterIsInstance<Clause>(),
            model.numBoolVars, variables, primitive, Cancellation.Never,
            available = component::potentialHintAvailable, knownBool = component::potentialHintBool))
        try {
            assertIs<ComponentResult.Consistent>(planned.session.initialize())
            assertEquals(false, hints.preferredBool(0))

            val caller = SearchCandidateHints.ofLiterals(intArrayOf(0)).followedBy(hints)
            val result = planned.session.openRun(4, candidateHints = caller).next()

            assertIs<SearchRunEvent.Satisfied>(result)
            assertEquals(false, planned.session.boolValue(0))
        } finally {
            hints.close()
            planned.close()
        }
    }

    @Test
    fun `stopped gate indexing retains its containing work charge`() {
        var visits = 0L
        val meter = WorkMeter { visits += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = visits >= 2L
            override fun workMeter(): WorkMeter = meter
        }
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)))
        val primitive = SearchCandidateHints.ofLiterals(intArrayOf(2, 4))

        val first = SourceConjunctionHints.create(clauses, 3, intArrayOf(1, 2), primitive, stop)
        val repeated = SourceConjunctionHints.create(clauses, 3, intArrayOf(1, 2), primitive, stop)
        val session = SearchSession(listOf(ClauseSearchComponent(clauses)), cancellation = stop)

        assertNull(first)
        assertNull(repeated)
        assertEquals(2L, visits)
        assertIs<SearchRunEvent.Indeterminate>(session.openRun(3).next())
    }

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
