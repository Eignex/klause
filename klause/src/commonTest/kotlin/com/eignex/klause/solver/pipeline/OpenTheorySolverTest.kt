package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.sourceRoute
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.theory.difference.DifferenceSearchComponent
import com.eignex.klause.theory.qflra.ExactLiraAssignment
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTheorySolverTest {
    @Test
    fun `default ordinary traversal should consume a shared affine bound`() {
        val model = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(2.0, -3.0),
                LinearOp.LE, 1.0, strict = true),
            ReifiedRealLinear(1, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(-4.0, 6.0),
                LinearOp.GE, -2.0, strict = true),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY })
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = assertIs<OpenTheoryResult.Sat>(engine.solve())

        assertEquals(true, result.assignment.boolValue(1))
        assertTrue((result.stats.smt.completeSearchStages?.get("PARTIAL_LINEAR_AFFINE_DEDUCTION") ?: 0L) > 0L)
    }

    @Test
    fun `default ordinary linear traversal should publish a strict guard consequence`() {
        val model = Problem(2, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ofLong(2), BigFraction.ofLong(3)), LinearOp.LE, BigFraction.ONE),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1),
                doubleArrayOf(2.0, -3.0), LinearOp.LE, 0.0, strict = true),
        ), numRealVars = 2, realLower = DoubleArray(2), realUpper = DoubleArray(2))
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model).withPresolve(PresolveConfig.NONE))

        val result = assertIs<OpenTheoryResult.Sat>(engine.solve())

        val witness = result.assignment
        assertEquals(false, witness.boolValue(0))
        assertEquals(BigFraction.ZERO, TheoryCompletion.parseRational(witness.realValue(0)))
        assertEquals(BigFraction.ZERO, TheoryCompletion.parseRational(witness.realValue(1)))
        assertTrue((result.stats.smt.completeSearchStages?.get("PARTIAL_LINEAR_DEDUCTION") ?: 0L) > 0L)
    }

    @Test
    fun `separate integer linear openings should report their own primary LP work`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const y Int)
                (assert (= (+ (* 2 x) y) 1))
                (check-sat)
            """.trimIndent(),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(parsed.model).withPresolve(PresolveConfig.NONE))

        val first = assertIs<OpenTheoryResult.Sat>(engine.solve()).stats.lp
        val second = assertIs<OpenTheoryResult.Sat>(engine.solve()).stats.lp

        assertTrue(first.standalonePasses.sum > 0)
        assertEquals(first.standalonePasses.sum, second.standalonePasses.sum)
        assertTrue(first.phases.any { (key, value) -> key.endsWith("_STANDALONE") && value.calls > 0L })
        assertTrue(second.phases.any { (key, value) -> key.endsWith("_STANDALONE") && value.calls > 0L })
    }

    @Test
    fun `a decision limited integer opening should retain primary LP work before the limit`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const b Bool)
                (declare-const x Int) (declare-const y Int)
                (assert (= (+ (* 2 x) y) 1))
                (check-sat)
            """.trimIndent(),
        )
        val engine = OpenTheoryPipeline.engineFor(OpenTheoryRequest(parsed.model).withPresolve(PresolveConfig.NONE))

        val result = engine.solve(TheoryParams(maxDecisions = 0))

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertTrue(result.stats.lp.standalonePasses.sum > 0)
        assertTrue(result.stats.lp.phases.any { (key, value) -> key.endsWith("_STANDALONE") && value.calls > 0L })
    }

    @Test
    fun `a spent node allowance should stop open traversal before a decision`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(1, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            emptyArray())
        val budget = NodeBudget(0)

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)
            .solve(TheoryParams(nodeBudget = budget))

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(0L, budget.spent)
        assertEquals(0L, result.stats.openTheory.openBoolDecisions)
    }

    @Test
    fun `the same node allowance should span separate open feasibility rounds`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = Problem(1, IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            emptyArray())
        val budget = NodeBudget(1)
        val params = TheoryParams(nodeBudget = budget)
        val engine = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY)

        assertIs<OpenTheoryResult.Sat>(engine.solve(params))
        val second = engine.solve(params)

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(second).reason)
        assertEquals(1L, budget.spent)
    }

    @Test
    fun `an exhausted node allowance should retain a root open witness`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val budget = NodeBudget(0)

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA).solve(TheoryParams(nodeBudget = budget))

        assertIs<OpenTheoryResult.Sat>(result)
        assertEquals(0L, budget.spent)
    }

    private fun sourceRoute(model: Problem): ProblemPipeline = model.sourceRoute()

    @Test
    fun `difference openings should keep their component owned model payload`() {
        val open = Bits.full(2)
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
            factors = arrayOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0),
            ),
        )
        model.componentPlan().search(model, emptyMap()).use { planned ->
            assertIs<ComponentResult.Consistent>(planned.session.initialize())

            val result = assertIs<SearchRunEvent.Satisfied>(planned.session.openRun(model.numBoolVars).next())

            assertNotNull(result.model.valueOf(assertIs<DifferenceSearchComponent>(planned.theory).assignmentKey))
        }
    }

    @Test
    fun `settled Boolean sources should complete relative graphs without arithmetic decisions`() {
        for (width in listOf(1L, 256L)) {
            val open = Bits.full(2)
            val model = Problem(
                numBoolVars = 0,
                intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open),
                factors = arrayOf(
                    Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, width),
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
                ),
            )

            val result = OpenTheoryPipeline.engineFor(OpenTheoryRequest(model)).solve(TheoryParams(maxDecisions = 0))

            val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
            val difference = assignment.intValue(0).toLong() - assignment.intValue(1).toLong()
            assertTrue(difference in 0L..width)
        }
    }

    @Test
    fun `relative integer choices should keep guarded source equalities and disequalities`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_IDL)
                (declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const r Int)
                (assert (and (>= (- x r) 0) (<= (- x r) 1)))
                (assert (and (>= (- y r) 0) (<= (- y r) 1)))
                (assert (and (>= (- z r) 0) (<= (- z r) 1)))
                (assert (not (= (- x y) 0)))
                (assert (not (= (- y z) 0)))
                (assert (= (- x z) 0))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryPipeline.engineFor(OpenTheoryRequest(parsed.model)).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val values = LongArray(4) { assignment.intValue(it).toLong() }
        assertTrue((0..2).all { values[it] - values[3] in 0L..1L })
        assertTrue(values[0] != values[1] && values[1] != values[2] && values[0] == values[2])
    }

    @Test
    fun `relative integer traversal should refute incompatible source disequalities`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_IDL)
                (declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const r Int)
                (assert (and (>= (- x r) 0) (<= (- x r) 1)))
                (assert (and (>= (- y r) 0) (<= (- y r) 1)))
                (assert (and (>= (- z r) 0) (<= (- z r) 1)))
                (assert (not (= (- x y) 0)))
                (assert (not (= (- y z) 0)))
                (assert (not (= (- x z) 0)))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryPipeline.engineFor(OpenTheoryRequest(parsed.model)).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `a spent presolve phase should leave signed difference witnesses searchable`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_IDL)
                (declare-const x Int) (declare-const y Int) (declare-const z Int)
                (assert (not (<= x y)))
                (assert (<= y z))
                (assert (<= z x))
                (check-sat)
            """.trimIndent(),
        )
        for (workLimited in listOf(false, true)) {
            val budget = if (workLimited) PresolveBudget(0) else null
            val request = OpenTheoryRequest(parsed.model).withPresolve(
                PresolveConfig.DEFAULT,
                cancellation = budget?.orSpent(Cancellation.Never) ?: Cancellation { true },
                budget = budget,
            )

            val result = OpenTheoryPipeline.engineFor(request).solve()

            val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
            val x = assignment.intValue(0).toLong()
            val y = assignment.intValue(1).toLong()
            val z = assignment.intValue(2).toLong()
            assertTrue(x > y && y <= z && z <= x)
        }
    }

    @Test
    fun `a spent presolve phase should leave signed difference contradictions searchable`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_IDL)
                (declare-const x Int) (declare-const y Int) (declare-const z Int)
                (assert (<= x y))
                (assert (<= y z))
                (assert (not (<= x z)))
                (check-sat)
            """.trimIndent(),
        )
        for (workLimited in listOf(false, true)) {
            val budget = if (workLimited) PresolveBudget(0) else null
            val request = OpenTheoryRequest(parsed.model).withPresolve(
                PresolveConfig.DEFAULT,
                cancellation = budget?.orSpent(Cancellation.Never) ?: Cancellation { true },
                budget = budget,
            )

            val result = OpenTheoryPipeline.engineFor(request).solve()

            assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `required encoders should keep the invocation meter after presolve stops`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_IDL)
                (declare-const x Int) (declare-const y Int)
                (assert (< x y))
                (check-sat)
            """.trimIndent(),
        )
        val request = OpenTheoryRequest(parsed.model).withPresolve(
            PresolveConfig.DEFAULT,
            cancellation = Cancellation { true },
        )
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val result = OpenTheoryPipeline.engineFor(request).solve(TheoryParams(cancellation = stop))

        assertIs<OpenTheoryResult.Unknown>(result)
        assertTrue(charged > 0L)
        assertEquals(0L, result.stats.openTheory.openTheoryChecks)
    }

    @Test
    fun `the open route should replan after a source factor rewrite`() {
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
    fun `the open route should distinguish external cancellation from a wall timeout`() {
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
    fun `a spent shared decision allowance should report budget exhaustion`() {
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
    fun `the shared decision allowance should span feasibility rounds`() {
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
    fun `the open exact LIA route should assemble its theory assignment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        // The `>= 1` row stops dual fixing, so the theory produces the witness.
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(
                Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LIRA).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val x = TheoryCompletion.parseRational(assignment.intValue(0))
        val y = TheoryCompletion.parseRational(assignment.intValue(1))
        assertTrue(x >= BigFraction.ZERO && y >= BigFraction.ZERO)
        assertTrue(x * BigFraction.ofLong(2) + y <= BigFraction.ofLong(3))
        assertTrue(x + y >= BigFraction.ONE)
    }

    @Test
    fun `the open exact LRA route should assemble its theory assignment`() {
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
        val y = TheoryCompletion.parseRational(sat.assignment.realValue(0))
        assertTrue(y >= BigFraction.ZERO && y <= BigFraction.ofLong(3))
        assertEquals(y <= BigFraction.ofLong(2), sat.assignment.boolValue(0))
        assertEquals(sat.stats.smt.witnessCandidates, sat.stats.smt.witnessAccepted)
        assertTrue(sat.stats.smt.witnessCandidates > 0L)
    }

    @Test
    fun `the open exact LIRA route should assemble its theory assignment`() {
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
        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val x = TheoryCompletion.parseRational(assignment.intValue(0))
        val y = TheoryCompletion.parseRational(assignment.realValue(0))
        assertEquals(x + BigFraction.ofLong(3).reciprocal(), y)
    }

    @Test
    fun `an open pure LIA difference fragment should keep the difference route`() {
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
    fun `a cancelled exact LIA run should report unknown on a satisfiable model`() {
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

    // Three columns over {0, 3} cannot be all-different, while their bound hull [0, 3] could.
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
    fun `a CP column domain should be materialized from its declaration rather than its bound hull`() {
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
    fun `the composed open route should refute a model its declared holes make infeasible`() {
        val model = pigeonholeOverDeclaredHoles()

        val result = OpenTheoryEngine(model, model.sourceRoute()).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `exact source witnesses should stay readable after native producer closure`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LRA)
                (declare-const x Real)
                (assert (= x (/ 1.0 3.0)))
                (check-sat)
            """.trimIndent(),
        )
        val engine = OpenTheoryEngine(parsed.model, parsed.model.componentPlan().theoryPipeline)
        val witness = assertIs<OpenTheoryResult.Sat>(engine.solve()).assignment

        assertIs<OpenTheoryResult.Unknown>(engine.solve(TheoryParams(cancellation = Cancellation { true })))

        assertEquals("1/3", witness.realValue(0))
    }

    @Test
    fun `source reconstruction should keep typed irrational real coordinates`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val base = OpenTheoryAssignment.ExactNra(ExactNraAssignment(booleanArrayOf(false), listOf(root)))

        val rebuilt = OpenTheoryAssignment.Rebuilt(base, booleanArrayOf(true), ints = null)

        assertEquals(true, rebuilt.boolValue(0))
        assertEquals(0, arithmetic.compare(root, assertNotNull(rebuilt.algebraicRealValue(0))))
        assertEquals("root(2;[-2,0,1])", rebuilt.realValue(0))
    }

    @Test
    fun `rational polynomial coordinates should keep exact rational text`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val third = BigFraction.ofLong(3).reciprocal()
        val assignment = OpenTheoryAssignment.ExactNra(
            ExactNraAssignment(BooleanArray(0), listOf(arithmetic.rational(third))),
        )

        val text = assignment.realValue(0)

        assertEquals("1/3", text)
        assertEquals(third, assignment.algebraicRealValue(0)?.rational)
    }

    @Test
    fun `linear real witnesses should keep their rational compatibility surface`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val assignment = OpenTheoryAssignment.ExactLra(ExactLraAssignment(BooleanArray(0), listOf(third)))

        assertEquals("1/3", assignment.realValue(0))
        assertNull(assignment.algebraicRealValue(0))
    }

    @Test
    fun `recovered witness coordinates should resist external mutation`() {
        val base = OpenTheoryAssignment.Difference(Sample(booleanArrayOf(false), longArrayOf(1)))
        val bools = booleanArrayOf(true)
        val ints = arrayOf(parseBigInt("9223372036854775808"))
        val rebuilt = OpenTheoryAssignment.Rebuilt(base, bools, ints)

        bools[0] = false
        ints[0] = BIG_ZERO
        rebuilt.bools[0] = false
        checkNotNull(rebuilt.ints)[0] = BIG_ZERO

        assertEquals(true, rebuilt.boolValue(0))
        assertEquals("9223372036854775808", rebuilt.intValue(0))
    }

    private fun pos(v: Int) = Lit.make(v, true)

    private fun witness(bools: BooleanArray, ints: LongArray = longArrayOf()) =
        OpenTheoryAssignment.Difference(Sample(bools, ints))

    @Test
    fun `a rebuild should recover an eliminated column of an open witness`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))

        val lifted = rebuild.lift(witness(booleanArrayOf(false, true)), numBoolVars = 2, numIntVars = 1)

        assertTrue(lifted.boolValue(0))
        assertTrue(lifted.boolValue(1))
    }

    @Test
    fun `an eliminated integer column should be recovered at arbitrary precision`() {
        val huge = BIG_ONE shl 70
        val rebuild = SourceRebuilds(
            listOf(RebuildStep.AffineValue(0, 1L, intArrayOf(1), longArrayOf(2L), divisor = 1L)),
        )
        val base = OpenTheoryAssignment.ExactLira(
            ExactLiraAssignment(
                bools = booleanArrayOf(),
                ints = arrayOf(BIG_ZERO, huge),
                reals = emptyList(),
            ),
        )

        val lifted = rebuild.lift(base, numBoolVars = 0, numIntVars = 2)

        assertEquals((huge * bigIntOf(2L) + BIG_ONE).toString(), lifted.intValue(0))
        assertEquals(huge.toString(), lifted.intValue(1))
    }

    @Test
    fun `a boolean only rebuild should leave integer columns to the route underneath`() {
        val rebuild = SourceRebuilds(listOf(RebuildStep.CopyLiteral(variable = 0, source = pos(1))))
        val base = witness(booleanArrayOf(false, true), longArrayOf(42))

        val lifted = assertIs<OpenTheoryAssignment.Rebuilt>(rebuild.lift(base, numBoolVars = 2, numIntVars = 1))

        assertEquals(null, lifted.ints)
        assertEquals("42", lifted.intValue(0))
    }
}
