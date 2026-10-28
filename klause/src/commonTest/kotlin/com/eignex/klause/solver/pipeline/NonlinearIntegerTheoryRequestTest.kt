package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonlinearIntegerTheoryRequestTest {
    @Test
    fun `integer residue exclusions should reach the surviving original source branch`() {
        val source = assertIs<NonlinearIntegerProblem>(SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (or (= (+ (* x x) (* y y)) 3) (and (= x 1) (= y 1))))",
        ).source.originalSource)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(),
            NonlinearIntegerTheoryParams(residueRefutation = true), FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals("1", witness.intValue(0))
        assertEquals("1", witness.intValue(1))
        assertEquals(1L, assertNotNull(execution.integerResidues).excludedAtoms)
    }

    @Test
    fun `residue refutations should close complete source search before polynomial completion`() {
        val source = assertIs<NonlinearIntegerProblem>(SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) (declare-const z Int) " +
                "(assert (= (+ (* x x) (* y y)) (+ (* 4 z) 3)))",
        ).source.originalSource)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(),
            NonlinearIntegerTheoryParams(residueRefutation = true), FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(execution.result)
        val residues = assertNotNull(execution.integerResidues)
        assertEquals(1L, residues.refutedScopes)
        assertTrue(residues.tuples > 0)
        assertTrue(residues.workSpent <= execution.stats.workSpent)
    }

    @Test
    fun `a nonlinear request should keep the prepared source declaration mapping`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const x Real) (declare-const n Int) " +
                    "(assert (= (* n n) 4)) (assert (= (* x x) (to_real n)))",
            )).source.originalSource)

        val request = source.pipelineRequest()

        assertTrue(request.preparedPlan.belongsTo(source))
        assertEquals(1, request.preparedPlan.integerCoordinate(0))
        assertEquals(0, request.preparedPlan.realCoordinate(0))
        assertEquals(null, request.preparedPlan.finiteIntegerCoordinate(0))
    }

    @Test
    fun `every engine should find a checked open integer square witness`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 4))",
            )).source.originalSource)

        val engines = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED)
        for (engine in engines) {
            val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = engine)

            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            val value = parseBigInt(witness.intValue(0))
            assertEquals(parseBigInt("4"), value * value)
            assertTrue(execution.stats.workSpent > 0)
        }
    }

    @Test
    fun `heuristic neighborhoods should preserve arbitrary width integer output`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= n 100000000000000000000)) " +
                    "(assert (= (* n n) 10000000000000000000000000000000000000000))",
            )).source.originalSource)

        for (engine in listOf(FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS)) {
            val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = engine)

            val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
            assertEquals("100000000000000000000", witness.intValue(0))
            assertTrue(execution.result.stats.ls.moves.sum > 0)
        }
    }

    @Test
    fun `mixed output mapping should separate interleaved real and integer declarations`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const x Real) (declare-const n Int) " +
                    "(assert (= (* n n) 4)) (assert (> n 0)) (assert (= (* x x) (to_real n)))",
            )).source.originalSource)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        val witness = assertIs<OpenTheoryResult.Sat>(execution.result).assignment
        assertEquals("2", witness.intValue(0))
        assertNotNull(witness.algebraicRealValue(0))
    }

    @Test
    fun `nonlinear neighborhoods should publish their source binding and coordinate mapping`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const x Real) (declare-const n Int) " +
                    "(assert (= (* n n) 4)) (assert (= (* x x) (to_real n)))",
            )).source.originalSource)
        val request = source.pipelineRequest()
        val params = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(
                strategy = PolynomialStrategyParams(assumptions = Assumptions(ints = mapOf(0 to 2L))),
            ),
        )
        val binding = EngineInvocation(
            request.preparedPlan,
            ModelIdentity.of(source),
            sourceAssumptions = params.polynomial.strategy.assumptions,
        )
        val invocation = NonlinearIntegerInvocation(request, params, FiniteEngine.LOCAL_SEARCH, binding)
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        val result = invocation.check(condition, Cancellation.Never)

        val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
        val publication = assertIs<CompletedEnginePublication.Witness>(sample.witnessCertificate?.publication)
        assertSame(binding, publication.invocation)
        assertEquals("2", publication.sample.exactIntValue(0).toString())
        assertNotNull(publication.sample.algebraicReals?.get(0))
        assertIs<Verification.Accepted<*, *>>(
            polynomialWitnessVerifier(binding, Cancellation.Never)
                .verify(Candidate(sample, null)),
        )
    }

    @Test
    fun `finite mixed products should complete through finite source ownership`() {
        val source = (
            SmtLib.parse(
                "(declare-const n Int) (declare-const x Real) " +
                    "(assert (and (<= 1 n 3) (<= 1.0 x 2.0) (= (* (to_real n) x) 6.0)))",
            ))
        val route = assertIs<SourceProblemRoute.Finite>(source.model.pipelineRoute())

        val result = BacktrackSolver.source(route.problem).solve()

        val witness = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("3", witness.intValue(source.intVarNames.getValue("n")))
        assertEquals("2", witness.realValue(source.realVarNames.getValue("x")))
    }

    @Test
    fun `failed exact integer neighborhoods should keep unknown proof status`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 2))",
            )).source.originalSource)

        val execution = OpenTheoryPipeline.execute(
            source.pipelineRequest(),
            NonlinearIntegerTheoryParams(
                maximumCandidates = 2,
                maximumMoves = 2,
            ),
            FiniteEngine.LOCAL_SEARCH,
        )

        assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertTrue(execution.result.stats.ls.moves.sum > 0)
    }

    @Test
    fun `Boolean invocation assumptions should stay local to the source call`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (declare-const p Bool) (assert (= (* n n) 4)) (assert p)",
            )).source.originalSource)
        val rejected = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(
                strategy = PolynomialStrategyParams(assumptions = Assumptions.None.withBool(0, false)),
            ),
        )

        val conditioned = OpenTheoryPipeline.execute(source.pipelineRequest(), rejected, FiniteEngine.BACKTRACK)
        val fresh = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK)

        assertIs<OpenTheoryResult.Unsat>(conditioned.result)
        assertTrue(assertIs<OpenTheoryResult.Sat>(fresh.result).assignment.boolValue(0))
    }

    @Test
    fun `native work charges should stay cumulative across heuristic callbacks`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 2))",
            )).source.originalSource)
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val execution = OpenTheoryPipeline.execute(
            source.pipelineRequest(),
            NonlinearIntegerTheoryParams(
                polynomial = PolynomialTheoryParams(maximumWork = 128, cancellation = cancellation),
                maximumMoves = 8,
            ),
            FiniteEngine.LOCAL_SEARCH,
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(charged, execution.stats.workSpent)
        assertTrue(charged >= 128)
    }

    @Test
    fun `an exhausted solve wide node allowance should prevent nonlinear entry`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 4))",
            )).source.originalSource)

        val execution = OpenTheoryPipeline.execute(
            source.pipelineRequest(),
            NonlinearIntegerTheoryParams(
                nodeBudget = NodeBudget(0),
            ),
            FiniteEngine.MIXED,
        )

        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.stats.workSpent)
    }

    @Test
    fun `parallel nonlinear integer workers should preserve the source coordinates`() {
        val input = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const x Int) (declare-const y Int) (assert (= x 3)) (assert (= (* x y) 21))",
            )).source.originalSource)
        val request = input.pipelineRequest()
        val params = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(
                strategy = PolynomialStrategyParams(assumptions = Assumptions(ints = mapOf(0 to 3L, 1 to 7L))),
            ),
        )

        for (engine in listOf(FiniteEngine.MIXED, FiniteEngine.LOCAL_SEARCH)) {
            val execution = OpenTheoryPipeline.execute(request, params, engine, 4)

            val sat = assertIs<OpenTheoryResult.Sat>(execution.result)
            assertEquals("3", sat.assignment.intValue(0))
            assertEquals("7", sat.assignment.intValue(1))
            assertEquals(4, sat.stats.portfolio.arms.size)
        }
    }

    @Test
    fun `fixed nonlinear execution should ignore additional worker requests`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 4))",
            )).source.originalSource)

        val result = OpenTheoryPipeline.execute(
            source.pipelineRequest(),
            NonlinearIntegerTheoryParams(),
            FiniteEngine.FIXED,
            4,
        ).result

        assertIs<OpenTheoryResult.Sat>(result)
        assertTrue(result.stats.portfolio.arms.isEmpty())
    }

    @Test
    fun `admitted integer roots should refute a rootless square only through complete search`() {
        val source = assertIs<NonlinearIntegerProblem>((
            SmtLib.parse(
                "(declare-const n Int) (assert (= (* n n) 2))",
            )).source.originalSource)
        val params = NonlinearIntegerTheoryParams(maximumCandidates = 4, maximumMoves = 4, admitIntegerRoots = true)

        val complete = OpenTheoryPipeline.execute(source.pipelineRequest(), params, FiniteEngine.BACKTRACK)
        val neighborhood = OpenTheoryPipeline.execute(source.pipelineRequest(), params, FiniteEngine.LOCAL_SEARCH)

        assertIs<OpenTheoryResult.Unsat>(complete.result)
        assertEquals(1L, assertNotNull(complete.integerRoots).refutedScopes)
        assertIs<OpenTheoryResult.Unknown>(neighborhood.result)
        val roots = assertNotNull(neighborhood.integerRoots)
        assertEquals(1L, roots.admittedEquations)
        assertEquals(roots.reusedAdmissions + 1, roots.refutedScopes)
    }
}
